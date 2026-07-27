#include "foundry_java_runtime.h"
#include "foundry_java_interface.h"

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

namespace foundry_java {

namespace {

struct ExtensionState {
	std::mutex mutex;
	std::shared_ptr<const BridgeServices> services;
	FoundryExtensionClassLibraryPtr library = nullptr;
	ContextHandle context = 0;
	bool entry_active = false;
	bool shutting_down = false;
};

ExtensionState extension_state;

void report_entry_error(FoundryExtensionInterfacePrintError print_error, const char *message) {
	if (print_error != nullptr) {
		print_error(
				message,
				"foundry_java_library_init",
				"foundry_java_entry.cpp",
				0,
				0);
	}
}

void initialize_level(void *userdata, FoundryExtensionInitializationLevel level) {
	auto *state = static_cast<ExtensionState *>(userdata);
	if (state == nullptr) {
		return;
	}
	ContextHandle context = 0;
	{
		std::lock_guard lock(state->mutex);
		if (!state->entry_active || state->shutting_down) {
			return;
		}
		if (state->context == 0) {
			state->context = jni_bridge_create_context();
		}
		context = state->context;
	}
	if (context == 0 || !jni_bridge_initialize(context, static_cast<std::int32_t>(level))) {
		FoundryExtensionInterfacePrintError print_error = nullptr;
		{
			std::lock_guard lock(state->mutex);
			if (state->services != nullptr) {
				print_error = state->services->print_error;
			}
		}
		report_entry_error(print_error, "Foundry Java initialization callback failed.");
	}
}

void deinitialize_level(void *userdata, FoundryExtensionInitializationLevel level) {
	auto *state = static_cast<ExtensionState *>(userdata);
	if (state == nullptr) {
		return;
	}
	ContextHandle context = 0;
	const bool final_level = level == FOUNDRY_EXTENSION_INITIALIZATION_CORE;
	{
		std::lock_guard lock(state->mutex);
		if (!state->entry_active) {
			return;
		}
		if (final_level) {
			state->shutting_down = true;
		}
		context = state->context;
	}
	if (context != 0) {
		if (final_level) {
			if (!jni_bridge_shutdown_context(context, static_cast<std::int32_t>(level))) {
				return;
			}
			std::lock_guard lock(state->mutex);
			if (state->context == context) {
				state->context = 0;
			}
		} else {
			jni_bridge_deinitialize(context, static_cast<std::int32_t>(level));
		}
	}
	if (!final_level) {
		return;
	}
	if (!jni_bridge_shutdown()) {
		return;
	}
	std::lock_guard lock(state->mutex);
	state->context = 0;
	state->services.reset();
	state->library = nullptr;
	state->entry_active = false;
	state->shutting_down = false;
}

} // namespace

FoundryExtensionBool initialize_extension(
		FoundryExtensionInterfaceGetProcAddress get_proc_address,
		FoundryExtensionClassLibraryPtr library,
		FoundryExtensionInitialization *initialization) {
	if (get_proc_address == nullptr || library == nullptr || initialization == nullptr) {
		return 0;
	}
	const BridgeResolution resolution = resolve_bridge_services(get_proc_address);
	if (resolution.services == nullptr) {
		const std::string message =
				"Foundry Java could not resolve required interface: " + resolution.missing_name;
		report_entry_error(resolution.print_error, message.c_str());
		return 0;
	}
	if (!jni_bridge_is_ready()) {
		report_entry_error(resolution.services->print_error, "Foundry Java JNI bootstrap is not ready.");
		return 0;
	}
	bool entry_already_active = false;
	{
		std::lock_guard lock(extension_state.mutex);
		if (extension_state.entry_active) {
			entry_already_active = true;
		} else {
			extension_state.services = resolution.services;
			extension_state.library = library;
			extension_state.context = 0;
			extension_state.entry_active = true;
			extension_state.shutting_down = false;
		}
	}
	if (entry_already_active) {
		report_entry_error(resolution.services->print_error, "Foundry Java extension entry is already active.");
		return 0;
	}
	jni_bridge_install_foundry_error_interface(resolution.services->print_error);
	FoundryExtensionInitialization result{};
	result.minimum_initialization_level = FOUNDRY_EXTENSION_INITIALIZATION_CORE;
	result.userdata = &extension_state;
	result.initialize = initialize_level;
	result.deinitialize = deinitialize_level;
	*initialization = result;
	return 1;
}

} // namespace foundry_java

extern "C" __attribute__((visibility("default"))) FoundryExtensionBool foundry_java_library_init(
		FoundryExtensionInterfaceGetProcAddress get_proc_address,
		FoundryExtensionClassLibraryPtr library,
		FoundryExtensionInitialization *initialization) {
	try {
		return foundry_java::initialize_extension(get_proc_address, library, initialization);
	} catch (...) {
		return 0;
	}
}
