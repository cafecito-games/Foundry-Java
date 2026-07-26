#include "foundry_java_runtime.h"

#include "foundry_java_contract.h"

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace foundry_java {

namespace {

constexpr char INITIALIZER_CLASS[] = "games/cafecito/foundry/java/FoundryJavaInitializer";

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
			if (java_vm->AttachCurrentThread(&environment, nullptr) == JNI_OK) {
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
			std::shared_ptr<FoundryErrorSink> errors) :
			java_vm(java_vm),
			callbacks(callbacks),
			initialize_method(initialize_method),
			deinitialize_method(deinitialize_method),
			invoke_method(invoke_method),
			invalidate_method(invalidate_method),
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

	void invalidate(ContextHandle context) override {
		AttachedEnvironment attached(java_vm);
		JNIEnv *environment = attached.get();
		if (environment == nullptr) {
			errors->error("Could not attach the Foundry Java invalidation callback thread.");
			return;
		}
		environment->CallVoidMethod(callbacks, invalidate_method, static_cast<jlong>(context));
		clear_java_exception(environment, errors, "context invalidation");
	}

private:
	JavaVM *java_vm = nullptr;
	jobject callbacks = nullptr;
	jmethodID initialize_method = nullptr;
	jmethodID deinitialize_method = nullptr;
	jmethodID invoke_method = nullptr;
	jmethodID invalidate_method = nullptr;
	std::shared_ptr<FoundryErrorSink> errors;
};

struct JniState {
	std::mutex mutex;
	JavaVM *java_vm = nullptr;
	jobject class_loader = nullptr;
	std::shared_ptr<FoundryErrorSink> errors = std::make_shared<FoundryErrorSink>();
	std::shared_ptr<BridgeRuntime> runtime;
	bool bootstrap_in_progress = false;
};

JniState state;

class BootstrapReservation {
public:
	bool begin(JavaVM *&java_vm, jobject &class_loader) {
		std::lock_guard lock(state.mutex);
		if (state.runtime != nullptr || state.bootstrap_in_progress ||
				state.java_vm == nullptr || state.class_loader == nullptr) {
			return false;
		}
		state.bootstrap_in_progress = true;
		java_vm = state.java_vm;
		class_loader = state.class_loader;
		active = true;
		return true;
	}

	bool publish(std::shared_ptr<BridgeRuntime> runtime) {
		std::lock_guard lock(state.mutex);
		if (!active || !state.bootstrap_in_progress || state.runtime != nullptr) {
			return false;
		}
		state.runtime = std::move(runtime);
		state.bootstrap_in_progress = false;
		active = false;
		return true;
	}

	~BootstrapReservation() {
		if (!active) {
			return;
		}
		std::lock_guard lock(state.mutex);
		state.bootstrap_in_progress = false;
	}

	BootstrapReservation() = default;
	BootstrapReservation(const BootstrapReservation &) = delete;
	BootstrapReservation &operator=(const BootstrapReservation &) = delete;

private:
	bool active = false;
};

std::string java_string(JNIEnv *environment, jstring value) {
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
	return java_string(environment, api_sha256) == contract::API_SHA256 &&
			java_string(environment, generator_version) == contract::GENERATOR_VERSION &&
			java_string(environment, runtime_version) == contract::RUNTIME_VERSION &&
			java_string(environment, bridge_version) == contract::BRIDGE_CONTRACT_VERSION;
}

std::shared_ptr<BridgeRuntime> live_runtime() {
	std::lock_guard lock(state.mutex);
	return state.runtime;
}

} // namespace

bool jni_bridge_is_ready() noexcept {
	std::lock_guard lock(state.mutex);
	return state.java_vm != nullptr && state.class_loader != nullptr && state.runtime != nullptr;
}

ContextHandle jni_bridge_create_context() noexcept {
	try {
		auto runtime = live_runtime();
		return runtime == nullptr ? 0 : runtime->create_context();
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

bool jni_bridge_shutdown() noexcept {
	auto runtime = live_runtime();
	if (runtime != nullptr &&
			!runtime->shutdown_all(FOUNDRY_EXTENSION_INITIALIZATION_CORE)) {
		return false;
	}
	JavaVM *java_vm = nullptr;
	jobject class_loader = nullptr;
	{
		std::lock_guard lock(state.mutex);
		if (state.bootstrap_in_progress) {
			return false;
		}
		if (state.runtime == runtime) {
			state.runtime.reset();
		}
		java_vm = state.java_vm;
		class_loader = std::exchange(state.class_loader, nullptr);
	}
	AttachedEnvironment attached(java_vm);
	if (attached.get() != nullptr && class_loader != nullptr) {
		attached.get()->DeleteGlobalRef(class_loader);
	}
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
		{
			std::lock_guard lock(foundry_java::state.mutex);
			if (foundry_java::state.java_vm != nullptr || foundry_java::state.class_loader != nullptr) {
				return JNI_ERR;
			}
			foundry_java::state.java_vm = java_vm;
			foundry_java::state.class_loader = loader_guard.release();
		}
		return JNI_VERSION_1_6;
	} catch (...) {
		return JNI_ERR;
	}
}

FOUNDRY_JAVA_JNI_EXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
	try {
		(void)foundry_java::jni_bridge_shutdown();
		std::lock_guard lock(foundry_java::state.mutex);
		foundry_java::state.java_vm = nullptr;
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
		jobject installed_class_loader = nullptr;
		if (!bootstrap.begin(java_vm, installed_class_loader) ||
				!environment->IsSameObject(installed_class_loader, class_loader)) {
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
				foundry_java::state.errors);
		callback_guard.release();
		auto runtime = std::make_shared<foundry_java::BridgeRuntime>(target, foundry_java::state.errors);
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
