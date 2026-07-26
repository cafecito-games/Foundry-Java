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

thread_local std::vector<ContextHandle> active_contexts;

class CallbackLease {
public:
	explicit CallbackLease(std::shared_ptr<Context> context) : context(std::move(context)) {
		std::lock_guard lock(this->context->mutex);
		if (this->context->accepting_callbacks) {
			this->context->active_callbacks++;
			active_contexts.push_back(this->context->handle);
			acquired = true;
		}
	}

	~CallbackLease() {
		if (!acquired) {
			return;
		}
		auto active = std::find(active_contexts.rbegin(), active_contexts.rend(), context->handle);
		if (active != active_contexts.rend()) {
			active_contexts.erase(std::next(active).base());
		}
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
	bool acquired = false;
};

bool current_thread_owns(ContextHandle handle) {
	return std::find(active_contexts.begin(), active_contexts.end(), handle) != active_contexts.end();
}

} // namespace

struct BridgeRuntime::Impl {
	Impl(std::shared_ptr<CallbackTarget> callbacks, std::shared_ptr<ErrorSink> errors) :
			callbacks(std::move(callbacks)),
			errors(std::move(errors)) {
	}

	void report(const std::string &message) noexcept {
		if (errors != nullptr) {
			errors->error(message);
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
	std::unordered_map<ContextHandle, std::shared_ptr<Context>> contexts;
	std::shared_ptr<CallbackTarget> callbacks;
	std::shared_ptr<ErrorSink> errors;
	ContextHandle next_handle = 1;
	std::uint64_t generation = 1;
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
	} catch (const std::exception &exception) {
		impl->report(std::string("Java initialization callback failed: ") + exception.what());
	} catch (...) {
		impl->report("Java initialization callback failed with an unknown exception.");
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
	} catch (const std::exception &exception) {
		impl->report(std::string("Java deinitialization callback failed: ") + exception.what());
	} catch (...) {
		impl->report("Java deinitialization callback failed with an unknown exception.");
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
	} catch (const std::exception &exception) {
		impl->report(std::string("Java callback failed: ") + exception.what());
	} catch (...) {
		impl->report("Java callback failed with an unknown exception.");
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
		impl->contexts.erase(found);
	}
	{
		std::unique_lock lock(context->mutex);
		context->drained.wait(lock, [&context] { return context->active_callbacks == 0; });
	}
	try {
		impl->callbacks->deinitialize(handle, level);
	} catch (const std::exception &exception) {
		impl->report(std::string("Java deinitialization callback failed: ") + exception.what());
	} catch (...) {
		impl->report("Java deinitialization callback failed with an unknown exception.");
	}
	try {
		impl->callbacks->invalidate(handle);
	} catch (const std::exception &exception) {
		impl->report(std::string("Java context invalidation failed: ") + exception.what());
	} catch (...) {
		impl->report("Java context invalidation failed with an unknown exception.");
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
	std::vector<ContextHandle> handles;
	bool callback_owned_by_current_thread = false;
	{
		std::lock_guard lock(impl->mutex);
		for (const auto &[handle, context] : impl->contexts) {
			(void)context;
			if (current_thread_owns(handle)) {
				callback_owned_by_current_thread = true;
				break;
			}
		}
		if (!callback_owned_by_current_thread) {
			impl->accepting_contexts = false;
			handles.reserve(impl->contexts.size());
			for (const auto &[handle, context] : impl->contexts) {
				(void)context;
				handles.push_back(handle);
			}
		}
	}
	if (callback_owned_by_current_thread) {
		impl->report("The Foundry Java bridge cannot shut down from an active callback.");
		return false;
	}
	for (ContextHandle handle : handles) {
		shutdown_context(handle, level);
	}
	return true;
}

} // namespace foundry_java
