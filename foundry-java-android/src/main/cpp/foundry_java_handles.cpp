#include "foundry_java_runtime.h"
#include "foundry_java_transport.h"

#include <algorithm>
#include <condition_variable>
#include <exception>
#include <limits>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <utility>

namespace foundry_java {

namespace {

struct Context {
	enum class Phase : std::uint8_t {
		ACTIVE,
		JAVA_CLEANUP,
		NATIVE_TEARDOWN,
		CLOSED,
	};

	ContextHandle handle = 0;
	std::uint64_t generation = 0;
	std::mutex mutex;
	std::condition_variable drained;
	Phase phase = Phase::ACTIVE;
	std::size_t active_callbacks = 0;
	std::size_t active_operations = 0;
	std::shared_ptr<const BridgeServices> services;
	std::shared_ptr<NativeTransport> transport;
	FoundryExtensionClassLibraryPtr library = nullptr;
};

std::mutex active_context_mutex;
std::unordered_map<
		std::thread::id,
		std::unordered_map<ContextHandle, std::size_t>>
		active_contexts;

class ActiveContextScope {
public:
	ActiveContextScope() = default;

	explicit ActiveContextScope(ContextHandle handle) {
		enter(handle);
	}

	~ActiveContextScope() {
		leave();
	}

	ActiveContextScope(const ActiveContextScope &) = delete;
	ActiveContextScope &operator=(const ActiveContextScope &) = delete;

	void enter(ContextHandle handle) {
		owner_thread = std::this_thread::get_id();
		context = handle;
		std::lock_guard lock(active_context_mutex);
		active_contexts[owner_thread][context]++;
		entered = true;
	}

	void leave() {
		if (!entered) {
			return;
		}
		std::lock_guard lock(active_context_mutex);
		auto thread = active_contexts.find(owner_thread);
		if (thread != active_contexts.end()) {
			auto context_entry = thread->second.find(context);
			if (context_entry != thread->second.end()) {
				if (--context_entry->second == 0) {
					thread->second.erase(context_entry);
				}
			}
			if (thread->second.empty()) {
				active_contexts.erase(thread);
			}
		}
		entered = false;
	}

private:
	std::thread::id owner_thread;
	ContextHandle context = 0;
	bool entered = false;
};

class CallbackLease {
public:
	explicit CallbackLease(std::shared_ptr<Context> context) : context(std::move(context)) {
		// This lock protects only lease acquisition and is released before any callback begins.
		std::lock_guard lock(this->context->mutex);
		if (this->context->phase == Context::Phase::ACTIVE) {
			this->context->active_callbacks++;
			active_scope.enter(this->context->handle);
			acquired = true;
		}
	}

	~CallbackLease() {
		if (!acquired) {
			return;
		}
		active_scope.leave();
		std::lock_guard lock(context->mutex);
		context->active_callbacks--;
		if (context->active_callbacks == 0 && context->active_operations == 0) {
			context->drained.notify_all();
		}
	}

	explicit operator bool() const {
		return acquired;
	}

private:
	std::shared_ptr<Context> context;
	ActiveContextScope active_scope;
	bool acquired = false;
};

bool current_thread_owns(ContextHandle handle) {
	std::lock_guard lock(active_context_mutex);
	auto thread = active_contexts.find(std::this_thread::get_id());
	return thread != active_contexts.end() &&
			thread->second.find(handle) != thread->second.end();
}

bool current_thread_has_active_context() {
	std::lock_guard lock(active_context_mutex);
	auto thread = active_contexts.find(std::this_thread::get_id());
	return thread != active_contexts.end() && !thread->second.empty();
}

} // namespace

struct BridgeRuntime::Impl {
	Impl(std::shared_ptr<CallbackTarget> callbacks, std::shared_ptr<ErrorSink> errors) :
			callbacks(std::move(callbacks)),
			errors(std::move(errors)) {
	}

	void report(const char *message) noexcept {
		try {
			if (errors != nullptr) {
				errors->error(std::string(message));
			}
		} catch (...) {
		}
	}

	std::shared_ptr<Context> live_context(ContextHandle handle) {
		std::lock_guard lock(mutex);
		auto found = contexts.find(handle);
		if (found == contexts.end() || found->second->generation != generation) {
			return nullptr;
		}
		return found->second;
	}

	std::mutex mutex;
	std::condition_variable shutdowns_drained;
	std::unordered_map<ContextHandle, std::shared_ptr<Context>> contexts;
	std::shared_ptr<CallbackTarget> callbacks;
	std::shared_ptr<ErrorSink> errors;
	std::function<void(ContextHandle, std::uint64_t)> context_teardown;
	std::shared_ptr<const BridgeServices> services;
	FoundryExtensionClassLibraryPtr library = nullptr;
	ContextHandle next_handle = 1;
	std::uint64_t generation = 1;
	std::size_t active_shutdowns = 0;
	bool accepting_contexts = true;
};

struct JniTransitionState::Impl {
	enum class Phase : std::uint8_t {
		IDLE,
		BOOTSTRAP,
		SHUTDOWN,
	};

	mutable std::mutex mutex;
	Token java_vm = 0;
	Token class_loader = 0;
	std::shared_ptr<BridgeRuntime> runtime;
	Phase phase = Phase::IDLE;
	Ticket active_ticket = 0;
	Ticket next_ticket = 1;
};

JniTransitionState::JniTransitionState() :
		impl(std::make_unique<Impl>()) {
}

JniTransitionState::~JniTransitionState() = default;

bool JniTransitionState::install(Token java_vm, Token class_loader) noexcept {
	std::lock_guard lock(impl->mutex);
	if (java_vm == 0 || class_loader == 0 ||
			impl->java_vm != 0 || impl->class_loader != 0 ||
			impl->runtime != nullptr || impl->phase != Impl::Phase::IDLE) {
		return false;
	}
	impl->java_vm = java_vm;
	impl->class_loader = class_loader;
	return true;
}

JniTransitionState::Ticket JniTransitionState::reserve_bootstrap(
		Token &java_vm) noexcept {
	std::lock_guard lock(impl->mutex);
	if (impl->runtime != nullptr || impl->phase != Impl::Phase::IDLE ||
			impl->java_vm == 0 || impl->class_loader == 0 ||
			impl->next_ticket == 0 ||
			impl->next_ticket == std::numeric_limits<Ticket>::max()) {
		return 0;
	}
	impl->phase = Impl::Phase::BOOTSTRAP;
	impl->active_ticket = impl->next_ticket++;
	java_vm = impl->java_vm;
	return impl->active_ticket;
}

bool JniTransitionState::publish_bootstrap(
		Ticket ticket,
		std::shared_ptr<BridgeRuntime> runtime,
		Token requested_class_loader,
		Token &previous_class_loader) noexcept {
	std::lock_guard lock(impl->mutex);
	if (ticket == 0 || impl->phase != Impl::Phase::BOOTSTRAP ||
			impl->active_ticket != ticket || impl->runtime != nullptr ||
			runtime == nullptr || requested_class_loader == 0) {
		return false;
	}
	previous_class_loader = std::exchange(
			impl->class_loader, requested_class_loader);
	impl->runtime = std::move(runtime);
	impl->phase = Impl::Phase::IDLE;
	impl->active_ticket = 0;
	return true;
}

bool JniTransitionState::cancel_bootstrap(Ticket ticket) noexcept {
	std::lock_guard lock(impl->mutex);
	if (ticket == 0 || impl->phase != Impl::Phase::BOOTSTRAP ||
			impl->active_ticket != ticket) {
		return false;
	}
	impl->phase = Impl::Phase::IDLE;
	impl->active_ticket = 0;
	return true;
}

JniTransitionState::Ticket JniTransitionState::reserve_shutdown(
		std::shared_ptr<BridgeRuntime> &runtime) noexcept {
	std::lock_guard lock(impl->mutex);
	if (impl->phase != Impl::Phase::IDLE || impl->next_ticket == 0 ||
			impl->next_ticket == std::numeric_limits<Ticket>::max()) {
		return 0;
	}
	impl->phase = Impl::Phase::SHUTDOWN;
	impl->active_ticket = impl->next_ticket++;
	runtime = impl->runtime;
	return impl->active_ticket;
}

bool JniTransitionState::finish_shutdown(
		Ticket ticket,
		const std::shared_ptr<BridgeRuntime> &runtime,
		Token &java_vm,
		Token &class_loader) noexcept {
	std::lock_guard lock(impl->mutex);
	if (ticket == 0 || impl->phase != Impl::Phase::SHUTDOWN ||
			impl->active_ticket != ticket || impl->runtime != runtime) {
		return false;
	}
	impl->runtime.reset();
	java_vm = impl->java_vm;
	class_loader = std::exchange(impl->class_loader, 0);
	impl->phase = Impl::Phase::IDLE;
	impl->active_ticket = 0;
	return true;
}

bool JniTransitionState::cancel_shutdown(
		Ticket ticket,
		const std::shared_ptr<BridgeRuntime> &runtime) noexcept {
	std::lock_guard lock(impl->mutex);
	if (ticket == 0 || impl->phase != Impl::Phase::SHUTDOWN ||
			impl->active_ticket != ticket || impl->runtime != runtime) {
		return false;
	}
	impl->phase = Impl::Phase::IDLE;
	impl->active_ticket = 0;
	return true;
}

std::shared_ptr<BridgeRuntime> JniTransitionState::runtime() const noexcept {
	std::lock_guard lock(impl->mutex);
	return impl->runtime;
}

JniTransitionState::Token JniTransitionState::java_vm() const noexcept {
	std::lock_guard lock(impl->mutex);
	return impl->java_vm;
}

JniTransitionState::Token JniTransitionState::pin_class_loader(
		const std::function<Token(Token)> &pin) const {
	std::lock_guard lock(impl->mutex);
	return impl->class_loader == 0 ? 0 : pin(impl->class_loader);
}

bool JniTransitionState::ready() const noexcept {
	std::lock_guard lock(impl->mutex);
	return impl->java_vm != 0 && impl->class_loader != 0 &&
			impl->runtime != nullptr && impl->phase == Impl::Phase::IDLE;
}

void JniTransitionState::clear_java_vm() noexcept {
	std::lock_guard lock(impl->mutex);
	if (impl->phase == Impl::Phase::IDLE && impl->runtime == nullptr &&
			impl->class_loader == 0) {
		impl->java_vm = 0;
	}
}

struct ContextOperationLease::Impl {
	std::shared_ptr<Context> context;
	ActiveContextScope active_scope;
	std::uint64_t generation = 0;

	~Impl() {
		if (context == nullptr) {
			return;
		}
		active_scope.leave();
		std::lock_guard lock(context->mutex);
		context->active_operations--;
		if (context->active_callbacks == 0 && context->active_operations == 0) {
			context->drained.notify_all();
		}
	}
};

ContextOperationLease::ContextOperationLease(std::unique_ptr<Impl> impl) :
		impl(std::move(impl)) {
}

ContextOperationLease::ContextOperationLease() = default;

ContextOperationLease::~ContextOperationLease() = default;

ContextOperationLease::ContextOperationLease(ContextOperationLease &&other) noexcept = default;

ContextOperationLease &ContextOperationLease::operator=(ContextOperationLease &&other) noexcept = default;

ContextOperationLease::operator bool() const noexcept {
	return impl != nullptr;
}

std::uint64_t ContextOperationLease::generation() const noexcept {
	return impl == nullptr ? 0 : impl->generation;
}

std::shared_ptr<const BridgeServices> ContextOperationLease::services() const noexcept {
	return impl == nullptr || impl->context == nullptr ? nullptr : impl->context->services;
}

NativeTransport *ContextOperationLease::transport() const noexcept {
	return impl == nullptr || impl->context == nullptr ? nullptr : impl->context->transport.get();
}

FoundryExtensionClassLibraryPtr ContextOperationLease::library() const noexcept {
	return impl == nullptr || impl->context == nullptr ? nullptr : impl->context->library;
}

BridgeRuntime::BridgeRuntime(
		std::shared_ptr<CallbackTarget> callbacks,
		std::shared_ptr<ErrorSink> errors) :
		impl(std::make_unique<Impl>(std::move(callbacks), std::move(errors))) {
}

BridgeRuntime::~BridgeRuntime() {
	(void)shutdown_all(FOUNDRY_EXTENSION_INITIALIZATION_CORE);
}

ContextHandle BridgeRuntime::create_context() {
	bool exhausted = false;
	ContextHandle handle = 0;
	{
		std::lock_guard lock(impl->mutex);
		if (!impl->accepting_contexts || impl->callbacks == nullptr) {
			return 0;
		}
		if (impl->next_handle == 0 ||
				impl->next_handle == std::numeric_limits<ContextHandle>::max()) {
			exhausted = true;
		} else {
			handle = impl->next_handle++;
			auto context = std::make_shared<Context>();
			context->handle = handle;
			context->generation = impl->generation;
			context->services = impl->services;
			context->library = impl->library;
			if (context->services != nullptr) {
				context->transport = std::make_shared<NativeTransport>(
						context->services, context->library);
			}
			impl->contexts.emplace(handle, std::move(context));
		}
	}
	if (exhausted) {
		impl->report("Foundry Java context handle space is exhausted.");
	}
	return handle;
}

ContextHandle BridgeRuntime::create_native_context() {
	{
		std::lock_guard lock(impl->mutex);
		if (impl->services == nullptr || impl->library == nullptr) {
			return 0;
		}
	}
	return create_context();
}

bool BridgeRuntime::initialize(ContextHandle handle, std::int32_t level) noexcept {
	auto context = impl->live_context(handle);
	if (context == nullptr) {
		return false;
	}
	CallbackLease lease(context);
	if (!lease) {
		return false;
	}
	try {
		return impl->callbacks->initialize(handle, level);
	} catch (...) {
		impl->report("Java initialization callback failed.");
	}
	return false;
}

void BridgeRuntime::deinitialize(ContextHandle handle, std::int32_t level) noexcept {
	auto context = impl->live_context(handle);
	if (context == nullptr) {
		return;
	}
	CallbackLease lease(context);
	if (!lease) {
		return;
	}
	try {
		impl->callbacks->deinitialize(handle, level);
	} catch (...) {
		impl->report("Java deinitialization callback failed.");
	}
}

std::int64_t BridgeRuntime::invoke(
		ContextHandle handle,
		std::int64_t callback,
		const std::vector<std::int64_t> &arguments) noexcept {
	auto context = impl->live_context(handle);
	if (context == nullptr) {
		return 0;
	}
	CallbackLease lease(context);
	if (!lease) {
		return 0;
	}
	try {
		return impl->callbacks->invoke(handle, callback, arguments);
	} catch (...) {
		impl->report("Java callback failed.");
	}
	return 0;
}

ContextOperationLease BridgeRuntime::acquire_operation(
		ContextHandle handle,
		ContextOperationKind kind) noexcept {
	try {
		auto context = impl->live_context(handle);
		if (context == nullptr) {
			return {};
		}
		std::lock_guard lock(context->mutex);
		const bool admitted =
				context->phase == Context::Phase::ACTIVE ||
				(kind == ContextOperationKind::CLEANUP &&
						context->phase == Context::Phase::JAVA_CLEANUP &&
						current_thread_owns(handle));
		if (!admitted) {
			return {};
		}
		auto lease = std::make_unique<ContextOperationLease::Impl>();
		context->active_operations++;
		lease->context = std::move(context);
		lease->generation = lease->context->generation;
		lease->active_scope.enter(handle);
		return ContextOperationLease(std::move(lease));
	} catch (...) {
		impl->report("Foundry Java native operation admission failed.");
		return {};
	}
}

bool BridgeRuntime::install_native_services(
		std::shared_ptr<const BridgeServices> services,
		FoundryExtensionClassLibraryPtr library) noexcept {
	bool installed = false;
	{
		std::lock_guard lock(impl->mutex);
		if (impl->contexts.empty() && services != nullptr && library != nullptr) {
			impl->services = std::move(services);
			impl->library = library;
			installed = true;
		}
	}
	if (!installed) {
		impl->report("Foundry Java native services cannot change while contexts are live.");
	}
	return installed;
}

void BridgeRuntime::set_context_teardown(
		std::function<void(ContextHandle, std::uint64_t)> teardown) noexcept {
	std::lock_guard lock(impl->mutex);
	impl->context_teardown = std::move(teardown);
}

bool BridgeRuntime::shutdown_context(ContextHandle handle, std::int32_t level) noexcept {
	if (current_thread_owns(handle)) {
		impl->report("A Foundry Java context cannot shut down from its own callback.");
		return false;
	}
	std::shared_ptr<Context> context;
	{
		std::lock_guard lock(impl->mutex);
		auto found = impl->contexts.find(handle);
		if (found == impl->contexts.end() || found->second->generation != impl->generation) {
			return false;
		}
		context = found->second;
		std::lock_guard context_lock(context->mutex);
		if (context->phase != Context::Phase::ACTIVE) {
			return false;
		}
		context->phase = Context::Phase::JAVA_CLEANUP;
		impl->active_shutdowns++;
	}
	{
		std::unique_lock lock(context->mutex);
		context->drained.wait(lock, [&context] {
			return context->active_callbacks == 0 && context->active_operations == 0;
		});
	}
	ActiveContextScope shutdown_callback_scope(handle);
	try {
		impl->callbacks->deinitialize(handle, level);
	} catch (...) {
		impl->report("Java deinitialization callback failed.");
	}
	try {
		impl->callbacks->invalidate(handle);
	} catch (...) {
		impl->report("Java context invalidation failed.");
	}
	std::function<void(ContextHandle, std::uint64_t)> teardown;
	{
		try {
			std::lock_guard lock(impl->mutex);
			teardown = impl->context_teardown;
		} catch (...) {
			impl->report("Foundry Java native teardown observer could not be copied.");
		}
	}
	{
		std::unique_lock lock(context->mutex);
		context->phase = Context::Phase::NATIVE_TEARDOWN;
		context->drained.wait(lock, [&context] {
			return context->active_callbacks == 0 && context->active_operations == 0;
		});
	}
	if (teardown) {
		try {
			teardown(handle, context->generation);
		} catch (...) {
			impl->report("Foundry Java native context teardown failed.");
		}
	}
	if (context->transport != nullptr) {
		(void)context->transport->handles().teardown(handle, context->generation);
		context->transport.reset();
	}
	context->services.reset();
	context->library = nullptr;
	{
		std::lock_guard lock(impl->mutex);
		auto found = impl->contexts.find(handle);
		if (found != impl->contexts.end() && found->second == context) {
			impl->contexts.erase(found);
		}
		{
			std::lock_guard context_lock(context->mutex);
			context->phase = Context::Phase::CLOSED;
		}
		impl->active_shutdowns--;
		if (impl->active_shutdowns == 0) {
			impl->shutdowns_drained.notify_all();
		}
	}
	return true;
}

void BridgeRuntime::begin_new_generation() noexcept {
	if (!shutdown_all(FOUNDRY_EXTENSION_INITIALIZATION_CORE)) {
		return;
	}
	std::lock_guard lock(impl->mutex);
	impl->generation++;
	impl->accepting_contexts = true;
}

bool BridgeRuntime::shutdown_all(std::int32_t level) noexcept {
	if (current_thread_has_active_context()) {
		impl->report("The Foundry Java bridge cannot shut down from an active callback.");
		return false;
	}
	{
		std::lock_guard lock(impl->mutex);
		impl->accepting_contexts = false;
	}
	while (true) {
		ContextHandle handle = 0;
		{
			std::unique_lock lock(impl->mutex);
			if (impl->contexts.empty()) {
				impl->shutdowns_drained.wait(lock, [this] { return impl->active_shutdowns == 0; });
				return true;
			}
			for (const auto &[candidate_handle, context] : impl->contexts) {
				std::lock_guard context_lock(context->mutex);
				if (context->phase == Context::Phase::ACTIVE) {
					handle = candidate_handle;
					break;
				}
			}
			if (handle == 0) {
				impl->shutdowns_drained.wait(lock, [this] {
					if (impl->active_shutdowns == 0) {
						return true;
					}
					for (const auto &[candidate_handle, context] : impl->contexts) {
						(void)candidate_handle;
						std::lock_guard context_lock(context->mutex);
						if (context->phase == Context::Phase::ACTIVE) {
							return true;
						}
					}
					return false;
				});
				continue;
			}
		}
		(void)shutdown_context(handle, level);
	}
}

} // namespace foundry_java
