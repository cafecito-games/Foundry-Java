#include "foundry_java_runtime.h"

#include <algorithm>
#include <condition_variable>
#include <exception>
#include <limits>
#include <mutex>
#include <unordered_map>
#include <utility>

namespace foundry_java {

namespace {

struct Context {
	ContextHandle handle = 0;
	std::uint64_t generation = 0;
	std::mutex mutex;
	std::condition_variable drained;
	bool accepting_callbacks = true;
	std::size_t active_callbacks = 0;
};

struct ActiveContextFrame {
	ContextHandle handle = 0;
	ActiveContextFrame *previous = nullptr;
};

thread_local ActiveContextFrame *active_context = nullptr;

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
		frame.handle = handle;
		frame.previous = active_context;
		active_context = &frame;
		entered = true;
	}

	void leave() {
		if (!entered) {
			return;
		}
		active_context = frame.previous;
		entered = false;
	}

private:
	ActiveContextFrame frame;
	bool entered = false;
};

class CallbackLease {
public:
	explicit CallbackLease(std::shared_ptr<Context> context) : context(std::move(context)) {
		// This lock protects only lease acquisition and is released before any callback begins.
		std::lock_guard lock(this->context->mutex);
		if (this->context->accepting_callbacks) {
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
		if (context->active_callbacks == 0) {
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
	for (ActiveContextFrame *frame = active_context; frame != nullptr; frame = frame->previous) {
		if (frame->handle == handle) {
			return true;
		}
	}
	return false;
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
	ContextHandle next_handle = 1;
	std::uint64_t generation = 1;
	std::size_t active_shutdowns = 0;
	bool accepting_contexts = true;
};

BridgeRuntime::BridgeRuntime(
		std::shared_ptr<CallbackTarget> callbacks,
		std::shared_ptr<ErrorSink> errors) :
		impl(std::make_unique<Impl>(std::move(callbacks), std::move(errors))) {
}

BridgeRuntime::~BridgeRuntime() {
	(void)shutdown_all(FOUNDRY_EXTENSION_INITIALIZATION_CORE);
}

ContextHandle BridgeRuntime::create_context() {
	std::lock_guard lock(impl->mutex);
	if (!impl->accepting_contexts || impl->callbacks == nullptr) {
		return 0;
	}
	if (impl->next_handle == 0 || impl->next_handle == std::numeric_limits<ContextHandle>::max()) {
		impl->report("Foundry Java context handle space is exhausted.");
		return 0;
	}
	const ContextHandle handle = impl->next_handle++;
	auto context = std::make_shared<Context>();
	context->handle = handle;
	context->generation = impl->generation;
	impl->contexts.emplace(handle, std::move(context));
	return handle;
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
		{
			std::lock_guard context_lock(context->mutex);
			context->accepting_callbacks = false;
		}
		impl->active_shutdowns++;
		impl->contexts.erase(found);
	}
	{
		std::unique_lock lock(context->mutex);
		context->drained.wait(lock, [&context] { return context->active_callbacks == 0; });
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
	{
		std::lock_guard lock(impl->mutex);
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
	{
		std::lock_guard lock(impl->mutex);
		if (active_context != nullptr) {
			impl->report("The Foundry Java bridge cannot shut down from an active callback.");
			return false;
		}
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
			handle = impl->contexts.begin()->first;
		}
		(void)shutdown_context(handle, level);
	}
}

} // namespace foundry_java
