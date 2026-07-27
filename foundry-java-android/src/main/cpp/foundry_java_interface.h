#pragma once

#include "foundry_extension_interface.h"

#include <array>
#include <memory>
#include <string>
#include <string_view>

#define FOUNDRY_JAVA_BRIDGE_SERVICE_LIST(X) \
	X(mem_alloc2, FoundryExtensionInterfaceMemAlloc2, "mem_alloc2") \
	X(mem_realloc2, FoundryExtensionInterfaceMemRealloc2, "mem_realloc2") \
	X(mem_free2, FoundryExtensionInterfaceMemFree2, "mem_free2") \
	X(print_error, FoundryExtensionInterfacePrintError, "print_error") \
	X(get_native_struct_size, FoundryExtensionInterfaceGetNativeStructSize, "get_native_struct_size") \
	X(variant_new_copy, FoundryExtensionInterfaceVariantNewCopy, "variant_new_copy") \
	X(variant_new_nil, FoundryExtensionInterfaceVariantNewNil, "variant_new_nil") \
	X(variant_destroy, FoundryExtensionInterfaceVariantDestroy, "variant_destroy") \
	X(variant_call, FoundryExtensionInterfaceVariantCall, "variant_call") \
	X(variant_construct, FoundryExtensionInterfaceVariantConstruct, "variant_construct") \
	X(variant_get_type, FoundryExtensionInterfaceVariantGetType, "variant_get_type") \
	X(get_variant_from_type_constructor, FoundryExtensionInterfaceGetVariantFromTypeConstructor, \
			"get_variant_from_type_constructor") \
	X(get_variant_to_type_constructor, FoundryExtensionInterfaceGetVariantToTypeConstructor, \
			"get_variant_to_type_constructor") \
	X(variant_get_internal, FoundryExtensionInterfaceGetVariantGetInternalPtrFunc, \
			"variant_get_ptr_internal_getter") \
	X(variant_get_ptr_builtin_method, FoundryExtensionInterfaceVariantGetPtrBuiltinMethod, \
			"variant_get_ptr_builtin_method") \
	X(variant_get_ptr_constructor, FoundryExtensionInterfaceVariantGetPtrConstructor, \
			"variant_get_ptr_constructor") \
	X(variant_get_ptr_destructor, FoundryExtensionInterfaceVariantGetPtrDestructor, \
			"variant_get_ptr_destructor") \
	X(variant_get_ptr_getter, FoundryExtensionInterfaceVariantGetPtrGetter, "variant_get_ptr_getter") \
	X(variant_get_ptr_setter, FoundryExtensionInterfaceVariantGetPtrSetter, "variant_get_ptr_setter") \
	X(variant_get_named, FoundryExtensionInterfaceVariantGetNamed, "variant_get_named") \
	X(variant_set_named, FoundryExtensionInterfaceVariantSetNamed, "variant_set_named") \
	X(variant_get_keyed, FoundryExtensionInterfaceVariantGetKeyed, "variant_get_keyed") \
	X(variant_set_keyed, FoundryExtensionInterfaceVariantSetKeyed, "variant_set_keyed") \
	X(variant_get_indexed, FoundryExtensionInterfaceVariantGetIndexed, "variant_get_indexed") \
	X(variant_set_indexed, FoundryExtensionInterfaceVariantSetIndexed, "variant_set_indexed") \
	X(variant_iter_init, FoundryExtensionInterfaceVariantIterInit, "variant_iter_init") \
	X(variant_iter_next, FoundryExtensionInterfaceVariantIterNext, "variant_iter_next") \
	X(variant_iter_get, FoundryExtensionInterfaceVariantIterGet, "variant_iter_get") \
	X(variant_evaluate, FoundryExtensionInterfaceVariantEvaluate, "variant_evaluate") \
	X(variant_get_constant_value, FoundryExtensionInterfaceVariantGetConstantValue, \
			"variant_get_constant_value") \
	X(variant_get_ptr_utility_function, FoundryExtensionInterfaceVariantGetPtrUtilityFunction, \
			"variant_get_ptr_utility_function") \
	X(string_new_with_utf8_chars_and_len2, FoundryExtensionInterfaceStringNewWithUtf8CharsAndLen2, \
			"string_new_with_utf8_chars_and_len2") \
	X(string_to_utf8_chars, FoundryExtensionInterfaceStringToUtf8Chars, "string_to_utf8_chars") \
	X(string_name_new_with_utf8_chars_and_len, \
			FoundryExtensionInterfaceStringNameNewWithUtf8CharsAndLen, \
			"string_name_new_with_utf8_chars_and_len") \
	X(object_method_bind_call, FoundryExtensionInterfaceObjectMethodBindCall, "object_method_bind_call") \
	X(object_method_bind_ptrcall, FoundryExtensionInterfaceObjectMethodBindPtrcall, \
			"object_method_bind_ptrcall") \
	X(object_destroy, FoundryExtensionInterfaceObjectDestroy, "object_destroy") \
	X(global_get_singleton, FoundryExtensionInterfaceGlobalGetSingleton, "global_get_singleton") \
	X(object_get_instance_binding, FoundryExtensionInterfaceObjectGetInstanceBinding, \
			"object_get_instance_binding") \
	X(object_set_instance_binding, FoundryExtensionInterfaceObjectSetInstanceBinding, \
			"object_set_instance_binding") \
	X(object_free_instance_binding, FoundryExtensionInterfaceObjectFreeInstanceBinding, \
			"object_free_instance_binding") \
	X(object_set_instance, FoundryExtensionInterfaceObjectSetInstance, "object_set_instance") \
	X(object_get_class_name, FoundryExtensionInterfaceObjectGetClassName, "object_get_class_name") \
	X(object_cast_to, FoundryExtensionInterfaceObjectCastTo, "object_cast_to") \
	X(object_get_instance_from_id, FoundryExtensionInterfaceObjectGetInstanceFromId, \
			"object_get_instance_from_id") \
	X(object_get_instance_id, FoundryExtensionInterfaceObjectGetInstanceId, "object_get_instance_id") \
	X(callable_custom_create2, FoundryExtensionInterfaceCallableCustomCreate2, "callable_custom_create2") \
	X(callable_custom_get_userdata, FoundryExtensionInterfaceCallableCustomGetUserData, \
			"callable_custom_get_userdata") \
	X(classdb_construct_object2, FoundryExtensionInterfaceClassdbConstructObject2, \
			"classdb_construct_object2") \
	X(classdb_get_method_bind, FoundryExtensionInterfaceClassdbGetMethodBind, "classdb_get_method_bind") \
	X(classdb_get_class_tag, FoundryExtensionInterfaceClassdbGetClassTag, "classdb_get_class_tag") \
	X(classdb_register_extension_class5, FoundryExtensionInterfaceClassdbRegisterExtensionClass5, \
			"classdb_register_extension_class5") \
	X(classdb_register_extension_class_method, FoundryExtensionInterfaceClassdbRegisterExtensionClassMethod, \
			"classdb_register_extension_class_method") \
	X(classdb_register_extension_class_integer_constant, \
			FoundryExtensionInterfaceClassdbRegisterExtensionClassIntegerConstant, \
			"classdb_register_extension_class_integer_constant") \
	X(classdb_register_extension_class_property, FoundryExtensionInterfaceClassdbRegisterExtensionClassProperty, \
			"classdb_register_extension_class_property") \
	X(classdb_register_extension_class_property_indexed, \
			FoundryExtensionInterfaceClassdbRegisterExtensionClassPropertyIndexed, \
			"classdb_register_extension_class_property_indexed") \
	X(classdb_register_extension_class_property_group, \
			FoundryExtensionInterfaceClassdbRegisterExtensionClassPropertyGroup, \
			"classdb_register_extension_class_property_group") \
	X(classdb_register_extension_class_property_subgroup, \
			FoundryExtensionInterfaceClassdbRegisterExtensionClassPropertySubgroup, \
			"classdb_register_extension_class_property_subgroup") \
	X(classdb_register_extension_class_signal, FoundryExtensionInterfaceClassdbRegisterExtensionClassSignal, \
			"classdb_register_extension_class_signal") \
	X(classdb_unregister_extension_class, FoundryExtensionInterfaceClassdbUnregisterExtensionClass, \
			"classdb_unregister_extension_class")

namespace foundry_java {

struct BridgeServices {
#define FOUNDRY_JAVA_DECLARE_SERVICE(member, type, name) type member = nullptr;
	FOUNDRY_JAVA_BRIDGE_SERVICE_LIST(FOUNDRY_JAVA_DECLARE_SERVICE)
#undef FOUNDRY_JAVA_DECLARE_SERVICE
};

struct BridgeResolution {
	std::shared_ptr<const BridgeServices> services;
	std::string missing_name;
	FoundryExtensionInterfacePrintError print_error = nullptr;
};

BridgeResolution resolve_bridge_services(FoundryExtensionInterfaceGetProcAddress get_proc_address);
const std::array<std::string_view, 60> &required_bridge_service_names();

} // namespace foundry_java
