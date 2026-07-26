#include "foundry_java_runtime.h"

#include <cstdint>
#include <cstring>
#include <mutex>

namespace foundry_java {

namespace {

struct InterfaceTable {
	FoundryExtensionInterfacePrintError print_error = nullptr;
	FoundryExtensionInterfaceClassdbRegisterExtensionClass5 register_class = nullptr;
	FoundryExtensionInterfaceClassdbUnregisterExtensionClass unregister_class = nullptr;
	FoundryExtensionInterfaceStringNameNewWithUtf8Chars string_name_from_utf8 = nullptr;
	FoundryExtensionInterfaceVariantGetPtrDestructor variant_destructor = nullptr;

	explicit operator bool() const {
		return print_error != nullptr &&
				register_class != nullptr &&
				unregister_class != nullptr &&
				string_name_from_utf8 != nullptr &&
				variant_destructor != nullptr;
	}
};

struct ExtensionState {
	std::mutex mutex;
	InterfaceTable interface;
	FoundryExtensionClassLibraryPtr library = nullptr;
	ContextHandle context = 0;
	bool entry_active = false;
	bool shutting_down = false;
};

ExtensionState extension_state;

template <typename Function>
Function load_interface(FoundryExtensionInterfaceGetProcAddress get_proc_address, const char *name) {
	FoundryExtensionInterfaceFunctionPtr raw = get_proc_address(name);
	Function function = nullptr;
	static_assert(sizeof(function) == sizeof(raw));
	std::memcpy(&function, &raw, sizeof(function));
	return function;
}

InterfaceTable resolve_interfaces(FoundryExtensionInterfaceGetProcAddress get_proc_address) {
	InterfaceTable interface;
	interface.print_error =
			load_interface<FoundryExtensionInterfacePrintError>(get_proc_address, "print_error");
	interface.register_class =
			load_interface<FoundryExtensionInterfaceClassdbRegisterExtensionClass5>(
					get_proc_address,
					"classdb_register_extension_class5");
	interface.unregister_class =
			load_interface<FoundryExtensionInterfaceClassdbUnregisterExtensionClass>(
					get_proc_address,
					"classdb_unregister_extension_class");
	interface.string_name_from_utf8 =
			load_interface<FoundryExtensionInterfaceStringNameNewWithUtf8Chars>(
					get_proc_address,
					"string_name_new_with_utf8_chars");
	interface.variant_destructor =
			load_interface<FoundryExtensionInterfaceVariantGetPtrDestructor>(
					get_proc_address,
					"variant_get_ptr_destructor");
	return interface;
}

void report_entry_error(const InterfaceTable &interface, const char *message) {
	if (interface.print_error != nullptr) {
		interface.print_error(
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
		InterfaceTable interface;
		{
			std::lock_guard lock(state->mutex);
			interface = state->interface;
		}
		report_entry_error(interface, "Foundry Java initialization callback failed.");
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
			jni_bridge_shutdown_context(context, static_cast<std::int32_t>(level));
		} else {
			jni_bridge_deinitialize(context, static_cast<std::int32_t>(level));
		}
	}
	if (!final_level) {
		return;
	}
	jni_bridge_shutdown();
	std::lock_guard lock(state->mutex);
	state->context = 0;
	state->interface = {};
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
	const InterfaceTable interface = resolve_interfaces(get_proc_address);
	if (!interface) {
		report_entry_error(interface, "Foundry Java could not resolve the required interface table.");
		return 0;
	}
	if (!jni_bridge_is_ready()) {
		report_entry_error(interface, "Foundry Java JNI bootstrap is not ready.");
		return 0;
	}
	bool entry_already_active = false;
	{
		std::lock_guard lock(extension_state.mutex);
		if (extension_state.entry_active) {
			entry_already_active = true;
		} else {
			extension_state.interface = interface;
			extension_state.library = library;
			extension_state.context = 0;
			extension_state.entry_active = true;
			extension_state.shutting_down = false;
		}
	}
	if (entry_already_active) {
		report_entry_error(interface, "Foundry Java extension entry is already active.");
		return 0;
	}
	jni_bridge_install_foundry_error_interface(interface.print_error);
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
