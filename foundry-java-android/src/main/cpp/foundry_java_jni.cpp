#include "foundry_java_runtime.h"

#include "foundry_java_abi_layout.h"
#include "foundry_java_contract.h"
#include "foundry_java_registration_bridge.h"
#include "foundry_java_transport.h"

#include <jni.h>

#include <atomic>
#include <array>
#include <cstring>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace foundry_java {

namespace {

constexpr char INITIALIZER_CLASS[] = "games/cafecito/foundry/java/FoundryJavaInitializer";
constexpr char ENGINE_CLASS[] =
		"games/cafecito/foundry/java/FoundryNativeEngine";
constexpr char SNAPSHOT_CLASS[] =
		"games/cafecito/foundry/java/FoundryNativeEngine$NativeVariantSnapshot";
constexpr char VARIANT_CLASS[] = "games/cafecito/foundry/types/Variant";

class JniRegistrationBridge;

class FoundryErrorSink final : public ErrorSink {
public:
	void install(FoundryExtensionInterfacePrintError error_function) noexcept {
		print_error.store(error_function, std::memory_order_release);
	}

	void error(const std::string &message) noexcept override {
		auto function = print_error.load(std::memory_order_acquire);
		if (function != nullptr) {
			function(
					message.c_str(),
					"foundry_java",
					"foundry_java_jni.cpp",
					0,
					0);
		}
	}

private:
	std::atomic<FoundryExtensionInterfacePrintError> print_error = nullptr;
};

class AttachedEnvironment final {
public:
	explicit AttachedEnvironment(JavaVM *java_vm) : java_vm(java_vm) {
		if (java_vm == nullptr) {
			return;
		}
		const jint status = java_vm->GetEnv(reinterpret_cast<void **>(&environment), JNI_VERSION_1_6);
		if (status == JNI_EDETACHED) {
#if defined(__ANDROID__)
			const jint attach_status =
					java_vm->AttachCurrentThread(&environment, nullptr);
#else
			const jint attach_status = java_vm->AttachCurrentThread(
					reinterpret_cast<void **>(&environment), nullptr);
#endif
			if (attach_status == JNI_OK) {
				attached_here = true;
			} else {
				environment = nullptr;
			}
		} else if (status != JNI_OK) {
			environment = nullptr;
		}
	}

	~AttachedEnvironment() {
		if (attached_here) {
			java_vm->DetachCurrentThread();
		}
	}

	JNIEnv *get() const {
		return environment;
	}

private:
	JavaVM *java_vm = nullptr;
	JNIEnv *environment = nullptr;
	bool attached_here = false;
};

class GlobalReferenceGuard final {
public:
	GlobalReferenceGuard(JNIEnv *environment, jobject reference) :
			environment(environment),
			reference(reference) {
	}

	~GlobalReferenceGuard() {
		if (environment != nullptr && reference != nullptr) {
			environment->DeleteGlobalRef(reference);
		}
	}

	GlobalReferenceGuard(const GlobalReferenceGuard &) = delete;
	GlobalReferenceGuard &operator=(const GlobalReferenceGuard &) = delete;

	jobject get() const {
		return reference;
	}

	jobject release() {
		return std::exchange(reference, nullptr);
	}

private:
	JNIEnv *environment = nullptr;
	jobject reference = nullptr;
};

bool clear_java_exception(JNIEnv *environment, const std::shared_ptr<FoundryErrorSink> &errors, const char *operation) {
	if (environment == nullptr || !environment->ExceptionCheck()) {
		return false;
	}
	environment->ExceptionClear();
	errors->error(std::string("Java exception contained during ") + operation + ".");
	return true;
}

template <typename Value>
bool jni_reference_failed(
		JNIEnv *environment,
		Value value,
		const std::shared_ptr<FoundryErrorSink> &errors,
		const char *operation) {
	const bool exception_was_cleared = clear_java_exception(environment, errors, operation);
	if (value == nullptr && !exception_was_cleared) {
		errors->error(std::string("JNI returned null during ") + operation + ".");
	}
	return value == nullptr || exception_was_cleared;
}

class JniCallbackTarget final : public CallbackTarget {
public:
	JniCallbackTarget(
			JavaVM *java_vm,
			jobject callbacks,
			jmethodID initialize_method,
			jmethodID deinitialize_method,
			jmethodID invoke_method,
			jmethodID invalidate_method,
			jmethodID terminal_cleanup_complete_method,
			std::shared_ptr<FoundryErrorSink> errors) :
			java_vm(java_vm),
			callbacks(callbacks),
			initialize_method(initialize_method),
			deinitialize_method(deinitialize_method),
			invoke_method(invoke_method),
			invalidate_method(invalidate_method),
			terminal_cleanup_complete_method(terminal_cleanup_complete_method),
			errors(std::move(errors)) {
	}

	~JniCallbackTarget() override {
		AttachedEnvironment attached(java_vm);
		if (attached.get() != nullptr && callbacks != nullptr) {
			attached.get()->DeleteGlobalRef(callbacks);
		}
	}

	bool initialize(ContextHandle context, std::int32_t level) override {
		AttachedEnvironment attached(java_vm);
		JNIEnv *environment = attached.get();
		if (environment == nullptr) {
			errors->error("Could not attach the Foundry Java initialization callback thread.");
			return false;
		}
		const jboolean result = environment->CallBooleanMethod(
				callbacks,
				initialize_method,
				static_cast<jlong>(context),
				static_cast<jint>(level));
		if (clear_java_exception(environment, errors, "initialization callback")) {
			return false;
		}
		return result == JNI_TRUE;
	}

	void deinitialize(ContextHandle context, std::int32_t level) override {
		AttachedEnvironment attached(java_vm);
		JNIEnv *environment = attached.get();
		if (environment == nullptr) {
			errors->error("Could not attach the Foundry Java deinitialization callback thread.");
			return;
		}
		environment->CallVoidMethod(
				callbacks,
				deinitialize_method,
				static_cast<jlong>(context),
				static_cast<jint>(level));
		clear_java_exception(environment, errors, "deinitialization callback");
	}

	std::int64_t invoke(
			ContextHandle context,
			std::int64_t callback,
			const std::vector<std::int64_t> &arguments) override {
		AttachedEnvironment attached(java_vm);
		JNIEnv *environment = attached.get();
		if (environment == nullptr) {
			errors->error("Could not attach the Foundry Java callback thread.");
			return 0;
		}
		jlongArray java_arguments = environment->NewLongArray(static_cast<jsize>(arguments.size()));
		if (jni_reference_failed(environment, java_arguments, errors, "argument allocation")) {
			return 0;
		}
		if (!arguments.empty()) {
			std::vector<jlong> java_argument_values;
			java_argument_values.reserve(arguments.size());
			for (const std::int64_t argument : arguments) {
				java_argument_values.push_back(static_cast<jlong>(argument));
			}
			environment->SetLongArrayRegion(
					java_arguments,
					0,
					static_cast<jsize>(arguments.size()),
					java_argument_values.data());
			if (clear_java_exception(environment, errors, "argument marshaling")) {
				environment->DeleteLocalRef(java_arguments);
				return 0;
			}
		}
		const jlong result = environment->CallLongMethod(
				callbacks,
				invoke_method,
				static_cast<jlong>(context),
				static_cast<jlong>(callback),
				java_arguments);
		environment->DeleteLocalRef(java_arguments);
		if (clear_java_exception(environment, errors, "runtime callback")) {
			return 0;
		}
		return static_cast<std::int64_t>(result);
	}

	bool invalidate(ContextHandle context) override {
		AttachedEnvironment attached(java_vm);
		JNIEnv *environment = attached.get();
		if (environment == nullptr) {
			errors->error("Could not attach the Foundry Java invalidation callback thread.");
			return false;
		}
		environment->CallVoidMethod(callbacks, invalidate_method, static_cast<jlong>(context));
		return !clear_java_exception(environment, errors, "context invalidation");
	}

	bool terminal_cleanup_complete(ContextHandle context) override {
		AttachedEnvironment attached(java_vm);
		JNIEnv *environment = attached.get();
		if (environment == nullptr) {
			errors->error("Could not attach the Foundry Java terminal cleanup query thread.");
			return false;
		}
		const jboolean complete = environment->CallBooleanMethod(
				callbacks,
				terminal_cleanup_complete_method,
				static_cast<jlong>(context));
		if (clear_java_exception(environment, errors, "terminal cleanup query")) {
			return false;
		}
		return complete == JNI_TRUE;
	}

private:
	JavaVM *java_vm = nullptr;
	jobject callbacks = nullptr;
	jmethodID initialize_method = nullptr;
	jmethodID deinitialize_method = nullptr;
	jmethodID invoke_method = nullptr;
	jmethodID invalidate_method = nullptr;
	jmethodID terminal_cleanup_complete_method = nullptr;
	std::shared_ptr<FoundryErrorSink> errors;
};

struct JniState {
	JniTransitionState transition;
	std::shared_ptr<FoundryErrorSink> errors = std::make_shared<FoundryErrorSink>();
	std::mutex registration_mutex;
	std::shared_ptr<JniRegistrationBridge> registration;
};

JniState state;

class BootstrapReservation {
public:
	bool begin(JNIEnv *environment, jobject requested_class_loader, JavaVM *&java_vm) {
		jobject requested_global = environment->NewGlobalRef(requested_class_loader);
		if (jni_reference_failed(
					environment,
					requested_global,
					state.errors,
					"application class loader pinning")) {
			return false;
		}
		GlobalReferenceGuard loader_guard(environment, requested_global);
		JniTransitionState::Token java_vm_token = 0;
		ticket = state.transition.reserve_bootstrap(java_vm_token);
		if (ticket == 0) {
			return false;
		}
		this->environment = environment;
		this->requested_global = loader_guard.release();
		java_vm = reinterpret_cast<JavaVM *>(java_vm_token);
		active = true;
		return true;
	}

	bool publish(std::shared_ptr<BridgeRuntime> runtime) {
		if (!active || environment == nullptr || requested_global == nullptr) {
			return false;
		}
		JniTransitionState::Token previous_class_loader = 0;
		if (!state.transition.publish_bootstrap(
					ticket,
					std::move(runtime),
					reinterpret_cast<JniTransitionState::Token>(requested_global),
					previous_class_loader)) {
			return false;
		}
		requested_global = nullptr;
		active = false;
		environment->DeleteGlobalRef(
				reinterpret_cast<jobject>(previous_class_loader));
		return true;
	}

	~BootstrapReservation() {
		if (!active) {
			return;
		}
		(void)state.transition.cancel_bootstrap(ticket);
		if (environment != nullptr && requested_global != nullptr) {
			environment->DeleteGlobalRef(requested_global);
		}
	}

	BootstrapReservation() = default;
	BootstrapReservation(const BootstrapReservation &) = delete;
	BootstrapReservation &operator=(const BootstrapReservation &) = delete;

private:
	JNIEnv *environment = nullptr;
	jobject requested_global = nullptr;
	JniTransitionState::Ticket ticket = 0;
	bool active = false;
};

std::string contract_java_string(JNIEnv *environment, jstring value) {
	if (value == nullptr) {
		return {};
	}
	const char *utf = environment->GetStringUTFChars(value, nullptr);
	if (utf == nullptr) {
		clear_java_exception(environment, state.errors, "contract string conversion");
		return {};
	}
	std::string result(utf);
	environment->ReleaseStringUTFChars(value, utf);
	return result;
}

bool contract_matches(
		JNIEnv *environment,
		jstring api_sha256,
		jstring generator_version,
		jstring runtime_version,
		jstring bridge_version) {
	return contract_java_string(environment, api_sha256) == contract::API_SHA256 &&
			contract_java_string(environment, generator_version) == contract::GENERATOR_VERSION &&
			contract_java_string(environment, runtime_version) == contract::RUNTIME_VERSION &&
			contract_java_string(environment, bridge_version) == contract::BRIDGE_CONTRACT_VERSION;
}

std::shared_ptr<BridgeRuntime> live_runtime() {
	return state.transition.runtime();
}

bool install_registration_bridge(
		std::shared_ptr<const BridgeServices> services) noexcept;
void reset_registration_bridge() noexcept;
bool shutdown_context_registration(ContextHandle context,
		std::uint64_t generation) noexcept;

} // namespace

bool jni_bridge_is_ready() noexcept {
	return state.transition.ready();
}

ContextHandle jni_bridge_create_context() noexcept {
	try {
		auto runtime = live_runtime();
		return runtime == nullptr ? 0 : runtime->create_native_context();
	} catch (...) {
		try {
			state.errors->error("Could not allocate a Foundry Java context.");
		} catch (...) {
		}
		return 0;
	}
}

bool jni_bridge_initialize(ContextHandle context, std::int32_t level) noexcept {
	auto runtime = live_runtime();
	return runtime != nullptr && runtime->initialize(context, level);
}

void jni_bridge_deinitialize(ContextHandle context, std::int32_t level) noexcept {
	auto runtime = live_runtime();
	if (runtime != nullptr) {
		runtime->deinitialize(context, level);
	}
}

bool jni_bridge_shutdown_context(ContextHandle context, std::int32_t level) noexcept {
	auto runtime = live_runtime();
	return runtime != nullptr && runtime->shutdown_context(context, level);
}

void jni_bridge_install_foundry_error_interface(FoundryExtensionInterfacePrintError print_error) noexcept {
	state.errors->install(print_error);
}

bool jni_bridge_install_native_services(
		std::shared_ptr<const BridgeServices> services,
		FoundryExtensionClassLibraryPtr library) noexcept {
	auto runtime = live_runtime();
	if (runtime == nullptr || services == nullptr ||
			!install_registration_bridge(services)) {
	return false;
	}
	if (!runtime->install_native_services(std::move(services), library)) {
		reset_registration_bridge();
		return false;
	}
	return true;
}

bool jni_bridge_shutdown() noexcept {
	std::shared_ptr<BridgeRuntime> runtime;
	const JniTransitionState::Ticket ticket =
			state.transition.reserve_shutdown(runtime);
	if (ticket == 0) {
		return false;
	}
	if (runtime != nullptr &&
			!runtime->shutdown_all(FOUNDRY_EXTENSION_INITIALIZATION_CORE)) {
		(void)state.transition.cancel_shutdown(ticket, runtime);
		return false;
	}
	JniTransitionState::Token java_vm_token = 0;
	JniTransitionState::Token class_loader_token = 0;
	if (!state.transition.finish_shutdown(
				ticket, runtime, java_vm_token, class_loader_token)) {
		(void)state.transition.cancel_shutdown(ticket, runtime);
		return false;
	}
	auto *java_vm = reinterpret_cast<JavaVM *>(java_vm_token);
	auto class_loader = reinterpret_cast<jobject>(class_loader_token);
	AttachedEnvironment attached(java_vm);
	if (attached.get() != nullptr && class_loader != nullptr) {
		attached.get()->DeleteGlobalRef(class_loader);
	}
	reset_registration_bridge();
	state.errors->install(nullptr);
	return true;
}

} // namespace foundry_java

#define FOUNDRY_JAVA_JNI_EXPORT extern "C" JNIEXPORT __attribute__((visibility("default")))

FOUNDRY_JAVA_JNI_EXPORT jint JNICALL JNI_OnLoad(JavaVM *java_vm, void *) {
	try {
		JNIEnv *environment = nullptr;
		if (java_vm == nullptr ||
				java_vm->GetEnv(reinterpret_cast<void **>(&environment), JNI_VERSION_1_6) != JNI_OK ||
				environment == nullptr) {
			return JNI_ERR;
		}
		jclass initializer = environment->FindClass(foundry_java::INITIALIZER_CLASS);
		if (initializer == nullptr || environment->ExceptionCheck()) {
			environment->ExceptionClear();
			return JNI_ERR;
		}
		jclass class_class = environment->FindClass("java/lang/Class");
		if (class_class == nullptr || environment->ExceptionCheck()) {
			environment->ExceptionClear();
			environment->DeleteLocalRef(initializer);
			return JNI_ERR;
		}
		jmethodID get_class_loader =
				environment->GetMethodID(class_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
		jobject local_loader =
				get_class_loader == nullptr ? nullptr : environment->CallObjectMethod(initializer, get_class_loader);
		if (local_loader == nullptr || environment->ExceptionCheck()) {
			environment->ExceptionClear();
			environment->DeleteLocalRef(class_class);
			environment->DeleteLocalRef(initializer);
			return JNI_ERR;
		}
		jobject global_loader = environment->NewGlobalRef(local_loader);
		environment->DeleteLocalRef(local_loader);
		environment->DeleteLocalRef(class_class);
		environment->DeleteLocalRef(initializer);
		if (global_loader == nullptr || environment->ExceptionCheck()) {
			environment->ExceptionClear();
			return JNI_ERR;
		}
		foundry_java::GlobalReferenceGuard loader_guard(environment, global_loader);
		if (!foundry_java::state.transition.install(
					reinterpret_cast<foundry_java::JniTransitionState::Token>(java_vm),
					reinterpret_cast<foundry_java::JniTransitionState::Token>(global_loader))) {
			return JNI_ERR;
		}
		(void)loader_guard.release();
		return JNI_VERSION_1_6;
	} catch (...) {
		return JNI_ERR;
	}
}

FOUNDRY_JAVA_JNI_EXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
	try {
		(void)foundry_java::jni_bridge_shutdown();
		foundry_java::state.transition.clear_java_vm();
	} catch (...) {
	}
}

FOUNDRY_JAVA_JNI_EXPORT jboolean JNICALL
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeBootstrapV1(
		JNIEnv *environment,
		jclass,
		jobject class_loader,
		jobject callbacks,
		jstring api_sha256,
		jstring generator_version,
		jstring runtime_version,
		jstring bridge_version) {
	try {
		if (environment == nullptr || class_loader == nullptr || callbacks == nullptr ||
				!foundry_java::contract_matches(
						environment,
						api_sha256,
						generator_version,
						runtime_version,
						bridge_version)) {
			return JNI_FALSE;
		}
		foundry_java::BootstrapReservation bootstrap;
		JavaVM *java_vm = nullptr;
		if (!bootstrap.begin(environment, class_loader, java_vm)) {
			return JNI_FALSE;
		}
		jclass callback_class = environment->GetObjectClass(callbacks);
		if (foundry_java::jni_reference_failed(
					environment,
					callback_class,
					foundry_java::state.errors,
					"callback class resolution")) {
			return JNI_FALSE;
		}
		jmethodID initialize_method = environment->GetMethodID(callback_class, "initialize", "(JI)Z");
		if (foundry_java::jni_reference_failed(
					environment,
					initialize_method,
					foundry_java::state.errors,
					"initialize callback resolution")) {
			environment->DeleteLocalRef(callback_class);
			return JNI_FALSE;
		}
		jmethodID deinitialize_method = environment->GetMethodID(callback_class, "deinitialize", "(JI)V");
		if (foundry_java::jni_reference_failed(
					environment,
					deinitialize_method,
					foundry_java::state.errors,
					"deinitialize callback resolution")) {
			environment->DeleteLocalRef(callback_class);
			return JNI_FALSE;
		}
		jmethodID invoke_method = environment->GetMethodID(callback_class, "invoke", "(JJ[J)J");
		if (foundry_java::jni_reference_failed(
					environment,
					invoke_method,
					foundry_java::state.errors,
					"runtime callback resolution")) {
			environment->DeleteLocalRef(callback_class);
			return JNI_FALSE;
		}
		jmethodID invalidate_method = environment->GetMethodID(callback_class, "invalidate", "(J)V");
		if (foundry_java::jni_reference_failed(
					environment,
					invalidate_method,
					foundry_java::state.errors,
					"invalidation callback resolution")) {
			environment->DeleteLocalRef(callback_class);
			return JNI_FALSE;
		}
		jmethodID terminal_cleanup_complete_method =
				environment->GetMethodID(
						callback_class, "terminalCleanupComplete", "(J)Z");
		if (foundry_java::jni_reference_failed(
					environment,
					terminal_cleanup_complete_method,
					foundry_java::state.errors,
					"terminal cleanup callback resolution")) {
			environment->DeleteLocalRef(callback_class);
			return JNI_FALSE;
		}
		jobject global_callbacks = environment->NewGlobalRef(callbacks);
		environment->DeleteLocalRef(callback_class);
		if (foundry_java::jni_reference_failed(
					environment,
					global_callbacks,
					foundry_java::state.errors,
					"bootstrap validation")) {
			if (global_callbacks != nullptr) {
				environment->DeleteGlobalRef(global_callbacks);
			}
			return JNI_FALSE;
		}
		foundry_java::GlobalReferenceGuard callback_guard(environment, global_callbacks);
		auto target = std::make_shared<foundry_java::JniCallbackTarget>(
				java_vm,
				callback_guard.get(),
				initialize_method,
				deinitialize_method,
				invoke_method,
				invalidate_method,
				terminal_cleanup_complete_method,
				foundry_java::state.errors);
		callback_guard.release();
		auto runtime = std::make_shared<foundry_java::BridgeRuntime>(target, foundry_java::state.errors);
		runtime->set_context_teardown(
				[](foundry_java::ContextHandle context, std::uint64_t generation) {
					return foundry_java::shutdown_context_registration(context, generation);
				});
		return bootstrap.publish(std::move(runtime)) ? JNI_TRUE : JNI_FALSE;
	} catch (...) {
		return JNI_FALSE;
	}
}

FOUNDRY_JAVA_JNI_EXPORT jlong JNICALL
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeCreateContextV1(
		JNIEnv *,
		jclass) {
	try {
		return static_cast<jlong>(foundry_java::jni_bridge_create_context());
	} catch (...) {
		return 0;
	}
}

FOUNDRY_JAVA_JNI_EXPORT jlong JNICALL
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jlong callback,
		jlongArray arguments) {
	try {
		std::vector<std::int64_t> native_arguments;
		if (arguments != nullptr) {
			const jsize size = environment->GetArrayLength(arguments);
			std::vector<jlong> java_arguments(static_cast<std::size_t>(size));
			environment->GetLongArrayRegion(
					arguments,
					0,
					size,
					java_arguments.data());
			if (foundry_java::clear_java_exception(
						environment,
						foundry_java::state.errors,
						"argument unmarshaling")) {
				return 0;
			}
			native_arguments.reserve(java_arguments.size());
			for (const jlong argument : java_arguments) {
				native_arguments.push_back(static_cast<std::int64_t>(argument));
			}
		}
		auto runtime = foundry_java::live_runtime();
		return runtime == nullptr ?
				0 :
				static_cast<jlong>(runtime->invoke(
						static_cast<foundry_java::ContextHandle>(context),
						static_cast<std::int64_t>(callback),
						native_arguments));
	} catch (...) {
		return 0;
	}
}

FOUNDRY_JAVA_JNI_EXPORT jlong JNICALL
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackOnThreadV1(
		JNIEnv *environment,
		jclass type,
		jlong context,
		jlong callback,
		jlongArray arguments) {
	try {
		std::vector<std::int64_t> native_arguments;
		if (arguments != nullptr) {
			const jsize size = environment->GetArrayLength(arguments);
			std::vector<jlong> java_arguments(static_cast<std::size_t>(size));
			environment->GetLongArrayRegion(
					arguments,
					0,
					size,
					java_arguments.data());
			if (foundry_java::clear_java_exception(
						environment,
						foundry_java::state.errors,
						"argument unmarshaling")) {
				return 0;
			}
			native_arguments.reserve(java_arguments.size());
			for (const jlong argument : java_arguments) {
				native_arguments.push_back(static_cast<std::int64_t>(argument));
			}
		}
		std::int64_t result = 0;
		std::thread callback_thread([&] {
			auto runtime = foundry_java::live_runtime();
			if (runtime != nullptr) {
				result = runtime->invoke(
						static_cast<foundry_java::ContextHandle>(context),
						static_cast<std::int64_t>(callback),
						native_arguments);
			}
		});
		callback_thread.join();
		// std::thread::join synchronizes with callback completion, so the callback's
		// write to result is visible before this thread reads it.
		(void)type;
		return static_cast<jlong>(result);
	} catch (...) {
		return 0;
	}
}

FOUNDRY_JAVA_JNI_EXPORT jboolean JNICALL
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownContextV1(
		JNIEnv *,
		jclass,
		jlong context) {
	try {
		return foundry_java::jni_bridge_shutdown_context(
					   static_cast<foundry_java::ContextHandle>(context),
					   FOUNDRY_EXTENSION_INITIALIZATION_CORE) ?
				JNI_TRUE :
				JNI_FALSE;
	} catch (...) {
		return JNI_FALSE;
	}
}

FOUNDRY_JAVA_JNI_EXPORT void JNICALL
Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownBridgeV1(
		JNIEnv *,
		jclass) {
	try {
		(void)foundry_java::jni_bridge_shutdown();
	} catch (...) {
	}
}

namespace foundry_java {
namespace {

void throw_java(JNIEnv *environment, const char *type, const std::string &message) {
	if (environment == nullptr || environment->ExceptionCheck()) {
		return;
	}
	jclass exception = environment->FindClass(type);
	if (exception != nullptr) {
		environment->ThrowNew(exception, message.c_str());
		environment->DeleteLocalRef(exception);
	}
}

ContextOperationLease require_operation(
		JNIEnv *environment,
		jlong context,
		ContextOperationKind kind = ContextOperationKind::ORDINARY) {
	if (context <= 0) {
		throw_java(environment, "java/lang/IllegalArgumentException", "invalid_context_handle");
		return {};
	}
	auto runtime = live_runtime();
	ContextOperationLease lease =
			runtime == nullptr ?
			ContextOperationLease{} :
			runtime->acquire_operation(static_cast<ContextHandle>(context), kind);
	if (!lease || lease.transport() == nullptr || lease.library() == nullptr) {
		throw_java(environment, "java/lang/IllegalStateException", "native_context_unavailable");
		return {};
	}
	return lease;
}

std::string java_utf8(JNIEnv *environment, jstring value) {
	if (value == nullptr) {
		return {};
	}
	const jsize length = environment->GetStringLength(value);
	const jchar *characters = environment->GetStringChars(value, nullptr);
	if (characters == nullptr) {
		return {};
	}
	std::string result;
	result.reserve(static_cast<std::size_t>(length));
	for (jsize index = 0; index < length; index++) {
		std::uint32_t codepoint = characters[index];
		if (codepoint >= 0xd800 && codepoint <= 0xdbff && index + 1 < length) {
			const std::uint32_t low = characters[index + 1];
			if (low >= 0xdc00 && low <= 0xdfff) {
				codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (low - 0xdc00);
				index++;
			}
		}
		if (codepoint <= 0x7f) {
			result.push_back(static_cast<char>(codepoint));
		} else if (codepoint <= 0x7ff) {
			result.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
			result.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
		} else if (codepoint <= 0xffff) {
			result.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
			result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
			result.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
		} else {
			result.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
			result.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
			result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
			result.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
		}
	}
	environment->ReleaseStringChars(value, characters);
	return result;
}

jstring new_java_string(JNIEnv *environment, const std::string &utf8) {
	std::vector<jchar> characters;
	for (std::size_t index = 0; index < utf8.size();) {
		const std::uint8_t first = static_cast<std::uint8_t>(utf8[index++]);
		std::uint32_t codepoint = first;
		if ((first & 0xe0) == 0xc0 && index < utf8.size()) {
			const std::uint8_t second = static_cast<std::uint8_t>(utf8[index++]);
			codepoint = ((first & 0x1f) << 6) | (second & 0x3f);
		} else if ((first & 0xf0) == 0xe0 && index + 1 < utf8.size()) {
			const std::uint8_t second = static_cast<std::uint8_t>(utf8[index++]);
			const std::uint8_t third = static_cast<std::uint8_t>(utf8[index++]);
			codepoint = ((first & 0x0f) << 12) |
					((second & 0x3f) << 6) |
					(third & 0x3f);
		} else if ((first & 0xf8) == 0xf0 && index + 2 < utf8.size()) {
			const std::uint8_t second = static_cast<std::uint8_t>(utf8[index++]);
			const std::uint8_t third = static_cast<std::uint8_t>(utf8[index++]);
			const std::uint8_t fourth = static_cast<std::uint8_t>(utf8[index++]);
			codepoint = ((first & 0x07) << 18) |
					((second & 0x3f) << 12) |
					((third & 0x3f) << 6) |
					(fourth & 0x3f);
		}
		if (codepoint <= 0xffff) {
			characters.push_back(static_cast<jchar>(codepoint));
		} else {
			codepoint -= 0x10000;
			characters.push_back(static_cast<jchar>(0xd800 + (codepoint >> 10)));
			characters.push_back(static_cast<jchar>(0xdc00 + (codepoint & 0x3ff)));
		}
	}
	return environment->NewString(
			characters.empty() ? nullptr : characters.data(),
			static_cast<jsize>(characters.size()));
}

const VariantCategoryInfo *category_for_java_name(const std::string &java_name) {
	for (const VariantCategoryInfo &category : variant_categories()) {
		if (category.java_name == java_name) {
			return &category;
		}
	}
	return nullptr;
}

jclass load_application_class(JNIEnv *environment, const char *slash_name) {
	if (environment == nullptr || slash_name == nullptr) {
		return nullptr;
	}
	jobject loader = nullptr;
	const JniTransitionState::Token loader_token =
			state.transition.pin_class_loader(
					[environment](JniTransitionState::Token class_loader) {
						return reinterpret_cast<JniTransitionState::Token>(
								environment->NewLocalRef(
										reinterpret_cast<jobject>(class_loader)));
					});
	loader = reinterpret_cast<jobject>(loader_token);
	if (loader == nullptr) {
		return nullptr;
	}
	jclass loader_class = environment->FindClass("java/lang/ClassLoader");
	if (loader_class == nullptr) {
		environment->DeleteLocalRef(loader);
		return nullptr;
	}
	jmethodID load_class = environment->GetMethodID(
			loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
	std::string dotted(slash_name);
	for (char &character : dotted) {
		if (character == '/') {
			character = '.';
		}
	}
	jstring name = new_java_string(environment, dotted);
	auto result = static_cast<jclass>(
			load_class == nullptr || name == nullptr ?
			nullptr :
			environment->CallObjectMethod(loader, load_class, name));
	environment->DeleteLocalRef(name);
	environment->DeleteLocalRef(loader_class);
	environment->DeleteLocalRef(loader);
	return result;
}

class ScopedLocalFrame final {
public:
	explicit ScopedLocalFrame(JNIEnv *environment, jint capacity) :
			environment(environment),
			active(environment != nullptr && environment->PushLocalFrame(capacity) == 0) {
	}

	~ScopedLocalFrame() {
		if (active) {
			environment->PopLocalFrame(nullptr);
		}
	}

	ScopedLocalFrame(const ScopedLocalFrame &) = delete;
	ScopedLocalFrame &operator=(const ScopedLocalFrame &) = delete;

	explicit operator bool() const noexcept {
		return active;
	}

private:
	JNIEnv *environment = nullptr;
	bool active = false;
};

struct SnapshotMethods {
	JNIEnv *environment = nullptr;
	jclass engine = nullptr;
	jclass snapshot = nullptr;
	jclass variant = nullptr;
	jmethodID encode = nullptr;
	jmethodID decode = nullptr;
	jmethodID invoke_callable = nullptr;
	jmethodID constructor = nullptr;
	jmethodID type = nullptr;
	jmethodID integers = nullptr;
	jmethodID reals = nullptr;
	jmethodID text = nullptr;
	jmethodID keys = nullptr;
	jmethodID values = nullptr;
	jmethodID native_context = nullptr;
	jmethodID native_handle = nullptr;
	jmethodID callback = nullptr;
	jmethodID callable_arity = nullptr;

	explicit SnapshotMethods(JNIEnv *environment) : environment(environment) {
		engine = load_application_class(environment, ENGINE_CLASS);
		snapshot = load_application_class(environment, SNAPSHOT_CLASS);
		variant = load_application_class(environment, VARIANT_CLASS);
		if (engine == nullptr || snapshot == nullptr || variant == nullptr) {
			return;
		}
		encode = environment->GetStaticMethodID(
				engine,
				"nativeSnapshotV1",
				"(JLgames/cafecito/foundry/types/Variant;)"
				"Lgames/cafecito/foundry/java/FoundryNativeEngine$NativeVariantSnapshot;");
		decode = environment->GetStaticMethodID(
				engine,
				"nativeVariantFromSnapshotV1",
				"(JJLgames/cafecito/foundry/java/FoundryNativeEngine$NativeVariantSnapshot;)"
				"Lgames/cafecito/foundry/types/Variant;");
		invoke_callable = environment->GetStaticMethodID(
				engine,
				"invokeLocalCallableV1",
				"(JLgames/cafecito/foundry/runtime/FoundryCallable;"
				"[Lgames/cafecito/foundry/types/Variant;)"
				"Lgames/cafecito/foundry/types/Variant;");
		constructor = environment->GetMethodID(
				snapshot,
				"<init>",
				"(I[J[DLjava/lang/String;[Lgames/cafecito/foundry/types/Variant;"
				"[Lgames/cafecito/foundry/types/Variant;JJ"
				"Lgames/cafecito/foundry/runtime/FoundryCallable;I)V");
		type = environment->GetMethodID(snapshot, "type", "()I");
		integers = environment->GetMethodID(snapshot, "integers", "()[J");
		reals = environment->GetMethodID(snapshot, "reals", "()[D");
		text = environment->GetMethodID(snapshot, "text", "()Ljava/lang/String;");
		keys = environment->GetMethodID(
				snapshot, "keys", "()[Lgames/cafecito/foundry/types/Variant;");
		values = environment->GetMethodID(
				snapshot, "values", "()[Lgames/cafecito/foundry/types/Variant;");
		native_context = environment->GetMethodID(snapshot, "nativeContext", "()J");
		native_handle = environment->GetMethodID(snapshot, "nativeHandle", "()J");
		callback = environment->GetMethodID(
				snapshot,
				"callback",
				"()Lgames/cafecito/foundry/runtime/FoundryCallable;");
		callable_arity = environment->GetMethodID(snapshot, "callableArity", "()I");
	}

	~SnapshotMethods() {
		if (environment != nullptr) {
			if (engine != nullptr) {
				environment->DeleteLocalRef(engine);
			}
			if (snapshot != nullptr) {
				environment->DeleteLocalRef(snapshot);
			}
			if (variant != nullptr) {
				environment->DeleteLocalRef(variant);
			}
		}
	}

	explicit operator bool() const {
		return engine != nullptr && snapshot != nullptr && variant != nullptr &&
				encode != nullptr && decode != nullptr && invoke_callable != nullptr &&
				constructor != nullptr && type != nullptr && integers != nullptr && reals != nullptr &&
				text != nullptr && keys != nullptr && values != nullptr &&
				native_context != nullptr && native_handle != nullptr && callback != nullptr &&
				callable_arity != nullptr;
	}
};

jobject make_snapshot(
		JNIEnv *environment,
		const SnapshotMethods &methods,
		std::int32_t type,
		const std::vector<jlong> &integers,
		const std::vector<jdouble> &reals,
		const std::string &text,
		const std::vector<jobject> &keys,
		const std::vector<jobject> &values) {
	jlongArray integer_array = environment->NewLongArray(static_cast<jsize>(integers.size()));
	jdoubleArray real_array = environment->NewDoubleArray(static_cast<jsize>(reals.size()));
	jclass variant_class = methods.variant;
	jobjectArray key_array = environment->NewObjectArray(
			static_cast<jsize>(keys.size()), variant_class, nullptr);
	jobjectArray value_array = environment->NewObjectArray(
			static_cast<jsize>(values.size()), variant_class, nullptr);
	if (integer_array == nullptr || real_array == nullptr || variant_class == nullptr ||
			key_array == nullptr || value_array == nullptr) {
		if (integer_array != nullptr) {
			environment->DeleteLocalRef(integer_array);
		}
		if (real_array != nullptr) {
			environment->DeleteLocalRef(real_array);
		}
		if (key_array != nullptr) {
			environment->DeleteLocalRef(key_array);
		}
		if (value_array != nullptr) {
			environment->DeleteLocalRef(value_array);
		}
		return nullptr;
	}
	if (!integers.empty()) {
		environment->SetLongArrayRegion(
				integer_array, 0, static_cast<jsize>(integers.size()), integers.data());
	}
	if (!reals.empty()) {
		environment->SetDoubleArrayRegion(
				real_array, 0, static_cast<jsize>(reals.size()), reals.data());
	}
	for (std::size_t index = 0; index < keys.size(); index++) {
		environment->SetObjectArrayElement(key_array, static_cast<jsize>(index), keys[index]);
	}
	for (std::size_t index = 0; index < values.size(); index++) {
		environment->SetObjectArrayElement(value_array, static_cast<jsize>(index), values[index]);
	}
	jstring java_text = new_java_string(environment, text);
	jobject result = environment->NewObject(
			methods.snapshot,
			methods.constructor,
			static_cast<jint>(type),
			integer_array,
			real_array,
			java_text,
			key_array,
			value_array,
			static_cast<jlong>(0),
			static_cast<jlong>(0),
			nullptr,
			static_cast<jint>(-1));
	environment->DeleteLocalRef(integer_array);
	environment->DeleteLocalRef(real_array);
	environment->DeleteLocalRef(java_text);
	environment->DeleteLocalRef(key_array);
	environment->DeleteLocalRef(value_array);
	return result;
}

NativeHandle encode_variant(
		JNIEnv *environment,
		ContextHandle context,
		std::uint64_t generation,
		NativeTransport &transport,
		jobject variant,
		int depth);

jobject decode_variant(
		JNIEnv *environment,
		ContextHandle context,
		std::uint64_t generation,
		NativeTransport &transport,
		NativeHandle handle,
		int depth);

jobject decode_variant_internal(
		JNIEnv *environment,
		ContextHandle context,
		std::uint64_t generation,
		NativeTransport &transport,
		NativeHandle handle,
		int depth,
		std::vector<NativeHandle> &transferred_handles);

template <typename Value>
std::vector<Value> primitive_array(
		JNIEnv *environment,
		jarray array,
		void (JNIEnv::*reader)(jlongArray, jsize, jsize, jlong *)) {
	const jsize size = environment->GetArrayLength(array);
	std::vector<Value> result(static_cast<std::size_t>(size));
	std::vector<jlong> source(static_cast<std::size_t>(size));
	(environment->*reader)(static_cast<jlongArray>(array), 0, size, source.data());
	for (jsize index = 0; index < size; index++) {
		result[static_cast<std::size_t>(index)] = static_cast<Value>(source[static_cast<std::size_t>(index)]);
	}
	return result;
}

struct JavaCallableReference {
	JavaVM *java_vm = nullptr;
	jobject callable = nullptr;

	~JavaCallableReference() {
		AttachedEnvironment attached(java_vm);
		if (attached.get() != nullptr && callable != nullptr) {
			attached.get()->DeleteGlobalRef(callable);
		}
	}
};

NativeHandle encode_variant(
		JNIEnv *environment,
		ContextHandle context,
		std::uint64_t generation,
		NativeTransport &transport,
		jobject variant,
		int depth) {
	if (environment == nullptr || variant == nullptr || depth > 128) {
		throw_java(environment, "java/lang/IllegalArgumentException", "variant_encode_depth");
		return 0;
	}
	ScopedLocalFrame local_frame(environment, 32);
	if (!local_frame) {
		throw_java(environment, "java/lang/IllegalStateException", "variant_local_frame");
		return 0;
	}
	SnapshotMethods methods(environment);
	if (!methods) {
		throw_java(environment, "java/lang/IllegalStateException", "variant_snapshot_contract");
		return 0;
	}
	jobject snapshot = environment->CallStaticObjectMethod(
			methods.engine, methods.encode, static_cast<jlong>(context), variant);
	if (snapshot == nullptr || environment->ExceptionCheck()) {
		return 0;
	}
	const auto type = static_cast<FoundryExtensionVariantType>(
			environment->CallIntMethod(snapshot, methods.type));
	const jlong native_context = environment->CallLongMethod(snapshot, methods.native_context);
	const jlong native_handle = environment->CallLongMethod(snapshot, methods.native_handle);
	if (environment->ExceptionCheck() || type < FOUNDRY_EXTENSION_VARIANT_TYPE_NIL ||
			type >= FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX) {
		throw_java(environment, "java/lang/IllegalArgumentException", "invalid_native_variant_type");
		return 0;
	}
	if (native_handle > 0) {
		if (native_context != static_cast<jlong>(context)) {
			throw_java(environment, "java/lang/IllegalArgumentException", "native_value_context_mismatch");
			return 0;
		}
		return transport.copy_native_backed_variant(
				context, generation, static_cast<NativeHandle>(native_handle), type);
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING ||
			type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME ||
			type == FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH) {
		auto text = static_cast<jstring>(environment->CallObjectMethod(snapshot, methods.text));
		if (jni_reference_failed(
					environment, text, state.errors, "variant text snapshot")) {
			return 0;
		}
		return transport.construct_text_variant(context, generation, type, java_utf8(environment, text));
	}
	auto integers = static_cast<jlongArray>(
			environment->CallObjectMethod(snapshot, methods.integers));
	if (jni_reference_failed(
				environment, integers, state.errors, "variant integer snapshot")) {
		return 0;
	}
	auto reals = static_cast<jdoubleArray>(
			environment->CallObjectMethod(snapshot, methods.reals));
	if (jni_reference_failed(
				environment, reals, state.errors, "variant real snapshot")) {
		return 0;
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_NIL) {
		return transport.construct_variant(context, generation, type, nullptr, ValueBackend::JAVA_LOCAL);
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL) {
		jlong raw = 0;
		environment->GetLongArrayRegion(integers, 0, 1, &raw);
		const FoundryExtensionBool value = raw != 0;
		return transport.construct_variant(context, generation, type, &value, ValueBackend::JAVA_LOCAL);
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_INT) {
		jlong value = 0;
		environment->GetLongArrayRegion(integers, 0, 1, &value);
		const std::int64_t native_value = value;
		return transport.construct_variant(
				context, generation, type, &native_value, ValueBackend::JAVA_LOCAL);
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT) {
		jdouble value = 0;
		environment->GetDoubleArrayRegion(reals, 0, 1, &value);
		return transport.construct_variant(context, generation, type, &value, ValueBackend::JAVA_LOCAL);
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_RID) {
		jlong value = 0;
		environment->GetLongArrayRegion(integers, 0, 1, &value);
		const std::uint64_t local_rid = static_cast<std::uint64_t>(value);
		return transport.construct_variant(
				context, generation, type, &local_rid, ValueBackend::JAVA_LOCAL);
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT) {
		jlong object_handle = 0;
		environment->GetLongArrayRegion(integers, 0, 1, &object_handle);
		HandleLease object = transport.handles().inspect(
				static_cast<NativeHandle>(object_handle), context, generation);
		if (!object || object.record().kind != HandleKind::OBJECT) {
			return 0;
		}
		const std::string expected_type = object.record().expected_type;
		object = {};
		return transport.construct_object_variant(
				context,
				generation,
				static_cast<NativeHandle>(object_handle),
				expected_type);
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE) {
		jobject callback = environment->CallObjectMethod(snapshot, methods.callback);
		const jint arity = environment->CallIntMethod(snapshot, methods.callable_arity);
		jlong identity = 0;
		environment->GetLongArrayRegion(integers, 0, 1, &identity);
		if (callback == nullptr || identity <= 0 || environment->ExceptionCheck()) {
			return 0;
		}
		jobject global = environment->NewGlobalRef(callback);
		if (global == nullptr) {
			return 0;
		}
		auto reference = std::make_shared<JavaCallableReference>();
		reference->java_vm = reinterpret_cast<JavaVM *>(
				state.transition.java_vm());
		reference->callable = global;
		return transport.construct_local_callable(
				context,
				generation,
				[reference, context](
						const FoundryExtensionConstVariantPtr *arguments,
						FoundryExtensionInt argument_count,
						FoundryExtensionVariantPtr result,
						FoundryExtensionCallError *error) {
					if (error != nullptr) {
						*error = { FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD, 0, 0 };
					}
					auto runtime = live_runtime();
					ContextOperationLease operation =
							runtime == nullptr ? ContextOperationLease{} :
														 runtime->acquire_operation(context);
					AttachedEnvironment attached(reference->java_vm);
					JNIEnv *callback_environment = attached.get();
					if (!operation || operation.transport() == nullptr ||
							callback_environment == nullptr || error == nullptr || result == nullptr ||
							(argument_count > 0 && arguments == nullptr) || argument_count < 0) {
						return;
					}
					SnapshotMethods callback_methods(callback_environment);
					if (!callback_methods) {
						clear_java_exception(
								callback_environment, state.errors, "local Callable contract resolution");
						return;
					}
					jobjectArray java_arguments = callback_environment->NewObjectArray(
							static_cast<jsize>(argument_count), callback_methods.variant, nullptr);
					if (java_arguments == nullptr || callback_environment->ExceptionCheck()) {
						clear_java_exception(
								callback_environment, state.errors, "local Callable argument allocation");
						return;
					}
					std::vector<NativeHandle> temporary;
					std::vector<NativeHandle> transferred;
					bool converted = true;
					for (FoundryExtensionInt index = 0; index < argument_count; index++) {
						NativeHandle copied = 0;
						for (const VariantCategoryInfo &category : variant_categories()) {
							copied = operation.transport()->copy_variant(
									context,
									operation.generation(),
									arguments[index],
									category.abi_type);
							if (copied != 0) {
								break;
							}
						}
						if (copied == 0) {
							converted = false;
							break;
						}
						temporary.push_back(copied);
						jobject decoded = decode_variant_internal(
								callback_environment,
								context,
								operation.generation(),
								*operation.transport(),
								copied,
								0,
								transferred);
						if (decoded == nullptr || callback_environment->ExceptionCheck()) {
							converted = false;
							if (decoded != nullptr) {
								callback_environment->DeleteLocalRef(decoded);
							}
							break;
						}
						callback_environment->SetObjectArrayElement(
								java_arguments, static_cast<jsize>(index), decoded);
						callback_environment->DeleteLocalRef(decoded);
						if (callback_environment->ExceptionCheck()) {
							converted = false;
							break;
						}
					}
					jobject returned = nullptr;
					bool invoked = false;
					if (converted) {
						invoked = true;
						returned = callback_environment->CallStaticObjectMethod(
								callback_methods.engine,
								callback_methods.invoke_callable,
								static_cast<jlong>(context),
								reference->callable,
								java_arguments);
					}
					NativeHandle encoded =
							callback_environment->ExceptionCheck() || returned == nullptr ?
							0 :
							encode_variant(
									callback_environment,
									context,
									operation.generation(),
									*operation.transport(),
									returned,
									0);
					if (encoded != 0 &&
							operation.transport()
									->copy_variant_to(
											encoded,
											context,
											operation.generation(),
											result)
									.ok) {
						*error = { FOUNDRY_EXTENSION_CALL_OK, 0, 0 };
					}
					if (callback_environment->ExceptionCheck()) {
						clear_java_exception(
								callback_environment, state.errors, "local Callable invocation");
					}
					if (encoded != 0) {
						operation.transport()->release_handle(
								encoded, context, operation.generation());
					}
					for (NativeHandle handle : temporary) {
						operation.transport()->release_handle(
								handle, context, operation.generation());
					}
					if (!invoked) {
						for (NativeHandle handle : transferred) {
							operation.transport()->release_handle(
									handle, context, operation.generation());
						}
					}
					if (returned != nullptr) {
						callback_environment->DeleteLocalRef(returned);
					}
					callback_environment->DeleteLocalRef(java_arguments);
				},
				static_cast<std::uint64_t>(identity),
				static_cast<FoundryExtensionInt>(arity));
	}
	if (type >= FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY) {
		auto key_array = static_cast<jobjectArray>(
				environment->CallObjectMethod(snapshot, methods.keys));
		if (jni_reference_failed(
					environment, key_array, state.errors, "variant key snapshot")) {
			return 0;
		}
		auto value_array = static_cast<jobjectArray>(
				environment->CallObjectMethod(snapshot, methods.values));
		if (jni_reference_failed(
					environment, value_array, state.errors, "variant value snapshot")) {
			return 0;
		}
		const jsize key_count = environment->GetArrayLength(key_array);
		const jsize value_count = environment->GetArrayLength(value_array);
		if ((type == FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY && key_count != value_count) ||
				key_count < 0 || value_count < 0) {
			return 0;
		}
		std::vector<NativeHandle> key_handles;
		std::vector<NativeHandle> value_handles;
		for (jsize index = 0; index < key_count; index++) {
			jobject element = environment->GetObjectArrayElement(key_array, index);
			NativeHandle handle =
					encode_variant(environment, context, generation, transport, element, depth + 1);
			environment->DeleteLocalRef(element);
			if (handle == 0) {
				break;
			}
			key_handles.push_back(handle);
		}
		for (jsize index = 0; index < value_count && key_handles.size() == static_cast<std::size_t>(key_count);
				index++) {
			jobject element = environment->GetObjectArrayElement(value_array, index);
			NativeHandle handle =
					encode_variant(environment, context, generation, transport, element, depth + 1);
			environment->DeleteLocalRef(element);
			if (handle == 0) {
				break;
			}
			value_handles.push_back(handle);
		}
		NativeHandle result_handle = 0;
		if (key_handles.size() == static_cast<std::size_t>(key_count) &&
				value_handles.size() == static_cast<std::size_t>(value_count)) {
			result_handle = transport.construct_collection(
					context, generation, type, key_handles, value_handles);
		}
		for (NativeHandle handle : key_handles) {
			transport.release_handle(handle, context, generation);
		}
		for (NativeHandle handle : value_handles) {
			transport.release_handle(handle, context, generation);
		}
		return result_handle;
	}
	const jsize integer_count = environment->GetArrayLength(integers);
	const jsize real_count = environment->GetArrayLength(reals);
	NativeValue native = NativeValue::storage(abi_layout_size(variant_category(type)->native_name));
	if (integer_count > 0) {
		std::vector<jlong> source(static_cast<std::size_t>(integer_count));
		environment->GetLongArrayRegion(integers, 0, integer_count, source.data());
		auto *destination = static_cast<std::int32_t *>(native.data());
		for (jsize index = 0; index < integer_count; index++) {
			destination[index] = static_cast<std::int32_t>(source[static_cast<std::size_t>(index)]);
		}
	} else if (real_count > 0) {
		std::vector<jdouble> source(static_cast<std::size_t>(real_count));
		environment->GetDoubleArrayRegion(reals, 0, real_count, source.data());
		auto *destination = static_cast<float *>(native.data());
		for (jsize index = 0; index < real_count; index++) {
			destination[index] = static_cast<float>(source[static_cast<std::size_t>(index)]);
		}
	}
	return transport.construct_variant(
			context, generation, type, native.data(), ValueBackend::JAVA_LOCAL);
}

jobject decode_variant_internal(
		JNIEnv *environment,
		ContextHandle context,
		std::uint64_t generation,
		NativeTransport &transport,
		NativeHandle handle,
		int depth,
		std::vector<NativeHandle> &transferred_handles) {
	if (depth > 128) {
		throw_java(environment, "java/lang/IllegalArgumentException", "variant_decode_depth");
		return nullptr;
	}
	HandleLease lease = transport.handles().inspect(handle, context, generation);
	if (!lease || lease.record().kind != HandleKind::VARIANT) {
		throw_java(environment, "java/lang/IllegalArgumentException", "invalid_variant_handle");
		return nullptr;
	}
	const VariantCategoryInfo *category = category_for_java_name(lease.record().expected_type);
	if (category == nullptr) {
		return nullptr;
	}
	const FoundryExtensionVariantType type = category->abi_type;
	lease = {};
	SnapshotMethods methods(environment);
	if (!methods) {
		return nullptr;
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_RID ||
			type == FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE ||
			type == FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL) {
		const NativeHandle copy = transport.copy_native_backed_variant(
				context, generation, handle, type);
		if (copy == 0) {
			return nullptr;
		}
		jobject snapshot = make_snapshot(environment, methods, type, {}, {}, "", {}, {});
		jobject decoded = environment->CallStaticObjectMethod(
				methods.engine,
				methods.decode,
				static_cast<jlong>(context),
				static_cast<jlong>(copy),
				snapshot);
		environment->DeleteLocalRef(snapshot);
		if (decoded == nullptr || environment->ExceptionCheck()) {
			transport.release_handle(copy, context, generation);
			return nullptr;
		}
		transferred_handles.push_back(copy);
		return decoded;
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT) {
		bool object_created = false;
		const NativeHandle object =
				transport.track_object_variant(handle, context, generation, &object_created);
		if (object == 0) {
			return nullptr;
		}
		jobject snapshot = make_snapshot(environment, methods, type, {}, {}, "", {}, {});
		jobject decoded = environment->CallStaticObjectMethod(
				methods.engine,
				methods.decode,
				static_cast<jlong>(context),
				static_cast<jlong>(object),
				snapshot);
		environment->DeleteLocalRef(snapshot);
		if (decoded == nullptr || environment->ExceptionCheck()) {
			if (object_created) {
				transport.release_handle(object, context, generation);
			}
			return nullptr;
		}
		if (object_created) {
			transferred_handles.push_back(object);
		}
		return decoded;
	}
	std::vector<jlong> integers;
	std::vector<jdouble> reals;
	std::string text;
	std::vector<jobject> keys;
	std::vector<jobject> values;
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING ||
			type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME ||
			type == FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH) {
		if (!transport.inspect_text_variant(handle, context, generation, type, text).ok) {
			return nullptr;
		}
	} else if (type >= FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY) {
		HandleLease collection = transport.handles().inspect(handle, context, generation);
		if (!collection) {
			return nullptr;
		}
		const FoundryExtensionConstVariantPtr raw = collection.record().value.data();
		TransportResult iterated = transport.collection_iterate(
				raw,
				type,
				[&](FoundryExtensionConstVariantPtr key, FoundryExtensionConstVariantPtr value) {
					NativeHandle key_handle = 0;
					if (key != nullptr) {
						for (const VariantCategoryInfo &candidate : variant_categories()) {
							key_handle = transport.copy_variant(
									context, generation, key, candidate.abi_type);
							if (key_handle != 0) {
								break;
							}
						}
					}
					NativeHandle value_handle = 0;
					for (const VariantCategoryInfo &candidate : variant_categories()) {
						value_handle = transport.copy_variant(
								context, generation, value, candidate.abi_type);
						if (value_handle != 0) {
							break;
						}
					}
					jobject decoded_key =
							key_handle == 0 ? nullptr :
											  decode_variant_internal(
													  environment,
													  context,
													  generation,
													  transport,
													  key_handle,
													  depth + 1,
													  transferred_handles);
					jobject decoded_value =
							value_handle == 0 ? nullptr :
												decode_variant_internal(
														environment,
														context,
														generation,
														transport,
														value_handle,
														depth + 1,
														transferred_handles);
					if (key_handle != 0) {
						transport.release_handle(key_handle, context, generation);
					}
					if (value_handle != 0) {
						transport.release_handle(value_handle, context, generation);
					}
					if ((key != nullptr && decoded_key == nullptr) || decoded_value == nullptr) {
						return false;
					}
					if (decoded_key != nullptr) {
						jobject global_key = environment->NewGlobalRef(decoded_key);
						environment->DeleteLocalRef(decoded_key);
						if (global_key == nullptr) {
							environment->DeleteLocalRef(decoded_value);
							return false;
						}
						keys.push_back(global_key);
					}
					jobject global_value = environment->NewGlobalRef(decoded_value);
					environment->DeleteLocalRef(decoded_value);
					if (global_value == nullptr) {
						return false;
					}
					values.push_back(global_value);
					return true;
				});
		if (!iterated.ok) {
			for (jobject key : keys) {
				environment->DeleteGlobalRef(key);
			}
			for (jobject value : values) {
				environment->DeleteGlobalRef(value);
			}
			return nullptr;
		}
	} else {
		NativeValue native = NativeValue::storage(abi_layout_size(category->native_name));
		if (!transport.inspect_variant(handle, context, generation, type, native.data()).ok) {
			return nullptr;
		}
		if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL) {
			integers.push_back(*static_cast<const FoundryExtensionBool *>(native.data()) != 0);
		} else if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_INT) {
			integers.push_back(*static_cast<const std::int64_t *>(native.data()));
		} else if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT) {
			reals.push_back(*static_cast<const double *>(native.data()));
		} else {
			const std::size_t size = abi_layout_size(category->native_name);
			const bool integer_type =
					type == FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2I ||
					type == FOUNDRY_EXTENSION_VARIANT_TYPE_RECT2I ||
					type == FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3I ||
					type == FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR4I;
			if (integer_type) {
				const auto *source = static_cast<const std::int32_t *>(native.data());
				for (std::size_t index = 0; index < size / sizeof(std::int32_t); index++) {
					integers.push_back(source[index]);
				}
			} else {
				const auto *source = static_cast<const float *>(native.data());
				for (std::size_t index = 0; index < size / sizeof(float); index++) {
					reals.push_back(source[index]);
				}
			}
		}
	}
	jobject snapshot = make_snapshot(environment, methods, type, integers, reals, text, keys, values);
	jobject decoded = environment->CallStaticObjectMethod(
			methods.engine,
			methods.decode,
			static_cast<jlong>(context),
			static_cast<jlong>(0),
			snapshot);
	environment->DeleteLocalRef(snapshot);
	for (jobject key : keys) {
		environment->DeleteGlobalRef(key);
	}
	for (jobject value : values) {
		environment->DeleteGlobalRef(value);
	}
	return decoded;
}

jobject decode_variant(
		JNIEnv *environment,
		ContextHandle context,
		std::uint64_t generation,
		NativeTransport &transport,
		NativeHandle handle,
		int depth) {
	std::vector<NativeHandle> transferred_handles;
	jobject decoded = decode_variant_internal(
			environment,
			context,
			generation,
			transport,
			handle,
			depth,
			transferred_handles);
	if (decoded == nullptr || environment->ExceptionCheck()) {
		for (NativeHandle transferred : transferred_handles) {
			transport.release_handle(transferred, context, generation);
		}
	}
	return decoded;
}

struct JniRegistrationMethods {
	JavaVM *java_vm = nullptr;
	jclass engine = nullptr;
	jclass variant = nullptr;
	jclass constant_details = nullptr;
	jclass property_details = nullptr;
	jmethodID descriptor_foundry_name = nullptr;
	jmethodID descriptor_base_name = nullptr;
	jmethodID registration_members = nullptr;
	jmethodID registration_access = nullptr;
	jmethodID registration_details = nullptr;
	jmethodID member_kind = nullptr;
	jmethodID member_foundry_name = nullptr;
	jmethodID member_java_name = nullptr;
	jmethodID member_signature = nullptr;
	jmethodID constant_enum_name = nullptr;
	jmethodID constant_value = nullptr;
	jmethodID constant_bitfield = nullptr;
	jmethodID property_getter = nullptr;
	jmethodID property_setter = nullptr;
	jmethodID property_index = nullptr;
	jmethodID property_group_name = nullptr;
	jmethodID property_group_prefix = nullptr;
	jmethodID property_subgroup_name = nullptr;
	jmethodID property_subgroup_prefix = nullptr;
	jmethodID resolve_object_type = nullptr;
	jmethodID construct = nullptr;
	jmethodID invoke = nullptr;
	jmethodID get_property = nullptr;
	jmethodID set_property = nullptr;
	jmethodID to_string = nullptr;

	~JniRegistrationMethods() {
		AttachedEnvironment attached(java_vm);
		JNIEnv *environment = attached.get();
		if (environment == nullptr) {
			return;
		}
		for (jclass type : { engine, variant, constant_details, property_details }) {
			if (type != nullptr) {
				environment->DeleteGlobalRef(type);
			}
		}
	}
};

bool method_failed(JNIEnv *environment, jmethodID method,
		const char *operation) {
	return jni_reference_failed(environment, method, state.errors, operation);
}

std::shared_ptr<JniRegistrationMethods>
load_registration_methods(JNIEnv *environment) {
	if (environment == nullptr) {
		return nullptr;
	}
	auto methods = std::make_shared<JniRegistrationMethods>();
	if (environment->GetJavaVM(&methods->java_vm) != JNI_OK) {
		return nullptr;
	}
	const auto pin_class = [&](const char *name, const char *operation) {
		jclass local = load_application_class(environment, name);
		if (jni_reference_failed(environment, local, state.errors, operation)) {
			return static_cast<jclass>(nullptr);
		}
		auto global = static_cast<jclass>(environment->NewGlobalRef(local));
		environment->DeleteLocalRef(local);
		if (jni_reference_failed(environment, global, state.errors, operation)) {
			if (global != nullptr) {
				environment->DeleteGlobalRef(global);
			}
			return static_cast<jclass>(nullptr);
		}
		return global;
	};
	methods->engine =
			pin_class(ENGINE_CLASS, "registration engine class resolution");
	methods->variant =
			pin_class(VARIANT_CLASS, "registration Variant class resolution");
	methods->constant_details =
			pin_class("games/cafecito/foundry/runtime/FoundryConstantDetails",
					"registration constant details class resolution");
	methods->property_details =
			pin_class("games/cafecito/foundry/runtime/FoundryPropertyDetails",
					"registration property details class resolution");
	jclass descriptor = load_application_class(
			environment, "games/cafecito/foundry/runtime/FoundryClassDescriptor");
	jclass member = load_application_class(
			environment, "games/cafecito/foundry/runtime/FoundryMemberDescriptor");
	if (methods->engine == nullptr || methods->variant == nullptr ||
			methods->constant_details == nullptr ||
			methods->property_details == nullptr ||
			jni_reference_failed(environment, descriptor, state.errors,
					"registration descriptor class resolution") ||
			jni_reference_failed(environment, member, state.errors,
					"registration member class resolution")) {
		if (descriptor != nullptr) {
			environment->DeleteLocalRef(descriptor);
		}
		if (member != nullptr) {
			environment->DeleteLocalRef(member);
		}
		return nullptr;
	}
	methods->descriptor_foundry_name = environment->GetMethodID(
			descriptor, "foundryName", "()Ljava/lang/String;");
	methods->descriptor_base_name =
			environment->GetMethodID(descriptor, "baseName", "()Ljava/lang/String;");
	methods->registration_members = environment->GetStaticMethodID(
			methods->engine, "nativeRegistrationMembersV1",
			"(Lgames/cafecito/foundry/runtime/FoundryClassDescriptor;)"
			"[Lgames/cafecito/foundry/runtime/FoundryMemberDescriptor;");
	methods->registration_access = environment->GetStaticMethodID(
			methods->engine, "nativeRegistrationAccessV1",
			"(Lgames/cafecito/foundry/runtime/FoundryClassDescriptor;)"
			"Lgames/cafecito/foundry/runtime/FoundryExtensionAccess;");
	methods->registration_details = environment->GetStaticMethodID(
			methods->engine, "nativeRegistrationDetailsV1",
			"(Lgames/cafecito/foundry/runtime/FoundryMemberDescriptor;)"
			"Lgames/cafecito/foundry/runtime/FoundryMemberDetails;");
	methods->member_kind =
			environment->GetMethodID(member, "kind", "()Ljava/lang/String;");
	methods->member_foundry_name =
			environment->GetMethodID(member, "foundryName", "()Ljava/lang/String;");
	methods->member_java_name =
			environment->GetMethodID(member, "javaName", "()Ljava/lang/String;");
	methods->member_signature =
			environment->GetMethodID(member, "signature", "()Ljava/lang/String;");
	methods->constant_enum_name = environment->GetMethodID(
			methods->constant_details, "enumName", "()Ljava/lang/String;");
	methods->constant_value =
			environment->GetMethodID(methods->constant_details, "value", "()J");
	methods->constant_bitfield =
			environment->GetMethodID(methods->constant_details, "bitfield", "()Z");
	methods->property_getter = environment->GetMethodID(
			methods->property_details, "getter", "()Ljava/lang/String;");
	methods->property_setter = environment->GetMethodID(
			methods->property_details, "setter", "()Ljava/lang/String;");
	methods->property_index =
			environment->GetMethodID(methods->property_details, "index", "()I");
	methods->property_group_name = environment->GetMethodID(
			methods->property_details, "groupName", "()Ljava/lang/String;");
	methods->property_group_prefix = environment->GetMethodID(
			methods->property_details, "groupPrefix", "()Ljava/lang/String;");
	methods->property_subgroup_name = environment->GetMethodID(
			methods->property_details, "subgroupName", "()Ljava/lang/String;");
	methods->property_subgroup_prefix = environment->GetMethodID(
			methods->property_details, "subgroupPrefix", "()Ljava/lang/String;");
	methods->resolve_object_type = environment->GetStaticMethodID(
			methods->engine, "nativeRegistrationFoundryTypeV1",
			"(Ljava/lang/String;)Ljava/lang/String;");
	methods->construct = environment->GetStaticMethodID(
			methods->engine, "nativeConstructExtensionV1",
			"(JLgames/cafecito/foundry/runtime/FoundryExtensionAccess;J)"
			"Ljava/lang/Object;");
	methods->invoke = environment->GetStaticMethodID(
			methods->engine, "nativeInvokeExtensionV1",
			"(JLgames/cafecito/foundry/runtime/FoundryExtensionAccess;"
			"Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/String;"
			"Ljava/lang/String;[Lgames/cafecito/foundry/types/Variant;)"
			"Lgames/cafecito/foundry/types/Variant;");
	methods->get_property = environment->GetStaticMethodID(
			methods->engine, "nativeGetExtensionPropertyV1",
			"(JLgames/cafecito/foundry/runtime/FoundryExtensionAccess;"
			"Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)"
			"Lgames/cafecito/foundry/types/Variant;");
	methods->set_property = environment->GetStaticMethodID(
			methods->engine, "nativeSetExtensionPropertyV1",
			"(JLgames/cafecito/foundry/runtime/FoundryExtensionAccess;"
			"Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;"
			"Lgames/cafecito/foundry/types/Variant;)V");
	methods->to_string = environment->GetStaticMethodID(
			methods->engine, "nativeExtensionToStringV1",
			"(Ljava/lang/Object;)Ljava/lang/String;");
	const std::array<std::pair<jmethodID, const char *>, 25> required = { {
			{ methods->descriptor_foundry_name,
					"registration foundry name resolution" },
			{ methods->descriptor_base_name, "registration base name resolution" },
			{ methods->registration_members, "registration members helper resolution" },
			{ methods->registration_access, "registration access helper resolution" },
			{ methods->registration_details, "registration details helper resolution" },
			{ methods->member_kind, "registration member kind resolution" },
			{ methods->member_foundry_name,
					"registration member foundry name resolution" },
			{ methods->member_java_name, "registration member Java name resolution" },
			{ methods->member_signature, "registration member signature resolution" },
			{ methods->constant_enum_name, "registration enum name resolution" },
			{ methods->constant_value, "registration constant value resolution" },
			{ methods->constant_bitfield, "registration bitfield resolution" },
			{ methods->property_getter, "registration getter resolution" },
			{ methods->property_setter, "registration setter resolution" },
			{ methods->property_index, "registration property index resolution" },
			{ methods->property_group_name, "registration property group resolution" },
			{ methods->property_group_prefix,
					"registration property group prefix resolution" },
			{ methods->property_subgroup_name,
					"registration property subgroup resolution" },
			{ methods->property_subgroup_prefix,
					"registration property subgroup prefix resolution" },
			{ methods->resolve_object_type,
					"registration object type helper resolution" },
			{ methods->construct, "registration construct helper resolution" },
			{ methods->invoke, "registration invoke helper resolution" },
			{ methods->get_property, "registration get helper resolution" },
			{ methods->set_property, "registration set helper resolution" },
			{ methods->to_string, "registration string helper resolution" },
	} };
	bool valid = true;
	for (const auto &[method, operation] : required) {
		if (method_failed(environment, method, operation)) {
			valid = false;
			break;
		}
	}
	environment->DeleteLocalRef(member);
	environment->DeleteLocalRef(descriptor);
	return valid ? methods : nullptr;
}

struct JniAccess {
	ContextHandle context = 0;
	std::uint64_t generation = 0;
	FoundryExtensionClassLibraryPtr library = nullptr;
	std::shared_ptr<const BridgeServices> services;
	std::shared_ptr<NativeTransport> transport;
	std::shared_ptr<JniRegistrationMethods> methods;
	jobject access = nullptr;
	std::unordered_map<std::string, std::vector<JavaMethodSignature>>
			methods_by_name;
	std::unordered_map<std::string, JavaMethodSignature> virtuals_by_name;
	std::unordered_map<std::string, JavaTransportType> properties_by_name;
};

struct JniInstance {
	JniAccess *access = nullptr;
	jobject target = nullptr;
	NativeHandle object_handle = 0;
	FoundryExtensionObjectPtr object = nullptr;
};

class CallbackHandleScope final {
public:
	explicit CallbackHandleScope(JniAccess *access) : access(access) {}

	~CallbackHandleScope() {
		if (access == nullptr || access->transport == nullptr) {
			return;
		}
		for (NativeHandle handle : handles) {
			access->transport->release_handle(handle, access->context,
					access->generation);
		}
	}

	void add(NativeHandle handle) {
		if (handle == 0) {
			return;
		}
		try {
			handles.push_back(handle);
		} catch (...) {
			if (access != nullptr && access->transport != nullptr) {
				access->transport->release_handle(handle, access->context,
						access->generation);
			}
			throw;
		}
	}

private:
	JniAccess *access = nullptr;
	std::vector<NativeHandle> handles;
};

class CallbackVariant final {
public:
	explicit CallbackVariant(std::shared_ptr<const BridgeServices> services) : services(std::move(services)),
																			   value(NativeValue::storage(abi_layout_size("Variant"))) {}

	~CallbackVariant() { reset(); }

	CallbackVariant(CallbackVariant &&other) noexcept
			: services(std::move(other.services)),
			  value(std::move(other.value)) {
		other.value.constructed = false;
	}

	CallbackVariant &operator=(CallbackVariant &&other) noexcept {
		if (this != &other) {
			reset();
			services = std::move(other.services);
			value = std::move(other.value);
			other.value.constructed = false;
		}
		return *this;
	}

	CallbackVariant(const CallbackVariant &) = delete;
	CallbackVariant &operator=(const CallbackVariant &) = delete;

	bool copy_from(FoundryExtensionConstVariantPtr source) noexcept {
		if (source == nullptr || services == nullptr ||
				services->variant_new_copy == nullptr) {
			return false;
		}
		services->variant_new_copy(value.data(), source);
		value.constructed = true;
		return true;
	}

	bool initialize_nil() noexcept {
		if (services == nullptr || services->variant_new_nil == nullptr) {
			return false;
		}
		services->variant_new_nil(value.data());
		value.constructed = true;
		return true;
	}

	FoundryExtensionVariantPtr data() noexcept { return value.data(); }

private:
	void reset() noexcept {
		if (value.constructed && services != nullptr &&
				services->variant_destroy != nullptr) {
			services->variant_destroy(value.data());
		}
		value.constructed = false;
	}

	std::shared_ptr<const BridgeServices> services;
	NativeValue value;
};

class CallbackName final {
public:
	CallbackName(const BridgeServices &services, const std::string &text) : value(NativeValue::storage(abi_layout_size("StringName"))) {
		if (services.string_name_new_with_utf8_chars_and_len == nullptr ||
				services.variant_get_ptr_destructor == nullptr) {
			return;
		}
		destructor = services.variant_get_ptr_destructor(
				FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME);
		if (destructor == nullptr) {
			return;
		}
		services.string_name_new_with_utf8_chars_and_len(
				value.data(), text.data(),
				static_cast<FoundryExtensionInt>(text.size()));
		value.constructed = true;
	}
	~CallbackName() {
		if (value.constructed) {
			destructor(value.data());
		}
	}
	explicit operator bool() const noexcept { return value.constructed; }
	FoundryExtensionConstStringNamePtr get() const noexcept {
		return value.data();
	}

private:
	NativeValue value;
	FoundryExtensionPtrDestructor destructor = nullptr;
};

JavaMethodSignature *find_signature(JniAccess *access, const std::string &name,
		FoundryExtensionInt argument_count) {
	if (access == nullptr || argument_count < 0) {
		return nullptr;
	}
	const auto found = access->methods_by_name.find(name);
	if (found == access->methods_by_name.end()) {
		return nullptr;
	}
	JavaMethodSignature *result = nullptr;
	for (JavaMethodSignature &signature : found->second) {
		if (signature.arguments.size() ==
				static_cast<std::size_t>(argument_count)) {
			if (result != nullptr) {
				return nullptr;
			}
			result = &signature;
		}
	}
	return result;
}

NativeHandle copy_callback_variant(JniAccess &access,
		FoundryExtensionConstVariantPtr value,
		const JavaTransportType &type) {
	if (value == nullptr || access.transport == nullptr) {
		return 0;
	}
	if (type.java_type != "games.cafecito.foundry.types.Variant") {
		return access.transport->copy_variant(access.context, access.generation,
				value, type.abi_type);
	}
	for (const VariantCategoryInfo &category : variant_categories()) {
		const NativeHandle result = access.transport->copy_variant(
				access.context, access.generation, value, category.abi_type);
		if (result != 0) {
			return result;
		}
	}
	return 0;
}

jobject decode_callback_variant(JNIEnv *environment, JniAccess &access,
		FoundryExtensionConstVariantPtr value,
		const JavaTransportType &type,
		NativeHandle &temporary) {
	temporary = copy_callback_variant(access, value, type);
	return temporary == 0
			? nullptr
			: decode_variant(environment, access.context, access.generation,
					  *access.transport, temporary, 0);
}

class JniRegistrationCallbacks final : public RegistrationCallbacks {
public:
	explicit JniRegistrationCallbacks(
			std::shared_ptr<JniRegistrationMethods> methods) : methods(std::move(methods)) {}

	void release_access(RegistrationAccessToken token) noexcept override {
		auto *access = reinterpret_cast<JniAccess *>(token);
		if (access == nullptr) {
			return;
		}
		AttachedEnvironment attached(access->methods->java_vm);
		if (attached.get() != nullptr && access->access != nullptr) {
			attached.get()->DeleteGlobalRef(access->access);
		}
		delete access;
	}

	NativeInstance create_instance(
			RegistrationAccessToken token, const std::string &class_name,
			const std::string &base_name, bool notify_postinitialize,
			FoundryExtensionClassInstancePtr instance_userdata) noexcept override {
		try {
			auto *access = reinterpret_cast<JniAccess *>(token);
			if (access == nullptr || access->services == nullptr ||
					access->transport == nullptr || instance_userdata == nullptr) {
				return {};
			}
			auto instance = std::make_unique<JniInstance>();
			instance->access = access;
			const auto &services = *access->services;
			if (services.classdb_construct_object2 == nullptr ||
					services.object_set_instance == nullptr ||
					services.object_destroy == nullptr) {
				return {};
			}
			CallbackName native_base(services, base_name);
			CallbackName native_class(services, class_name);
			if (!native_base || !native_class) {
				return {};
			}
			FoundryExtensionObjectPtr object =
					services.classdb_construct_object2(native_base.get());
			if (object == nullptr) {
				return {};
			}
			services.object_set_instance(object, native_class.get(),
					instance_userdata);
			if (notify_postinitialize) {
				CallbackName object_name(services, "Object");
				CallbackName notification_name(services, "notification");
				if (!object_name || !notification_name ||
						services.classdb_get_method_bind == nullptr ||
						services.object_method_bind_ptrcall == nullptr) {
					services.object_destroy(object);
					return {};
				}
				const FoundryExtensionMethodBindPtr notification =
						services.classdb_get_method_bind(
								object_name.get(), notification_name.get(), 4023243586);
				if (notification == nullptr) {
					services.object_destroy(object);
					return {};
				}
				const std::int32_t postinitialize = 0;
				const FoundryExtensionBool reversed = 0;
				const FoundryExtensionConstTypePtr arguments[] = { &postinitialize,
					&reversed };
				services.object_method_bind_ptrcall(notification, object, arguments,
						nullptr);
			}
			const NativeHandle object_handle = access->transport->track_object(
					access->context, access->generation, object, class_name, false);
			if (object_handle == 0) {
				services.object_destroy(object);
				return {};
			}
			AttachedEnvironment attached(access->methods->java_vm);
			JNIEnv *environment = attached.get();
			if (environment == nullptr) {
				access->transport->release_handle(object_handle, access->context,
						access->generation);
				services.object_destroy(object);
				return {};
			}
			jobject target = environment->CallStaticObjectMethod(
					access->methods->engine, access->methods->construct,
					static_cast<jlong>(access->context), access->access,
					static_cast<jlong>(object_handle));
			if (clear_java_exception(environment, state.errors,
						"extension instance construction") ||
					target == nullptr) {
				if (target != nullptr) {
					environment->DeleteLocalRef(target);
				}
				access->transport->release_handle(object_handle, access->context,
						access->generation);
				services.object_destroy(object);
				return {};
			}
			jobject global_target = environment->NewGlobalRef(target);
			environment->DeleteLocalRef(target);
			if (jni_reference_failed(environment, global_target, state.errors,
						"extension instance retention")) {
				if (global_target != nullptr) {
					environment->DeleteGlobalRef(global_target);
				}
				access->transport->release_handle(object_handle, access->context,
						access->generation);
				services.object_destroy(object);
				return {};
			}
			instance->target = global_target;
			instance->object_handle = object_handle;
			instance->object = object;
			return { object,
				reinterpret_cast<RegistrationInstanceToken>(instance.release()) };
		} catch (...) {
			state.errors->error(
					"Native failure contained during extension instance construction.");
			return {};
		}
	}

	void discard_partial_instance(RegistrationAccessToken token,
			NativeInstance native) noexcept override {
		auto *access = reinterpret_cast<JniAccess *>(token);
		auto *instance = reinterpret_cast<JniInstance *>(native.access_instance);
		FoundryExtensionObjectPtr object_to_destroy = native.object;
		if (instance != nullptr) {
			if (object_to_destroy == nullptr) {
				object_to_destroy = instance->object;
			}
			AttachedEnvironment attached(instance->access->methods->java_vm);
			if (attached.get() != nullptr && instance->target != nullptr) {
				attached.get()->DeleteGlobalRef(instance->target);
			}
			if (instance->object_handle != 0) {
				instance->access->transport->release_handle(
						instance->object_handle, instance->access->context,
						instance->access->generation);
			}
			delete instance;
		}
		if (object_to_destroy != nullptr && access != nullptr &&
				access->services != nullptr &&
				access->services->object_destroy != nullptr) {
			access->services->object_destroy(object_to_destroy);
		}
	}

	void free_instance(RegistrationAccessToken,
			RegistrationInstanceToken token) noexcept override {
		auto *instance = reinterpret_cast<JniInstance *>(token);
		if (instance == nullptr || instance->access == nullptr) {
			return;
		}
		AttachedEnvironment attached(instance->access->methods->java_vm);
		if (attached.get() != nullptr && instance->target != nullptr) {
			attached.get()->DeleteGlobalRef(instance->target);
		}
		if (instance->object_handle != 0) {
			instance->access->transport->release_handle(instance->object_handle,
					instance->access->context,
					instance->access->generation);
		}
		delete instance;
	}

	bool invoke(RegistrationAccessToken access_token,
			RegistrationInstanceToken instance_token,
			const std::string &java_name,
			const FoundryExtensionConstVariantPtr *arguments,
			FoundryExtensionInt argument_count,
			FoundryExtensionVariantPtr result,
			FoundryExtensionCallError *error) noexcept override {
		try {
			auto *access = reinterpret_cast<JniAccess *>(access_token);
			auto *instance = reinterpret_cast<JniInstance *>(instance_token);
			JavaMethodSignature *signature =
					find_signature(access, java_name, argument_count);
			if (access == nullptr || instance == nullptr || signature == nullptr ||
					(argument_count > 0 && arguments == nullptr) ||
					(!signature->return_type.is_void && result == nullptr)) {
				if (error != nullptr) {
					error->error = FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT;
				}
				return false;
			}
			AttachedEnvironment attached(access->methods->java_vm);
			JNIEnv *environment = attached.get();
			ScopedLocalFrame frame(environment, 64 + argument_count * 4);
			if (environment == nullptr || !frame) {
				return false;
			}
			jobjectArray java_arguments =
					environment->NewObjectArray(static_cast<jsize>(argument_count),
							access->methods->variant, nullptr);
			jclass string_class = environment->FindClass("java/lang/String");
			jobjectArray java_types = environment->NewObjectArray(
					static_cast<jsize>(argument_count), string_class, nullptr);
			if (java_arguments == nullptr || string_class == nullptr ||
					java_types == nullptr) {
				clear_java_exception(environment, state.errors,
						"extension callback argument allocation");
				return false;
			}
			CallbackHandleScope temporary_handles(access);
			for (FoundryExtensionInt index = 0; index < argument_count; index++) {
				NativeHandle handle = 0;
				jobject decoded = decode_callback_variant(
						environment, *access, arguments[index],
						signature->arguments[static_cast<std::size_t>(index)], handle);
				if (handle != 0) {
					temporary_handles.add(handle);
				}
				jstring type = new_java_string(
						environment,
						signature->arguments[static_cast<std::size_t>(index)].java_type);
				if (decoded == nullptr || type == nullptr ||
						environment->ExceptionCheck()) {
					clear_java_exception(environment, state.errors,
							"extension callback argument decoding");
					return false;
				}
				environment->SetObjectArrayElement(java_arguments,
						static_cast<jsize>(index), decoded);
				environment->SetObjectArrayElement(java_types,
						static_cast<jsize>(index), type);
			}
			jstring name = new_java_string(environment, java_name);
			jstring return_type =
					new_java_string(environment, signature->return_type.java_type);
			jobject returned = environment->CallStaticObjectMethod(
					access->methods->engine, access->methods->invoke,
					static_cast<jlong>(access->context), access->access, instance->target,
					name, java_types, return_type, java_arguments);
			bool success = !clear_java_exception(environment, state.errors,
								   "extension method callback") &&
					returned != nullptr;
			if (success && !signature->return_type.is_void) {
				const NativeHandle encoded =
						encode_variant(environment, access->context, access->generation,
								*access->transport, returned, 0);
				temporary_handles.add(encoded);
				success =
						encoded != 0 && access->transport->replace_variant_to(
								encoded, access->context, access->generation, result)
												.ok;
			}
			if (!success) {
				clear_java_exception(environment, state.errors,
						"extension callback result encoding");
			}
			return success;
		} catch (...) {
			state.errors->error(
					"Native failure contained during extension method callback.");
			return false;
		}
	}

	bool invoke_virtual(RegistrationAccessToken access_token,
			RegistrationInstanceToken instance_token,
			const std::string &java_name,
			const FoundryExtensionConstTypePtr *arguments,
			FoundryExtensionTypePtr result) noexcept override {
		try {
			auto *access = reinterpret_cast<JniAccess *>(access_token);
			auto *instance = reinterpret_cast<JniInstance *>(instance_token);
			if (access == nullptr || instance == nullptr ||
					access->services == nullptr || access->transport == nullptr ||
					access->services->variant_new_copy == nullptr ||
					access->services->variant_destroy == nullptr) {
				return false;
			}
			const auto found = access->virtuals_by_name.find(java_name);
			if (found == access->virtuals_by_name.end()) {
				return false;
			}
			const JavaMethodSignature &signature = found->second;
			if ((!signature.arguments.empty() && arguments == nullptr) ||
					(!signature.return_type.is_void && result == nullptr)) {
				return false;
			}
			CallbackHandleScope temporary_handles(access);
			std::vector<CallbackVariant> variants;
			std::vector<FoundryExtensionConstVariantPtr> variant_pointers;
			variants.reserve(signature.arguments.size());
			variant_pointers.reserve(signature.arguments.size());
			for (std::size_t index = 0; index < signature.arguments.size(); index++) {
				if (arguments[index] == nullptr) {
					return false;
				}
				variants.emplace_back(access->services);
				CallbackVariant &value = variants.back();
				if (signature.arguments[index].java_type ==
						"games.cafecito.foundry.types.Variant") {
					if (!value.copy_from(arguments[index])) {
						return false;
					}
					variant_pointers.push_back(value.data());
					continue;
				}
				const NativeHandle handle = access->transport->construct_variant(
						access->context, access->generation,
						signature.arguments[index].abi_type, arguments[index]);
				if (handle == 0) {
					return false;
				}
				temporary_handles.add(handle);
				HandleLease lease = access->transport->handles().inspect(
						handle, access->context, access->generation);
				if (!lease || !value.copy_from(lease.record().value.data())) {
					return false;
				}
				variant_pointers.push_back(value.data());
			}
			FoundryExtensionCallError error{};
			CallbackVariant variant_result(access->services);
			if (!variant_result.initialize_nil()) {
				return false;
			}
			const bool invoked = invoke(
					access_token, instance_token, java_name, variant_pointers.data(),
					static_cast<FoundryExtensionInt>(variant_pointers.size()),
					variant_result.data(), &error);
			bool success = invoked;
			if (success && !signature.return_type.is_void) {
				if (signature.return_type.java_type ==
						"games.cafecito.foundry.types.Variant") {
					success = replace_initialized_variant(
							*access->services, result, variant_result.data());
				} else {
					const VariantCategoryInfo *category =
							variant_category(signature.return_type.abi_type);
					if (category == nullptr ||
							!can_replace_initialized_native(
									*access->services,
									signature.return_type.abi_type)) {
						return false;
					}
					const NativeHandle handle = access->transport->copy_variant(
							access->context, access->generation, variant_result.data(),
							signature.return_type.abi_type);
					temporary_handles.add(handle);
					NativeValue typed_result = NativeValue::storage(
							abi_layout_size(category->native_name));
					success =
							handle != 0 &&
							access->transport
									->inspect_variant(handle, access->context, access->generation,
											signature.return_type.abi_type,
											typed_result.data())
									.ok;
					if (success) {
						typed_result.constructed = true;
						success = replace_initialized_native(
								*access->services,
								signature.return_type.abi_type,
								result, typed_result.data());
					}
					if (typed_result.constructed) {
						access->transport->destroy_native_value(
								signature.return_type.abi_type,
								typed_result.data());
					}
				}
			}
			return success;
		} catch (...) {
			state.errors->error(
					"Native failure contained during extension virtual callback.");
			return false;
		}
	}

	void
	initialize_virtual_default(const JavaTransportType &return_type,
			FoundryExtensionTypePtr result) noexcept override {
		if (result == nullptr || return_type.is_void) {
			return;
		}
		if (return_type.java_type == "games.cafecito.foundry.types.Variant") {
			if (methods_services != nullptr &&
					methods_services->variant_new_nil != nullptr) {
				methods_services->variant_new_nil(result);
			}
			return;
		}
		if (return_type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL) {
			*static_cast<FoundryExtensionBool *>(result) = 0;
			return;
		}
		if (return_type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_INT) {
			*static_cast<std::int64_t *>(result) = 0;
			return;
		}
		if (return_type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT) {
			*static_cast<double *>(result) = 0;
			return;
		}
		if (return_type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT) {
			*static_cast<FoundryExtensionObjectPtr *>(result) = nullptr;
			return;
		}
		const FoundryExtensionPtrConstructor constructor =
				methods_services == nullptr ||
						methods_services->variant_get_ptr_constructor == nullptr
				? nullptr
				: methods_services->variant_get_ptr_constructor(
						  return_type.abi_type, 0);
		if (constructor != nullptr) {
			constructor(result, nullptr);
		}
	}

	bool get_property(RegistrationAccessToken access_token,
			RegistrationInstanceToken instance_token,
			const std::string &java_name,
			FoundryExtensionVariantPtr result) noexcept override {
		try {
			auto *access = reinterpret_cast<JniAccess *>(access_token);
			auto *instance = reinterpret_cast<JniInstance *>(instance_token);
			if (access == nullptr || instance == nullptr) {
				return false;
			}
			const auto found = access->properties_by_name.find(java_name);
			if (found == access->properties_by_name.end()) {
				return false;
			}
			AttachedEnvironment attached(access->methods->java_vm);
			JNIEnv *environment = attached.get();
			if (environment == nullptr) {
				return false;
			}
			jstring name = new_java_string(environment, java_name);
			jstring type = new_java_string(environment, found->second.java_type);
			jobject returned = environment->CallStaticObjectMethod(
					access->methods->engine, access->methods->get_property,
					static_cast<jlong>(access->context), access->access, instance->target,
					name, type);
			bool success = !clear_java_exception(environment, state.errors,
								   "extension property get") &&
					returned != nullptr;
			CallbackHandleScope encoded_handles(access);
			const NativeHandle encoded =
					success
					? encode_variant(environment, access->context, access->generation,
							  *access->transport, returned, 0)
					: 0;
			encoded_handles.add(encoded);
			success =
					encoded != 0 && access->transport->replace_variant_to(
							encoded, access->context, access->generation, result)
											.ok;
			if (!success) {
				clear_java_exception(environment, state.errors,
						"extension property result encoding");
			}
			for (jobject local :
					{ returned, static_cast<jobject>(name), static_cast<jobject>(type) }) {
				if (local != nullptr) {
					environment->DeleteLocalRef(local);
				}
			}
			return success;
		} catch (...) {
			state.errors->error(
					"Native failure contained during extension property get.");
			return false;
		}
	}

	bool set_property(RegistrationAccessToken access_token,
			RegistrationInstanceToken instance_token,
			const std::string &java_name,
			FoundryExtensionConstVariantPtr value) noexcept override {
		try {
			auto *access = reinterpret_cast<JniAccess *>(access_token);
			auto *instance = reinterpret_cast<JniInstance *>(instance_token);
			if (access == nullptr || instance == nullptr) {
				return false;
			}
			const auto found = access->properties_by_name.find(java_name);
			if (found == access->properties_by_name.end()) {
				return false;
			}
			AttachedEnvironment attached(access->methods->java_vm);
			JNIEnv *environment = attached.get();
			if (environment == nullptr) {
				return false;
			}
			CallbackHandleScope temporary_handles(access);
			NativeHandle temporary = 0;
			jobject decoded = decode_callback_variant(environment, *access, value,
					found->second, temporary);
			temporary_handles.add(temporary);
			jstring name = new_java_string(environment, java_name);
			jstring type = new_java_string(environment, found->second.java_type);
			if (decoded != nullptr && name != nullptr && type != nullptr) {
				environment->CallStaticVoidMethod(
						access->methods->engine, access->methods->set_property,
						static_cast<jlong>(access->context), access->access,
						instance->target, name, type, decoded);
			}
			const bool callback_exception = clear_java_exception(
					environment, state.errors, "extension property set");
			const bool success = decoded != nullptr && !callback_exception;
			for (jobject local :
					{ decoded, static_cast<jobject>(name), static_cast<jobject>(type) }) {
				if (local != nullptr) {
					environment->DeleteLocalRef(local);
				}
			}
			return success;
		} catch (...) {
			state.errors->error(
					"Native failure contained during extension property set.");
			return false;
		}
	}

	bool to_string(RegistrationAccessToken access_token,
			RegistrationInstanceToken instance_token,
			FoundryExtensionStringPtr result) noexcept override {
		try {
			auto *access = reinterpret_cast<JniAccess *>(access_token);
			auto *instance = reinterpret_cast<JniInstance *>(instance_token);
			if (access == nullptr || instance == nullptr || result == nullptr ||
					access->services->string_new_with_utf8_chars_and_len2 == nullptr) {
				return false;
			}
			AttachedEnvironment attached(access->methods->java_vm);
			JNIEnv *environment = attached.get();
			if (environment == nullptr) {
				return false;
			}
			auto text = static_cast<jstring>(environment->CallStaticObjectMethod(
					access->methods->engine, access->methods->to_string,
					instance->target));
			if (clear_java_exception(environment, state.errors,
						"extension toString") ||
					text == nullptr) {
				return false;
			}
			const std::string utf8 = java_utf8(environment, text);
			environment->DeleteLocalRef(text);
			if (clear_java_exception(environment, state.errors,
						"extension toString conversion")) {
				return false;
			}
			return access->services->string_new_with_utf8_chars_and_len2(
						   result, utf8.data(),
						   static_cast<FoundryExtensionInt>(utf8.size())) == 0;
		} catch (...) {
			state.errors->error(
					"Native failure contained during extension toString.");
			return false;
		}
	}

	void set_services(std::shared_ptr<const BridgeServices> services) {
		methods_services = std::move(services);
	}

private:
	std::shared_ptr<JniRegistrationMethods> methods;
	std::shared_ptr<const BridgeServices> methods_services;
};

std::string call_text(JNIEnv *environment, jobject target, jmethodID method) {
	if (target == nullptr || method == nullptr) {
		return {};
	}
	auto value =
			static_cast<jstring>(environment->CallObjectMethod(target, method));
	if (value == nullptr || environment->ExceptionCheck()) {
		if (value != nullptr) {
			environment->DeleteLocalRef(value);
		}
		return {};
	}
	std::string result = java_utf8(environment, value);
	environment->DeleteLocalRef(value);
	return result;
}

class JniRegistrationBridge final
		: public std::enable_shared_from_this<JniRegistrationBridge> {
public:
	static std::shared_ptr<JniRegistrationBridge>
	create(JNIEnv *environment, std::shared_ptr<const BridgeServices> services) {
		auto methods = load_registration_methods(environment);
		if (methods == nullptr || services == nullptr) {
			return nullptr;
		}
		auto bridge = std::shared_ptr<JniRegistrationBridge>(
				new JniRegistrationBridge(std::move(methods), std::move(services)));
		std::weak_ptr<JniRegistrationBridge> weak = bridge;
		bridge->native_services = std::make_shared<AbiRegistrationServices>(
				bridge->services,
				[weak](const std::string &java_type, std::string &foundry_type) {
					auto live = weak.lock();
					return live != nullptr &&
							live->resolve_object_type(java_type, foundry_type);
				});
		bridge->callbacks =
				std::make_shared<JniRegistrationCallbacks>(bridge->methods);
		bridge->callbacks->set_services(bridge->services);
		bridge->registry = std::make_unique<RegistrationRegistry>(
				bridge->native_services, bridge->callbacks);
		return bridge;
	}

	RegistrationResult
	register_extension_class(JNIEnv *environment, ContextHandle context,
			const ContextOperationLease &operation,
			jobject java_descriptor) noexcept {
		try {
			if (environment == nullptr || java_descriptor == nullptr ||
					operation.services() != services ||
					operation.transport() == nullptr || operation.library() == nullptr) {
				return { RegistrationStatus::INVALID_DESCRIPTOR,
					"registration_descriptor" };
			}
			RegistrationClassDescriptor descriptor;
			descriptor.foundry_name = call_text(environment, java_descriptor,
					methods->descriptor_foundry_name);
			descriptor.base_name = call_text(environment, java_descriptor,
					methods->descriptor_base_name);
			auto java_members =
					static_cast<jobjectArray>(environment->CallStaticObjectMethod(
							methods->engine, methods->registration_members, java_descriptor));
			jobject java_access = environment->CallStaticObjectMethod(
					methods->engine, methods->registration_access, java_descriptor);
			if (java_members == nullptr || java_access == nullptr ||
					environment->ExceptionCheck()) {
				clear_java_exception(environment, state.errors,
						"registration descriptor extraction");
				if (java_members != nullptr) {
					environment->DeleteLocalRef(java_members);
				}
				if (java_access != nullptr) {
					environment->DeleteLocalRef(java_access);
				}
				return { RegistrationStatus::INVALID_DESCRIPTOR,
					"registration_descriptor" };
			}
			auto access = std::make_unique<JniAccess>();
			access->context = context;
			access->generation = operation.generation();
			access->library = operation.library();
			access->services = operation.services();
			access->transport = operation.transport();
			access->methods = methods;
			const jsize member_count = environment->GetArrayLength(java_members);
			descriptor.members.reserve(static_cast<std::size_t>(member_count));
			bool decoded = !environment->ExceptionCheck();
			for (jsize index = 0; decoded && index < member_count; index++) {
				jobject member =
						environment->GetObjectArrayElement(java_members, index);
				if (member == nullptr || environment->ExceptionCheck()) {
					decoded = false;
					break;
				}
				RegistrationMemberDescriptor native;
				const std::string kind =
						call_text(environment, member, methods->member_kind);
				native.foundry_name =
						call_text(environment, member, methods->member_foundry_name);
				native.java_name =
						call_text(environment, member, methods->member_java_name);
				native.signature =
						call_text(environment, member, methods->member_signature);
				if (kind == "method") {
					native.kind = RegistrationMemberKind::METHOD;
				} else if (kind == "override") {
					native.kind = RegistrationMemberKind::OVERRIDE;
				} else if (kind == "constant") {
					native.kind = RegistrationMemberKind::CONSTANT;
				} else if (kind == "property") {
					native.kind = RegistrationMemberKind::PROPERTY;
				} else if (kind == "signal") {
					native.kind = RegistrationMemberKind::SIGNAL;
				} else {
					decoded = false;
				}
				jobject details = decoded ? environment->CallStaticObjectMethod(
													methods->engine,
													methods->registration_details, member)
										  : nullptr;
				if (decoded && native.kind == RegistrationMemberKind::CONSTANT) {
					if (details == nullptr ||
							!environment->IsInstanceOf(details, methods->constant_details)) {
						decoded = false;
					} else {
						native.constant = RegistrationConstantDetails{
							call_text(environment, details, methods->constant_enum_name),
							static_cast<std::int64_t>(environment->CallLongMethod(
									details, methods->constant_value)),
							environment->CallBooleanMethod(
									details, methods->constant_bitfield) == JNI_TRUE,
						};
					}
				} else if (decoded && native.kind == RegistrationMemberKind::PROPERTY &&
						details != nullptr &&
						environment->IsInstanceOf(details,
								methods->property_details)) {
					native.property = RegistrationPropertyDetails{
						call_text(environment, details, methods->property_getter),
						call_text(environment, details, methods->property_setter),
						static_cast<std::int32_t>(
								environment->CallIntMethod(details, methods->property_index)),
						call_text(environment, details, methods->property_group_name),
						call_text(environment, details, methods->property_group_prefix),
						call_text(environment, details, methods->property_subgroup_name),
						call_text(environment, details,
								methods->property_subgroup_prefix),
					};
				}
				if (environment->ExceptionCheck()) {
					decoded = false;
				}
				if (details != nullptr) {
					environment->DeleteLocalRef(details);
				}
				environment->DeleteLocalRef(member);
				if (!decoded) {
					break;
				}
				if (native.kind == RegistrationMemberKind::METHOD ||
						native.kind == RegistrationMemberKind::OVERRIDE) {
					const JavaSignatureParseResult signature =
							parse_java_method_signature(native.signature);
					if (!signature.ok()) {
						decoded = false;
						break;
					}
					auto &signatures = access->methods_by_name[native.java_name];
					const bool duplicate_arity =
							std::any_of(signatures.begin(), signatures.end(),
									[&signature](const JavaMethodSignature &candidate) {
										return candidate.arguments.size() ==
												signature.signature.arguments.size();
									});
					if (duplicate_arity) {
						decoded = false;
						break;
					}
					signatures.push_back(signature.signature);
					if (native.kind == RegistrationMemberKind::OVERRIDE) {
						if (!access->virtuals_by_name
										.emplace(native.java_name, signature.signature)
										.second) {
							decoded = false;
							break;
						}
					}
				} else if (native.kind == RegistrationMemberKind::PROPERTY) {
					const JavaTypeParseResult type =
							parse_java_property_type(native.signature);
					if (!type.ok()) {
						decoded = false;
						break;
					}
					const std::string getter =
							native.property ? native.property->getter : native.java_name;
					if (!access->properties_by_name.emplace(getter, type.type).second) {
						decoded = false;
						break;
					}
					if (native.property && !native.property->setter.empty() &&
							!access->properties_by_name
									.emplace(native.property->setter, type.type)
									.second) {
						decoded = false;
						break;
					}
				}
				descriptor.members.push_back(std::move(native));
			}
			environment->DeleteLocalRef(java_members);
			if (!decoded) {
				clear_java_exception(environment, state.errors,
						"registration descriptor validation");
				environment->DeleteLocalRef(java_access);
				return { RegistrationStatus::INVALID_DESCRIPTOR,
					"registration_descriptor" };
			}
			access->access = environment->NewGlobalRef(java_access);
			environment->DeleteLocalRef(java_access);
			if (jni_reference_failed(environment, access->access, state.errors,
						"registration access retention")) {
				if (access->access != nullptr) {
					environment->DeleteGlobalRef(access->access);
				}
				return { RegistrationStatus::NATIVE_FAILURE, "registration_access" };
			}
			JniAccess *stable_access = access.release();
			descriptor.access =
					reinterpret_cast<RegistrationAccessToken>(stable_access);
			const RegistrationResult result =
					registry->register_class(context, operation.generation(),
							operation.library(), std::move(descriptor));
			if (result.status == RegistrationStatus::INVALID_DESCRIPTOR ||
					result.status == RegistrationStatus::CONFLICT ||
					result.status == RegistrationStatus::RETRY ||
					result.status == RegistrationStatus::STALE) {
				callbacks->release_access(
						reinterpret_cast<RegistrationAccessToken>(stable_access));
			}
			return result;
		} catch (...) {
			return { RegistrationStatus::NATIVE_FAILURE, "registration_jni" };
		}
	}

	RegistrationResult
	unregister_extension_class(ContextHandle context, std::uint64_t generation,
			const std::string &class_name) noexcept {
		return registry->unregister_class(context, generation, class_name);
	}

	RegistrationResult shutdown(ContextHandle context,
			std::uint64_t generation) noexcept {
		return registry->shutdown(context, generation);
	}

private:
	JniRegistrationBridge(std::shared_ptr<JniRegistrationMethods> methods,
			std::shared_ptr<const BridgeServices> services) : methods(std::move(methods)),
															  services(std::move(services)) {}

	bool resolve_object_type(const std::string &java_type,
			std::string &foundry_type) noexcept {
		try {
			AttachedEnvironment attached(methods->java_vm);
			JNIEnv *environment = attached.get();
			if (environment == nullptr) {
				return false;
			}
			jstring name = new_java_string(environment, java_type);
			auto resolved = static_cast<jstring>(
					name == nullptr
							? nullptr
							: environment->CallStaticObjectMethod(
									  methods->engine, methods->resolve_object_type, name));
			const bool success =
					resolved != nullptr &&
					!clear_java_exception(environment, state.errors,
							"registration object type resolution");
			if (success) {
				foundry_type = java_utf8(environment, resolved);
			}
			if (resolved != nullptr) {
				environment->DeleteLocalRef(resolved);
			}
			if (name != nullptr) {
				environment->DeleteLocalRef(name);
			}
			return success && !foundry_type.empty();
		} catch (...) {
			state.errors->error("Native failure contained during registration object "
								"type resolution.");
			return false;
		}
	}

	std::shared_ptr<JniRegistrationMethods> methods;
	std::shared_ptr<const BridgeServices> services;
	std::shared_ptr<AbiRegistrationServices> native_services;
	std::shared_ptr<JniRegistrationCallbacks> callbacks;
	std::unique_ptr<RegistrationRegistry> registry;
};

std::shared_ptr<JniRegistrationBridge> registration_bridge() {
	std::lock_guard lock(state.registration_mutex);
	return state.registration;
}

bool install_registration_bridge(
		std::shared_ptr<const BridgeServices> services) noexcept {
	try {
		{
			std::lock_guard lock(state.registration_mutex);
			if (state.registration != nullptr) {
				return true;
			}
		}
		AttachedEnvironment attached(
				reinterpret_cast<JavaVM *>(state.transition.java_vm()));
		auto bridge = JniRegistrationBridge::create(attached.get(), services);
		if (bridge == nullptr) {
			return false;
		}
		std::lock_guard lock(state.registration_mutex);
		if (state.registration == nullptr) {
			state.registration = std::move(bridge);
		}
		return true;
	} catch (...) {
		return false;
	}
}

void reset_registration_bridge() noexcept {
	std::shared_ptr<JniRegistrationBridge> released;
	{
		std::lock_guard lock(state.registration_mutex);
		released = std::move(state.registration);
	}
	released.reset();
}

bool shutdown_context_registration(ContextHandle context,
		std::uint64_t generation) noexcept {
	auto bridge = registration_bridge();
	if (bridge == nullptr) {
		return true;
	}
	const RegistrationResult result = bridge->shutdown(context, generation);
	if (!result.ok()) {
		state.errors->error(
				"Foundry Java registration teardown failed: " + result.phase + ".");
	}
	return result.ok();
}

void throw_registration_result(JNIEnv *environment,
		const RegistrationResult &result) {
	if (result.ok()) {
		return;
	}
	throw_java(environment,
			result.status == RegistrationStatus::INVALID_DESCRIPTOR
					? "java/lang/IllegalArgumentException"
					: "java/lang/IllegalStateException",
			result.phase.empty() ? "registration_failed" : result.phase);
}

} // namespace
} // namespace foundry_java

namespace foundry_java {
namespace {

class ScopedTransportHandle final {
public:
	ScopedTransportHandle(
			NativeTransport &transport,
			ContextHandle context,
			std::uint64_t generation) noexcept :
			transport(&transport),
			context(context),
			generation(generation) {
	}

	~ScopedTransportHandle() {
		release();
	}

	ScopedTransportHandle(const ScopedTransportHandle &) = delete;
	ScopedTransportHandle &operator=(const ScopedTransportHandle &) = delete;

	bool adopt(NativeHandle value) noexcept {
		if (handle != 0) {
			if (value != 0) {
				transport->release_handle(value, context, generation);
			}
			return false;
		}
		handle = value;
		return handle != 0;
	}

	bool inspect() {
		lease = transport->handles().inspect(handle, context, generation);
		return static_cast<bool>(lease);
	}

	FoundryExtensionVariantPtr variant() const {
		return const_cast<FoundryExtensionVariantPtr>(lease.record().value.data());
	}

	explicit operator bool() const noexcept {
		return handle != 0;
	}

	bool release() noexcept {
		lease = {};
		if (handle == 0) {
			return false;
		}
		const NativeHandle released = std::exchange(handle, 0);
		return transport->release_handle(released, context, generation);
	}

private:
	NativeTransport *transport = nullptr;
	ContextHandle context = 0;
	std::uint64_t generation = 0;
	NativeHandle handle = 0;
	HandleLease lease;
};

template <typename Ready, typename Failed>
decltype(auto) with_scoped_object_receiver_variant(
		NativeTransport &transport,
		ContextHandle context,
		std::uint64_t generation,
		NativeHandle object_handle,
		const std::string &expected_object_type,
		Ready &&ready,
		Failed &&failed) {
	ScopedTransportHandle receiver(transport, context, generation);
	if (!receiver.adopt(transport.construct_object_variant(
				context, generation, object_handle, expected_object_type)) ||
			!receiver.inspect()) {
		return std::forward<Failed>(failed)();
	}
	return std::forward<Ready>(ready)(receiver.variant());
}

jobject call_result(
		JNIEnv *environment,
		jobject value,
		FoundryExtensionCallErrorType error,
		std::int32_t argument_index,
		const std::string &expected_type) {
	jclass error_class = environment->FindClass(
			"games/cafecito/foundry/runtime/FoundryCallError");
	jmethodID value_of_method = environment->GetStaticMethodID(
			error_class,
			"valueOf",
			"(Ljava/lang/String;)Lgames/cafecito/foundry/runtime/FoundryCallError;");
	const char *error_name = "UNKNOWN";
	switch (error) {
		case FOUNDRY_EXTENSION_CALL_OK:
			error_name = "OK";
			break;
		case FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD:
			error_name = "INVALID_METHOD";
			break;
		case FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT:
			error_name = "INVALID_ARGUMENT";
			break;
		case FOUNDRY_EXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS:
			error_name = "TOO_MANY_ARGUMENTS";
			break;
		case FOUNDRY_EXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS:
			error_name = "TOO_FEW_ARGUMENTS";
			break;
		case FOUNDRY_EXTENSION_CALL_ERROR_INSTANCE_IS_NULL:
			error_name = "INSTANCE_IS_NULL";
			break;
		case FOUNDRY_EXTENSION_CALL_ERROR_METHOD_NOT_CONST:
			error_name = "METHOD_NOT_CONST";
			break;
	}
	jstring java_error_name = environment->NewStringUTF(error_name);
	jobject java_error =
			environment->CallStaticObjectMethod(error_class, value_of_method, java_error_name);
	jclass result_class = environment->FindClass(
			"games/cafecito/foundry/runtime/FoundryEngine$CallResult");
	jmethodID constructor = environment->GetMethodID(
			result_class,
			"<init>",
			"(Lgames/cafecito/foundry/types/Variant;"
			"Lgames/cafecito/foundry/runtime/FoundryCallError;ILjava/lang/String;)V");
	jstring expected = new_java_string(environment, expected_type);
	jobject result = environment->NewObject(
			result_class,
			constructor,
			value,
			java_error,
			static_cast<jint>(argument_index),
			expected);
	environment->DeleteLocalRef(expected);
	environment->DeleteLocalRef(result_class);
	environment->DeleteLocalRef(java_error);
	environment->DeleteLocalRef(java_error_name);
	environment->DeleteLocalRef(error_class);
	return result;
}

std::vector<std::string> java_string_array(JNIEnv *environment, jobjectArray values) {
	std::vector<std::string> result;
	if (values == nullptr) {
		return result;
	}
	const jsize size = environment->GetArrayLength(values);
	result.reserve(static_cast<std::size_t>(size));
	for (jsize index = 0; index < size; index++) {
		auto value = static_cast<jstring>(environment->GetObjectArrayElement(values, index));
		result.push_back(java_utf8(environment, value));
		environment->DeleteLocalRef(value);
	}
	return result;
}

NativeDispatch read_dispatch(JNIEnv *environment, jobject value) {
	NativeDispatch result;
	jclass type = environment->GetObjectClass(value);
	auto text = [&](const char *name) {
		jmethodID method = environment->GetMethodID(type, name, "()Ljava/lang/String;");
		auto string = static_cast<jstring>(environment->CallObjectMethod(value, method));
		std::string decoded = java_utf8(environment, string);
		environment->DeleteLocalRef(string);
		return decoded;
	};
	auto long_value = [&](const char *name) {
		jmethodID method = environment->GetMethodID(type, name, "()J");
		return static_cast<std::int64_t>(environment->CallLongMethod(value, method));
	};
	auto int_value = [&](const char *name) {
		jmethodID method = environment->GetMethodID(type, name, "()I");
		return static_cast<std::int32_t>(environment->CallIntMethod(value, method));
	};
	auto bool_value = [&](const char *name) {
		jmethodID method = environment->GetMethodID(type, name, "()Z");
		return environment->CallBooleanMethod(value, method) == JNI_TRUE;
	};
	result.identity = text("identity");
	result.owner_native_type = text("ownerNativeType");
	result.native_name = text("nativeName");
	result.compatibility_hash = long_value("compatibilityHash");
	result.constructor_index = int_value("constructorIndex");
	jclass engine = load_application_class(environment, ENGINE_CLASS);
	jmethodID arguments_method = environment->GetStaticMethodID(
			engine,
			"nativeDispatchArgumentTypesV1",
			"(Lgames/cafecito/foundry/runtime/FoundryNativeDispatch;)[Ljava/lang/String;");
	auto arguments = static_cast<jobjectArray>(
			environment->CallStaticObjectMethod(engine, arguments_method, value));
	result.argument_native_types = java_string_array(environment, arguments);
	environment->DeleteLocalRef(arguments);
	environment->DeleteLocalRef(engine);
	result.minimum_argument_count =
			static_cast<std::size_t>(int_value("minimumArgumentCount"));
	result.return_native_type = text("returnNativeType");
	result.getter_identity = text("getterIdentity");
	result.getter_native_name = text("getterNativeName");
	result.getter_compatibility_hash = long_value("getterCompatibilityHash");
	result.setter_identity = text("setterIdentity");
	result.setter_native_name = text("setterNativeName");
	result.setter_compatibility_hash = long_value("setterCompatibilityHash");
	result.vararg = bool_value("vararg");
	result.static_call = bool_value("staticCall");
	jmethodID kind_method = environment->GetMethodID(
			type,
			"kind",
			"()Lgames/cafecito/foundry/runtime/FoundryNativeDispatch$Kind;");
	jobject kind = environment->CallObjectMethod(value, kind_method);
	jclass kind_class = environment->GetObjectClass(kind);
	jmethodID wire_code = environment->GetMethodID(kind_class, "wireCode", "()I");
	result.kind = static_cast<DispatchKind>(
			environment->CallIntMethod(kind, wire_code));
	environment->DeleteLocalRef(kind_class);
	environment->DeleteLocalRef(kind);
	environment->DeleteLocalRef(type);
	return result;
}

FoundryExtensionVariantOperator operator_for_name(const std::string &name) {
	static const std::array<std::pair<std::string_view, FoundryExtensionVariantOperator>, 25> operators = { {
		{ "==", FOUNDRY_EXTENSION_VARIANT_OP_EQUAL },
		{ "!=", FOUNDRY_EXTENSION_VARIANT_OP_NOT_EQUAL },
		{ "<", FOUNDRY_EXTENSION_VARIANT_OP_LESS },
		{ "<=", FOUNDRY_EXTENSION_VARIANT_OP_LESS_EQUAL },
		{ ">", FOUNDRY_EXTENSION_VARIANT_OP_GREATER },
		{ ">=", FOUNDRY_EXTENSION_VARIANT_OP_GREATER_EQUAL },
		{ "+", FOUNDRY_EXTENSION_VARIANT_OP_ADD },
		{ "-", FOUNDRY_EXTENSION_VARIANT_OP_SUBTRACT },
		{ "*", FOUNDRY_EXTENSION_VARIANT_OP_MULTIPLY },
		{ "/", FOUNDRY_EXTENSION_VARIANT_OP_DIVIDE },
		{ "unary-", FOUNDRY_EXTENSION_VARIANT_OP_NEGATE },
		{ "unary+", FOUNDRY_EXTENSION_VARIANT_OP_POSITIVE },
		{ "%", FOUNDRY_EXTENSION_VARIANT_OP_MODULE },
		{ "**", FOUNDRY_EXTENSION_VARIANT_OP_POWER },
		{ "<<", FOUNDRY_EXTENSION_VARIANT_OP_SHIFT_LEFT },
		{ ">>", FOUNDRY_EXTENSION_VARIANT_OP_SHIFT_RIGHT },
		{ "&", FOUNDRY_EXTENSION_VARIANT_OP_BIT_AND },
		{ "|", FOUNDRY_EXTENSION_VARIANT_OP_BIT_OR },
		{ "^", FOUNDRY_EXTENSION_VARIANT_OP_BIT_XOR },
		{ "~", FOUNDRY_EXTENSION_VARIANT_OP_BIT_NEGATE },
		{ "and", FOUNDRY_EXTENSION_VARIANT_OP_AND },
		{ "or", FOUNDRY_EXTENSION_VARIANT_OP_OR },
		{ "xor", FOUNDRY_EXTENSION_VARIANT_OP_XOR },
		{ "not", FOUNDRY_EXTENSION_VARIANT_OP_NOT },
		{ "in", FOUNDRY_EXTENSION_VARIANT_OP_IN },
	} };
	for (const auto &[candidate, operation] : operators) {
		if (candidate == name) {
			return operation;
		}
	}
	return FOUNDRY_EXTENSION_VARIANT_OP_MAX;
}

NativeHandle copy_raw_variant(
		NativeTransport &transport,
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionConstVariantPtr value) {
	for (const VariantCategoryInfo &category : variant_categories()) {
		NativeHandle handle = transport.copy_variant(
				context, generation, value, category.abi_type);
		if (handle != 0) {
			return handle;
		}
	}
	return 0;
}

std::string expected_call_type(
		const NativeDispatch &dispatch,
		const FoundryExtensionCallError &error) {
	if (error.argument >= 0 &&
			static_cast<std::size_t>(error.argument) < dispatch.argument_native_types.size()) {
		return dispatch.argument_native_types[static_cast<std::size_t>(error.argument)];
	}
	const auto expected = static_cast<FoundryExtensionVariantType>(error.expected);
	const VariantCategoryInfo *category = variant_category(expected);
	return category == nullptr ? std::string{} : std::string(category->native_name);
}

} // namespace

#if defined(FOUNDRY_JAVA_TESTING)
std::size_t receiver_variant_handle_count_after_validation_for_testing(
		NativeTransport &transport,
		ContextHandle context,
		std::uint64_t generation,
		NativeHandle object_handle,
		DispatchKind kind,
		std::size_t formal_count,
		int callback_stage,
		bool &receiver_ready,
		bool &validation_valid,
		bool &callback_completed) {
	const auto ready = [&](FoundryExtensionVariantPtr receiver_variant) {
		receiver_ready = receiver_variant != nullptr;
		NativeDispatch dispatch;
		dispatch.kind = kind;
		dispatch.owner_native_type = "Node";
		dispatch.minimum_argument_count = 0;
		if (kind == DispatchKind::CLASS_PROPERTY) {
			dispatch.argument_native_types = { "Vector2" };
		}
		validation_valid = validate_dispatch(dispatch, formal_count, {}).valid;
		if (!validation_valid) {
			return false;
		}
		if (callback_stage == 1) {
			NativeValue converted = NativeValue::storage(abi_layout_size("Vector2"));
			if (transport
						.inspect_variant(
								object_handle,
								context,
								generation,
								FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2,
								converted.data())
						.ok) {
				return true;
			}
			return false;
		}
		if (callback_stage == 3) {
			throw std::runtime_error("receiver_execute_test_error");
		}
		callback_completed = callback_stage == 2;
		return callback_stage == 2;
	};
	const auto failed = [&] {
		receiver_ready = false;
		validation_valid = false;
		return false;
	};
	(void)with_scoped_object_receiver_variant(
			transport,
			context,
			generation,
			object_handle,
			"Node",
			ready,
			failed);
	return transport.handles().size();
}

std::size_t receiver_variant_handle_count_after_readoption_for_testing(
		NativeTransport &transport,
		ContextHandle context,
		std::uint64_t generation,
		NativeHandle object_handle,
		bool &first_adopted,
		bool &second_rejected) {
	{
		ScopedTransportHandle receiver(transport, context, generation);
		first_adopted = receiver.adopt(transport.construct_object_variant(
				context, generation, object_handle, "Node"));
		second_rejected = !receiver.adopt(transport.construct_object_variant(
				context, generation, object_handle, "Node"));
	}
	return transport.handles().size();
}
#endif

} // namespace foundry_java

FOUNDRY_JAVA_JNI_EXPORT jobject JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeDecodeVariantV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jlong variant_handle) {
	try {
		auto operation = foundry_java::require_operation(environment, context);
		if (!operation || variant_handle <= 0) {
			return nullptr;
		}
		return foundry_java::decode_variant(
				environment,
				static_cast<foundry_java::ContextHandle>(context),
				operation.generation(),
				*operation.transport(),
				static_cast<foundry_java::NativeHandle>(variant_handle),
				0);
	} catch (const std::exception &error) {
		foundry_java::throw_java(environment, "java/lang/IllegalStateException", error.what());
		return nullptr;
	} catch (...) {
		foundry_java::throw_java(environment, "java/lang/IllegalStateException", "native_decode_failed");
		return nullptr;
	}
}

FOUNDRY_JAVA_JNI_EXPORT jlong JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeEncodeVariantV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jobject variant) {
	try {
		auto operation = foundry_java::require_operation(environment, context);
		if (!operation) {
			return 0;
		}
		return static_cast<jlong>(foundry_java::encode_variant(
				environment,
				static_cast<foundry_java::ContextHandle>(context),
				operation.generation(),
				*operation.transport(),
				variant,
				0));
	} catch (const std::exception &error) {
		foundry_java::throw_java(environment, "java/lang/IllegalStateException", error.what());
		return 0;
	} catch (...) {
		foundry_java::throw_java(environment, "java/lang/IllegalStateException", "native_encode_failed");
		return 0;
	}
}

FOUNDRY_JAVA_JNI_EXPORT jobject JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeCallV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jlong object_handle,
		jobject dispatch_value,
		jobjectArray arguments) {
	using namespace foundry_java;
	try {
		auto operation = require_operation(environment, context);
		if (!operation || dispatch_value == nullptr || arguments == nullptr) {
			return call_result(
					environment,
					nullptr,
					FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD,
					-1,
					"");
		}
		const ContextHandle native_context = static_cast<ContextHandle>(context);
		const std::uint64_t generation = operation.generation();
		NativeTransport &transport = *operation.transport();
		const NativeDispatch dispatch = read_dispatch(environment, dispatch_value);
		if (environment->ExceptionCheck()) {
			return nullptr;
		}

		const jsize java_argument_count = environment->GetArrayLength(arguments);
		const bool has_builtin_receiver =
				dispatch.kind == DispatchKind::BUILTIN_METHOD ||
				dispatch.kind == DispatchKind::BUILTIN_OPERATOR ||
				dispatch.kind == DispatchKind::BUILTIN_MEMBER;
		const std::size_t receiver_count = has_builtin_receiver ? 1 : 0;
		if (java_argument_count < static_cast<jsize>(receiver_count)) {
			return call_result(
					environment,
					nullptr,
					FOUNDRY_EXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS,
					0,
					dispatch.owner_native_type);
		}

		std::vector<NativeHandle> argument_handles;
		std::vector<HandleLease> argument_leases;
		argument_handles.reserve(static_cast<std::size_t>(java_argument_count));
		argument_leases.reserve(static_cast<std::size_t>(java_argument_count));
		const auto release_arguments = [&] {
			argument_leases.clear();
			for (NativeHandle handle : argument_handles) {
				transport.release_handle(handle, native_context, generation);
			}
			argument_handles.clear();
		};
		for (jsize index = 0; index < java_argument_count; index++) {
			jobject argument = environment->GetObjectArrayElement(arguments, index);
			NativeHandle handle = encode_variant(
					environment,
					native_context,
					generation,
					transport,
					argument,
					0);
			environment->DeleteLocalRef(argument);
			if (handle == 0 || environment->ExceptionCheck()) {
				release_arguments();
				if (environment->ExceptionCheck()) {
					return nullptr;
				}
				return call_result(
						environment,
						nullptr,
						FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
						index,
						index >= static_cast<jsize>(receiver_count) &&
										static_cast<std::size_t>(index) - receiver_count <
												dispatch.argument_native_types.size() ?
								dispatch.argument_native_types[
										static_cast<std::size_t>(index) - receiver_count] :
								dispatch.owner_native_type);
			}
			HandleLease lease = transport.handles().inspect(handle, native_context, generation);
			if (!lease || lease.record().kind != HandleKind::VARIANT) {
				transport.release_handle(handle, native_context, generation);
				release_arguments();
				return call_result(
						environment,
						nullptr,
						FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
						index,
						"Variant");
			}
			argument_handles.push_back(handle);
			argument_leases.push_back(std::move(lease));
		}

		DispatchCall call;
		for (std::size_t index = receiver_count; index < argument_leases.size(); index++) {
			call.variant_arguments.push_back(argument_leases[index].record().value.data());
		}
		call.property_set =
				dispatch.kind == DispatchKind::CLASS_PROPERTY && !call.variant_arguments.empty();
		call.variant_operator =
				dispatch.kind == DispatchKind::BUILTIN_OPERATOR ?
				operator_for_name(dispatch.native_name) :
				FOUNDRY_EXTENSION_VARIANT_OP_MAX;

		ObjectLease object;
		std::string expected_object_type;
		if (object_handle > 0) {
			HandleLease inspected = transport.handles().inspect(
					static_cast<NativeHandle>(object_handle), native_context, generation);
			if (!inspected || inspected.record().kind != HandleKind::OBJECT) {
				release_arguments();
				return call_result(
						environment,
						nullptr,
						FOUNDRY_EXTENSION_CALL_ERROR_INSTANCE_IS_NULL,
						-1,
						dispatch.owner_native_type);
			}
			expected_object_type = inspected.record().expected_type;
			inspected = {};
			object = transport.acquire_object(
					static_cast<NativeHandle>(object_handle),
					native_context,
					generation,
					expected_object_type);
			if (!object) {
				release_arguments();
				return call_result(
						environment,
						nullptr,
						FOUNDRY_EXTENSION_CALL_ERROR_INSTANCE_IS_NULL,
						-1,
						dispatch.owner_native_type);
			}
			call.object = object.object;
			if (!transport.is_object_assignable(object.object, dispatch.owner_native_type)) {
				release_arguments();
				return call_result(
						environment,
						nullptr,
						FOUNDRY_EXTENSION_CALL_ERROR_INSTANCE_IS_NULL,
						-1,
						dispatch.owner_native_type);
			}
		}

		const auto complete_call = [&](FoundryExtensionVariantPtr receiver_variant) -> jobject {
			if (receiver_variant != nullptr) {
				call.receiver_variant = receiver_variant;
			}

			std::vector<NativeValue> native_values;
			std::vector<FoundryExtensionVariantType> native_value_types;
			std::vector<HandleLease> native_structure_leases;
			native_values.reserve(dispatch.argument_native_types.size() + 1);
			native_value_types.reserve(dispatch.argument_native_types.size() + 1);
			native_structure_leases.reserve(dispatch.argument_native_types.size());
			const auto destroy_native_values = [&] {
				for (std::size_t index = 0; index < native_values.size(); index++) {
					if (native_values[index].constructed) {
						transport.destroy_native_value(
								native_value_types[index], native_values[index].data());
						native_values[index].constructed = false;
					}
				}
			};

			if (has_builtin_receiver) {
				const VariantCategoryInfo *owner = variant_category(dispatch.owner_native_type);
				if (owner == nullptr) {
					release_arguments();
					return call_result(
							environment,
							nullptr,
							FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
							0,
							dispatch.owner_native_type);
				}
				call.receiver_variant = const_cast<FoundryExtensionVariantPtr>(
						argument_leases.front().record().value.data());
				native_values.push_back(NativeValue::storage(abi_layout_size(owner->native_name)));
				if (!transport
							 .inspect_variant(
									 argument_handles.front(),
									 native_context,
									 generation,
									 owner->abi_type,
									 native_values.back().data())
							 .ok) {
					release_arguments();
					return call_result(
							environment,
							nullptr,
							FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
							0,
							dispatch.owner_native_type);
				}
				native_values.back().constructed = true;
				native_value_types.push_back(owner->abi_type);
				call.receiver_native = native_values.back().data();
				call.receiver_native_type = dispatch.owner_native_type;
			}

			const std::size_t formal_count =
					static_cast<std::size_t>(java_argument_count) - receiver_count;
			const DispatchValidation argument_validation = validate_dispatch(
					dispatch,
					formal_count,
					has_builtin_receiver ? std::string_view(dispatch.owner_native_type) : std::string_view{});
			if (!argument_validation.valid) {
				destroy_native_values();
				release_arguments();
				return call_result(
						environment,
						nullptr,
						FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
						static_cast<std::int32_t>(formal_count),
						argument_validation.phase);
			}
			const bool raw_variant_vararg =
					dispatch.vararg &&
					(dispatch.kind == DispatchKind::BUILTIN_METHOD ||
							dispatch.kind == DispatchKind::UTILITY_FUNCTION);
			const std::size_t typed_count =
					raw_variant_vararg ?
					0 :
					std::min(formal_count, dispatch.argument_native_types.size());
			for (std::size_t index = 0; index < typed_count; index++) {
				const std::string &type_name = dispatch.argument_native_types[index];
				const NormalizedNativeType normalized = normalize_native_type(type_name);
				const NativeHandle variant_handle = argument_handles[index + receiver_count];
				if (normalized.kind == NativeTypeKind::NATIVE_STRUCTURE) {
					NativeValue bridge_handle = NativeValue::storage(sizeof(std::int64_t));
					if (!transport
								 .inspect_variant(
										 variant_handle,
										 native_context,
										 generation,
										 FOUNDRY_EXTENSION_VARIANT_TYPE_INT,
										 bridge_handle.data())
								 .ok) {
						destroy_native_values();
						release_arguments();
						return call_result(
								environment,
								nullptr,
								FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
								static_cast<std::int32_t>(index),
								type_name);
					}
					const auto handle =
							static_cast<NativeHandle>(*static_cast<std::int64_t *>(bridge_handle.data()));
					HandleLease lease = transport.handles().acquire(
							handle,
							native_context,
							generation,
							HandleKind::NATIVE_STRUCTURE,
							std::string(normalized.token));
					if (!lease) {
						destroy_native_values();
						release_arguments();
						return call_result(
								environment,
								nullptr,
								FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
								static_cast<std::int32_t>(index),
								type_name);
					}
					call.native_arguments.push_back(lease.record().value.data());
					native_structure_leases.push_back(std::move(lease));
				} else if (normalized.kind == NativeTypeKind::OBJECT) {
					native_values.push_back(NativeValue::storage(sizeof(FoundryExtensionObjectPtr)));
					if (!transport
								 .inspect_variant(
										 variant_handle,
										 native_context,
										 generation,
										 FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT,
										 native_values.back().data())
								 .ok) {
						destroy_native_values();
						release_arguments();
						return call_result(
								environment,
								nullptr,
								FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
								static_cast<std::int32_t>(index),
								type_name);
					}
					auto native_object =
							*static_cast<FoundryExtensionObjectPtr *>(native_values.back().data());
					if (!transport.is_object_assignable(native_object, type_name)) {
						destroy_native_values();
						release_arguments();
						return call_result(
								environment,
								nullptr,
								FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
								static_cast<std::int32_t>(index),
								type_name);
					}
					call.native_arguments.push_back(native_values.back().data());
					native_value_types.push_back(FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT);
				} else if (normalized.kind == NativeTypeKind::BUILTIN &&
						normalized.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX) {
					call.native_arguments.push_back(
							argument_leases[index + receiver_count].record().value.data());
				} else if (normalized.kind == NativeTypeKind::BUILTIN) {
					const VariantCategoryInfo *category = variant_category(normalized.abi_type);
					native_values.push_back(
							NativeValue::storage(abi_layout_size(category->native_name)));
					if (!transport
								 .inspect_variant(
										 variant_handle,
										 native_context,
										 generation,
										 normalized.abi_type,
										 native_values.back().data())
								 .ok) {
						destroy_native_values();
						release_arguments();
						return call_result(
								environment,
								nullptr,
								FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT,
								static_cast<std::int32_t>(index),
								type_name);
					}
					native_values.back().constructed = true;
					native_value_types.push_back(normalized.abi_type);
					call.native_arguments.push_back(native_values.back().data());
				}
			}
			prepare_native_arguments_for_dispatch(dispatch, call);

			DispatchFamily family = dispatch_family(dispatch);
			if (family == DispatchFamily::CLASS_PTRCALL &&
					formal_count >= dispatch.minimum_argument_count &&
					formal_count < dispatch.argument_native_types.size()) {
				family = DispatchFamily::CLASS_VARIANT_CALL;
			}
			const bool variant_result_family =
					family == DispatchFamily::CLASS_VARIANT_CALL ||
					family == DispatchFamily::CLASS_PROPERTY ||
					family == DispatchFamily::CLASS_SIGNAL ||
					family == DispatchFamily::BUILTIN_OPERATOR ||
					family == DispatchFamily::BUILTIN_CONSTANT ||
					dispatch.return_native_type == "void";
			NativeValue variant_result = NativeValue::storage(abi_layout_size("Variant"));
			NativeValue native_result;
			NativeHandle native_structure_result_handle = 0;
			HandleLease native_structure_result_lease;
			const NormalizedNativeType return_type =
					normalize_native_type(dispatch.return_native_type);
			call.variant_result = variant_result.data();
			if (!variant_result_family) {
				if (return_type.kind == NativeTypeKind::NATIVE_STRUCTURE) {
					native_structure_result_handle = transport.create_native_structure(
							native_context, generation, dispatch.return_native_type);
					native_structure_result_lease = transport.handles().acquire(
							native_structure_result_handle,
							native_context,
							generation,
							HandleKind::NATIVE_STRUCTURE,
							std::string(return_type.token));
					if (!native_structure_result_lease) {
						destroy_native_values();
						release_arguments();
						return call_result(
								environment,
								nullptr,
								FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD,
								-1,
								dispatch.return_native_type);
					}
					call.native_result = const_cast<FoundryExtensionTypePtr>(
							native_structure_result_lease.record().value.data());
				} else {
					const std::size_t result_size =
							return_type.kind == NativeTypeKind::OBJECT ?
							sizeof(FoundryExtensionObjectPtr) :
							return_type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX ?
							abi_layout_size("Variant") :
							abi_layout_size(return_type.token);
					native_result = NativeValue::storage(result_size);
					call.native_result = native_result.data();
				}
			}

			const TransportResult executed = transport.execute(dispatch, call);
			if (!executed.ok) {
				if (native_structure_result_handle != 0) {
					native_structure_result_lease = {};
					transport.release_handle(
							native_structure_result_handle, native_context, generation);
				}
				destroy_native_values();
				release_arguments();
				return call_result(
						environment,
						nullptr,
						executed.call_error.error,
						executed.call_error.argument,
						expected_call_type(dispatch, executed.call_error));
			}

			NativeHandle result_handle = 0;
			if (variant_result_family) {
				result_handle =
						copy_raw_variant(transport, native_context, generation, variant_result.data());
			} else if (return_type.kind == NativeTypeKind::NATIVE_STRUCTURE) {
				std::int64_t bridge_handle =
						static_cast<std::int64_t>(native_structure_result_handle);
				result_handle = transport.construct_variant(
						native_context,
						generation,
						FOUNDRY_EXTENSION_VARIANT_TYPE_INT,
						&bridge_handle);
				native_structure_result_lease = {};
				if (result_handle == 0) {
					transport.release_handle(
							native_structure_result_handle, native_context, generation);
				}
			} else if (return_type.kind == NativeTypeKind::OBJECT) {
				auto object = *static_cast<FoundryExtensionObjectPtr *>(native_result.data());
				result_handle = transport.construct_variant(
						native_context,
						generation,
						FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT,
						&object);
			} else if (return_type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX) {
				result_handle =
						copy_raw_variant(transport, native_context, generation, native_result.data());
			} else {
				result_handle = transport.construct_variant(
						native_context,
						generation,
						return_type.abi_type,
						native_result.data());
				transport.destroy_native_value(return_type.abi_type, native_result.data());
			}
			jobject result_value =
					result_handle == 0 ?
					nullptr :
					decode_variant(
							environment,
							native_context,
							generation,
							transport,
							result_handle,
							0);
			if (result_handle != 0) {
				transport.release_handle(result_handle, native_context, generation);
			}
			destroy_native_values();
			release_arguments();
			if (result_value == nullptr || environment->ExceptionCheck()) {
				if (environment->ExceptionCheck()) {
					return nullptr;
				}
				return call_result(
						environment,
						nullptr,
						FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD,
						-1,
						dispatch.return_native_type);
			}
			jobject result = call_result(
					environment,
					result_value,
					FOUNDRY_EXTENSION_CALL_OK,
					-1,
					"");
			environment->DeleteLocalRef(result_value);
			return result;
		};
		if (object_handle > 0 &&
				(dispatch.kind == DispatchKind::CLASS_PROPERTY ||
						dispatch.kind == DispatchKind::CLASS_SIGNAL)) {
			const auto receiver_ready = [&](FoundryExtensionVariantPtr receiver_variant) {
				return complete_call(receiver_variant);
			};
			const auto receiver_failed = [&] {
				release_arguments();
				return call_result(
						environment,
						nullptr,
						FOUNDRY_EXTENSION_CALL_ERROR_INSTANCE_IS_NULL,
						-1,
						dispatch.owner_native_type);
			};
			return with_scoped_object_receiver_variant(
					transport,
					native_context,
					generation,
					static_cast<NativeHandle>(object_handle),
					expected_object_type,
					receiver_ready,
					receiver_failed);
		}
		return complete_call(nullptr);
	} catch (const std::exception &error) {
		throw_java(environment, "java/lang/IllegalStateException", error.what());
		return nullptr;
	} catch (...) {
		throw_java(environment, "java/lang/IllegalStateException", "native_call_failed");
		return nullptr;
	}
}

FOUNDRY_JAVA_JNI_EXPORT jboolean JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeIsObjectValidV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jlong object_handle) {
	try {
		auto operation = foundry_java::require_operation(environment, context);
		return operation && object_handle > 0 &&
						operation.transport()->is_object_valid(
								static_cast<foundry_java::NativeHandle>(object_handle),
								static_cast<foundry_java::ContextHandle>(context),
								operation.generation()) ?
				JNI_TRUE :
				JNI_FALSE;
	} catch (...) {
		return JNI_FALSE;
	}
}

FOUNDRY_JAVA_JNI_EXPORT jstring JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeObjectTypeV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jlong object_handle) {
	try {
		auto operation = foundry_java::require_operation(environment, context);
		if (!operation || object_handle <= 0) {
			return nullptr;
		}
		std::string type;
		if (!operation.transport()
					 ->object_type(
							 static_cast<foundry_java::NativeHandle>(object_handle),
							 static_cast<foundry_java::ContextHandle>(context),
							 operation.generation(),
							 operation.library(),
							 type)
					 .ok) {
			foundry_java::throw_java(environment, "java/lang/IllegalArgumentException", "invalid_object_handle");
			return nullptr;
		}
		return foundry_java::new_java_string(environment, type);
	} catch (...) {
		return nullptr;
	}
}

FOUNDRY_JAVA_JNI_EXPORT jlong JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeInstantiateV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jstring class_name) {
	try {
		auto operation = foundry_java::require_operation(environment, context);
		return operation ?
				static_cast<jlong>(operation.transport()->instantiate(
						static_cast<foundry_java::ContextHandle>(context),
						operation.generation(),
						foundry_java::java_utf8(environment, class_name))) :
				0;
	} catch (...) {
		return 0;
	}
}

FOUNDRY_JAVA_JNI_EXPORT void JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeRetainV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jlong handle) {
	try {
		auto operation = foundry_java::require_operation(environment, context);
		if (!operation || handle <= 0 ||
				!operation.transport()->retain_handle(
						static_cast<foundry_java::NativeHandle>(handle),
						static_cast<foundry_java::ContextHandle>(context),
						operation.generation())) {
			foundry_java::throw_java(environment, "java/lang/IllegalArgumentException", "native_retain_failed");
		}
	} catch (...) {
		foundry_java::throw_java(environment, "java/lang/IllegalStateException", "native_retain_failed");
	}
}

FOUNDRY_JAVA_JNI_EXPORT void JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeReleaseV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jlong handle) {
	try {
		auto operation = foundry_java::require_operation(
				environment, context, foundry_java::ContextOperationKind::CLEANUP);
		if (!operation || handle <= 0 ||
				!operation.transport()->release_handle(
						static_cast<foundry_java::NativeHandle>(handle),
						static_cast<foundry_java::ContextHandle>(context),
						operation.generation())) {
			foundry_java::throw_java(environment, "java/lang/IllegalArgumentException", "native_release_failed");
		}
	} catch (...) {
		foundry_java::throw_java(environment, "java/lang/IllegalStateException", "native_release_failed");
	}
}

FOUNDRY_JAVA_JNI_EXPORT jlong JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeSingletonV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jstring name) {
	try {
		auto operation = foundry_java::require_operation(environment, context);
		return operation ?
				static_cast<jlong>(operation.transport()->singleton(
						static_cast<foundry_java::ContextHandle>(context),
						operation.generation(),
						foundry_java::java_utf8(environment, name))) :
				0;
	} catch (...) {
		return 0;
	}
}

FOUNDRY_JAVA_JNI_EXPORT void JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeReportCallbackExceptionV1(
		JNIEnv *environment,
		jclass,
		jlong context,
		jlong,
		jthrowable failure) {
	try {
		auto operation = foundry_java::require_operation(
				environment, context, foundry_java::ContextOperationKind::CLEANUP);
		if (!operation || failure == nullptr) {
			return;
		}
		jclass throwable_class = environment->FindClass("java/lang/Throwable");
		jmethodID to_string = environment->GetMethodID(
				throwable_class, "toString", "()Ljava/lang/String;");
		auto description = static_cast<jstring>(
				environment->CallObjectMethod(failure, to_string));
		foundry_java::state.errors->error(
				"Java callback exception: " + foundry_java::java_utf8(environment, description));
		environment->DeleteLocalRef(description);
		environment->DeleteLocalRef(throwable_class);
	} catch (...) {
	}
}

FOUNDRY_JAVA_JNI_EXPORT void JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeRegisterExtensionClassV1(
		JNIEnv *environment,
		jclass, jlong context, jobject descriptor) {
	try {
		auto operation = foundry_java::require_operation(environment, context);
		auto bridge = foundry_java::registration_bridge();
		if (!operation || bridge == nullptr) {
			if (operation && bridge == nullptr) {
				foundry_java::throw_java(environment, "java/lang/IllegalStateException",
						"registration_bridge_unavailable");
			}
			return;
		}
		foundry_java::throw_registration_result(
				environment,
				bridge->register_extension_class(
						environment, static_cast<foundry_java::ContextHandle>(context),
						operation, descriptor));
	} catch (...) {
		foundry_java::throw_java(environment, "java/lang/IllegalStateException",
				"registration_jni");
	}
}

FOUNDRY_JAVA_JNI_EXPORT void JNICALL
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeUnregisterExtensionClassV1(
		JNIEnv *environment,
		jclass, jlong context, jstring class_name) {
	try {
		auto operation = foundry_java::require_operation(
				environment, context, foundry_java::ContextOperationKind::CLEANUP);
		auto bridge = foundry_java::registration_bridge();
		if (!operation || bridge == nullptr || class_name == nullptr) {
			if (operation && bridge == nullptr) {
				foundry_java::throw_java(environment, "java/lang/IllegalStateException",
						"registration_bridge_unavailable");
} else if (operation && class_name == nullptr) {
				foundry_java::throw_java(environment,
						"java/lang/IllegalArgumentException",
						"registration_class_name");
			}
			return;
		}
		foundry_java::throw_registration_result(
				environment, bridge->unregister_extension_class(static_cast<foundry_java::ContextHandle>(context), operation.generation(), foundry_java::java_utf8(environment, class_name)));
	} catch (...) {
		foundry_java::throw_java(environment, "java/lang/IllegalStateException",
				"registration_jni");
	}
}
