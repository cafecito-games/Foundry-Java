#include "foundry_java_runtime.h"
#include "foundry_java_abi_layout.h"
#include "foundry_java_interface.h"
#include "foundry_java_transport.h"

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
std::string missing_interface_name;
int native_string_name_construct_count = 0;
int native_string_name_destroy_count = 0;
int native_object_destroy_count = 0;
std::uint64_t requested_object_id = 0;
int ref_method_lookup_count = 0;
int ref_reference_count = 0;
int ref_unreference_count = 0;
bool ref_hashes_valid = true;
FoundryExtensionVariantType copied_variant_type = FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
int variant_copy_count = 0;
int variant_destroy_count = 0;

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

void fake_untyped_interface() {
}

void fake_string_name_from_utf8_and_len(
		FoundryExtensionUninitializedStringNamePtr,
		const char *,
		FoundryExtensionInt) {
	native_string_name_construct_count++;
}

void fake_string_name_destroy(FoundryExtensionTypePtr) {
	native_string_name_destroy_count++;
}

FoundryExtensionPtrDestructor fake_transport_variant_destructor(FoundryExtensionVariantType type) {
	return type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME ? &fake_string_name_destroy : nullptr;
}

std::uint64_t fake_native_struct_size(FoundryExtensionConstStringNamePtr) {
	return 128;
}

GDObjectInstanceID fake_object_instance_id(FoundryExtensionConstObjectPtr object) {
	return object == reinterpret_cast<FoundryExtensionConstObjectPtr>(0x1234) ? 91 : 0;
}

FoundryExtensionObjectPtr fake_object_from_id(GDObjectInstanceID id) {
	requested_object_id = id;
	return id == 91 ? reinterpret_cast<FoundryExtensionObjectPtr>(0x1234) : nullptr;
}

void fake_object_destroy(FoundryExtensionObjectPtr object) {
	if (object == reinterpret_cast<FoundryExtensionObjectPtr>(0x1234)) {
		native_object_destroy_count++;
	}
}

void *fake_ref_counted_class_tag(FoundryExtensionConstStringNamePtr) {
	return reinterpret_cast<void *>(0x55);
}

FoundryExtensionObjectPtr fake_object_cast_to(FoundryExtensionConstObjectPtr object, void *class_tag) {
	return class_tag == reinterpret_cast<void *>(0x55) ?
			const_cast<FoundryExtensionObjectPtr>(object) :
			nullptr;
}

FoundryExtensionMethodBindPtr fake_ref_method_bind(
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionInt hash) {
	ref_hashes_valid = ref_hashes_valid && hash == 2240911060;
	ref_method_lookup_count++;
	return reinterpret_cast<FoundryExtensionMethodBindPtr>(
			static_cast<std::uintptr_t>(ref_method_lookup_count));
}

void fake_ref_ptrcall(
		FoundryExtensionMethodBindPtr method,
		FoundryExtensionObjectPtr,
		const FoundryExtensionConstTypePtr *,
		FoundryExtensionTypePtr result) {
	const auto index = reinterpret_cast<std::uintptr_t>(method);
	if (index == 1) {
		ref_reference_count++;
		*static_cast<FoundryExtensionBool *>(result) = 1;
	} else if (index == 2) {
		ref_unreference_count++;
		*static_cast<FoundryExtensionBool *>(result) = 1;
	}
}

FoundryExtensionVariantType fake_variant_get_type(FoundryExtensionConstVariantPtr) {
	return copied_variant_type;
}

void fake_variant_new_copy(
		FoundryExtensionUninitializedVariantPtr,
		FoundryExtensionConstVariantPtr) {
	variant_copy_count++;
}

void fake_variant_destroy(FoundryExtensionVariantPtr) {
	variant_destroy_count++;
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
	return erase_function_type(&fake_untyped_interface);
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

void test_generated_abi_layout_is_complete() {
	expect(foundry_java::kFloat32AbiLayout.size() == 40, "float_32 layout must contain 40 rows");
	expect(foundry_java::kFloat64AbiLayout.size() == 40, "float_64 layout must contain 40 rows");
	expect(foundry_java::kActiveAbiLayout.front().name == "Nil", "Nil must be the first layout row");
	expect(foundry_java::kActiveAbiLayout.front().size == 0, "Nil layout size must be zero");
	for (std::size_t index = 1; index < foundry_java::kActiveAbiLayout.size(); index++) {
		expect(foundry_java::kActiveAbiLayout[index].size > 0, "non-Nil layout sizes must be positive");
		expect(
				foundry_java::kFloat32AbiLayout[index].name == foundry_java::kFloat64AbiLayout[index].name,
				"float layouts must use identical name order");
	}
	expect(foundry_java::abi_layout_size("String") == sizeof(void *), "String size must match pointer width");
	expect(foundry_java::abi_layout_size("StringName") == sizeof(void *), "StringName size must match pointer width");
	expect(foundry_java::abi_layout_size("Object") == sizeof(void *), "Object size must match pointer width");
	expect(foundry_java::abi_layout_size("Variant") == 24, "Variant size must remain 24 bytes");
}

void test_bridge_services_resolve_all_or_nothing() {
	const auto complete = foundry_java::resolve_bridge_services(complete_get_proc_address);
	expect(complete.services != nullptr, "complete interface table must resolve");
	expect(complete.missing_name.empty(), "complete interface table must not report a missing name");
	expect(complete.services->print_error == &fake_print_error, "resolved services must preserve exact pointers");

	const auto incomplete = foundry_java::resolve_bridge_services(incomplete_get_proc_address);
	expect(incomplete.services == nullptr, "incomplete interface table must not publish services");
	expect(
			incomplete.missing_name == "variant_get_ptr_destructor",
			"resolution must report the first exact missing interface name");
}

void test_typed_handles_reject_wrong_identity_and_destroy_once() {
	foundry_java::NativeHandleStore handles;
	std::atomic<int> destroy_count = 0;
	foundry_java::NativeValue value = foundry_java::NativeValue::storage(24);
	value.constructed = true;
	const auto handle = handles.insert(
			11,
			7,
			foundry_java::HandleKind::VARIANT,
			"Variant",
			std::move(value),
			true,
			[&](foundry_java::HandleRecord &record) {
				expect(record.value.constructed, "destructor must receive constructed storage");
				record.value.constructed = false;
				destroy_count++;
			});
	expect(handle != 0, "native handles must be opaque and nonzero");
	expect(
			static_cast<bool>(
					handles.acquire(handle, 11, 7, foundry_java::HandleKind::VARIANT, "Variant")),
			"matching handle identity must acquire");
	expect(
			!handles.acquire(handle, 12, 7, foundry_java::HandleKind::VARIANT, "Variant"),
			"cross-context handle must be rejected");
	expect(
			!handles.acquire(handle, 11, 8, foundry_java::HandleKind::VARIANT, "Variant"),
			"stale generation must be rejected");
	expect(
			!handles.acquire(handle, 11, 7, foundry_java::HandleKind::OBJECT, "Variant"),
			"wrong handle kind must be rejected");
	expect(
			!handles.acquire(handle, 11, 7, foundry_java::HandleKind::VARIANT, "String"),
			"wrong native type token must be rejected");
	expect(
			!handles.release(handle, 12, 7, foundry_java::HandleKind::VARIANT, "Variant"),
			"wrong-context release must fail closed");
	expect(
			handles.release(handle, 11, 7, foundry_java::HandleKind::VARIANT, "Variant"),
			"matching release must succeed");
	expect(destroy_count == 1, "owned storage must be destroyed exactly once");
	expect(
			!handles.release(handle, 11, 7, foundry_java::HandleKind::VARIANT, "Variant"),
			"released handle must stay dead");
	expect(destroy_count == 1, "repeated release must not destroy twice");
}

void test_handle_teardown_waits_for_active_lease() {
	foundry_java::NativeHandleStore handles;
	std::atomic<int> destroy_count = 0;
	const auto handle = handles.insert(
			22,
			4,
			foundry_java::HandleKind::NATIVE_STRUCTURE,
			"PhysicsServer3DExtensionMotionResult",
			foundry_java::NativeValue::storage(128),
			true,
			[&](foundry_java::HandleRecord &) { destroy_count++; });
	auto lease = handles.acquire(
			handle,
			22,
			4,
			foundry_java::HandleKind::NATIVE_STRUCTURE,
			"PhysicsServer3DExtensionMotionResult");
	expect(static_cast<bool>(lease), "matching native-structure handle must acquire");

	std::atomic<bool> teardown_finished = false;
	std::thread teardown([&] {
		expect(handles.teardown(22, 4) == 1, "teardown must own the matching live handle");
		teardown_finished = true;
	});
	while (handles.acquire(
				   handle,
				   22,
				   4,
				   foundry_java::HandleKind::NATIVE_STRUCTURE,
				   "PhysicsServer3DExtensionMotionResult")) {
		std::this_thread::yield();
	}
	expect(!teardown_finished, "teardown must wait for the active handle lease");
	expect(destroy_count == 0, "teardown cannot destroy active storage");
	lease = {};
	teardown.join();
	expect(teardown_finished, "teardown must finish after the lease drains");
	expect(destroy_count == 1, "teardown must destroy owned storage exactly once");
}

void test_variant_inventory_and_dispatch_validation() {
	const auto &categories = foundry_java::variant_categories();
	expect(categories.size() == 39, "transport must freeze all 39 public Variant categories");
	for (std::size_t index = 0; index < categories.size(); index++) {
		expect(
				static_cast<std::size_t>(categories[index].abi_type) == index,
				"Variant categories must preserve ABI enum order");
		expect(!categories[index].java_name.empty(), "every Variant category needs a stable Java name");
	}

	foundry_java::NativeDispatch dispatch;
	dispatch.kind = foundry_java::DispatchKind::CLASS_METHOD;
	dispatch.minimum_argument_count = 1;
	dispatch.argument_native_types = { "int", "String" };
	expect(foundry_java::validate_dispatch(dispatch, 1, {}).valid, "minimum fixed arity must pass");
	expect(foundry_java::validate_dispatch(dispatch, 2, {}).valid, "maximum fixed arity must pass");
	expect(!foundry_java::validate_dispatch(dispatch, 0, {}).valid, "below-minimum arity must fail");
	expect(!foundry_java::validate_dispatch(dispatch, 3, {}).valid, "above-maximum fixed arity must fail");
	dispatch.vararg = true;
	expect(foundry_java::validate_dispatch(dispatch, 9, {}).valid, "vararg extras must remain valid");

	dispatch.kind = foundry_java::DispatchKind::BUILTIN_METHOD;
	dispatch.owner_native_type = "Vector2";
	dispatch.static_call = true;
	expect(
			!foundry_java::validate_dispatch(dispatch, 1, {}).valid,
			"built-in methods require a separate receiver even when static");
	expect(
			!foundry_java::validate_dispatch(dispatch, 1, "Vector3").valid,
			"built-in receiver type must match the owner");
	expect(
			foundry_java::validate_dispatch(dispatch, 1, "Vector2").valid,
			"matching built-in receiver must pass");
	dispatch.kind = foundry_java::DispatchKind::UTILITY_FUNCTION;
	expect(
			!foundry_java::validate_dispatch(dispatch, 1, "Vector2").valid,
			"utility functions must reject an implicit receiver");

	expect(static_cast<int>(foundry_java::DispatchKind::CLASS_METHOD) == 1, "class method wire code");
	expect(static_cast<int>(foundry_java::DispatchKind::UTILITY_FUNCTION) == 9, "utility wire code");
}

void test_native_structure_and_object_transport() {
	auto services = std::make_shared<foundry_java::BridgeServices>();
	services->string_name_new_with_utf8_chars_and_len = &fake_string_name_from_utf8_and_len;
	services->variant_get_ptr_destructor = &fake_transport_variant_destructor;
	services->get_native_struct_size = &fake_native_struct_size;
	services->object_get_instance_id = &fake_object_instance_id;
	services->object_get_instance_from_id = &fake_object_from_id;
	services->object_destroy = &fake_object_destroy;
	foundry_java::NativeTransport transport(services);

	native_string_name_construct_count = 0;
	native_string_name_destroy_count = 0;
	const auto structure = transport.create_native_structure(
			31,
			5,
			"PhysicsServer3DExtensionMotionResult");
	expect(structure != 0, "native structure allocation must produce an opaque handle");
	auto structure_lease = transport.handles().acquire(
			structure,
			31,
			5,
			foundry_java::HandleKind::NATIVE_STRUCTURE,
			"PhysicsServer3DExtensionMotionResult");
	expect(static_cast<bool>(structure_lease), "native structure handle must preserve its exact type token");
	expect(structure_lease.record().value.byte_size == 128, "native structure must use interface-reported size");
	expect(native_string_name_construct_count == 1, "type lookup must construct one StringName");
	expect(native_string_name_destroy_count == 1, "type lookup must destroy its StringName on success");
	structure_lease = {};
	expect(
			transport.handles().release(
					structure,
					31,
					5,
					foundry_java::HandleKind::NATIVE_STRUCTURE,
					"PhysicsServer3DExtensionMotionResult"),
			"native structure must release");

	native_object_destroy_count = 0;
	requested_object_id = 0;
	const auto object = transport.track_object(
			31,
			5,
			reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"Resource",
			true);
	expect(object != 0, "object transport must return an opaque instance-ID handle");
	auto object_lease = transport.acquire_object(object, 31, 5, "Resource");
	expect(
			object_lease.object == reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"object lookup must reacquire the pointer from its instance ID");
	expect(requested_object_id == 91, "object lookup must use the stored unsigned instance ID");
	object_lease = {};
	expect(
			transport.handles().release(object, 31, 5, foundry_java::HandleKind::OBJECT, "Resource"),
			"owned object handle must release");
	expect(native_object_destroy_count == 1, "owned object release must destroy exactly once");
}

void test_dispatch_families_and_ref_counted_ownership() {
	foundry_java::NativeDispatch dispatch;
	dispatch.kind = foundry_java::DispatchKind::CLASS_METHOD;
	dispatch.argument_native_types = { "Variant" };
	dispatch.return_native_type = "Variant";
	expect(
			foundry_java::dispatch_family(dispatch) == foundry_java::DispatchFamily::CLASS_VARIANT_CALL,
			"Variant-only class methods must use object_method_bind_call");
	dispatch.argument_native_types = { "Vector3" };
	expect(
			foundry_java::dispatch_family(dispatch) == foundry_java::DispatchFamily::CLASS_PTRCALL,
			"typed class methods must use object_method_bind_ptrcall");
	dispatch.vararg = true;
	expect(
			foundry_java::dispatch_family(dispatch) == foundry_java::DispatchFamily::CLASS_VARIANT_CALL,
			"vararg class methods must use Variant call semantics");
	dispatch.vararg = false;
	for (int wire_code = 2; wire_code <= 9; wire_code++) {
		dispatch.kind = static_cast<foundry_java::DispatchKind>(wire_code);
		expect(
				foundry_java::dispatch_family(dispatch) != foundry_java::DispatchFamily::INVALID,
				"every frozen dispatch kind must map to a native family");
	}
	expect(
			foundry_java::validate_value_backend(
					FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE,
					foundry_java::ValueBackend::JAVA_LOCAL)
					.valid,
			"Java-local Callable must be supported through callable_custom_create2");
	expect(
			!foundry_java::validate_value_backend(
					 FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL,
					 foundry_java::ValueBackend::JAVA_LOCAL)
					 .valid,
			"Java-local Signal must fail because the ABI has no custom constructor");
	expect(
			foundry_java::validate_value_backend(
					FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL,
					foundry_java::ValueBackend::NATIVE)
					.valid,
			"native-backed Signal must round-trip");

	auto services = std::make_shared<foundry_java::BridgeServices>();
	services->string_name_new_with_utf8_chars_and_len = &fake_string_name_from_utf8_and_len;
	services->variant_get_ptr_destructor = &fake_transport_variant_destructor;
	services->classdb_get_class_tag = &fake_ref_counted_class_tag;
	services->object_cast_to = &fake_object_cast_to;
	services->classdb_get_method_bind = &fake_ref_method_bind;
	services->object_method_bind_ptrcall = &fake_ref_ptrcall;
	services->object_get_instance_id = &fake_object_instance_id;
	services->object_get_instance_from_id = &fake_object_from_id;
	services->object_destroy = &fake_object_destroy;
	foundry_java::NativeTransport transport(services);
	ref_method_lookup_count = 0;
	ref_reference_count = 0;
	ref_unreference_count = 0;
	ref_hashes_valid = true;
	native_object_destroy_count = 0;
	const auto ref_handle = transport.retain_ref_counted(
			44,
			2,
			reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"Resource");
	expect(ref_handle != 0, "validated RefCounted object must retain");
	expect(ref_method_lookup_count == 2, "reference and unreference MethodBinds must resolve exactly once");
	expect(ref_hashes_valid, "reference MethodBinds must use compatibility hash 2240911060");
	expect(ref_reference_count == 1, "retain must invoke RefCounted.reference");
	expect(
			transport.handles().release(
					ref_handle,
					44,
					2,
					foundry_java::HandleKind::OBJECT,
					"Resource"),
			"retained RefCounted handle must release");
	expect(ref_unreference_count == 1, "release must invoke RefCounted.unreference exactly once");
	expect(native_object_destroy_count == 1, "true unreference result must destroy the object exactly once");
}

void test_all_variant_categories_copy_and_destroy_through_public_abi() {
	auto services = std::make_shared<foundry_java::BridgeServices>();
	services->variant_get_type = &fake_variant_get_type;
	services->variant_new_copy = &fake_variant_new_copy;
	services->variant_destroy = &fake_variant_destroy;
	foundry_java::NativeTransport transport(services);
	variant_copy_count = 0;
	variant_destroy_count = 0;
	std::max_align_t source_storage[4]{};
	for (const auto &category : foundry_java::variant_categories()) {
		copied_variant_type = category.abi_type;
		const auto handle = transport.copy_variant(
				70,
				9,
				source_storage,
				category.abi_type);
		expect(handle != 0, "every public Variant category must copy into opaque storage");
		expect(
				transport.handles().release(
						handle,
						70,
						9,
						foundry_java::HandleKind::VARIANT,
						std::string(category.java_name)),
				"copied Variant category must release");
	}
	expect(variant_copy_count == 39, "all 39 Variant categories must use variant_new_copy");
	expect(variant_destroy_count == 39, "all 39 copied Variants must destroy exactly once");
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
	test_generated_abi_layout_is_complete();
	test_bridge_services_resolve_all_or_nothing();
	test_typed_handles_reject_wrong_identity_and_destroy_once();
	test_handle_teardown_waits_for_active_lease();
	test_variant_inventory_and_dispatch_validation();
	test_native_structure_and_object_transport();
	test_dispatch_families_and_ref_counted_ownership();
	test_all_variant_categories_copy_and_destroy_through_public_abi();
	std::cout << "Foundry Java native runtime tests passed\n";
	return 0;
}
