#include "foundry_java_registration.h"
#include "foundry_java_registration_bridge.h"
#include "foundry_java_transport.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

void expect(bool condition, const char *message) {
	if (!condition) {
		std::cerr << "FAILED: " << message << '\n';
		std::exit(1);
	}
}

std::vector<std::string> abi_operations;
int abi_string_error = 0;
int abi_string_destructor_count = 0;
int output_variant_default_count = 0;
int output_variant_copy_count = 0;
int output_variant_destructor_count = 0;
int output_native_default_count = 0;
int output_native_copy_count = 0;
int output_native_destructor_count = 0;

const char *abi_text(const void *value) {
	return value == nullptr ? "" : *static_cast<const char *const *>(value);
}

void abi_noop_destructor(FoundryExtensionTypePtr) {}

void abi_string_destructor(FoundryExtensionTypePtr) {
	abi_string_destructor_count++;
}

FoundryExtensionPtrDestructor abi_get_destructor(
		FoundryExtensionVariantType type) {
	return type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING
			? &abi_string_destructor
			: &abi_noop_destructor;
}

void abi_make_name(FoundryExtensionUninitializedStringNamePtr result,
		const char *value, FoundryExtensionInt) {
	*static_cast<const char **>(result) = value;
}

FoundryExtensionInt
abi_make_string(FoundryExtensionUninitializedStringPtr result,
		const char *value, FoundryExtensionInt) {
	*static_cast<const char **>(result) = value;
	return abi_string_error;
}

FoundryExtensionInt abi_read_string(FoundryExtensionConstStringPtr value,
		char *result, FoundryExtensionInt maximum) {
	const std::string text = abi_text(value);
	if (result == nullptr) {
		return static_cast<FoundryExtensionInt>(text.size());
	}
	const auto count = std::min<FoundryExtensionInt>(
			maximum, static_cast<FoundryExtensionInt>(text.size()));
	std::memcpy(result, text.data(), static_cast<std::size_t>(count));
	return count;
}

void abi_string_from_name(FoundryExtensionUninitializedTypePtr result,
		const FoundryExtensionConstTypePtr *arguments) {
	*static_cast<const char **>(result) =
			*static_cast<const char *const *>(arguments[0]);
}

FoundryExtensionPtrConstructor
abi_get_constructor(FoundryExtensionVariantType type, std::int32_t index) {
	return type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING && index == 2
			? &abi_string_from_name
			: nullptr;
}

void abi_register_class(FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr class_name,
		FoundryExtensionConstStringNamePtr base_name,
		const FoundryExtensionClassCreationInfo5 *creation) {
	abi_operations.push_back("class:" + std::string(abi_text(class_name)) + ":" +
			abi_text(base_name));
	expect(creation != nullptr && creation->get_virtual_func == nullptr,
			"class5 mapping must keep the forbidden virtual callback absent");
}

void abi_register_method(FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr class_name,
		const FoundryExtensionClassMethodInfo *method) {
	abi_operations.push_back("method:" + std::string(abi_text(class_name)) + ":" +
			abi_text(method->name));
	expect(method->call_func != nullptr && method->ptrcall_func == nullptr,
			"method mapping must use only the registry variant callback");
}

void abi_register_constant(FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionConstStringNamePtr enum_name,
		FoundryExtensionConstStringNamePtr constant_name,
		FoundryExtensionInt value,
		FoundryExtensionBool bitfield) {
	abi_operations.push_back("constant:" + std::string(abi_text(enum_name)) +
			":" + abi_text(constant_name) + ":" +
			std::to_string(value) + ":" +
			std::to_string(bitfield));
}

void abi_register_group(FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionConstStringPtr name,
		FoundryExtensionConstStringPtr prefix) {
	abi_operations.push_back("group:" + std::string(abi_text(name)) + ":" +
			abi_text(prefix));
}

void abi_register_subgroup(FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionConstStringPtr name,
		FoundryExtensionConstStringPtr prefix) {
	abi_operations.push_back("subgroup:" + std::string(abi_text(name)) + ":" +
			abi_text(prefix));
}

void abi_register_property(FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr,
		const FoundryExtensionPropertyInfo *property,
		FoundryExtensionConstStringNamePtr setter,
		FoundryExtensionConstStringNamePtr getter) {
	abi_operations.push_back("property:" + std::string(abi_text(property->name)) +
			":" + abi_text(setter) + ":" + abi_text(getter));
}

void abi_register_indexed_property(FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr,
		const FoundryExtensionPropertyInfo *property,
		FoundryExtensionConstStringNamePtr setter,
		FoundryExtensionConstStringNamePtr getter,
		FoundryExtensionInt index) {
	abi_operations.push_back("indexed:" + std::string(abi_text(property->name)) +
			":" + abi_text(setter) + ":" + abi_text(getter) +
			":" + std::to_string(index));
}

void abi_register_signal(FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionConstStringNamePtr signal,
		const FoundryExtensionPropertyInfo *arguments,
		FoundryExtensionInt count) {
	abi_operations.push_back("signal:" + std::string(abi_text(signal)) + ":" +
			std::to_string(count));
	expect(count == 1 &&
					arguments[0].type == FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT &&
					std::string(abi_text(arguments[0].class_name)) == "Node",
			"signal mapping must preserve resolved object metadata");
}

void abi_unregister_class(FoundryExtensionClassLibraryPtr,
		FoundryExtensionConstStringNamePtr class_name) {
	abi_operations.push_back("unregister:" + std::string(abi_text(class_name)));
}

void abi_initialize_nil(FoundryExtensionUninitializedVariantPtr value) {
	*static_cast<std::uint64_t *>(value) = 0;
}

void output_variant_copy(FoundryExtensionUninitializedVariantPtr destination,
		FoundryExtensionConstVariantPtr source) {
	output_variant_copy_count++;
	*static_cast<std::uint64_t *>(destination) =
			*static_cast<const std::uint64_t *>(source);
}

void output_variant_nil(FoundryExtensionUninitializedVariantPtr destination) {
	output_variant_default_count++;
	*static_cast<std::uint64_t *>(destination) = 0;
}

void output_variant_destroy(FoundryExtensionVariantPtr) {
	output_variant_destructor_count++;
}

void output_native_default(FoundryExtensionUninitializedTypePtr destination,
		const FoundryExtensionConstTypePtr *) {
	output_native_default_count++;
	*static_cast<std::uint64_t *>(destination) = 5;
}

void output_native_copy(FoundryExtensionUninitializedTypePtr destination,
		const FoundryExtensionConstTypePtr *arguments) {
	output_native_copy_count++;
	*static_cast<std::uint64_t *>(destination) =
			*static_cast<const std::uint64_t *>(arguments[0]);
}

void output_native_destroy(FoundryExtensionTypePtr) {
	output_native_destructor_count++;
}

FoundryExtensionPtrConstructor output_native_constructor(
		FoundryExtensionVariantType type, std::int32_t index) {
	if (type != FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3) {
		return nullptr;
	}
	return index == 0 ? &output_native_default
					  : index == 1 ? &output_native_copy : nullptr;
}

FoundryExtensionPtrDestructor output_native_destructor(
		FoundryExtensionVariantType type) {
	return type == FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3
			? &output_native_destroy
			: nullptr;
}

void test_public_abi_registration_adapter_maps_exact_void_services() {
	auto native = std::make_shared<foundry_java::BridgeServices>();
	native->string_name_new_with_utf8_chars_and_len = &abi_make_name;
	native->string_new_with_utf8_chars_and_len2 = &abi_make_string;
	native->string_to_utf8_chars = &abi_read_string;
	native->variant_get_ptr_constructor = &abi_get_constructor;
	native->variant_get_ptr_destructor = &abi_get_destructor;
	native->variant_new_nil = &abi_initialize_nil;
	native->classdb_register_extension_class5 = &abi_register_class;
	native->classdb_register_extension_class_method = &abi_register_method;
	native->classdb_register_extension_class_integer_constant =
			&abi_register_constant;
	native->classdb_register_extension_class_property_group = &abi_register_group;
	native->classdb_register_extension_class_property_subgroup =
			&abi_register_subgroup;
	native->classdb_register_extension_class_property = &abi_register_property;
	native->classdb_register_extension_class_property_indexed =
			&abi_register_indexed_property;
	native->classdb_register_extension_class_signal = &abi_register_signal;
	native->classdb_unregister_extension_class = &abi_unregister_class;
	foundry_java::AbiRegistrationServices services(
			native, [](const std::string &java_type, std::string &foundry_type) {
				if (java_type != "demo.Node") {
					return false;
				}
				foundry_type = "Node";
				return true;
			});
	const auto library = reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x991);
	FoundryExtensionClassCreationInfo5 creation{};
	foundry_java::NativeClassRegistration registration{ "Player", "Node",
		creation };
	abi_operations.clear();
	expect(
			services.register_class(library, registration),
			"complete class5 mapping must succeed after issuing the void ABI call");
	foundry_java::JavaTransportType object_type{
		FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT,
		FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_OBJECT_IS_REQUIRED,
		"demo.Node",
		"Node",
		false,
	};
	foundry_java::NativeMethodRegistration method;
	method.name = "move";
	method.signature.return_type.is_void = true;
	method.signature.arguments.push_back(object_type);
	method.call = reinterpret_cast<FoundryExtensionClassMethodCall>(0x1);
	expect(services.register_method(library, "Player", method),
			"complete method mapping must issue the void ABI call");
	expect(services.register_integer_constant(library, "Player",
				   { "IDLE", "Mode", 7, false }),
			"constant mapping must issue the void ABI call");
	expect(services.register_property_group(library, "Player", "Movement",
				   "move_") &&
					services.register_property_subgroup(library, "Player", "Speed",
							"speed_"),
			"group mappings must issue the void ABI calls");
	foundry_java::NativePropertyRegistration property{
		"target", object_type, "get_target", "set_target", -1
	};
	expect(services.register_property(library, "Player", property),
			"property mapping must issue the void ABI call");
	property.index = 4;
	expect(services.register_indexed_property(library, "Player", property),
			"indexed property mapping must issue the void ABI call");
	expect(
			services.register_signal(library, "Player", { "changed", { object_type } }),
			"signal mapping must issue the void ABI call");
	expect(services.unregister_class(library, "Player"),
			"unregister mapping must issue the void ABI call");
	expect(abi_operations ==
					std::vector<std::string>{
							"class:Player:Node",
							"method:Player:move",
							"constant:Mode:IDLE:7:0",
							"group:Movement:move_",
							"subgroup:Speed:speed_",
							"property:target:set_target:get_target",
							"indexed:target:set_target:get_target:4",
							"signal:changed:1",
							"unregister:Player",
					},
			"adapter must map exactly the nine public void registration services");
	const char *native_name_text = "_process";
	const char *native_name_storage = native_name_text;
	expect(services.string_name(&native_name_storage) == "_process",
			"adapter must decode callback StringName values through public ABI");
	std::uint64_t nil = 99;
	services.initialize_nil(&nil);
	expect(nil == 0,
			"adapter must initialize callback failures through variant_new_nil");
}

void test_public_abi_registration_adapter_destroys_failed_strings_before_mutation() {
	auto native = std::make_shared<foundry_java::BridgeServices>();
	native->string_name_new_with_utf8_chars_and_len = &abi_make_name;
	native->string_new_with_utf8_chars_and_len2 = &abi_make_string;
	native->variant_get_ptr_destructor = &abi_get_destructor;
	native->classdb_register_extension_class_signal = &abi_register_signal;
	foundry_java::AbiRegistrationServices services(native, {});
	foundry_java::JavaTransportType argument{
		FOUNDRY_EXTENSION_VARIANT_TYPE_INT,
		FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT64,
		"long",
		"",
		false,
	};
	abi_operations.clear();
	abi_string_error = 7;
	abi_string_destructor_count = 0;

	expect(
			!services.register_signal(
					reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x991),
					"Player", { "changed", { argument } }),
			"positive public ABI String errors must fail registration preparation");
	expect(abi_operations.empty(),
			"String preparation failure must perform zero native mutations");
	expect(abi_string_destructor_count == 1,
			"initialized String storage must be destroyed after an ABI error");
	abi_string_error = 0;
}

class RecordingServices final : public foundry_java::RegistrationServices {
public:
	bool record(std::string operation) {
		operations.push_back(std::move(operation));
		return (fail_operation.empty() || operations.back() != fail_operation) &&
				std::find(fail_operations.begin(), fail_operations.end(),
						operations.back()) == fail_operations.end();
	}

	bool register_class(FoundryExtensionClassLibraryPtr,
			const foundry_java::NativeClassRegistration
					&registration) noexcept override {
		class_registration = registration;
		const bool result = record("class");
		if (register_hook) {
			register_hook();
		}
		return result;
	}

	bool register_method(FoundryExtensionClassLibraryPtr, const std::string &,
			const foundry_java::NativeMethodRegistration
					&registration) noexcept override {
		methods.push_back(registration);
		return record("method:" + registration.name);
	}

	bool register_integer_constant(FoundryExtensionClassLibraryPtr,
			const std::string &,
			const foundry_java::NativeConstantRegistration
					&registration) noexcept override {
		constants.push_back(registration);
		return record("constant:" + registration.name);
	}

	bool register_property_group(FoundryExtensionClassLibraryPtr,
			const std::string &, const std::string &name,
			const std::string &prefix) noexcept override {
		return record("group:" + name + ":" + prefix);
	}

	bool register_property_subgroup(FoundryExtensionClassLibraryPtr,
			const std::string &, const std::string &name,
			const std::string &prefix) noexcept override {
		return record("subgroup:" + name + ":" + prefix);
	}

	bool register_property(FoundryExtensionClassLibraryPtr, const std::string &,
			const foundry_java::NativePropertyRegistration
					&registration) noexcept override {
		properties.push_back(registration);
		return record("property:" + registration.name);
	}

	bool register_indexed_property(FoundryExtensionClassLibraryPtr,
			const std::string &,
			const foundry_java::NativePropertyRegistration
					&registration) noexcept override {
		properties.push_back(registration);
		return record("indexed_property:" + registration.name);
	}

	bool register_signal(FoundryExtensionClassLibraryPtr, const std::string &,
			const foundry_java::NativeSignalRegistration
					&registration) noexcept override {
		signals.push_back(registration);
		return record("signal:" + registration.name);
	}

	bool unregister_class(FoundryExtensionClassLibraryPtr,
			const std::string &class_name) noexcept override {
		const bool result = record("unregister:" + class_name);
		if (unregister_hook) {
			unregister_hook();
		}
		return result;
	}

	bool resolve_object_type(const std::string &java_type,
			std::string &foundry_type) noexcept override {
		const auto found = object_types.find(java_type);
		if (found == object_types.end()) {
			foundry_type.clear();
			return false;
		}
		foundry_type = found->second;
		return true;
	}

	std::string
	string_name(FoundryExtensionConstStringNamePtr name) noexcept override {
		return name == nullptr ? std::string{} : static_cast<const char *>(name);
	}

	void initialize_nil(FoundryExtensionVariantPtr value) noexcept override {
		if (value != nullptr) {
			*static_cast<std::uint64_t *>(value) = 0;
		}
	}

	std::vector<std::string> operations;
	std::string fail_operation;
	std::vector<std::string> fail_operations;
	std::function<void()> unregister_hook;
	std::function<void()> register_hook;
	std::unordered_map<std::string, std::string> object_types;
	foundry_java::NativeClassRegistration class_registration;
	std::vector<foundry_java::NativeMethodRegistration> methods;
	std::vector<foundry_java::NativeConstantRegistration> constants;
	std::vector<foundry_java::NativePropertyRegistration> properties;
	std::vector<foundry_java::NativeSignalRegistration> signals;
};

class NoOpCallbacks : public foundry_java::RegistrationCallbacks {
public:
	void release_access(
			foundry_java::RegistrationAccessToken access) noexcept override {
		released_accesses.push_back(access);
		callback_events.emplace_back("release_access");
		release_count.fetch_add(1);
	}

	foundry_java::NativeInstance create_instance(
			foundry_java::RegistrationAccessToken, const std::string &class_name,
			const std::string &base_name,
			bool notify_postinitialize,
			FoundryExtensionClassInstancePtr instance_userdata) noexcept override {
		create_count++;
		if (create_hook) {
			create_hook();
		}
		created_class_name = class_name;
		created_base_name = base_name;
		created_notify = notify_postinitialize;
		created_instance_userdata = instance_userdata;
		return created_instance;
	}

	void discard_partial_instance(
			foundry_java::RegistrationAccessToken,
			foundry_java::NativeInstance instance) noexcept override {
		discarded_instances.push_back(instance);
	}

	void free_instance(
			foundry_java::RegistrationAccessToken,
			foundry_java::RegistrationInstanceToken instance) noexcept override {
		if (free_hook) {
			free_hook();
		}
		freed_instances.push_back(instance);
		callback_events.emplace_back("free_instance");
	}

	bool invoke(foundry_java::RegistrationAccessToken,
			foundry_java::RegistrationInstanceToken,
			const std::string &java_name,
			const FoundryExtensionConstVariantPtr *, FoundryExtensionInt,
			FoundryExtensionVariantPtr,
			FoundryExtensionCallError *) noexcept override {
		invoked_names.push_back(java_name);
		if (invoke_hook) {
			invoke_hook();
		}
		return callback_success;
	}

	bool invoke_virtual(foundry_java::RegistrationAccessToken,
			foundry_java::RegistrationInstanceToken,
			const std::string &java_name,
			const FoundryExtensionConstTypePtr *,
			FoundryExtensionTypePtr) noexcept override {
		virtual_names.push_back(java_name);
		return callback_success;
	}

	void
	initialize_virtual_default(const foundry_java::JavaTransportType &,
			FoundryExtensionTypePtr result) noexcept override {
		if (result != nullptr) {
			*static_cast<std::uint64_t *>(result) = 0;
		}
	}

	bool get_property(foundry_java::RegistrationAccessToken,
			foundry_java::RegistrationInstanceToken,
			const std::string &java_name,
			FoundryExtensionVariantPtr) noexcept override {
		get_names.push_back(java_name);
		return callback_success;
	}

	bool set_property(foundry_java::RegistrationAccessToken,
			foundry_java::RegistrationInstanceToken,
			const std::string &java_name,
			FoundryExtensionConstVariantPtr) noexcept override {
		set_names.push_back(java_name);
		return callback_success;
	}

	bool to_string(foundry_java::RegistrationAccessToken,
			foundry_java::RegistrationInstanceToken,
			FoundryExtensionStringPtr) noexcept override {
		to_string_count++;
		return callback_success;
	}

	std::vector<foundry_java::RegistrationAccessToken> released_accesses;
	std::vector<std::string> callback_events;
	std::atomic<int> release_count{ 0 };
	foundry_java::NativeInstance created_instance{
		reinterpret_cast<FoundryExtensionObjectPtr>(0x100),
		900,
	};
	std::string created_class_name;
	std::string created_base_name;
	bool created_notify = false;
	FoundryExtensionClassInstancePtr created_instance_userdata = nullptr;
	std::vector<foundry_java::RegistrationInstanceToken> freed_instances;
	std::vector<foundry_java::NativeInstance> discarded_instances;
	std::vector<std::string> invoked_names;
	std::vector<std::string> virtual_names;
	std::vector<std::string> get_names;
	std::vector<std::string> set_names;
	int to_string_count = 0;
	int create_count = 0;
	bool callback_success = true;
	std::function<void()> invoke_hook;
	std::function<void()> create_hook;
	std::function<void()> free_hook;
};

class ReplacingCallbacks final : public NoOpCallbacks {
public:
	ReplacingCallbacks() {
		services = std::make_shared<foundry_java::BridgeServices>();
		services->variant_new_nil = &output_variant_nil;
		services->variant_new_copy = &output_variant_copy;
		services->variant_destroy = &output_variant_destroy;
		services->variant_get_ptr_constructor = &output_native_constructor;
		services->variant_get_ptr_destructor = &output_native_destructor;
	}

	bool invoke(foundry_java::RegistrationAccessToken,
			foundry_java::RegistrationInstanceToken,
			const std::string &,
			const FoundryExtensionConstVariantPtr *, FoundryExtensionInt,
			FoundryExtensionVariantPtr result,
			FoundryExtensionCallError *) noexcept override {
		return callback_success &&
				foundry_java::replace_initialized_variant(
						*services, result, &variant_source);
	}

	bool get_property(foundry_java::RegistrationAccessToken,
			foundry_java::RegistrationInstanceToken,
			const std::string &,
			FoundryExtensionVariantPtr result) noexcept override {
		return callback_success &&
				foundry_java::replace_initialized_variant(
						*services, result, &variant_source);
	}

	bool invoke_virtual(foundry_java::RegistrationAccessToken,
			foundry_java::RegistrationInstanceToken,
			const std::string &java_name,
			const FoundryExtensionConstTypePtr *,
			FoundryExtensionTypePtr result) noexcept override {
		if (!callback_success) {
			return false;
		}
		return java_name == "generic"
				? foundry_java::replace_initialized_variant(
						  *services, result, &variant_source)
				: foundry_java::replace_initialized_native(
						  *services, FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3,
						  result, &native_source);
	}

	void initialize_virtual_default(
			const foundry_java::JavaTransportType &return_type,
			FoundryExtensionTypePtr result) noexcept override {
		if (return_type.java_type ==
				"games.cafecito.foundry.types.Variant") {
			services->variant_new_nil(result);
			return;
		}
		const FoundryExtensionPtrConstructor constructor =
				services->variant_get_ptr_constructor(
						return_type.abi_type, 0);
		if (constructor != nullptr) {
			constructor(result, nullptr);
		}
	}

	std::shared_ptr<foundry_java::BridgeServices> services;
	std::uint64_t variant_source = 42;
	std::uint64_t native_source = 77;
};

foundry_java::RegistrationClassDescriptor valid_descriptor() {
	foundry_java::RegistrationClassDescriptor descriptor;
	descriptor.foundry_name = "Player";
	descriptor.base_name = "Node";
	descriptor.access = 41;
	descriptor.members = {
		{
				foundry_java::RegistrationMemberKind::METHOD,
				"move",
				"move",
				"void(byte,short,int,long,char,float,double,java.lang.String)",
				{},
				{},
		},
	};
	return descriptor;
}

void test_strict_java_transport_signature_parser() {
	const auto parsed = foundry_java::parse_java_method_signature(
			"games.cafecito.foundry.types.Vector3(byte,short,int,long,char,float,"
			"double,java.lang.String)");
	expect(parsed.ok(), "valid Java transport signature must parse");
	expect(parsed.signature.return_type.abi_type ==
					FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3,
			"Vector3 must map to the exact Variant category");
	expect(parsed.signature.arguments.size() == 8,
			"all arguments must be preserved");
	expect(parsed.signature.arguments[0].metadata ==
					FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT8,
			"byte metadata must remain INT8");
	expect(parsed.signature.arguments[1].metadata ==
					FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT16,
			"short metadata must remain INT16");
	expect(parsed.signature.arguments[2].metadata ==
					FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT32,
			"int metadata must remain INT32");
	expect(parsed.signature.arguments[3].metadata ==
					FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT64,
			"long and generated enum transport must remain INT64");
	expect(parsed.signature.arguments[4].metadata ==
					FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_CHAR16,
			"char metadata must remain CHAR16");
	expect(parsed.signature.arguments[5].metadata ==
					FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_REAL_IS_FLOAT,
			"float metadata must remain FLOAT");
	expect(parsed.signature.arguments[6].metadata ==
					FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_REAL_IS_DOUBLE,
			"double metadata must remain DOUBLE");
	expect(parsed.signature.arguments[7].abi_type ==
					FOUNDRY_EXTENSION_VARIANT_TYPE_STRING,
			"java.lang.String must map to STRING");

	expect(!foundry_java::parse_java_method_signature("void(int").ok(),
			"unclosed signature must reject");
	expect(!foundry_java::parse_java_method_signature("void(int,,long)").ok(),
			"empty argument must reject");
	expect(!foundry_java::parse_java_method_signature("void(java.lang.Object[])")
					.ok(),
			"array spellings outside the frozen transport set must reject");
	expect(!foundry_java::parse_java_property_type("java.util.List").ok(),
			"unknown JDK reference types must reject instead of becoming Object");
	expect(foundry_java::parse_java_property_type(
				   "games.cafecito.foundry.types.FoundryArray<games.cafecito."
				   "foundry.types.Variant>")
							.type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY,
			"generic FoundryArray transport must map to ARRAY");
	expect(foundry_java::parse_java_property_type(
				   "games.cafecito.foundry.types.FoundryDictionary<java.lang."
				   "String,games.cafecito.foundry.types.Variant>")
							.type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY,
			"generic FoundryDictionary transport must map to DICTIONARY");
	expect(foundry_java::parse_java_property_type(
				   "games.cafecito.foundry.runtime.FoundryCallable")
							.type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE,
			"FoundryCallable transport must map to CALLABLE");
	expect(foundry_java::parse_java_property_type(
				   "games.cafecito.foundry.runtime.FoundrySignal")
							.type.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL,
			"FoundrySignal transport must map to SIGNAL");
	const auto generic_method = foundry_java::parse_java_method_signature(
			"void(games.cafecito.foundry.types.FoundryDictionary<java.lang."
			"String,games.cafecito.foundry.types.FoundryArray<games.cafecito."
			"foundry.types.Variant>>)");
	expect(
			generic_method.ok() && generic_method.signature.arguments.size() == 1 &&
					generic_method.signature.arguments[0].abi_type ==
							FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY,
			"generic commas and nested containers must parse as one method argument");
}

void test_registration_interface_inventory_excludes_virtual_registration() {
	const auto &names = foundry_java::required_registration_service_names();
	expect(std::vector<std::string>(names.begin(), names.end()) ==
					std::vector<std::string>{
							"classdb_register_extension_class5",
							"classdb_register_extension_class_method",
							"classdb_register_extension_class_integer_constant",
							"classdb_register_extension_class_property",
							"classdb_register_extension_class_property_indexed",
							"classdb_register_extension_class_property_group",
							"classdb_register_extension_class_property_subgroup",
							"classdb_register_extension_class_signal",
							"classdb_unregister_extension_class",
					},
			"registration core must freeze the exact nine public service names");
	expect(std::find(names.begin(), names.end(),
				   "classdb_register_extension_class_virtual_method") ==
					names.end(),
			"virtual registration service must remain forbidden");
}

void test_whole_descriptor_validation_precedes_native_mutation() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	auto descriptor = valid_descriptor();
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::SIGNAL,
			"broken",
			"broken",
			"void(int,,long)",
			{},
			{},
	});

	const auto result = registry.register_class(
			7, 3, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x11),
			std::move(descriptor));
	expect(!result.ok(), "malformed descriptor must reject");
	expect(result.phase == "registration_signature",
			"malformed signature must report its stable phase");
	expect(services->operations.empty(),
			"validation failure must perform zero native mutations");

	descriptor = valid_descriptor();
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::SIGNAL,
			"invalid_return",
			"invalidReturn",
			"int()",
			{},
			{},
	});
	const auto invalid_signal = registry.register_class(
			7, 3, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x11),
			std::move(descriptor));
	expect(!invalid_signal.ok(), "signal with a return value must reject");
	expect(services->operations.empty(),
			"invalid signal return must reject before class mutation");

	descriptor = valid_descriptor();
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::CONSTANT,
			"BROKEN",
			"BROKEN",
			"int",
			foundry_java::RegistrationConstantDetails{ "", 1, false },
			{},
	});
	const auto invalid_constant = registry.register_class(
			7, 3, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x11),
			std::move(descriptor));
	expect(!invalid_constant.ok(),
			"constant outside the frozen primitive-long transport must reject");
	expect(services->operations.empty(),
			"invalid constant transport must reject before class mutation");

	descriptor = valid_descriptor();
	descriptor.members[0].foundry_name =
			"__foundry_java_property_get_7370656564";
	descriptor.members.push_back({
					foundry_java::RegistrationMemberKind::PROPERTY,
					"speed",
					"speed",
					"double",
					{},
					{},
			});
	const auto reserved_collision = registry.register_class(
			7,
			3,
			reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x11),
			std::move(descriptor));
	expect(!reserved_collision.ok(),
			"exported members must not collide with reserved synthetic accessors");
	expect(services->operations.empty(),
			"synthetic accessor collision must reject before class mutation");
}

void test_object_types_resolve_before_native_mutation() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	auto descriptor = valid_descriptor();
	services->object_types.emplace("example.game.Player", "Player");
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::PROPERTY,
			"target",
			"target",
			"example.game.Player",
			{},
			foundry_java::RegistrationPropertyDetails{
					"getTarget", "", -1, "", "", "", "" },
	});
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::PROPERTY,
			"enemy",
			"enemy",
			"example.game.Enemy",
			{},
			foundry_java::RegistrationPropertyDetails{
					"getEnemy", "", -1, "", "", "", "" },
	});

	const auto unresolved = registry.register_class(
			7, 3, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x11),
			descriptor);
	expect(!unresolved.ok() && unresolved.phase == "object_type",
			"unresolved object type must report a deterministic validation phase");
	expect(services->operations.empty(),
			"unresolved object type must reject before native mutation");

	services->object_types.emplace("example.game.Enemy", "Enemy");
	const auto resolved = registry.register_class(
			7, 3, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x11),
			std::move(descriptor));
	expect(resolved.ok(), "resolved object type must register");
	expect(
			services->properties.size() == 2 &&
					services->properties[0].type.class_name == "Enemy" &&
					services->properties[1].type.class_name == "Player",
			"self and forward object constraints must use cataloged Foundry names");
	expect(services->properties[1].type.java_type == "example.game.Player",
			"callback transport must preserve canonical Java FQNs");
}

void test_registration_uses_class5_and_exact_member_order() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	auto descriptor = valid_descriptor();
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::OVERRIDE,
			"_process",
			"process",
			"void(double)",
			{},
			{},
	});
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::CONSTANT,
			"MAX_SPEED",
			"MAX_SPEED",
			"long",
			foundry_java::RegistrationConstantDetails{ "Speed", 9223372036854775807LL,
					false },
			{},
	});
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::PROPERTY,
			"speed",
			"speed",
			"double",
			{},
			foundry_java::RegistrationPropertyDetails{
					"getSpeed",
					"setSpeed",
					-1,
					"",
					"",
					"",
					"",
			},
	});
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::SIGNAL,
			"moved",
			"moved",
			"void(games.cafecito.foundry.types.Vector3)",
			{},
			{},
	});

	const auto result = registry.register_class(
			7, 3, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x11),
			std::move(descriptor));
	expect(result.ok(), "valid descriptor must register");
	expect(services->operations ==
					std::vector<std::string>{
							"class",
							"method:move",
							"constant:MAX_SPEED",
							"method:__foundry_java_property_get_7370656564",
							"method:__foundry_java_property_set_7370656564",
							"property:speed",
							"signal:moved",
					},
			"registration must use the frozen "
			"class/method/constant/property/signal order");
	expect(services->class_registration.class_name == "Player",
			"class name must survive descriptor destruction");
	expect(services->class_registration.base_name == "Node",
			"base name must survive descriptor destruction");
	const FoundryExtensionClassCreationInfo5 &creation =
			services->class_registration.creation_info;
	expect(creation.is_virtual == 0 && creation.is_abstract == 0 &&
					creation.is_exposed != 0,
			"v5 class flags must be concrete and exposed");
	expect(creation.set_func != nullptr && creation.get_func != nullptr,
			"v5 property callbacks must be installed");
	expect(creation.to_string_func != nullptr,
			"v5 to_string callback must be installed");
	expect(creation.create_instance_func != nullptr &&
					creation.free_instance_func != nullptr,
			"v5 instance callbacks must be installed");
	expect(creation.get_virtual_func == nullptr,
			"legacy virtual lookup must remain unused");
	expect(creation.get_virtual_call_data_func != nullptr &&
					creation.call_virtual_with_data_func != nullptr,
			"v5 virtual call-data callbacks must be installed");
	expect(creation.class_userdata != nullptr,
			"class userdata must remain stable after registration");
	expect(services->methods.size() == 3,
			"override must not register as an ordinary or virtual method");
	expect(services->methods[0].signature.arguments.size() == 8,
			"ordinary method metadata must remain available after temporary "
			"registration input dies");
	expect(services->signals[0].arguments[0].abi_type ==
					FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3,
			"signal argument metadata must retain its exact Variant type");
	expect(services->constants[0].value == 9223372036854775807LL,
			"signed constant limits must be preserved");
}

void test_property_details_emit_canonical_transitions_and_indexed_forms() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	foundry_java::RegistrationClassDescriptor descriptor;
	descriptor.foundry_name = "Settings";
	descriptor.base_name = "Node";
	descriptor.access = 42;
	descriptor.members = {
		{
				foundry_java::RegistrationMemberKind::PROPERTY,
				"speed",
				"speed",
				"double",
				{},
				foundry_java::RegistrationPropertyDetails{
						"getSpeed",
						"",
						-1,
						"Movement",
						"movement_",
						"",
						"",
				},
		},
		{
				foundry_java::RegistrationMemberKind::PROPERTY,
				"legacy",
				"legacyValue",
				"int",
				{},
				{},
		},
		{
				foundry_java::RegistrationMemberKind::PROPERTY,
				"position",
				"position",
				"games.cafecito.foundry.types.Vector3",
				{},
				foundry_java::RegistrationPropertyDetails{
						"getPosition",
						"setPosition",
						2,
						"Transform",
						"transform_",
						"Spatial",
						"spatial_",
				},
		},
	};

	expect(registry
					.register_class(
							8, 1, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x12),
							std::move(descriptor))
					.ok(),
			"property forms must register");
	expect(services->operations ==
					std::vector<std::string>{
							"class",
							"method:__foundry_java_property_get_6c6567616379",
							"property:legacy",
							"group:Transform:transform_",
							"subgroup:Spatial:spatial_",
							"method:__foundry_java_property_get_706f736974696f6e",
							"method:__foundry_java_property_set_706f736974696f6e",
							"indexed_property:position",
							"subgroup::",
							"group:Movement:movement_",
							"method:__foundry_java_property_get_7370656564",
							"property:speed",
							"group::",
					},
			"properties must emit deterministic group/subgroup resets and exact "
			"indexed registration");
	expect(services->properties[0].setter.empty(),
			"legacy property must be read-only");
	expect(services->properties[0].getter ==
					"__foundry_java_property_get_6c6567616379",
			"legacy property must use a synthetic getter backed by javaName");
	expect(services->properties[1].index == 2,
			"indexed property must preserve its index");
	expect(services->properties[2].setter.empty(),
			"typed read-only property must use an empty setter");
	const auto indexed_getter = std::find_if(
			services->methods.begin(),
			services->methods.end(),
			[](const foundry_java::NativeMethodRegistration &method) {
				return method.name ==
						"__foundry_java_property_get_706f736974696f6e";
			});
	const auto indexed_setter = std::find_if(
			services->methods.begin(),
			services->methods.end(),
			[](const foundry_java::NativeMethodRegistration &method) {
				return method.name ==
						"__foundry_java_property_set_706f736974696f6e";
			});
	expect(indexed_getter != services->methods.end(),
			"indexed getter method must exist");
	expect(indexed_setter != services->methods.end(),
			"indexed setter method must exist");
	expect(indexed_getter->signature.arguments.size() == 1 &&
					indexed_getter->signature.arguments[0].metadata ==
							FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT64,
			"indexed getter must expose the native index argument");
	expect(indexed_setter->signature.arguments.size() == 2 &&
					indexed_setter->signature.arguments[0].metadata ==
							FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT64,
			"indexed setter must expose native index then property value");
}

void test_provisional_failure_self_rolls_back_and_completed_classes_reverse() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	auto broken = valid_descriptor();
	broken.members.push_back({
			foundry_java::RegistrationMemberKind::CONSTANT,
			"MAX_SPEED",
			"MAX_SPEED",
			"long",
			foundry_java::RegistrationConstantDetails{ "", 7, false },
			{},
	});
	services->fail_operation = "constant:MAX_SPEED";
	const auto failed = registry.register_class(
			9, 4, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x13),
			std::move(broken));
	expect(!failed.ok() && failed.phase == "registration_constant",
			"native member failure must report exact phase");
	expect(services->operations ==
					std::vector<std::string>{
							"class",
							"method:move",
							"constant:MAX_SPEED",
							"unregister:Player",
					},
			"provisional class must self-unregister exactly once");
	expect(registry.active_class_count() == 0 && registry.tombstone_count() == 1,
			"failed class must be tombstoned");
	expect(
			callbacks->released_accesses ==
					std::vector<foundry_java::RegistrationAccessToken>{ 41 },
			"failed provisional class must release its access token after rollback");

	services->operations.clear();
	services->fail_operation.clear();
	auto first = valid_descriptor();
	first.foundry_name = "First";
	first.access = 51;
	auto second = valid_descriptor();
	second.foundry_name = "Second";
	second.access = 52;
	expect(registry
					.register_class(
							9, 4, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x13),
							std::move(first))
					.ok(),
			"first completed class must register");
	expect(registry
					.register_class(
							9, 4, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x13),
							std::move(second))
					.ok(),
			"second completed class must register");
	services->operations.clear();
	expect(registry.rollback(9, 4, { "First", "Second" }).ok(),
			"completed rollback must succeed");
	expect(services->operations ==
					std::vector<std::string>{ "unregister:Second", "unregister:First" },
			"completed classes must unregister in exact reverse order");
}

void test_stable_callbacks_dispatch_instances_properties_and_virtuals() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	auto descriptor = valid_descriptor();
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::OVERRIDE,
			"_process",
			"process",
			"void(double)",
			{},
			{},
	});
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::PROPERTY,
			"speed",
			"speed",
			"double",
			{},
			foundry_java::RegistrationPropertyDetails{
					"getSpeed",
					"setSpeed",
					-1,
					"",
					"",
					"",
					"",
			},
	});
	expect(registry
					.register_class(
							11, 5, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x14),
							std::move(descriptor))
					.ok(),
			"callback fixture must register");

	const FoundryExtensionClassCreationInfo5 creation =
			services->class_registration.creation_info;
	const FoundryExtensionObjectPtr object =
			creation.create_instance_func(creation.class_userdata, 1);
	expect(object == reinterpret_cast<FoundryExtensionObjectPtr>(0x100),
			"create callback must return adapter object");
	expect(callbacks->created_class_name == "Player" &&
					callbacks->created_base_name == "Node" &&
					callbacks->created_notify,
			"create callback must preserve class, base, and notification");
	expect(callbacks->created_instance_userdata != nullptr,
			"create callback must allocate stable instance userdata");

	std::uint64_t argument = 7;
	std::uint64_t result = 99;
	const FoundryExtensionConstVariantPtr arguments[]{ &argument };
	FoundryExtensionCallError error{
		FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD,
		-1,
		-1,
	};
	services->methods[0].call(services->methods[0].userdata,
			callbacks->created_instance_userdata, arguments, 1,
			&result, &error);
	expect(callbacks->invoked_names == std::vector<std::string>{ "move" },
			"ordinary method must dispatch by Java name");
	expect(error.error == FOUNDRY_EXTENSION_CALL_OK,
			"successful method callback must clear call error");

	const auto native_name = [](const char *name) {
		return reinterpret_cast<FoundryExtensionConstStringNamePtr>(name);
	};
	expect(creation.get_func(callbacks->created_instance_userdata,
				   native_name("speed"), &result) != 0,
			"property getter callback must dispatch");
	expect(creation.set_func(callbacks->created_instance_userdata,
				   native_name("speed"), &argument) != 0,
			"property setter callback must dispatch");
	expect(callbacks->get_names == std::vector<std::string>{ "getSpeed" },
			"getter must use direct access name");
	expect(callbacks->set_names == std::vector<std::string>{ "setSpeed" },
			"setter must use direct access name");

	void *virtual_data = creation.get_virtual_call_data_func(
			creation.class_userdata, native_name("_process"), 123);
	expect(virtual_data != nullptr,
			"known override must expose stable virtual call data");
	expect(creation.get_virtual_call_data_func(
				   creation.class_userdata, native_name("_missing"), 123) == nullptr,
			"unknown override must return null without virtual registration");
	creation.call_virtual_with_data_func(callbacks->created_instance_userdata,
			native_name("_process"), virtual_data,
			nullptr, &result);
	expect(callbacks->virtual_names == std::vector<std::string>{ "process" },
			"virtual callback must use direct Java name");

	FoundryExtensionBool string_valid = 0;
	creation.to_string_func(callbacks->created_instance_userdata, &string_valid,
			&result);
	expect(string_valid != 0 && callbacks->to_string_count == 1,
			"to_string must contain and report callback success");

	callbacks->callback_success = false;
	error.error = FOUNDRY_EXTENSION_CALL_OK;
	result = 99;
	services->methods[0].call(services->methods[0].userdata,
			callbacks->created_instance_userdata, arguments, 1,
			&result, &error);
	expect(error.error == FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD,
			"contained method failure must report deterministic error");
	expect(result == 0, "contained method failure must initialize NIL");
	result = 99;
	creation.call_virtual_with_data_func(callbacks->created_instance_userdata,
			native_name("_process"), virtual_data,
			nullptr, &result);
	expect(result == 0,
			"contained virtual failure must initialize its typed default");
	result = 99;
	expect(creation.get_func(callbacks->created_instance_userdata,
				   native_name("speed"), &result) == 0 &&
					result == 0,
			"contained property failure must return false and initialize NIL");
	string_valid = 1;
	creation.to_string_func(callbacks->created_instance_userdata, &string_valid,
			&result);
	expect(string_valid == 0, "contained to_string failure must report invalid");
	callbacks->callback_success = true;

	creation.free_instance_func(creation.class_userdata,
			callbacks->created_instance_userdata);
	expect(callbacks->freed_instances ==
					std::vector<foundry_java::RegistrationInstanceToken>{ 900 },
			"free callback must release the exact instance token once");
	expect(registry.unregister_class(11, 5, "Player").ok(),
			"class must unregister after instance free");
	expect(callbacks->released_accesses ==
					std::vector<foundry_java::RegistrationAccessToken>{ 41 },
			"class access must release only after instance/callback teardown");

	error.error = FOUNDRY_EXTENSION_CALL_OK;
	result = 99;
	services->methods[0].call(services->methods[0].userdata,
			callbacks->created_instance_userdata, arguments, 1,
			&result, &error);
	expect(error.error == FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD,
			"stale callback must return deterministic error");
	expect(result == 0, "stale callback must initialize deterministic NIL");
}

void test_callback_outputs_replace_initialized_defaults_exactly_once() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<ReplacingCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	auto descriptor = valid_descriptor();
	descriptor.members[0].signature = "long()";
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::PROPERTY,
			"score",
			"score",
			"long",
			{},
			foundry_java::RegistrationPropertyDetails{
					"getScore", "", -1, "", "", "", "" },
	});
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::OVERRIDE,
			"_calculate",
			"calculate",
			"games.cafecito.foundry.types.Vector3()",
			{},
			{},
	});
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::OVERRIDE,
			"_generic",
			"generic",
			"games.cafecito.foundry.types.Variant()",
			{},
			{},
	});
	expect(registry
					.register_class(
							21, 8,
							reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x21),
							std::move(descriptor))
					.ok(),
			"initialized output fixture must register");
	const FoundryExtensionClassCreationInfo5 creation =
			services->class_registration.creation_info;
	(void)creation.create_instance_func(creation.class_userdata, 1);
	const auto method = std::find_if(
			services->methods.begin(), services->methods.end(),
			[](const foundry_java::NativeMethodRegistration &candidate) {
				return candidate.java_name == "move";
			});
	expect(method != services->methods.end(),
			"initialized output fixture must retain its method callback");

	output_variant_copy_count = 0;
	output_variant_destructor_count = 0;
	std::uint64_t result = 99;
	FoundryExtensionCallError error{};
	method->call(method->userdata, callbacks->created_instance_userdata,
			nullptr, 0, &result, &error);
	expect(result == 42 && output_variant_destructor_count == 1 &&
					output_variant_copy_count == 1,
			"method success must destroy NIL once and copy the encoded result once");
	const auto native_name = [](const char *name) {
		return reinterpret_cast<FoundryExtensionConstStringNamePtr>(name);
	};
	result = 99;
	expect(creation.get_func(callbacks->created_instance_userdata,
				   native_name("score"), &result) != 0 &&
					result == 42 && output_variant_destructor_count == 2 &&
					output_variant_copy_count == 2,
			"property success must destroy NIL once and copy the encoded result once");

	callbacks->callback_success = false;
	result = 99;
	method->call(method->userdata, callbacks->created_instance_userdata,
			nullptr, 0, &result, &error);
	expect(result == 0 && output_variant_destructor_count == 2 &&
					output_variant_copy_count == 2,
			"method failure must preserve NIL without destroy or copy");
	result = 99;
	expect(creation.get_func(callbacks->created_instance_userdata,
				   native_name("score"), &result) == 0 &&
					result == 0 && output_variant_destructor_count == 2 &&
					output_variant_copy_count == 2,
			"property failure must preserve NIL without destroy or copy");
	foundry_java::BridgeServices incomplete_variant = *callbacks->services;
	incomplete_variant.variant_new_copy = nullptr;
	result = 0;
	expect(!foundry_java::replace_initialized_variant(
				   incomplete_variant, &result, &callbacks->variant_source) &&
					result == 0 && output_variant_destructor_count == 2 &&
					output_variant_copy_count == 2,
			"Variant replacement prevalidation failure must leave NIL untouched");

	void *generic_virtual_data = creation.get_virtual_call_data_func(
			creation.class_userdata, native_name("_generic"), 0);
	expect(generic_virtual_data != nullptr,
			"generic Variant virtual fixture must retain call data");
	output_variant_default_count = 0;
	output_variant_copy_count = 0;
	output_variant_destructor_count = 0;
	callbacks->callback_success = true;
	result = 99;
	creation.call_virtual_with_data_func(callbacks->created_instance_userdata,
			native_name("_generic"), generic_virtual_data, nullptr, &result);
	expect(result == 42 && output_variant_default_count == 1 &&
					output_variant_destructor_count == 1 &&
					output_variant_copy_count == 1,
			"generic Variant virtual success must replace its NIL exactly once");
	callbacks->callback_success = false;
	result = 99;
	creation.call_virtual_with_data_func(callbacks->created_instance_userdata,
			native_name("_generic"), generic_virtual_data, nullptr, &result);
	expect(result == 0 && output_variant_default_count == 2 &&
					output_variant_destructor_count == 1 &&
					output_variant_copy_count == 1,
			"generic Variant virtual failure must preserve its NIL default");

	void *virtual_data = creation.get_virtual_call_data_func(
			creation.class_userdata, native_name("_calculate"), 0);
	expect(virtual_data != nullptr,
			"initialized output fixture must retain virtual call data");
	output_native_default_count = 0;
	output_native_copy_count = 0;
	output_native_destructor_count = 0;
	callbacks->callback_success = true;
	result = 99;
	creation.call_virtual_with_data_func(callbacks->created_instance_userdata,
			native_name("_calculate"), virtual_data, nullptr, &result);
	expect(result == 77 && output_native_default_count == 1 &&
					output_native_destructor_count == 1 &&
					output_native_copy_count == 1,
			"nontrivial virtual success must destroy its default once and copy once");
	callbacks->callback_success = false;
	result = 99;
	creation.call_virtual_with_data_func(callbacks->created_instance_userdata,
			native_name("_calculate"), virtual_data, nullptr, &result);
	expect(result == 5 && output_native_default_count == 2 &&
					output_native_destructor_count == 1 &&
					output_native_copy_count == 1,
			"nontrivial virtual failure must preserve its constructed default");

	foundry_java::BridgeServices incomplete = *callbacks->services;
	incomplete.variant_get_ptr_constructor = nullptr;
	result = 5;
	expect(!foundry_java::replace_initialized_native(
				   incomplete, FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3,
				   &result, &callbacks->native_source) &&
					result == 5 && output_native_destructor_count == 1 &&
					output_native_copy_count == 1,
			"replacement prevalidation failure must leave the default untouched");
	callbacks->services->variant_get_ptr_destructor(
			FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3)(&callbacks->native_source);
	expect(output_native_destructor_count == 2,
			"temporary nontrivial replacement source must be destroyed once");

	creation.free_instance_func(
			creation.class_userdata, callbacks->created_instance_userdata);
	expect(registry.unregister_class(21, 8, "Player").ok(),
			"initialized output fixture must unregister");
}

void test_unregister_drains_callbacks_and_rejects_reentrant_cleanup() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	expect(registry
					.register_class(
							12, 6, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x15),
							valid_descriptor())
					.ok(),
			"concurrency fixture must register");
	const FoundryExtensionClassCreationInfo5 creation =
			services->class_registration.creation_info;
	(void)creation.create_instance_func(creation.class_userdata, 0);

	std::mutex gate_mutex;
	std::condition_variable gate_condition;
	bool callback_entered = false;
	bool allow_callback_return = false;
	callbacks->invoke_hook = [&] {
		std::unique_lock<std::mutex> lock(gate_mutex);
		callback_entered = true;
		gate_condition.notify_all();
		gate_condition.wait(lock, [&] { return allow_callback_return; });
	};
	FoundryExtensionCallError call_error{};
	std::uint64_t result = 0;
	std::thread callback_thread([&] {
		services->methods[0].call(services->methods[0].userdata,
				callbacks->created_instance_userdata, nullptr, 0,
				&result, &call_error);
	});
	{
		std::unique_lock<std::mutex> lock(gate_mutex);
		gate_condition.wait(lock, [&] { return callback_entered; });
	}

	std::mutex native_mutex;
	std::condition_variable native_condition;
	bool native_unregister_called = false;
	services->unregister_hook = [&] {
		std::lock_guard<std::mutex> lock(native_mutex);
		native_unregister_called = true;
		native_condition.notify_all();
	};
	foundry_java::RegistrationResult unregister_result;
	std::thread unregister_thread(
			[&] { unregister_result = registry.unregister_class(12, 6, "Player"); });
	{
		std::unique_lock<std::mutex> lock(native_mutex);
		native_condition.wait(lock, [&] { return native_unregister_called; });
	}
	expect(callbacks->release_count.load() == 0,
			"access token must remain live while an admitted callback is running");
	{
		std::lock_guard<std::mutex> lock(gate_mutex);
		allow_callback_return = true;
		gate_condition.notify_all();
	}
	callback_thread.join();
	unregister_thread.join();
	expect(unregister_result.ok(),
			"unregister must complete after callback drain");
	expect(callbacks->release_count.load() == 1,
			"access token must release after callback drain");

	services = std::make_shared<RecordingServices>();
	callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry reentrant_registry(services, callbacks);
	expect(reentrant_registry
					.register_class(
							13, 7, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x16),
							valid_descriptor())
					.ok(),
			"reentrant fixture must register");
	const FoundryExtensionClassCreationInfo5 reentrant_creation =
			services->class_registration.creation_info;
	(void)reentrant_creation.create_instance_func(
			reentrant_creation.class_userdata, 0);
	foundry_java::RegistrationResult reentrant_result;
	callbacks->invoke_hook = [&] {
		reentrant_result = reentrant_registry.unregister_class(13, 7, "Player");
	};
	services->methods[0].call(services->methods[0].userdata,
			callbacks->created_instance_userdata, nullptr, 0,
			&result, &call_error);
	expect(reentrant_result.status == foundry_java::RegistrationStatus::RETRY,
			"same-thread callback cleanup must return retry instead of waiting on "
			"itself");
	expect(std::find(services->operations.begin(), services->operations.end(),
				   "unregister:Player") == services->operations.end(),
			"reentrant cleanup must not mutate native registration");
	expect(reentrant_registry.unregister_class(13, 7, "Player").ok(),
			"cleanup must succeed when retried after callback return");
}

void test_cross_context_rejection_and_shutdown_free_instance_before_access() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	expect(registry
					.register_class(
							14, 8, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x17),
							valid_descriptor())
					.ok(),
			"shutdown fixture must register");
	const FoundryExtensionClassCreationInfo5 creation =
			services->class_registration.creation_info;
	(void)creation.create_instance_func(creation.class_userdata, 0);
	const std::size_t operation_count = services->operations.size();
	expect(registry.unregister_class(99, 8, "Player").status ==
					foundry_java::RegistrationStatus::STALE,
			"cross-context unregister must reject");
	expect(services->operations.size() == operation_count,
			"cross-context rejection must not mutate native state");
	expect(registry.shutdown(14, 8).ok(),
			"context shutdown must unregister its classes");
	expect(callbacks->callback_events ==
					std::vector<std::string>{ "free_instance", "release_access" },
			"shutdown must drain live instances before releasing class access");
}

void test_shutdown_waits_for_inflight_registration_and_closes_terminal_gate() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	std::mutex gate_mutex;
	std::condition_variable gate_condition;
	bool registration_entered = false;
	bool allow_registration = false;
	services->register_hook = [&] {
		std::unique_lock<std::mutex> lock(gate_mutex);
		registration_entered = true;
		gate_condition.notify_all();
		gate_condition.wait(lock, [&] { return allow_registration; });
	};
	foundry_java::RegistrationResult registration_result;
	std::thread registration_thread([&] {
		registration_result = registry.register_class(
				15, 9, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x19),
				valid_descriptor());
	});
	{
		std::unique_lock<std::mutex> lock(gate_mutex);
		gate_condition.wait(lock, [&] { return registration_entered; });
	}
	std::atomic<bool> shutdown_finished{ false };
	foundry_java::RegistrationResult shutdown_result;
	std::thread shutdown_thread([&] {
		shutdown_result = registry.shutdown(15, 9);
		shutdown_finished.store(true);
	});
	std::this_thread::yield();
	expect(!shutdown_finished.load(),
			"shutdown must wait for an admitted in-flight registration");
	{
		std::lock_guard<std::mutex> lock(gate_mutex);
		allow_registration = true;
		gate_condition.notify_all();
	}
	registration_thread.join();
	shutdown_thread.join();
	expect(registration_result.ok() && shutdown_result.ok(),
			"in-flight registration must finish before shutdown rolls it back");
	expect(registry.active_class_count() == 0 &&
					callbacks->release_count.load() == 1,
			"shutdown must not miss the class completed by an in-flight "
			"registration");
	expect(registry.register_class(
									15, 9,
									reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x19),
									valid_descriptor())
							.phase == "registration_shutdown",
			"terminal shutdown gate must reject later registration");
}

void test_shutdown_reentry_from_registration_attempt_returns_retry() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	foundry_java::RegistrationResult reentrant_result;
	services->register_hook = [&] {
		reentrant_result = registry.shutdown(23, 17);
	};
	expect(registry
					.register_class(
							23, 17,
							reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x27),
							valid_descriptor())
					.ok(),
			"registration must finish after reentrant shutdown returns retry");
	expect(reentrant_result.status == foundry_java::RegistrationStatus::RETRY,
			"registration attempt must not wait for shutdown draining itself");
	expect(registry.shutdown(23, 17).ok(),
			"shutdown must remain available after reentrant retry");
}

void test_unregister_waits_for_instance_cleanup_before_access_release() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	expect(registry
					.register_class(
							16, 10,
							reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x20),
							valid_descriptor())
					.ok(),
			"instance cleanup fixture must register");
	const FoundryExtensionClassCreationInfo5 creation =
			services->class_registration.creation_info;
	(void)creation.create_instance_func(creation.class_userdata, 0);

	std::mutex gate_mutex;
	std::condition_variable gate_condition;
	bool cleanup_entered = false;
	bool allow_cleanup = false;
	callbacks->free_hook = [&] {
		std::unique_lock<std::mutex> lock(gate_mutex);
		cleanup_entered = true;
		gate_condition.notify_all();
		gate_condition.wait(lock, [&] { return allow_cleanup; });
	};
	std::thread free_thread([&] {
		creation.free_instance_func(creation.class_userdata, callbacks->created_instance_userdata);
	});
	{
		std::unique_lock<std::mutex> lock(gate_mutex);
		gate_condition.wait(lock, [&] { return cleanup_entered; });
	}
	foundry_java::RegistrationResult unregister_result;
	std::thread unregister_thread(
			[&] {
		unregister_result = registry.unregister_class(16, 10, "Player");
	});
	std::this_thread::yield();
	expect(callbacks->release_count.load() == 0,
			"class access must remain live while instance cleanup is executing");
	{
		std::lock_guard<std::mutex> lock(gate_mutex);
		allow_cleanup = true;
		gate_condition.notify_all();
	}
	free_thread.join();
	unregister_thread.join();
	expect(unregister_result.ok() && callbacks->release_count.load() == 1,
			"unregister must release access only after instance cleanup returns");
}

void test_create_losing_unregister_race_discards_complete_instance() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	expect(registry
					.register_class(
							19, 13,
							reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x23),
							valid_descriptor())
					.ok(),
			"create race fixture must register");
	const FoundryExtensionClassCreationInfo5 creation =
			services->class_registration.creation_info;
	std::mutex gate_mutex;
	std::condition_variable gate_condition;
	bool create_entered = false;
	bool allow_create = false;
	callbacks->create_hook = [&] {
		std::unique_lock<std::mutex> lock(gate_mutex);
		create_entered = true;
		gate_condition.notify_all();
		gate_condition.wait(lock, [&] { return allow_create; });
	};
	FoundryExtensionObjectPtr created_object =
			reinterpret_cast<FoundryExtensionObjectPtr>(0x1);
	std::thread create_thread([&] {
		created_object =
				creation.create_instance_func(creation.class_userdata, 0);
	});
	{
		std::unique_lock<std::mutex> lock(gate_mutex);
		gate_condition.wait(lock, [&] { return create_entered; });
	}
	std::mutex unregister_mutex;
	std::condition_variable unregister_condition;
	bool unregister_entered = false;
	services->unregister_hook = [&] {
		std::lock_guard<std::mutex> lock(unregister_mutex);
		unregister_entered = true;
		unregister_condition.notify_all();
	};
	foundry_java::RegistrationResult unregister_result;
	std::thread unregister_thread(
			[&] {
		unregister_result = registry.unregister_class(19, 13, "Player");
	});
	{
		std::unique_lock<std::mutex> lock(unregister_mutex);
		unregister_condition.wait(lock, [&] { return unregister_entered; });
	}
	{
		std::lock_guard<std::mutex> lock(gate_mutex);
		allow_create = true;
		gate_condition.notify_all();
	}
	create_thread.join();
	unregister_thread.join();
	expect(created_object == nullptr && unregister_result.ok(),
			"create losing unregister race must reject and finish cleanup");
	expect(
			callbacks->discarded_instances.size() == 1 &&
					callbacks->discarded_instances[0].object ==
							reinterpret_cast<FoundryExtensionObjectPtr>(0x100) &&
					callbacks->discarded_instances[0].access_instance == 900,
			"losing complete create must discard both native object and Java token");
	expect(callbacks->freed_instances.empty(),
			"losing complete create must not use token-only free");
}

void test_concurrent_shutdown_callers_share_one_terminal_result() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	expect(registry
					.register_class(
							20, 14,
							reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x24),
							valid_descriptor())
					.ok(),
			"concurrent shutdown fixture must register");
	std::mutex gate_mutex;
	std::condition_variable gate_condition;
	bool unregister_entered = false;
	bool allow_unregister = false;
	foundry_java::RegistrationResult reentrant_result;
	services->unregister_hook = [&] {
		reentrant_result = registry.shutdown(20, 14);
		std::unique_lock<std::mutex> lock(gate_mutex);
		unregister_entered = true;
		gate_condition.notify_all();
		gate_condition.wait(lock, [&] { return allow_unregister; });
	};
	foundry_java::RegistrationResult first_result;
	foundry_java::RegistrationResult second_result;
	std::thread first([&] { first_result = registry.shutdown(20, 14); });
	{
		std::unique_lock<std::mutex> lock(gate_mutex);
		gate_condition.wait(lock, [&] { return unregister_entered; });
	}
	std::atomic<bool> second_started{ false };
	std::thread second([&] {
		second_started.store(true);
		second_result = registry.shutdown(20, 14);
	});
	while (!second_started.load()) {
		std::this_thread::yield();
	}
	std::this_thread::sleep_for(std::chrono::milliseconds(20));
	{
		std::lock_guard<std::mutex> lock(gate_mutex);
		allow_unregister = true;
		gate_condition.notify_all();
	}
	first.join();
	second.join();
	expect(
			first_result.ok() && second_result.ok(),
			"concurrent shutdown callers must share the successful terminal result");
	expect(reentrant_result.status == foundry_java::RegistrationStatus::RETRY,
			"same-thread reentrant shutdown must return retry instead of "
			"deadlocking");
	expect(std::count(services->operations.begin(), services->operations.end(),
				   "unregister:Player") == 1,
			"concurrent shutdown must unregister each class exactly once");
}

void test_shutdown_reentry_from_callback_lease_returns_retry() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	expect(registry
					.register_class(
							21, 15,
							reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x25),
							valid_descriptor())
					.ok(),
			"callback shutdown fixture must register");
	const FoundryExtensionClassCreationInfo5 creation =
			services->class_registration.creation_info;
	(void)creation.create_instance_func(creation.class_userdata, 0);
	std::mutex gate_mutex;
	std::condition_variable gate_condition;
	bool callback_entered = false;
	bool native_shutdown_entered = false;
	foundry_java::RegistrationResult reentrant_result;
	callbacks->invoke_hook = [&] {
		{
			std::unique_lock<std::mutex> lock(gate_mutex);
			callback_entered = true;
			gate_condition.notify_all();
			gate_condition.wait(lock, [&] { return native_shutdown_entered; });
		}
		reentrant_result = registry.shutdown(21, 15);
	};
	services->unregister_hook = [&] {
		std::lock_guard<std::mutex> lock(gate_mutex);
		native_shutdown_entered = true;
		gate_condition.notify_all();
	};
	FoundryExtensionCallError call_error{};
	std::uint64_t result = 0;
	std::thread callback_thread([&] {
		services->methods[0].call(services->methods[0].userdata,
				callbacks->created_instance_userdata, nullptr, 0,
				&result, &call_error);
	});
	{
		std::unique_lock<std::mutex> lock(gate_mutex);
		gate_condition.wait(lock, [&] { return callback_entered; });
	}
	foundry_java::RegistrationResult shutdown_result;
	std::thread shutdown_thread(
			[&] { shutdown_result = registry.shutdown(21, 15); });
	callback_thread.join();
	shutdown_thread.join();
	expect(reentrant_result.status == foundry_java::RegistrationStatus::RETRY &&
					shutdown_result.ok(),
			"callback lease owner must not wait for shutdown that is draining it");
}

void test_shutdown_reentry_from_instance_cleanup_returns_retry() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	expect(registry
					.register_class(
							22, 16,
							reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x26),
							valid_descriptor())
					.ok(),
			"cleanup shutdown fixture must register");
	const FoundryExtensionClassCreationInfo5 creation =
			services->class_registration.creation_info;
	(void)creation.create_instance_func(creation.class_userdata, 0);
	std::mutex gate_mutex;
	std::condition_variable gate_condition;
	bool cleanup_entered = false;
	bool native_shutdown_entered = false;
	foundry_java::RegistrationResult reentrant_result;
	callbacks->free_hook = [&] {
		{
			std::unique_lock<std::mutex> lock(gate_mutex);
			cleanup_entered = true;
			gate_condition.notify_all();
			gate_condition.wait(lock, [&] { return native_shutdown_entered; });
		}
		reentrant_result = registry.shutdown(22, 16);
	};
	services->unregister_hook = [&] {
		std::lock_guard<std::mutex> lock(gate_mutex);
		native_shutdown_entered = true;
		gate_condition.notify_all();
	};
	std::thread cleanup_thread([&] {
		creation.free_instance_func(creation.class_userdata, callbacks->created_instance_userdata);
	});
	{
		std::unique_lock<std::mutex> lock(gate_mutex);
		gate_condition.wait(lock, [&] { return cleanup_entered; });
	}
	foundry_java::RegistrationResult shutdown_result;
	std::thread shutdown_thread(
			[&] { shutdown_result = registry.shutdown(22, 16); });
	cleanup_thread.join();
	shutdown_thread.join();
	expect(
			reentrant_result.status == foundry_java::RegistrationStatus::RETRY &&
					shutdown_result.ok(),
			"instance cleanup owner must not wait for shutdown that is draining it");
}

void test_partial_instance_creation_is_discarded() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	expect(registry
					.register_class(
							17, 11,
							reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x21),
							valid_descriptor())
					.ok(),
			"partial create fixture must register");
	const FoundryExtensionClassCreationInfo5 creation =
			services->class_registration.creation_info;
	callbacks->created_instance = {
		reinterpret_cast<FoundryExtensionObjectPtr>(0x100),
		0,
	};
	expect(creation.create_instance_func(creation.class_userdata, 0) == nullptr,
			"object without Java instance token must reject");
	callbacks->created_instance = { nullptr, 900 };
	expect(creation.create_instance_func(creation.class_userdata, 0) == nullptr,
			"Java instance token without object must reject");
	expect(callbacks->discarded_instances.size() == 2,
			"each incomplete create result must be explicitly discarded");
	expect(registry.unregister_class(17, 11, "Player").ok(),
			"partial create fixture must cleanly unregister");
}

void test_registry_destructor_drains_owned_classes() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	{
		foundry_java::RegistrationRegistry registry(services, callbacks);
		expect(registry
						.register_class(
								18, 12,
								reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x22),
								valid_descriptor())
						.ok(),
				"destructor fixture must register");
		const FoundryExtensionClassCreationInfo5 creation =
				services->class_registration.creation_info;
		(void)creation.create_instance_func(creation.class_userdata, 0);
	}
	expect(std::find(services->operations.begin(), services->operations.end(),
				   "unregister:Player") != services->operations.end(),
			"registry destructor must unregister every owned native class");
	expect(callbacks->freed_instances ==
							std::vector<foundry_java::RegistrationInstanceToken>{ 900 } &&
					callbacks->released_accesses ==
							std::vector<foundry_java::RegistrationAccessToken>{ 41 },
			"registry destructor must drain instances before access globals");
}

void test_failed_provisional_rollback_retains_ownership_for_shutdown_retry() {
	auto services = std::make_shared<RecordingServices>();
	auto callbacks = std::make_shared<NoOpCallbacks>();
	foundry_java::RegistrationRegistry registry(services, callbacks);
	auto descriptor = valid_descriptor();
	descriptor.members.push_back({
			foundry_java::RegistrationMemberKind::CONSTANT,
			"FAIL",
			"FAIL",
			"long",
			foundry_java::RegistrationConstantDetails{ "", 1, false },
			{},
	});
	services->fail_operations = { "constant:FAIL", "unregister:Player" };
	const auto failure = registry.register_class(
			10, 4, reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x18),
			std::move(descriptor));
	expect(!failure.ok() && failure.phase == "registration_rollback",
			"failed provisional rollback must report retained native ownership");
	expect(registry.active_class_count() == 1 && registry.tombstone_count() == 0,
			"failed rollback must keep its class record retryable");
	expect(callbacks->released_accesses.empty(),
			"failed rollback must retain access globals");
	const FoundryExtensionClassCreationInfo5 quarantined_creation =
			services->class_registration.creation_info;
	expect(quarantined_creation.create_instance_func(
				   quarantined_creation.class_userdata, 0) == nullptr &&
					callbacks->create_count == 0,
			"failed rollback class must stay quarantined from callbacks");

	services->fail_operations.clear();
	services->operations.clear();
	expect(registry.shutdown(10, 4).ok(),
			"shutdown must retry retained provisional cleanup");
	expect(
			services->operations == std::vector<std::string>{ "unregister:Player" },
			"shutdown retry must unregister retained provisional class exactly once");
	expect(callbacks->release_count.load() == 1,
			"successful retry must finally release access");
}

} // namespace

int main() {
	test_strict_java_transport_signature_parser();
	test_registration_interface_inventory_excludes_virtual_registration();
	test_public_abi_registration_adapter_maps_exact_void_services();
	test_public_abi_registration_adapter_destroys_failed_strings_before_mutation();
	test_whole_descriptor_validation_precedes_native_mutation();
	test_object_types_resolve_before_native_mutation();
	test_registration_uses_class5_and_exact_member_order();
	test_property_details_emit_canonical_transitions_and_indexed_forms();
	test_provisional_failure_self_rolls_back_and_completed_classes_reverse();
	test_failed_provisional_rollback_retains_ownership_for_shutdown_retry();
	test_stable_callbacks_dispatch_instances_properties_and_virtuals();
	test_callback_outputs_replace_initialized_defaults_exactly_once();
	test_unregister_drains_callbacks_and_rejects_reentrant_cleanup();
	test_cross_context_rejection_and_shutdown_free_instance_before_access();
	test_shutdown_waits_for_inflight_registration_and_closes_terminal_gate();
	test_shutdown_reentry_from_registration_attempt_returns_retry();
	test_unregister_waits_for_instance_cleanup_before_access_release();
	test_create_losing_unregister_race_discards_complete_instance();
	test_concurrent_shutdown_callers_share_one_terminal_result();
	test_shutdown_reentry_from_callback_lease_returns_retry();
	test_shutdown_reentry_from_instance_cleanup_returns_retry();
	test_partial_instance_creation_is_discarded();
	test_registry_destructor_drains_owned_classes();
	std::cout << "Foundry Java native registration tests passed\n";
	return 0;
}
