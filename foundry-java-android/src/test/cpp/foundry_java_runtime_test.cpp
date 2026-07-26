#include "foundry_java_runtime.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

bool jni_ready = false;
int jni_initialize_count = 0;
int jni_deinitialize_count = 0;
int jni_shutdown_context_count = 0;
int jni_shutdown_count = 0;
bool jni_shutdown_context_result = true;
bool jni_shutdown_result = true;
FoundryExtensionInterfacePrintError installed_print_error = nullptr;

void fake_print_error(const char *, const char *, const char *, std::int32_t, FoundryExtensionBool) {
}

void fake_register_class(
		FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionConstStringNamePtr,
		const FoundryExtensionClassCreationInfo5 *) {
}

void fake_unregister_class(
		FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr) {
}

void fake_string_name_from_utf8(FoundryExtensionUninitializedStringNamePtr, const char *) {
}

FoundryExtensionPtrDestructor fake_variant_destructor(FoundryExtensionVariantType) {
	return nullptr;
}

template <typename Function>
FoundryExtensionInterfaceFunctionPtr erase_function_type(Function function) {
	FoundryExtensionInterfaceFunctionPtr result = nullptr;
	static_assert(sizeof(result) == sizeof(function));
	std::memcpy(&result, &function, sizeof(result));
	return result;
}

FoundryExtensionInterfaceFunctionPtr complete_get_proc_address(const char *name) {
	if (std::strcmp(name, "print_error") == 0) {
		return erase_function_type(&fake_print_error);
	}
	if (std::strcmp(name, "classdb_register_extension_class5") == 0) {
		return erase_function_type(&fake_register_class);
	}
	if (std::strcmp(name, "classdb_unregister_extension_class") == 0) {
		return erase_function_type(&fake_unregister_class);
	}
	if (std::strcmp(name, "string_name_new_with_utf8_chars") == 0) {
		return erase_function_type(&fake_string_name_from_utf8);
	}
	if (std::strcmp(name, "variant_get_ptr_destructor") == 0) {
		return erase_function_type(&fake_variant_destructor);
	}
	return nullptr;
}

FoundryExtensionInterfaceFunctionPtr incomplete_get_proc_address(const char *name) {
	if (std::strcmp(name, "variant_get_ptr_destructor") == 0) {
		return nullptr;
	}
	return complete_get_proc_address(name);
}

void expect(bool condition, const char *message) {
	if (!condition) {
		std::cerr << "FAILED: " << message << '\n';
		std::exit(1);
	}
}

class RecordingLogger final : public foundry_java::ErrorSink {
public:
	void error(const std::string &message) noexcept override {
		std::lock_guard lock(mutex);
		messages.push_back(message);
	}

	std::mutex mutex;
	std::vector<std::string> messages;
};

class RecordingCallbacks final : public foundry_java::CallbackTarget {
public:
	bool initialize(foundry_java::ContextHandle context, std::int32_t level) override {
		last_context = context;
		last_level = level;
		initialize_count++;
		return true;
	}

	void deinitialize(foundry_java::ContextHandle context, std::int32_t level) override {
		last_context = context;
		last_level = level;
		deinitialize_count++;
		{
			std::lock_guard lock(deinitialize_mutex);
			deinitialize_condition.notify_all();
		}
		if (shutdown_during_deinitialize) {
			shutdown_during_deinitialize_result =
					runtime->shutdown_all(FOUNDRY_EXTENSION_INITIALIZATION_CORE);
		}
		if (context == blocked_deinitialize_context) {
			std::unique_lock lock(deinitialize_mutex);
			deinitialize_started = true;
			deinitialize_condition.notify_all();
			deinitialize_condition.wait(lock, [this] { return release_deinitialize; });
		}
	}

	std::int64_t invoke(
			foundry_java::ContextHandle context,
			std::int64_t callback,
			const std::vector<std::int64_t> &arguments) override {
		invoke_count++;
		if (callback == 1) {
			return runtime->invoke(context, 2, {});
		}
		if (callback == 2) {
			reentrant_result = 42;
			return reentrant_result;
		}
		if (callback == 3) {
			throw std::runtime_error("callback failure");
		}
		if (callback == 4) {
			std::unique_lock lock(block_mutex);
			callback_started = true;
			block_condition.notify_all();
			block_condition.wait(lock, [this] { return release_callback; });
			return 99;
		}
		if (callback == 5) {
			shutdown_from_callback_result =
					runtime->shutdown_all(FOUNDRY_EXTENSION_INITIALIZATION_CORE);
			return 55;
		}
		std::int64_t result = callback;
		for (std::int64_t argument : arguments) {
			result += argument;
		}
		return result;
	}

	void invalidate(foundry_java::ContextHandle context) override {
		last_context = context;
		invalidate_count++;
	}

	void wait_until_blocked() {
		std::unique_lock lock(block_mutex);
		block_condition.wait(lock, [this] { return callback_started; });
	}

	void release_blocked_callback() {
		std::lock_guard lock(block_mutex);
		release_callback = true;
		block_condition.notify_all();
	}

	void wait_until_deinitialize_blocked() {
		std::unique_lock lock(deinitialize_mutex);
		deinitialize_condition.wait(lock, [this] { return deinitialize_started; });
	}

	void wait_until_deinitialize_count(int count) {
		std::unique_lock lock(deinitialize_mutex);
		deinitialize_condition.wait(lock, [this, count] { return deinitialize_count >= count; });
	}

	void release_blocked_deinitialize() {
		std::lock_guard lock(deinitialize_mutex);
		release_deinitialize = true;
		deinitialize_condition.notify_all();
	}

	foundry_java::BridgeRuntime *runtime = nullptr;
	foundry_java::ContextHandle last_context = 0;
	std::int32_t last_level = -1;
	std::atomic<int> initialize_count = 0;
	std::atomic<int> deinitialize_count = 0;
	std::atomic<int> invoke_count = 0;
	std::atomic<int> invalidate_count = 0;
	std::int64_t reentrant_result = 0;
	std::mutex block_mutex;
	std::condition_variable block_condition;
	bool callback_started = false;
	bool release_callback = false;
	bool shutdown_from_callback_result = true;
	bool shutdown_during_deinitialize = false;
	bool shutdown_during_deinitialize_result = true;
	foundry_java::ContextHandle blocked_deinitialize_context = 0;
	std::mutex deinitialize_mutex;
	std::condition_variable deinitialize_condition;
	bool deinitialize_started = false;
	bool release_deinitialize = false;
};

void test_context_identity_reentrancy_and_exception_containment() {
	auto callbacks = std::make_shared<RecordingCallbacks>();
	auto logger = std::make_shared<RecordingLogger>();
	foundry_java::BridgeRuntime runtime(callbacks, logger);
	callbacks->runtime = &runtime;

	const auto context = runtime.create_context();
	const auto second_context = runtime.create_context();
	expect(context != 0, "context handle must be nonzero");
	expect(second_context != 0 && second_context != context, "context handles must be unique");
	expect(runtime.initialize(context, 0), "live context must initialize");
	expect(runtime.invoke(context, 7, { 11, 13 }) == 31, "arguments must marshal in stable order");
	expect(runtime.invoke(context, 1, {}) == 42, "same-thread reentrant callback must succeed");
	expect(callbacks->reentrant_result == 42, "reentrant callback result must be preserved");
	expect(runtime.invoke(context, 3, {}) == 0, "callback exception must convert to default value");
	expect(logger->messages.size() == 1, "callback exception must be logged exactly once");
	expect(runtime.invoke(context, 5, {}) == 55, "callback-local bridge shutdown must not disrupt the call");
	expect(!callbacks->shutdown_from_callback_result, "bridge shutdown must reject its own active callback");
	expect(runtime.invoke(context, 7, {}) == 7, "rejected bridge shutdown must leave the context live");

	expect(runtime.shutdown_context(context, 0), "live context must shut down");
	expect(callbacks->deinitialize_count == 1, "deinitialize must run exactly once");
	expect(callbacks->invalidate_count == 1, "invalidate must run exactly once");
	expect(runtime.invoke(context, 7, {}) == 0, "closed context must reject callbacks");
	expect(!runtime.shutdown_context(context, 0), "closed context must reject repeated shutdown");

	runtime.begin_new_generation();
	expect(runtime.invoke(second_context, 7, {}) == 0, "old-generation context must be rejected");
	const auto third_context = runtime.create_context();
	expect(third_context != second_context, "handles must not be reused across generations");
	callbacks->shutdown_during_deinitialize = true;
	expect(runtime.shutdown_context(third_context, 0), "new-generation context must shut down");
	expect(
			!callbacks->shutdown_during_deinitialize_result,
			"bridge shutdown must reject reentry from a deinitialization callback");
}

void test_shutdown_waits_for_active_callback_lease() {
	auto callbacks = std::make_shared<RecordingCallbacks>();
	auto logger = std::make_shared<RecordingLogger>();
	foundry_java::BridgeRuntime runtime(callbacks, logger);
	callbacks->runtime = &runtime;
	const auto context = runtime.create_context();

	std::atomic<std::int64_t> callback_result = 0;
	std::atomic<bool> shutdown_finished = false;
	std::thread callback_thread([&] { callback_result = runtime.invoke(context, 4, {}); });
	callbacks->wait_until_blocked();
	std::thread shutdown_thread([&] {
		expect(runtime.shutdown_context(context, 0), "racing shutdown must own the live context");
		shutdown_finished = true;
	});

	bool rejected_during_drain = false;
	for (int attempt = 0; attempt < 10'000; attempt++) {
		if (runtime.invoke(context, 7, {}) == 0) {
			rejected_during_drain = true;
			break;
		}
		std::this_thread::yield();
	}
	expect(!shutdown_finished, "shutdown must wait while a callback lease is active");
	expect(callbacks->invalidate_count == 0, "context cannot invalidate before callbacks drain");
	expect(rejected_during_drain, "draining context must reject new callbacks");

	callbacks->release_blocked_callback();
	callback_thread.join();
	shutdown_thread.join();
	expect(callback_result == 99, "active callback must complete before shutdown");
	expect(shutdown_finished, "shutdown must finish after callback drain");
	expect(callbacks->invalidate_count == 1, "racing shutdown must invalidate exactly once");
}

void test_shutdown_all_waits_for_concurrent_context_teardown() {
	auto callbacks = std::make_shared<RecordingCallbacks>();
	auto logger = std::make_shared<RecordingLogger>();
	foundry_java::BridgeRuntime runtime(callbacks, logger);
	callbacks->runtime = &runtime;
	const auto first_context = runtime.create_context();
	const auto second_context = runtime.create_context();
	expect(second_context != 0, "bridge shutdown test requires a second context");
	callbacks->blocked_deinitialize_context = first_context;

	std::atomic<bool> context_shutdown_result = false;
	std::atomic<bool> bridge_shutdown_result = false;
	std::atomic<bool> bridge_shutdown_finished = false;
	std::thread context_shutdown_thread(
			[&] { context_shutdown_result = runtime.shutdown_context(first_context, 0); });
	callbacks->wait_until_deinitialize_blocked();
	std::thread bridge_shutdown_thread([&] {
		bridge_shutdown_result = runtime.shutdown_all(0);
		bridge_shutdown_finished = true;
	});
	callbacks->wait_until_deinitialize_count(2);
	expect(callbacks->deinitialize_count == 2, "bridge shutdown must drain the remaining context");
	expect(
			!bridge_shutdown_finished,
			"bridge shutdown must wait for a concurrently removed context to finish teardown");

	callbacks->release_blocked_deinitialize();
	context_shutdown_thread.join();
	bridge_shutdown_thread.join();
	expect(context_shutdown_result, "concurrent context shutdown must succeed");
	expect(bridge_shutdown_result, "bridge shutdown must succeed after every teardown completes");
	expect(callbacks->invalidate_count == 2, "bridge shutdown must invalidate both contexts");
}

void test_extension_entry_validates_and_orders_lifecycle() {
	FoundryExtensionInitialization unchanged{};
	unchanged.userdata = reinterpret_cast<void *>(0x1234);
	expect(
			foundry_java::initialize_extension(nullptr, reinterpret_cast<void *>(1), &unchanged) == 0,
			"entry must reject a null interface resolver");
	expect(unchanged.userdata == reinterpret_cast<void *>(0x1234), "failed entry must not mutate initialization");
	expect(
			foundry_java::initialize_extension(
					incomplete_get_proc_address,
					reinterpret_cast<void *>(1),
					&unchanged) == 0,
			"entry must reject an incomplete interface table");
	expect(unchanged.userdata == reinterpret_cast<void *>(0x1234), "incomplete table must not mutate initialization");

	jni_ready = false;
	expect(
			foundry_java::initialize_extension(
					complete_get_proc_address,
					reinterpret_cast<void *>(1),
					&unchanged) == 0,
			"entry must reject an unavailable JVM/bootstrap");
	expect(unchanged.userdata == reinterpret_cast<void *>(0x1234), "missing JVM must not mutate initialization");

	jni_ready = true;
	FoundryExtensionInitialization initialization{};
	expect(
			foundry_java::initialize_extension(
					complete_get_proc_address,
					reinterpret_cast<void *>(1),
					&initialization) == 1,
			"complete table and JNI bootstrap must initialize");
	expect(
			initialization.minimum_initialization_level == FOUNDRY_EXTENSION_INITIALIZATION_CORE,
			"bridge must initialize from core");
	expect(initialization.userdata != nullptr, "bridge must supply stable lifecycle userdata");
	expect(initialization.initialize != nullptr, "bridge must supply initialize callback");
	expect(initialization.deinitialize != nullptr, "bridge must supply deinitialize callback");
	expect(installed_print_error == &fake_print_error, "bridge must install Foundry error logging");

	FoundryExtensionInitialization duplicate{};
	duplicate.userdata = reinterpret_cast<void *>(0x5678);
	expect(
			foundry_java::initialize_extension(
					complete_get_proc_address,
					reinterpret_cast<void *>(2),
					&duplicate) == 0,
			"active bridge must reject a duplicate entry");
	expect(duplicate.userdata == reinterpret_cast<void *>(0x5678), "duplicate entry must not mutate initialization");

	initialization.initialize(initialization.userdata, FOUNDRY_EXTENSION_INITIALIZATION_CORE);
	initialization.initialize(initialization.userdata, FOUNDRY_EXTENSION_INITIALIZATION_SCENE);
	expect(jni_initialize_count == 2, "every initialization level must enter Java");
	initialization.deinitialize(initialization.userdata, FOUNDRY_EXTENSION_INITIALIZATION_SCENE);
	expect(jni_deinitialize_count == 1, "non-final deinitialization level must enter Java");
	jni_shutdown_context_result = false;
	initialization.deinitialize(initialization.userdata, FOUNDRY_EXTENSION_INITIALIZATION_CORE);
	expect(jni_shutdown_count == 0, "failed context drain must not release JNI state");
	FoundryExtensionInitialization rejected_after_failed_shutdown{};
	expect(
			foundry_java::initialize_extension(
					complete_get_proc_address,
					reinterpret_cast<void *>(2),
					&rejected_after_failed_shutdown) == 0,
			"failed core shutdown must keep the active entry from being replaced");
	jni_shutdown_context_result = true;
	jni_shutdown_result = false;
	initialization.deinitialize(initialization.userdata, FOUNDRY_EXTENSION_INITIALIZATION_CORE);
	expect(jni_shutdown_context_count == 1, "core deinit must drain its context once");
	expect(jni_shutdown_count == 1, "core deinit must attempt JNI state release once");
	initialization.deinitialize(initialization.userdata, FOUNDRY_EXTENSION_INITIALIZATION_CORE);
	expect(jni_shutdown_context_count == 1, "bridge-shutdown retry must not drain a closed context again");
	expect(jni_shutdown_count == 2, "bridge-shutdown retry must reach JNI state release");
	jni_shutdown_result = true;
	initialization.deinitialize(initialization.userdata, FOUNDRY_EXTENSION_INITIALIZATION_CORE);
	expect(jni_shutdown_context_count == 1, "successful retry must still preserve one context drain");
	expect(jni_shutdown_count == 3, "successful retry must release JNI state");
}

} // namespace

namespace foundry_java {

bool jni_bridge_is_ready() noexcept {
	return jni_ready;
}

ContextHandle jni_bridge_create_context() noexcept {
	return 77;
}

bool jni_bridge_initialize(ContextHandle context, std::int32_t) noexcept {
	jni_initialize_count++;
	return context == 77;
}

void jni_bridge_deinitialize(ContextHandle context, std::int32_t) noexcept {
	if (context == 77) {
		jni_deinitialize_count++;
	}
}

bool jni_bridge_shutdown_context(ContextHandle context, std::int32_t) noexcept {
	if (context == 77) {
		if (jni_shutdown_context_result) {
			jni_shutdown_context_count++;
		}
		return jni_shutdown_context_result;
	}
	return false;
}

void jni_bridge_install_foundry_error_interface(FoundryExtensionInterfacePrintError print_error) noexcept {
	installed_print_error = print_error;
}

bool jni_bridge_shutdown() noexcept {
	jni_shutdown_count++;
	return jni_shutdown_result;
}

} // namespace foundry_java

int main() {
	test_context_identity_reentrancy_and_exception_containment();
	test_shutdown_waits_for_active_callback_lease();
	test_shutdown_all_waits_for_concurrent_context_teardown();
	test_extension_entry_validates_and_orders_lifecycle();
	std::cout << "Foundry Java native runtime tests passed\n";
	return 0;
}
