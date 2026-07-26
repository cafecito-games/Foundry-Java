/**************************************************************************/
/*  foundry_extension_interface.h                                               */
/**************************************************************************/
/*                         This file is part of:                          */
/*                             FOUNDRY ENGINE                             */
/*                  https://docs.cafecito.games/foundry                   */
/**************************************************************************/
/* Copyright (c) 2014-present Godot Engine contributors (see AUTHORS.md). */
/* Copyright (c) 2007-2014 Juan Linietsky, Ariel Manzur.                  */
/*                                                                        */
/* Permission is hereby granted, free of charge, to any person obtaining  */
/* a copy of this software and associated documentation files (the        */
/* "Software"), to deal in the Software without restriction, including    */
/* without limitation the rights to use, copy, modify, merge, publish,    */
/* distribute, sublicense, and/or sell copies of the Software, and to     */
/* permit persons to whom the Software is furnished to do so, subject to  */
/* the following conditions:                                              */
/*                                                                        */
/* The above copyright notice and this permission notice shall be         */
/* included in all copies or substantial portions of the Software.        */
/*                                                                        */
/* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,        */
/* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF     */
/* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. */
/* IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY   */
/* CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,   */
/* TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE      */
/* SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.                 */
/**************************************************************************/

#pragma once

/* This is a C class header, you can copy it and use it directly in your own binders.
 * Together with the `extension_api.json` file, you should be able to generate any binder.
 */

#ifndef __cplusplus
#include <stddef.h>
#include <stdint.h>

typedef uint32_t char32_t;
typedef uint16_t char16_t;
#else
#include <cstddef>
#include <cstdint>

extern "C" {
#endif

typedef enum {
	FOUNDRY_EXTENSION_VARIANT_TYPE_NIL = 0,
	FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL = 1,
	FOUNDRY_EXTENSION_VARIANT_TYPE_INT = 2,
	FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT = 3,
	FOUNDRY_EXTENSION_VARIANT_TYPE_STRING = 4,
	FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2 = 5,
	FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2I = 6,
	FOUNDRY_EXTENSION_VARIANT_TYPE_RECT2 = 7,
	FOUNDRY_EXTENSION_VARIANT_TYPE_RECT2I = 8,
	FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3 = 9,
	FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3I = 10,
	FOUNDRY_EXTENSION_VARIANT_TYPE_TRANSFORM2D = 11,
	FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR4 = 12,
	FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR4I = 13,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PLANE = 14,
	FOUNDRY_EXTENSION_VARIANT_TYPE_QUATERNION = 15,
	FOUNDRY_EXTENSION_VARIANT_TYPE_AABB = 16,
	FOUNDRY_EXTENSION_VARIANT_TYPE_BASIS = 17,
	FOUNDRY_EXTENSION_VARIANT_TYPE_TRANSFORM3D = 18,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PROJECTION = 19,
	FOUNDRY_EXTENSION_VARIANT_TYPE_COLOR = 20,
	FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME = 21,
	FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH = 22,
	FOUNDRY_EXTENSION_VARIANT_TYPE_RID = 23,
	FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT = 24,
	FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE = 25,
	FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL = 26,
	FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY = 27,
	FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY = 28,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY = 29,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY = 30,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY = 31,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY = 32,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY = 33,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY = 34,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY = 35,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY = 36,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY = 37,
	FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY = 38,
	FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX = 39,
} FoundryExtensionVariantType;

typedef enum {
	FOUNDRY_EXTENSION_VARIANT_OP_EQUAL = 0,
	FOUNDRY_EXTENSION_VARIANT_OP_NOT_EQUAL = 1,
	FOUNDRY_EXTENSION_VARIANT_OP_LESS = 2,
	FOUNDRY_EXTENSION_VARIANT_OP_LESS_EQUAL = 3,
	FOUNDRY_EXTENSION_VARIANT_OP_GREATER = 4,
	FOUNDRY_EXTENSION_VARIANT_OP_GREATER_EQUAL = 5,
	FOUNDRY_EXTENSION_VARIANT_OP_ADD = 6,
	FOUNDRY_EXTENSION_VARIANT_OP_SUBTRACT = 7,
	FOUNDRY_EXTENSION_VARIANT_OP_MULTIPLY = 8,
	FOUNDRY_EXTENSION_VARIANT_OP_DIVIDE = 9,
	FOUNDRY_EXTENSION_VARIANT_OP_NEGATE = 10,
	FOUNDRY_EXTENSION_VARIANT_OP_POSITIVE = 11,
	FOUNDRY_EXTENSION_VARIANT_OP_MODULE = 12,
	FOUNDRY_EXTENSION_VARIANT_OP_POWER = 13,
	FOUNDRY_EXTENSION_VARIANT_OP_SHIFT_LEFT = 14,
	FOUNDRY_EXTENSION_VARIANT_OP_SHIFT_RIGHT = 15,
	FOUNDRY_EXTENSION_VARIANT_OP_BIT_AND = 16,
	FOUNDRY_EXTENSION_VARIANT_OP_BIT_OR = 17,
	FOUNDRY_EXTENSION_VARIANT_OP_BIT_XOR = 18,
	FOUNDRY_EXTENSION_VARIANT_OP_BIT_NEGATE = 19,
	FOUNDRY_EXTENSION_VARIANT_OP_AND = 20,
	FOUNDRY_EXTENSION_VARIANT_OP_OR = 21,
	FOUNDRY_EXTENSION_VARIANT_OP_XOR = 22,
	FOUNDRY_EXTENSION_VARIANT_OP_NOT = 23,
	FOUNDRY_EXTENSION_VARIANT_OP_IN = 24,
	FOUNDRY_EXTENSION_VARIANT_OP_MAX = 25,
} FoundryExtensionVariantOperator;

/* In this API there are multiple functions which expect the caller to pass a pointer
 * on return value as parameter.
 * In order to make it clear if the caller should initialize the return value or not
 * we have two flavor of types:
 * - `FoundryExtensionXXXPtr` for pointer on an initialized value
 * - `FoundryExtensionUninitializedXXXPtr` for pointer on uninitialized value
 *
 * Notes:
 * - Not respecting those requirements can seems harmless, but will lead to unexpected
 * segfault or memory leak (for instance with a specific compiler/OS, or when two
 * native extensions start doing ptrcall on each other).
 * - Initialization must be done with the function pointer returned by `variant_get_ptr_constructor`,
 * zero-initializing the variable should not be considered a valid initialization method here !
 * - Some types have no destructor (see `extension_api.json`'s `has_destructor` field), for
 * them it is always safe to skip the constructor for the return value if you are in a hurry ;-)
 */
typedef void *FoundryExtensionVariantPtr;
typedef const void *FoundryExtensionConstVariantPtr;
typedef void *FoundryExtensionUninitializedVariantPtr;
typedef void *FoundryExtensionStringNamePtr;
typedef const void *FoundryExtensionConstStringNamePtr;
typedef void *FoundryExtensionUninitializedStringNamePtr;
typedef void *FoundryExtensionStringPtr;
typedef const void *FoundryExtensionConstStringPtr;
typedef void *FoundryExtensionUninitializedStringPtr;
typedef void *FoundryExtensionObjectPtr;
typedef const void *FoundryExtensionConstObjectPtr;
typedef void *FoundryExtensionUninitializedObjectPtr;
typedef void *FoundryExtensionTypePtr;
typedef const void *FoundryExtensionConstTypePtr;
typedef void *FoundryExtensionUninitializedTypePtr;
typedef const void *FoundryExtensionMethodBindPtr;
typedef int64_t FoundryExtensionInt;
typedef uint8_t FoundryExtensionBool;
typedef uint64_t GDObjectInstanceID;
typedef void *FoundryExtensionRefPtr;
typedef const void *FoundryExtensionConstRefPtr;
typedef enum {
	FOUNDRY_EXTENSION_CALL_OK = 0,
	FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD = 1,
	/* Expected a different variant type. */
	FOUNDRY_EXTENSION_CALL_ERROR_INVALID_ARGUMENT = 2,
	/* Expected lower number of arguments. */
	FOUNDRY_EXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS = 3,
	/* Expected higher number of arguments. */
	FOUNDRY_EXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS = 4,
	FOUNDRY_EXTENSION_CALL_ERROR_INSTANCE_IS_NULL = 5,
	/* Used for const call. */
	FOUNDRY_EXTENSION_CALL_ERROR_METHOD_NOT_CONST = 6,
} FoundryExtensionCallErrorType;

typedef struct {
	FoundryExtensionCallErrorType error;
	int32_t argument;
	int32_t expected;
} FoundryExtensionCallError;

typedef void (*FoundryExtensionVariantFromTypeConstructorFunc)(FoundryExtensionUninitializedVariantPtr, FoundryExtensionTypePtr);
typedef void (*FoundryExtensionTypeFromVariantConstructorFunc)(FoundryExtensionUninitializedTypePtr, FoundryExtensionVariantPtr);
typedef void *(*FoundryExtensionVariantGetInternalPtrFunc)(FoundryExtensionVariantPtr);
typedef void (*FoundryExtensionPtrOperatorEvaluator)(FoundryExtensionConstTypePtr p_left, FoundryExtensionConstTypePtr p_right, FoundryExtensionTypePtr r_result);
typedef void (*FoundryExtensionPtrBuiltInMethod)(FoundryExtensionTypePtr p_base, const FoundryExtensionConstTypePtr *p_args, FoundryExtensionTypePtr r_return, int32_t p_argument_count);
typedef void (*FoundryExtensionPtrConstructor)(FoundryExtensionUninitializedTypePtr p_base, const FoundryExtensionConstTypePtr *p_args);
typedef void (*FoundryExtensionPtrDestructor)(FoundryExtensionTypePtr p_base);
typedef void (*FoundryExtensionPtrSetter)(FoundryExtensionTypePtr p_base, FoundryExtensionConstTypePtr p_value);
typedef void (*FoundryExtensionPtrGetter)(FoundryExtensionConstTypePtr p_base, FoundryExtensionTypePtr r_value);
typedef void (*FoundryExtensionPtrIndexedSetter)(FoundryExtensionTypePtr p_base, FoundryExtensionInt p_index, FoundryExtensionConstTypePtr p_value);
typedef void (*FoundryExtensionPtrIndexedGetter)(FoundryExtensionConstTypePtr p_base, FoundryExtensionInt p_index, FoundryExtensionTypePtr r_value);
typedef void (*FoundryExtensionPtrKeyedSetter)(FoundryExtensionTypePtr p_base, FoundryExtensionConstTypePtr p_key, FoundryExtensionConstTypePtr p_value);
typedef void (*FoundryExtensionPtrKeyedGetter)(FoundryExtensionConstTypePtr p_base, FoundryExtensionConstTypePtr p_key, FoundryExtensionTypePtr r_value);
typedef uint32_t (*FoundryExtensionPtrKeyedChecker)(FoundryExtensionConstVariantPtr p_base, FoundryExtensionConstVariantPtr p_key);
typedef void (*FoundryExtensionPtrUtilityFunction)(FoundryExtensionTypePtr r_return, const FoundryExtensionConstTypePtr *p_args, int32_t p_argument_count);
typedef FoundryExtensionObjectPtr (*FoundryExtensionClassConstructor)();
typedef void *(*FoundryExtensionInstanceBindingCreateCallback)(void *p_token, void *p_instance);
typedef void (*FoundryExtensionInstanceBindingFreeCallback)(void *p_token, void *p_instance, void *p_binding);
typedef FoundryExtensionBool (*FoundryExtensionInstanceBindingReferenceCallback)(void *p_token, void *p_binding, FoundryExtensionBool p_reference);
typedef struct {
	FoundryExtensionInstanceBindingCreateCallback create_callback;
	FoundryExtensionInstanceBindingFreeCallback free_callback;
	FoundryExtensionInstanceBindingReferenceCallback reference_callback;
} FoundryExtensionInstanceBindingCallbacks;

typedef void *FoundryExtensionClassInstancePtr;
typedef FoundryExtensionBool (*FoundryExtensionClassSet)(FoundryExtensionClassInstancePtr p_instance, FoundryExtensionConstStringNamePtr p_name, FoundryExtensionConstVariantPtr p_value);
typedef FoundryExtensionBool (*FoundryExtensionClassGet)(FoundryExtensionClassInstancePtr p_instance, FoundryExtensionConstStringNamePtr p_name, FoundryExtensionVariantPtr r_ret);
typedef uint64_t (*FoundryExtensionClassGetRID)(FoundryExtensionClassInstancePtr p_instance);
typedef struct {
	FoundryExtensionVariantType type;
	FoundryExtensionStringNamePtr name;
	FoundryExtensionStringNamePtr class_name;
	/* Bitfield of `PropertyHint` (defined in `extension_api.json`). */
	uint32_t hint;
	FoundryExtensionStringPtr hint_string;
	/* Bitfield of `PropertyUsageFlags` (defined in `extension_api.json`). */
	uint32_t usage;
} FoundryExtensionPropertyInfo;

typedef struct {
	FoundryExtensionStringNamePtr name;
	FoundryExtensionPropertyInfo return_value;
	/* Bitfield of `FoundryExtensionClassMethodFlags`. */
	uint32_t flags;
	int32_t id;
	/* Arguments: `default_arguments` is an array of size `argument_count`. */
	uint32_t argument_count;
	FoundryExtensionPropertyInfo *arguments;
	/* Default arguments: `default_arguments` is an array of size `default_argument_count`. */
	uint32_t default_argument_count;
	FoundryExtensionVariantPtr *default_arguments;
} FoundryExtensionMethodInfo;

typedef const FoundryExtensionPropertyInfo *(*FoundryExtensionClassGetPropertyList)(FoundryExtensionClassInstancePtr p_instance, uint32_t *r_count);
typedef void (*FoundryExtensionClassFreePropertyList)(FoundryExtensionClassInstancePtr p_instance, const FoundryExtensionPropertyInfo *p_list);
typedef void (*FoundryExtensionClassFreePropertyList2)(FoundryExtensionClassInstancePtr p_instance, const FoundryExtensionPropertyInfo *p_list, uint32_t p_count);
typedef FoundryExtensionBool (*FoundryExtensionClassPropertyCanRevert)(FoundryExtensionClassInstancePtr p_instance, FoundryExtensionConstStringNamePtr p_name);
typedef FoundryExtensionBool (*FoundryExtensionClassPropertyGetRevert)(FoundryExtensionClassInstancePtr p_instance, FoundryExtensionConstStringNamePtr p_name, FoundryExtensionVariantPtr r_ret);
typedef FoundryExtensionBool (*FoundryExtensionClassValidateProperty)(FoundryExtensionClassInstancePtr p_instance, FoundryExtensionPropertyInfo *p_property);
typedef void (*FoundryExtensionClassNotification)(FoundryExtensionClassInstancePtr p_instance, int32_t p_what); /* Deprecated in Foundry 4.2. Use `FoundryExtensionClassNotification2` instead. */
typedef void (*FoundryExtensionClassNotification2)(FoundryExtensionClassInstancePtr p_instance, int32_t p_what, FoundryExtensionBool p_reversed);
typedef void (*FoundryExtensionClassToString)(FoundryExtensionClassInstancePtr p_instance, FoundryExtensionBool *r_is_valid, FoundryExtensionStringPtr p_out);
typedef void (*FoundryExtensionClassReference)(FoundryExtensionClassInstancePtr p_instance);
typedef void (*FoundryExtensionClassUnreference)(FoundryExtensionClassInstancePtr p_instance);
typedef void (*FoundryExtensionClassCallVirtual)(FoundryExtensionClassInstancePtr p_instance, const FoundryExtensionConstTypePtr *p_args, FoundryExtensionTypePtr r_ret);
typedef FoundryExtensionObjectPtr (*FoundryExtensionClassCreateInstance)(void *p_class_userdata);
typedef FoundryExtensionObjectPtr (*FoundryExtensionClassCreateInstance2)(void *p_class_userdata, FoundryExtensionBool p_notify_postinitialize);
typedef void (*FoundryExtensionClassFreeInstance)(void *p_class_userdata, FoundryExtensionClassInstancePtr p_instance);
typedef FoundryExtensionClassInstancePtr (*FoundryExtensionClassRecreateInstance)(void *p_class_userdata, FoundryExtensionObjectPtr p_object);
typedef FoundryExtensionClassCallVirtual (*FoundryExtensionClassGetVirtual)(void *p_class_userdata, FoundryExtensionConstStringNamePtr p_name);
typedef FoundryExtensionClassCallVirtual (*FoundryExtensionClassGetVirtual2)(void *p_class_userdata, FoundryExtensionConstStringNamePtr p_name, uint32_t p_hash);
typedef void *(*FoundryExtensionClassGetVirtualCallData)(void *p_class_userdata, FoundryExtensionConstStringNamePtr p_name);
typedef void *(*FoundryExtensionClassGetVirtualCallData2)(void *p_class_userdata, FoundryExtensionConstStringNamePtr p_name, uint32_t p_hash);
typedef void (*FoundryExtensionClassCallVirtualWithData)(FoundryExtensionClassInstancePtr p_instance, FoundryExtensionConstStringNamePtr p_name, void *p_virtual_call_userdata, const FoundryExtensionConstTypePtr *p_args, FoundryExtensionTypePtr r_ret);
typedef struct {
	FoundryExtensionBool is_virtual;
	FoundryExtensionBool is_abstract;
	FoundryExtensionClassSet set_func;
	FoundryExtensionClassGet get_func;
	FoundryExtensionClassGetPropertyList get_property_list_func;
	FoundryExtensionClassFreePropertyList free_property_list_func;
	FoundryExtensionClassPropertyCanRevert property_can_revert_func;
	FoundryExtensionClassPropertyGetRevert property_get_revert_func;
	FoundryExtensionClassNotification notification_func;
	FoundryExtensionClassToString to_string_func;
	FoundryExtensionClassReference reference_func;
	FoundryExtensionClassUnreference unreference_func;
	/* (Default) constructor; mandatory. If the class is not instantiable, consider making it virtual or abstract. */
	FoundryExtensionClassCreateInstance create_instance_func;
	/* Destructor; mandatory. */
	FoundryExtensionClassFreeInstance free_instance_func;
	/* Queries a virtual function by name and returns a callback to invoke the requested virtual function. */
	FoundryExtensionClassGetVirtual get_virtual_func;
	FoundryExtensionClassGetRID get_rid_func;
	/* Per-class user data, later accessible in instance bindings. */
	void *class_userdata;
} FoundryExtensionClassCreationInfo; /* Deprecated in Foundry 4.2. Use `FoundryExtensionClassCreationInfo4` instead. */

typedef struct {
	FoundryExtensionBool is_virtual;
	FoundryExtensionBool is_abstract;
	FoundryExtensionBool is_exposed;
	FoundryExtensionClassSet set_func;
	FoundryExtensionClassGet get_func;
	FoundryExtensionClassGetPropertyList get_property_list_func;
	FoundryExtensionClassFreePropertyList free_property_list_func;
	FoundryExtensionClassPropertyCanRevert property_can_revert_func;
	FoundryExtensionClassPropertyGetRevert property_get_revert_func;
	FoundryExtensionClassValidateProperty validate_property_func;
	FoundryExtensionClassNotification2 notification_func;
	FoundryExtensionClassToString to_string_func;
	FoundryExtensionClassReference reference_func;
	FoundryExtensionClassUnreference unreference_func;
	/* (Default) constructor; mandatory. If the class is not instantiable, consider making it virtual or abstract. */
	FoundryExtensionClassCreateInstance create_instance_func;
	/* Destructor; mandatory. */
	FoundryExtensionClassFreeInstance free_instance_func;
	FoundryExtensionClassRecreateInstance recreate_instance_func;
	/* Queries a virtual function by name and returns a callback to invoke the requested virtual function. */
	FoundryExtensionClassGetVirtual get_virtual_func;
	/* Paired with `call_virtual_with_data_func`, this is an alternative to `get_virtual_func` for extensions that
	 * need or benefit from extra data when calling virtual functions.
	 * Returns user data that will be passed to `call_virtual_with_data_func`.
	 * Returning `NULL` from this function signals to Foundry that the virtual function is not overridden.
	 * Data returned from this function should be managed by the extension and must be valid until the extension is deinitialized.
	 * You should supply either `get_virtual_func`, or `get_virtual_call_data_func` with `call_virtual_with_data_func`.
	 */
	FoundryExtensionClassGetVirtualCallData get_virtual_call_data_func;
	/* Used to call virtual functions when `get_virtual_call_data_func` is not null. */
	FoundryExtensionClassCallVirtualWithData call_virtual_with_data_func;
	FoundryExtensionClassGetRID get_rid_func;
	/* Per-class user data, later accessible in instance bindings. */
	void *class_userdata;
} FoundryExtensionClassCreationInfo2; /* Deprecated in Foundry 4.3. Use `FoundryExtensionClassCreationInfo4` instead. */

typedef struct {
	FoundryExtensionBool is_virtual;
	FoundryExtensionBool is_abstract;
	FoundryExtensionBool is_exposed;
	FoundryExtensionBool is_runtime;
	FoundryExtensionClassSet set_func;
	FoundryExtensionClassGet get_func;
	FoundryExtensionClassGetPropertyList get_property_list_func;
	FoundryExtensionClassFreePropertyList2 free_property_list_func;
	FoundryExtensionClassPropertyCanRevert property_can_revert_func;
	FoundryExtensionClassPropertyGetRevert property_get_revert_func;
	FoundryExtensionClassValidateProperty validate_property_func;
	FoundryExtensionClassNotification2 notification_func;
	FoundryExtensionClassToString to_string_func;
	FoundryExtensionClassReference reference_func;
	FoundryExtensionClassUnreference unreference_func;
	/* (Default) constructor; mandatory. If the class is not instantiable, consider making it virtual or abstract. */
	FoundryExtensionClassCreateInstance create_instance_func;
	/* Destructor; mandatory. */
	FoundryExtensionClassFreeInstance free_instance_func;
	FoundryExtensionClassRecreateInstance recreate_instance_func;
	/* Queries a virtual function by name and returns a callback to invoke the requested virtual function. */
	FoundryExtensionClassGetVirtual get_virtual_func;
	/* Paired with `call_virtual_with_data_func`, this is an alternative to `get_virtual_func` for extensions that
	 * need or benefit from extra data when calling virtual functions.
	 * Returns user data that will be passed to `call_virtual_with_data_func`.
	 * Returning `NULL` from this function signals to Foundry that the virtual function is not overridden.
	 * Data returned from this function should be managed by the extension and must be valid until the extension is deinitialized.
	 * You should supply either `get_virtual_func`, or `get_virtual_call_data_func` with `call_virtual_with_data_func`.
	 */
	FoundryExtensionClassGetVirtualCallData get_virtual_call_data_func;
	/* Used to call virtual functions when `get_virtual_call_data_func` is not null. */
	FoundryExtensionClassCallVirtualWithData call_virtual_with_data_func;
	FoundryExtensionClassGetRID get_rid_func;
	/* Per-class user data, later accessible in instance bindings. */
	void *class_userdata;
} FoundryExtensionClassCreationInfo3; /* Deprecated in Foundry 4.4. Use `FoundryExtensionClassCreationInfo4` instead. */

typedef struct {
	FoundryExtensionBool is_virtual;
	FoundryExtensionBool is_abstract;
	FoundryExtensionBool is_exposed;
	FoundryExtensionBool is_runtime;
	FoundryExtensionConstStringPtr icon_path;
	FoundryExtensionClassSet set_func;
	FoundryExtensionClassGet get_func;
	FoundryExtensionClassGetPropertyList get_property_list_func;
	FoundryExtensionClassFreePropertyList2 free_property_list_func;
	FoundryExtensionClassPropertyCanRevert property_can_revert_func;
	FoundryExtensionClassPropertyGetRevert property_get_revert_func;
	FoundryExtensionClassValidateProperty validate_property_func;
	FoundryExtensionClassNotification2 notification_func;
	FoundryExtensionClassToString to_string_func;
	FoundryExtensionClassReference reference_func;
	FoundryExtensionClassUnreference unreference_func;
	/* (Default) constructor; mandatory. If the class is not instantiable, consider making it virtual or abstract. */
	FoundryExtensionClassCreateInstance2 create_instance_func;
	/* Destructor; mandatory. */
	FoundryExtensionClassFreeInstance free_instance_func;
	FoundryExtensionClassRecreateInstance recreate_instance_func;
	/* Queries a virtual function by name and returns a callback to invoke the requested virtual function. */
	FoundryExtensionClassGetVirtual2 get_virtual_func;
	/* Paired with `call_virtual_with_data_func`, this is an alternative to `get_virtual_func` for extensions that
	 * need or benefit from extra data when calling virtual functions.
	 * Returns user data that will be passed to `call_virtual_with_data_func`.
	 * Returning `NULL` from this function signals to Foundry that the virtual function is not overridden.
	 * Data returned from this function should be managed by the extension and must be valid until the extension is deinitialized.
	 * You should supply either `get_virtual_func`, or `get_virtual_call_data_func` with `call_virtual_with_data_func`.
	 */
	FoundryExtensionClassGetVirtualCallData2 get_virtual_call_data_func;
	/* Used to call virtual functions when `get_virtual_call_data_func` is not null. */
	FoundryExtensionClassCallVirtualWithData call_virtual_with_data_func;
	/* Per-class user data, later accessible in instance bindings. */
	void *class_userdata;
} FoundryExtensionClassCreationInfo4;

typedef FoundryExtensionClassCreationInfo4 FoundryExtensionClassCreationInfo5;
typedef void *FoundryExtensionClassLibraryPtr;
/* Passed a pointer to a PackedStringArray that should be filled with the classes that may be used by the FoundryExtension. */
typedef void (*FoundryExtensionEditorGetClassesUsedCallback)(FoundryExtensionTypePtr p_packed_string_array);
typedef enum {
	FOUNDRY_EXTENSION_METHOD_FLAG_NORMAL = 1,
	FOUNDRY_EXTENSION_METHOD_FLAG_EDITOR = 2,
	FOUNDRY_EXTENSION_METHOD_FLAG_CONST = 4,
	FOUNDRY_EXTENSION_METHOD_FLAG_VIRTUAL = 8,
	FOUNDRY_EXTENSION_METHOD_FLAG_VARARG = 16,
	FOUNDRY_EXTENSION_METHOD_FLAG_STATIC = 32,
	FOUNDRY_EXTENSION_METHOD_FLAG_VIRTUAL_REQUIRED = 128,
	FOUNDRY_EXTENSION_METHOD_FLAGS_DEFAULT = 1,
} FoundryExtensionClassMethodFlags;

typedef enum {
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_NONE = 0,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT8 = 1,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT16 = 2,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT32 = 3,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT64 = 4,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_UINT8 = 5,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_UINT16 = 6,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_UINT32 = 7,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_UINT64 = 8,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_REAL_IS_FLOAT = 9,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_REAL_IS_DOUBLE = 10,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_CHAR16 = 11,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_CHAR32 = 12,
	FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_OBJECT_IS_REQUIRED = 13,
} FoundryExtensionClassMethodArgumentMetadata;

typedef void (*FoundryExtensionClassMethodCall)(void *method_userdata, FoundryExtensionClassInstancePtr p_instance, const FoundryExtensionConstVariantPtr *p_args, FoundryExtensionInt p_argument_count, FoundryExtensionVariantPtr r_return, FoundryExtensionCallError *r_error);
typedef void (*FoundryExtensionClassMethodValidatedCall)(void *method_userdata, FoundryExtensionClassInstancePtr p_instance, const FoundryExtensionConstVariantPtr *p_args, FoundryExtensionVariantPtr r_return);
typedef void (*FoundryExtensionClassMethodPtrCall)(void *method_userdata, FoundryExtensionClassInstancePtr p_instance, const FoundryExtensionConstTypePtr *p_args, FoundryExtensionTypePtr r_ret);
typedef struct {
	FoundryExtensionStringNamePtr name;
	void *method_userdata;
	FoundryExtensionClassMethodCall call_func;
	FoundryExtensionClassMethodPtrCall ptrcall_func;
	/* Bitfield of `FoundryExtensionClassMethodFlags`. */
	uint32_t method_flags;
	/* If `has_return_value` is false, `return_value_info` and `return_value_metadata` are ignored.
	 *
	 * @todo Consider dropping `has_return_value` and making the other two properties match `FoundryExtensionMethodInfo` and `FoundryExtensionClassVirtualMethod` for consistency in future version of this struct.
	 */
	FoundryExtensionBool has_return_value;
	FoundryExtensionPropertyInfo *return_value_info;
	FoundryExtensionClassMethodArgumentMetadata return_value_metadata;
	/* Arguments: `arguments_info` and `arguments_metadata` are array of size `argument_count`.
	 * Name and hint information for the argument can be omitted in release builds. Class name should always be present if it applies.
	 *
	 * @todo Consider renaming `arguments_info` to `arguments` for consistency in future version of this struct.
	 */
	uint32_t argument_count;
	FoundryExtensionPropertyInfo *arguments_info;
	FoundryExtensionClassMethodArgumentMetadata *arguments_metadata;
	/* Default arguments: `default_arguments` is an array of size `default_argument_count`. */
	uint32_t default_argument_count;
	FoundryExtensionVariantPtr *default_arguments;
} FoundryExtensionClassMethodInfo;

typedef struct {
	FoundryExtensionStringNamePtr name;
	/* Bitfield of `FoundryExtensionClassMethodFlags`. */
	uint32_t method_flags;
	FoundryExtensionPropertyInfo return_value;
	FoundryExtensionClassMethodArgumentMetadata return_value_metadata;
	uint32_t argument_count;
	FoundryExtensionPropertyInfo *arguments;
	FoundryExtensionClassMethodArgumentMetadata *arguments_metadata;
} FoundryExtensionClassVirtualMethodInfo;

typedef void (*FoundryExtensionCallableCustomCall)(void *callable_userdata, const FoundryExtensionConstVariantPtr *p_args, FoundryExtensionInt p_argument_count, FoundryExtensionVariantPtr r_return, FoundryExtensionCallError *r_error);
typedef FoundryExtensionBool (*FoundryExtensionCallableCustomIsValid)(void *callable_userdata);
typedef void (*FoundryExtensionCallableCustomFree)(void *callable_userdata);
typedef uint32_t (*FoundryExtensionCallableCustomHash)(void *callable_userdata);
typedef FoundryExtensionBool (*FoundryExtensionCallableCustomEqual)(void *callable_userdata_a, void *callable_userdata_b);
typedef FoundryExtensionBool (*FoundryExtensionCallableCustomLessThan)(void *callable_userdata_a, void *callable_userdata_b);
typedef void (*FoundryExtensionCallableCustomToString)(void *callable_userdata, FoundryExtensionBool *r_is_valid, FoundryExtensionStringPtr r_out);
typedef FoundryExtensionInt (*FoundryExtensionCallableCustomGetArgumentCount)(void *callable_userdata, FoundryExtensionBool *r_is_valid);
/* Only `call_func` and `token` are strictly required, however, `object_id` should be passed if its not a static method.
 *
 * `token` should point to an address that uniquely identifies the FoundryExtension (for example, the
 * `FoundryExtensionClassLibraryPtr` passed to the entry symbol function.
 *
 * `hash_func`, `equal_func`, and `less_than_func` are optional. If not provided both `call_func` and
 * `callable_userdata` together are used as the identity of the callable for hashing and comparison purposes.
 *
 * The hash returned by `hash_func` is cached, `hash_func` will not be called more than once per callable.
 *
 * `is_valid_func` is necessary if the validity of the callable can change before destruction.
 *
 * `free_func` is necessary if `callable_userdata` needs to be cleaned up when the callable is freed.
 */
typedef struct {
	void *callable_userdata;
	void *token;
	GDObjectInstanceID object_id;
	FoundryExtensionCallableCustomCall call_func;
	FoundryExtensionCallableCustomIsValid is_valid_func;
	FoundryExtensionCallableCustomFree free_func;
	FoundryExtensionCallableCustomHash hash_func;
	FoundryExtensionCallableCustomEqual equal_func;
	FoundryExtensionCallableCustomLessThan less_than_func;
	FoundryExtensionCallableCustomToString to_string_func;
} FoundryExtensionCallableCustomInfo; /* Deprecated in Foundry 4.3. Use `FoundryExtensionCallableCustomInfo2` instead. */

/* Only `call_func` and `token` are strictly required, however, `object_id` should be passed if its not a static method.
 *
 * `token` should point to an address that uniquely identifies the FoundryExtension (for example, the
 * `FoundryExtensionClassLibraryPtr` passed to the entry symbol function.
 *
 * `hash_func`, `equal_func`, and `less_than_func` are optional. If not provided both `call_func` and
 * `callable_userdata` together are used as the identity of the callable for hashing and comparison purposes.
 *
 * The hash returned by `hash_func` is cached, `hash_func` will not be called more than once per callable.
 *
 * `is_valid_func` is necessary if the validity of the callable can change before destruction.
 *
 * `free_func` is necessary if `callable_userdata` needs to be cleaned up when the callable is freed.
 */
typedef struct {
	void *callable_userdata;
	void *token;
	GDObjectInstanceID object_id;
	FoundryExtensionCallableCustomCall call_func;
	FoundryExtensionCallableCustomIsValid is_valid_func;
	FoundryExtensionCallableCustomFree free_func;
	FoundryExtensionCallableCustomHash hash_func;
	FoundryExtensionCallableCustomEqual equal_func;
	FoundryExtensionCallableCustomLessThan less_than_func;
	FoundryExtensionCallableCustomToString to_string_func;
	FoundryExtensionCallableCustomGetArgumentCount get_argument_count_func;
} FoundryExtensionCallableCustomInfo2;

/* Pointer to custom ScriptInstance native implementation. */
typedef void *FoundryExtensionScriptInstanceDataPtr;
typedef FoundryExtensionBool (*FoundryExtensionScriptInstanceSet)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionConstStringNamePtr p_name, FoundryExtensionConstVariantPtr p_value);
typedef FoundryExtensionBool (*FoundryExtensionScriptInstanceGet)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionConstStringNamePtr p_name, FoundryExtensionVariantPtr r_ret);
typedef const FoundryExtensionPropertyInfo *(*FoundryExtensionScriptInstanceGetPropertyList)(FoundryExtensionScriptInstanceDataPtr p_instance, uint32_t *r_count);
typedef void (*FoundryExtensionScriptInstanceFreePropertyList)(FoundryExtensionScriptInstanceDataPtr p_instance, const FoundryExtensionPropertyInfo *p_list); /* Deprecated in Foundry 4.3. Use `FoundryExtensionScriptInstanceFreePropertyList2` instead. */
typedef void (*FoundryExtensionScriptInstanceFreePropertyList2)(FoundryExtensionScriptInstanceDataPtr p_instance, const FoundryExtensionPropertyInfo *p_list, uint32_t p_count);
typedef FoundryExtensionBool (*FoundryExtensionScriptInstanceGetClassCategory)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionPropertyInfo *p_class_category);
typedef FoundryExtensionVariantType (*FoundryExtensionScriptInstanceGetPropertyType)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionConstStringNamePtr p_name, FoundryExtensionBool *r_is_valid);
typedef FoundryExtensionBool (*FoundryExtensionScriptInstanceValidateProperty)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionPropertyInfo *p_property);
typedef FoundryExtensionBool (*FoundryExtensionScriptInstancePropertyCanRevert)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionConstStringNamePtr p_name);
typedef FoundryExtensionBool (*FoundryExtensionScriptInstancePropertyGetRevert)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionConstStringNamePtr p_name, FoundryExtensionVariantPtr r_ret);
typedef FoundryExtensionObjectPtr (*FoundryExtensionScriptInstanceGetOwner)(FoundryExtensionScriptInstanceDataPtr p_instance);
typedef void (*FoundryExtensionScriptInstancePropertyStateAdd)(FoundryExtensionConstStringNamePtr p_name, FoundryExtensionConstVariantPtr p_value, void *p_userdata);
typedef void (*FoundryExtensionScriptInstanceGetPropertyState)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionScriptInstancePropertyStateAdd p_add_func, void *p_userdata);
typedef const FoundryExtensionMethodInfo *(*FoundryExtensionScriptInstanceGetMethodList)(FoundryExtensionScriptInstanceDataPtr p_instance, uint32_t *r_count);
typedef void (*FoundryExtensionScriptInstanceFreeMethodList)(FoundryExtensionScriptInstanceDataPtr p_instance, const FoundryExtensionMethodInfo *p_list); /* Deprecated in Foundry 4.3. Use `FoundryExtensionScriptInstanceFreeMethodList2` instead. */
typedef void (*FoundryExtensionScriptInstanceFreeMethodList2)(FoundryExtensionScriptInstanceDataPtr p_instance, const FoundryExtensionMethodInfo *p_list, uint32_t p_count);
typedef FoundryExtensionBool (*FoundryExtensionScriptInstanceHasMethod)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionConstStringNamePtr p_name);
typedef FoundryExtensionInt (*FoundryExtensionScriptInstanceGetMethodArgumentCount)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionConstStringNamePtr p_name, FoundryExtensionBool *r_is_valid);
typedef void (*FoundryExtensionScriptInstanceCall)(FoundryExtensionScriptInstanceDataPtr p_self, FoundryExtensionConstStringNamePtr p_method, const FoundryExtensionConstVariantPtr *p_args, FoundryExtensionInt p_argument_count, FoundryExtensionVariantPtr r_return, FoundryExtensionCallError *r_error);
typedef void (*FoundryExtensionScriptInstanceNotification)(FoundryExtensionScriptInstanceDataPtr p_instance, int32_t p_what); /* Deprecated in Foundry 4.2. Use `FoundryExtensionScriptInstanceNotification2` instead. */
typedef void (*FoundryExtensionScriptInstanceNotification2)(FoundryExtensionScriptInstanceDataPtr p_instance, int32_t p_what, FoundryExtensionBool p_reversed);
typedef void (*FoundryExtensionScriptInstanceToString)(FoundryExtensionScriptInstanceDataPtr p_instance, FoundryExtensionBool *r_is_valid, FoundryExtensionStringPtr r_out);
typedef void (*FoundryExtensionScriptInstanceRefCountIncremented)(FoundryExtensionScriptInstanceDataPtr p_instance);
typedef FoundryExtensionBool (*FoundryExtensionScriptInstanceRefCountDecremented)(FoundryExtensionScriptInstanceDataPtr p_instance);
typedef FoundryExtensionObjectPtr (*FoundryExtensionScriptInstanceGetScript)(FoundryExtensionScriptInstanceDataPtr p_instance);
typedef FoundryExtensionBool (*FoundryExtensionScriptInstanceIsPlaceholder)(FoundryExtensionScriptInstanceDataPtr p_instance);
typedef void *FoundryExtensionScriptLanguagePtr;
typedef FoundryExtensionScriptLanguagePtr (*FoundryExtensionScriptInstanceGetLanguage)(FoundryExtensionScriptInstanceDataPtr p_instance);
typedef void (*FoundryExtensionScriptInstanceFree)(FoundryExtensionScriptInstanceDataPtr p_instance);
/* Pointer to ScriptInstance. */
typedef void *FoundryExtensionScriptInstancePtr;
typedef struct {
	FoundryExtensionScriptInstanceSet set_func;
	FoundryExtensionScriptInstanceGet get_func;
	FoundryExtensionScriptInstanceGetPropertyList get_property_list_func;
	FoundryExtensionScriptInstanceFreePropertyList free_property_list_func;
	FoundryExtensionScriptInstancePropertyCanRevert property_can_revert_func;
	FoundryExtensionScriptInstancePropertyGetRevert property_get_revert_func;
	FoundryExtensionScriptInstanceGetOwner get_owner_func;
	FoundryExtensionScriptInstanceGetPropertyState get_property_state_func;
	FoundryExtensionScriptInstanceGetMethodList get_method_list_func;
	FoundryExtensionScriptInstanceFreeMethodList free_method_list_func;
	FoundryExtensionScriptInstanceGetPropertyType get_property_type_func;
	FoundryExtensionScriptInstanceHasMethod has_method_func;
	FoundryExtensionScriptInstanceCall call_func;
	FoundryExtensionScriptInstanceNotification notification_func;
	FoundryExtensionScriptInstanceToString to_string_func;
	FoundryExtensionScriptInstanceRefCountIncremented refcount_incremented_func;
	FoundryExtensionScriptInstanceRefCountDecremented refcount_decremented_func;
	FoundryExtensionScriptInstanceGetScript get_script_func;
	FoundryExtensionScriptInstanceIsPlaceholder is_placeholder_func;
	FoundryExtensionScriptInstanceSet set_fallback_func;
	FoundryExtensionScriptInstanceGet get_fallback_func;
	FoundryExtensionScriptInstanceGetLanguage get_language_func;
	FoundryExtensionScriptInstanceFree free_func;
} FoundryExtensionScriptInstanceInfo; /* Deprecated in Foundry 4.2. Use `FoundryExtensionScriptInstanceInfo3` instead. */

typedef struct {
	FoundryExtensionScriptInstanceSet set_func;
	FoundryExtensionScriptInstanceGet get_func;
	FoundryExtensionScriptInstanceGetPropertyList get_property_list_func;
	FoundryExtensionScriptInstanceFreePropertyList free_property_list_func;
	/* Optional. Set to NULL for the default behavior. */
	FoundryExtensionScriptInstanceGetClassCategory get_class_category_func;
	FoundryExtensionScriptInstancePropertyCanRevert property_can_revert_func;
	FoundryExtensionScriptInstancePropertyGetRevert property_get_revert_func;
	FoundryExtensionScriptInstanceGetOwner get_owner_func;
	FoundryExtensionScriptInstanceGetPropertyState get_property_state_func;
	FoundryExtensionScriptInstanceGetMethodList get_method_list_func;
	FoundryExtensionScriptInstanceFreeMethodList free_method_list_func;
	FoundryExtensionScriptInstanceGetPropertyType get_property_type_func;
	FoundryExtensionScriptInstanceValidateProperty validate_property_func;
	FoundryExtensionScriptInstanceHasMethod has_method_func;
	FoundryExtensionScriptInstanceCall call_func;
	FoundryExtensionScriptInstanceNotification2 notification_func;
	FoundryExtensionScriptInstanceToString to_string_func;
	FoundryExtensionScriptInstanceRefCountIncremented refcount_incremented_func;
	FoundryExtensionScriptInstanceRefCountDecremented refcount_decremented_func;
	FoundryExtensionScriptInstanceGetScript get_script_func;
	FoundryExtensionScriptInstanceIsPlaceholder is_placeholder_func;
	FoundryExtensionScriptInstanceSet set_fallback_func;
	FoundryExtensionScriptInstanceGet get_fallback_func;
	FoundryExtensionScriptInstanceGetLanguage get_language_func;
	FoundryExtensionScriptInstanceFree free_func;
} FoundryExtensionScriptInstanceInfo2; /* Deprecated in Foundry 4.3. Use `FoundryExtensionScriptInstanceInfo3` instead. */

typedef struct {
	FoundryExtensionScriptInstanceSet set_func;
	FoundryExtensionScriptInstanceGet get_func;
	FoundryExtensionScriptInstanceGetPropertyList get_property_list_func;
	FoundryExtensionScriptInstanceFreePropertyList2 free_property_list_func;
	/* Optional. Set to NULL for the default behavior. */
	FoundryExtensionScriptInstanceGetClassCategory get_class_category_func;
	FoundryExtensionScriptInstancePropertyCanRevert property_can_revert_func;
	FoundryExtensionScriptInstancePropertyGetRevert property_get_revert_func;
	FoundryExtensionScriptInstanceGetOwner get_owner_func;
	FoundryExtensionScriptInstanceGetPropertyState get_property_state_func;
	FoundryExtensionScriptInstanceGetMethodList get_method_list_func;
	FoundryExtensionScriptInstanceFreeMethodList2 free_method_list_func;
	FoundryExtensionScriptInstanceGetPropertyType get_property_type_func;
	FoundryExtensionScriptInstanceValidateProperty validate_property_func;
	FoundryExtensionScriptInstanceHasMethod has_method_func;
	FoundryExtensionScriptInstanceGetMethodArgumentCount get_method_argument_count_func;
	FoundryExtensionScriptInstanceCall call_func;
	FoundryExtensionScriptInstanceNotification2 notification_func;
	FoundryExtensionScriptInstanceToString to_string_func;
	FoundryExtensionScriptInstanceRefCountIncremented refcount_incremented_func;
	FoundryExtensionScriptInstanceRefCountDecremented refcount_decremented_func;
	FoundryExtensionScriptInstanceGetScript get_script_func;
	FoundryExtensionScriptInstanceIsPlaceholder is_placeholder_func;
	FoundryExtensionScriptInstanceSet set_fallback_func;
	FoundryExtensionScriptInstanceGet get_fallback_func;
	FoundryExtensionScriptInstanceGetLanguage get_language_func;
	FoundryExtensionScriptInstanceFree free_func;
} FoundryExtensionScriptInstanceInfo3;

typedef void (*FoundryExtensionWorkerThreadPoolGroupTask)(void *, uint32_t);
typedef void (*FoundryExtensionWorkerThreadPoolTask)(void *);
typedef enum {
	FOUNDRY_EXTENSION_INITIALIZATION_CORE = 0,
	FOUNDRY_EXTENSION_INITIALIZATION_SERVERS = 1,
	FOUNDRY_EXTENSION_INITIALIZATION_SCENE = 2,
	FOUNDRY_EXTENSION_INITIALIZATION_EDITOR = 3,
	FOUNDRY_EXTENSION_MAX_INITIALIZATION_LEVEL = 4,
} FoundryExtensionInitializationLevel;

typedef void (*FoundryExtensionInitializeCallback)(void *p_userdata, FoundryExtensionInitializationLevel p_level);
typedef void (*FoundryExtensionDeinitializeCallback)(void *p_userdata, FoundryExtensionInitializationLevel p_level);
typedef struct {
	/* Minimum initialization level required.
	 * If Core or Servers, the extension needs editor or game restart to take effect
	 */
	FoundryExtensionInitializationLevel minimum_initialization_level;
	/* Up to the user to supply when initializing */
	void *userdata;
	/* This function will be called multiple times for each initialization level. */
	FoundryExtensionInitializeCallback initialize;
	FoundryExtensionDeinitializeCallback deinitialize;
} FoundryExtensionInitialization;

typedef void (*FoundryExtensionInterfaceFunctionPtr)();
typedef FoundryExtensionInterfaceFunctionPtr (*FoundryExtensionInterfaceGetProcAddress)(const char *p_function_name);
/* Each FoundryExtension should define a C function that matches the signature of FoundryExtensionInitializationFunction,
 * and export it so that it can be loaded via dlopen() or equivalent for the given platform.
 *
 * For example:
 *
 *   FoundryExtensionBool my_extension_init(FoundryExtensionInterfaceGetProcAddress p_get_proc_address, FoundryExtensionClassLibraryPtr p_library, FoundryExtensionInitialization *r_initialization);
 *
 * This function's name must be specified as the 'entry_symbol' in the .foundryextension file.
 *
 * This makes it the entry point of the FoundryExtension and will be called on initialization.
 *
 * The FoundryExtension can then modify the r_initialization structure, setting the minimum initialization level,
 * and providing pointers to functions that will be called at various stages of initialization/shutdown.
 *
 * The rest of the FoundryExtension's interface to Foundry consists of function pointers that can be loaded
 * by calling p_get_proc_address("...") with the name of the function.
 *
 * For example:
 *
 *   FoundryExtensionInterfaceGetFoundryVersion get_foundry_version = (FoundryExtensionInterfaceGetFoundryVersion)p_get_proc_address("get_foundry_version");
 *
 * (Note that snippet may cause "cast between incompatible function types" on some compilers, you can
 * silence this by adding an intermediary `void*` cast.)
 *
 * You can then call it like a normal function:
 *
 *   FoundryExtensionFoundryVersion foundry_version;
 *   get_foundry_version(&foundry_version);
 *   printf("Foundry v%d.%d.%d\n", foundry_version.major, foundry_version.minor, foundry_version.patch);
 *
 * All of these interface functions are described below, together with the name that's used to load it,
 * and the function pointer typedef that shows its signature.
 */
typedef FoundryExtensionBool (*FoundryExtensionInitializationFunction)(FoundryExtensionInterfaceGetProcAddress p_get_proc_address, FoundryExtensionClassLibraryPtr p_library, FoundryExtensionInitialization *r_initialization);
typedef struct {
	uint32_t major;
	uint32_t minor;
	uint32_t patch;
	const char *string;
} FoundryExtensionFoundryVersion;

typedef struct {
	uint32_t major;
	uint32_t minor;
	uint32_t patch;
	/* Full version encoded as hexadecimal with one byte (2 hex digits) per number (e.g. for "3.1.12" it would be 0x03010C) */
	uint32_t hex;
	/* (e.g. "stable", "beta", "rc1", "rc2") */
	const char *status;
	/* (e.g. "custom_build") */
	const char *build;
	/* Full Git commit hash. */
	const char *hash;
	/* Git commit date UNIX timestamp in seconds, or 0 if unavailable. */
	uint64_t timestamp;
	/* (e.g. "Foundry v3.1.4.stable.official.mono") */
	const char *string;
} FoundryExtensionFoundryVersion2;

/* Called when starting the main loop. */
typedef void (*FoundryExtensionMainLoopStartupCallback)();
/* Called when shutting down the main loop. */
typedef void (*FoundryExtensionMainLoopShutdownCallback)();
/* Called for every frame iteration of the main loop. */
typedef void (*FoundryExtensionMainLoopFrameCallback)();
typedef struct {
	/* Will be called after Foundry is started and is fully initialized. */
	FoundryExtensionMainLoopStartupCallback startup_func;
	/* Will be called before Foundry is shutdown when it is still fully initialized. */
	FoundryExtensionMainLoopShutdownCallback shutdown_func;
	/* Will be called for each process frame. This will run after all `_process()` methods on Node, and before `ScriptServer::frame()`.
	 * This is intended to be the equivalent of `ScriptLanguage::frame()` for FoundryExtension language bindings that don't use the script API.
	 */
	FoundryExtensionMainLoopFrameCallback frame_func;
} FoundryExtensionMainLoopCallbacks;

/**
 * @name get_foundry_version
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.5. Use `get_foundry_version2` instead.
 *
 * Gets the Foundry version that the FoundryExtension was loaded into.
 *
 * @param r_foundry_version A pointer to the structure to write the version information into.
 */
typedef void (*FoundryExtensionInterfaceGetFoundryVersion)(FoundryExtensionFoundryVersion *r_foundry_version);

/**
 * @name get_foundry_version2
 * @since 4.5
 *
 * Gets the Foundry version that the FoundryExtension was loaded into.
 *
 * @param r_foundry_version A pointer to the structure to write the version information into.
 */
typedef void (*FoundryExtensionInterfaceGetFoundryVersion2)(FoundryExtensionFoundryVersion2 *r_foundry_version);

/**
 * @name mem_alloc
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.6. Does not allow explicitly requesting padding. Use `mem_alloc2` instead.
 *
 * Allocates memory.
 *
 * @param p_bytes The amount of memory to allocate in bytes.
 *
 * @return A pointer to the allocated memory, or NULL if unsuccessful.
 */
typedef void *(*FoundryExtensionInterfaceMemAlloc)(size_t p_bytes);

/**
 * @name mem_realloc
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.6. Does not allow explicitly requesting padding. Use `mem_realloc2` instead.
 *
 * Reallocates memory.
 *
 * @param p_ptr A pointer to the previously allocated memory.
 * @param p_bytes The number of bytes to resize the memory block to.
 *
 * @return A pointer to the allocated memory, or NULL if unsuccessful.
 */
typedef void *(*FoundryExtensionInterfaceMemRealloc)(void *p_ptr, size_t p_bytes);

/**
 * @name mem_free
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.6. Does not allow explicitly requesting padding. Use `mem_free2` instead.
 *
 * Frees memory.
 *
 * @param p_ptr A pointer to the previously allocated memory.
 */
typedef void (*FoundryExtensionInterfaceMemFree)(void *p_ptr);

/**
 * @name mem_alloc2
 * @since 4.6
 *
 * Allocates memory.
 *
 * @param p_bytes The amount of memory to allocate in bytes.
 * @param p_pad_align If true, the returned memory will have prepadding of at least 8 bytes.
 *
 * @return A pointer to the allocated memory, or NULL if unsuccessful.
 */
typedef void *(*FoundryExtensionInterfaceMemAlloc2)(size_t p_bytes, FoundryExtensionBool p_pad_align);

/**
 * @name mem_realloc2
 * @since 4.6
 *
 * Reallocates memory.
 *
 * @param p_ptr A pointer to the previously allocated memory.
 * @param p_bytes The number of bytes to resize the memory block to.
 * @param p_pad_align If true, the returned memory will have prepadding of at least 8 bytes.
 *
 * @return A pointer to the allocated memory, or NULL if unsuccessful.
 */
typedef void *(*FoundryExtensionInterfaceMemRealloc2)(void *p_ptr, size_t p_bytes, FoundryExtensionBool p_pad_align);

/**
 * @name mem_free2
 * @since 4.6
 *
 * Frees memory.
 *
 * @param p_ptr A pointer to the previously allocated memory.
 * @param p_pad_align If true, the given memory was allocated with prepadding.
 */
typedef void (*FoundryExtensionInterfaceMemFree2)(void *p_ptr, FoundryExtensionBool p_pad_align);

/**
 * @name print_error
 * @since 4.1
 *
 * Logs an error to Foundry's built-in debugger and to the OS terminal.
 *
 * @param p_description The code triggering the error.
 * @param p_function The function name where the error occurred.
 * @param p_file The file where the error occurred.
 * @param p_line The line where the error occurred.
 * @param p_editor_notify Whether or not to notify the editor.
 */
typedef void (*FoundryExtensionInterfacePrintError)(const char *p_description, const char *p_function, const char *p_file, int32_t p_line, FoundryExtensionBool p_editor_notify);

/**
 * @name print_error_with_message
 * @since 4.1
 *
 * Logs an error with a message to Foundry's built-in debugger and to the OS terminal.
 *
 * @param p_description The code triggering the error.
 * @param p_message The message to show along with the error.
 * @param p_function The function name where the error occurred.
 * @param p_file The file where the error occurred.
 * @param p_line The line where the error occurred.
 * @param p_editor_notify Whether or not to notify the editor.
 */
typedef void (*FoundryExtensionInterfacePrintErrorWithMessage)(const char *p_description, const char *p_message, const char *p_function, const char *p_file, int32_t p_line, FoundryExtensionBool p_editor_notify);

/**
 * @name print_warning
 * @since 4.1
 *
 * Logs a warning to Foundry's built-in debugger and to the OS terminal.
 *
 * @param p_description The code triggering the warning.
 * @param p_function The function name where the warning occurred.
 * @param p_file The file where the warning occurred.
 * @param p_line The line where the warning occurred.
 * @param p_editor_notify Whether or not to notify the editor.
 */
typedef void (*FoundryExtensionInterfacePrintWarning)(const char *p_description, const char *p_function, const char *p_file, int32_t p_line, FoundryExtensionBool p_editor_notify);

/**
 * @name print_warning_with_message
 * @since 4.1
 *
 * Logs a warning with a message to Foundry's built-in debugger and to the OS terminal.
 *
 * @param p_description The code triggering the warning.
 * @param p_message The message to show along with the warning.
 * @param p_function The function name where the warning occurred.
 * @param p_file The file where the warning occurred.
 * @param p_line The line where the warning occurred.
 * @param p_editor_notify Whether or not to notify the editor.
 */
typedef void (*FoundryExtensionInterfacePrintWarningWithMessage)(const char *p_description, const char *p_message, const char *p_function, const char *p_file, int32_t p_line, FoundryExtensionBool p_editor_notify);

/**
 * @name print_script_error
 * @since 4.1
 *
 * Logs a script error to Foundry's built-in debugger and to the OS terminal.
 *
 * @param p_description The code triggering the error.
 * @param p_function The function name where the error occurred.
 * @param p_file The file where the error occurred.
 * @param p_line The line where the error occurred.
 * @param p_editor_notify Whether or not to notify the editor.
 */
typedef void (*FoundryExtensionInterfacePrintScriptError)(const char *p_description, const char *p_function, const char *p_file, int32_t p_line, FoundryExtensionBool p_editor_notify);

/**
 * @name print_script_error_with_message
 * @since 4.1
 *
 * Logs a script error with a message to Foundry's built-in debugger and to the OS terminal.
 *
 * @param p_description The code triggering the error.
 * @param p_message The message to show along with the error.
 * @param p_function The function name where the error occurred.
 * @param p_file The file where the error occurred.
 * @param p_line The line where the error occurred.
 * @param p_editor_notify Whether or not to notify the editor.
 */
typedef void (*FoundryExtensionInterfacePrintScriptErrorWithMessage)(const char *p_description, const char *p_message, const char *p_function, const char *p_file, int32_t p_line, FoundryExtensionBool p_editor_notify);

/**
 * @name get_native_struct_size
 * @since 4.1
 *
 * Gets the size of a native struct (ex. ObjectID) in bytes.
 *
 * @param p_name A pointer to a StringName identifying the struct name.
 *
 * @return The size in bytes.
 */
typedef uint64_t (*FoundryExtensionInterfaceGetNativeStructSize)(FoundryExtensionConstStringNamePtr p_name);

/**
 * @name variant_new_copy
 * @since 4.1
 *
 * Copies one Variant into a another.
 *
 * @param r_dest A pointer to the destination Variant.
 * @param p_src A pointer to the source Variant.
 */
typedef void (*FoundryExtensionInterfaceVariantNewCopy)(FoundryExtensionUninitializedVariantPtr r_dest, FoundryExtensionConstVariantPtr p_src);

/**
 * @name variant_new_nil
 * @since 4.1
 *
 * Creates a new Variant containing nil.
 *
 * @param r_dest A pointer to the destination Variant.
 */
typedef void (*FoundryExtensionInterfaceVariantNewNil)(FoundryExtensionUninitializedVariantPtr r_dest);

/**
 * @name variant_destroy
 * @since 4.1
 *
 * Destroys a Variant.
 *
 * @param p_self A pointer to the Variant to destroy.
 */
typedef void (*FoundryExtensionInterfaceVariantDestroy)(FoundryExtensionVariantPtr p_self);

/**
 * @name variant_call
 * @since 4.1
 *
 * Calls a method on a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param p_method A pointer to a StringName identifying the method.
 * @param p_args A pointer to a C array of Variant.
 * @param p_argument_count The number of arguments.
 * @param r_return A pointer a Variant which will be assigned the return value.
 * @param r_error A pointer the structure which will hold error information.
 *
 * @see Variant::callp()
 */
typedef void (*FoundryExtensionInterfaceVariantCall)(FoundryExtensionVariantPtr p_self, FoundryExtensionConstStringNamePtr p_method, const FoundryExtensionConstVariantPtr *p_args, FoundryExtensionInt p_argument_count, FoundryExtensionUninitializedVariantPtr r_return, FoundryExtensionCallError *r_error);

/**
 * @name variant_call_static
 * @since 4.1
 *
 * Calls a static method on a Variant.
 *
 * @param p_type The variant type.
 * @param p_method A pointer to a StringName identifying the method.
 * @param p_args A pointer to a C array of Variant.
 * @param p_argument_count The number of arguments.
 * @param r_return A pointer a Variant which will be assigned the return value.
 * @param r_error A pointer the structure which will be updated with error information.
 *
 * @see Variant::call_static()
 */
typedef void (*FoundryExtensionInterfaceVariantCallStatic)(FoundryExtensionVariantType p_type, FoundryExtensionConstStringNamePtr p_method, const FoundryExtensionConstVariantPtr *p_args, FoundryExtensionInt p_argument_count, FoundryExtensionUninitializedVariantPtr r_return, FoundryExtensionCallError *r_error);

/**
 * @name variant_evaluate
 * @since 4.1
 *
 * Evaluate an operator on two Variants.
 *
 * @param p_op The operator to evaluate.
 * @param p_a The first Variant.
 * @param p_b The second Variant.
 * @param r_return A pointer a Variant which will be assigned the return value.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 *
 * @see Variant::evaluate()
 */
typedef void (*FoundryExtensionInterfaceVariantEvaluate)(FoundryExtensionVariantOperator p_op, FoundryExtensionConstVariantPtr p_a, FoundryExtensionConstVariantPtr p_b, FoundryExtensionUninitializedVariantPtr r_return, FoundryExtensionBool *r_valid);

/**
 * @name variant_set
 * @since 4.1
 *
 * Sets a key on a Variant to a value.
 *
 * @param p_self A pointer to the Variant.
 * @param p_key A pointer to a Variant representing the key.
 * @param p_value A pointer to a Variant representing the value.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 *
 * @see Variant::set()
 */
typedef void (*FoundryExtensionInterfaceVariantSet)(FoundryExtensionVariantPtr p_self, FoundryExtensionConstVariantPtr p_key, FoundryExtensionConstVariantPtr p_value, FoundryExtensionBool *r_valid);

/**
 * @name variant_set_named
 * @since 4.1
 *
 * Sets a named key on a Variant to a value.
 *
 * @param p_self A pointer to the Variant.
 * @param p_key A pointer to a StringName representing the key.
 * @param p_value A pointer to a Variant representing the value.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 *
 * @see Variant::set_named()
 */
typedef void (*FoundryExtensionInterfaceVariantSetNamed)(FoundryExtensionVariantPtr p_self, FoundryExtensionConstStringNamePtr p_key, FoundryExtensionConstVariantPtr p_value, FoundryExtensionBool *r_valid);

/**
 * @name variant_set_keyed
 * @since 4.1
 *
 * Sets a keyed property on a Variant to a value.
 *
 * @param p_self A pointer to the Variant.
 * @param p_key A pointer to a Variant representing the key.
 * @param p_value A pointer to a Variant representing the value.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 *
 * @see Variant::set_keyed()
 */
typedef void (*FoundryExtensionInterfaceVariantSetKeyed)(FoundryExtensionVariantPtr p_self, FoundryExtensionConstVariantPtr p_key, FoundryExtensionConstVariantPtr p_value, FoundryExtensionBool *r_valid);

/**
 * @name variant_set_indexed
 * @since 4.1
 *
 * Sets an index on a Variant to a value.
 *
 * @param p_self A pointer to the Variant.
 * @param p_index The index.
 * @param p_value A pointer to a Variant representing the value.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 * @param r_oob A pointer to a boolean which will be set to true if the index is out of bounds.
 */
typedef void (*FoundryExtensionInterfaceVariantSetIndexed)(FoundryExtensionVariantPtr p_self, FoundryExtensionInt p_index, FoundryExtensionConstVariantPtr p_value, FoundryExtensionBool *r_valid, FoundryExtensionBool *r_oob);

/**
 * @name variant_get
 * @since 4.1
 *
 * Gets the value of a key from a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param p_key A pointer to a Variant representing the key.
 * @param r_ret A pointer to a Variant which will be assigned the value.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 */
typedef void (*FoundryExtensionInterfaceVariantGet)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionConstVariantPtr p_key, FoundryExtensionUninitializedVariantPtr r_ret, FoundryExtensionBool *r_valid);

/**
 * @name variant_get_named
 * @since 4.1
 *
 * Gets the value of a named key from a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param p_key A pointer to a StringName representing the key.
 * @param r_ret A pointer to a Variant which will be assigned the value.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 */
typedef void (*FoundryExtensionInterfaceVariantGetNamed)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionConstStringNamePtr p_key, FoundryExtensionUninitializedVariantPtr r_ret, FoundryExtensionBool *r_valid);

/**
 * @name variant_get_keyed
 * @since 4.1
 *
 * Gets the value of a keyed property from a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param p_key A pointer to a Variant representing the key.
 * @param r_ret A pointer to a Variant which will be assigned the value.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 */
typedef void (*FoundryExtensionInterfaceVariantGetKeyed)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionConstVariantPtr p_key, FoundryExtensionUninitializedVariantPtr r_ret, FoundryExtensionBool *r_valid);

/**
 * @name variant_get_indexed
 * @since 4.1
 *
 * Gets the value of an index from a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param p_index The index.
 * @param r_ret A pointer to a Variant which will be assigned the value.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 * @param r_oob A pointer to a boolean which will be set to true if the index is out of bounds.
 */
typedef void (*FoundryExtensionInterfaceVariantGetIndexed)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionInt p_index, FoundryExtensionUninitializedVariantPtr r_ret, FoundryExtensionBool *r_valid, FoundryExtensionBool *r_oob);

/**
 * @name variant_iter_init
 * @since 4.1
 *
 * Initializes an iterator over a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param r_iter A pointer to a Variant which will be assigned the iterator.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 *
 * @return true if the operation is valid; otherwise false.
 *
 * @see Variant::iter_init()
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceVariantIterInit)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionUninitializedVariantPtr r_iter, FoundryExtensionBool *r_valid);

/**
 * @name variant_iter_next
 * @since 4.1
 *
 * Gets the next value for an iterator over a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param r_iter A pointer to a Variant which will be assigned the iterator.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 *
 * @return true if the operation is valid; otherwise false.
 *
 * @see Variant::iter_next()
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceVariantIterNext)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionVariantPtr r_iter, FoundryExtensionBool *r_valid);

/**
 * @name variant_iter_get
 * @since 4.1
 *
 * Gets the next value for an iterator over a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param r_iter A pointer to a Variant which will be assigned the iterator.
 * @param r_ret A pointer to a Variant which will be assigned false if the operation is invalid.
 * @param r_valid A pointer to a boolean which will be set to false if the operation is invalid.
 *
 * @see Variant::iter_get()
 */
typedef void (*FoundryExtensionInterfaceVariantIterGet)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionVariantPtr r_iter, FoundryExtensionUninitializedVariantPtr r_ret, FoundryExtensionBool *r_valid);

/**
 * @name variant_hash
 * @since 4.1
 *
 * Gets the hash of a Variant.
 *
 * @param p_self A pointer to the Variant.
 *
 * @return The hash value.
 *
 * @see Variant::hash()
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceVariantHash)(FoundryExtensionConstVariantPtr p_self);

/**
 * @name variant_recursive_hash
 * @since 4.1
 *
 * Gets the recursive hash of a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param p_recursion_count The number of recursive loops so far.
 *
 * @return The hash value.
 *
 * @see Variant::recursive_hash()
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceVariantRecursiveHash)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionInt p_recursion_count);

/**
 * @name variant_hash_compare
 * @since 4.1
 *
 * Compares two Variants by their hash.
 *
 * @param p_self A pointer to the Variant.
 * @param p_other A pointer to the other Variant to compare it to.
 *
 * @return The hash value.
 *
 * @see Variant::hash_compare()
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceVariantHashCompare)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionConstVariantPtr p_other);

/**
 * @name variant_booleanize
 * @since 4.1
 *
 * Converts a Variant to a boolean.
 *
 * @param p_self A pointer to the Variant.
 *
 * @return The boolean value of the Variant.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceVariantBooleanize)(FoundryExtensionConstVariantPtr p_self);

/**
 * @name variant_duplicate
 * @since 4.1
 *
 * Duplicates a Variant.
 *
 * @param p_self A pointer to the Variant.
 * @param r_ret A pointer to a Variant to store the duplicated value.
 * @param p_deep Whether or not to duplicate deeply (when supported by the Variant type).
 */
typedef void (*FoundryExtensionInterfaceVariantDuplicate)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionVariantPtr r_ret, FoundryExtensionBool p_deep);

/**
 * @name variant_stringify
 * @since 4.1
 *
 * Converts a Variant to a string.
 *
 * @param p_self A pointer to the Variant.
 * @param r_ret A pointer to a String to store the resulting value.
 */
typedef void (*FoundryExtensionInterfaceVariantStringify)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionStringPtr r_ret);

/**
 * @name variant_get_type
 * @since 4.1
 *
 * Gets the type of a Variant.
 *
 * @param p_self A pointer to the Variant.
 *
 * @return The variant type.
 */
typedef FoundryExtensionVariantType (*FoundryExtensionInterfaceVariantGetType)(FoundryExtensionConstVariantPtr p_self);

/**
 * @name variant_has_method
 * @since 4.1
 *
 * Checks if a Variant has the given method.
 *
 * @param p_self A pointer to the Variant.
 * @param p_method A pointer to a StringName with the method name.
 *
 * @return true if the variant has the given method; otherwise false.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceVariantHasMethod)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionConstStringNamePtr p_method);

/**
 * @name variant_has_member
 * @since 4.1
 *
 * Checks if a type of Variant has the given member.
 *
 * @param p_type The Variant type.
 * @param p_member A pointer to a StringName with the member name.
 *
 * @return true if the variant has the given method; otherwise false.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceVariantHasMember)(FoundryExtensionVariantType p_type, FoundryExtensionConstStringNamePtr p_member);

/**
 * @name variant_has_key
 * @since 4.1
 *
 * Checks if a Variant has a key.
 *
 * @param p_self A pointer to the Variant.
 * @param p_key A pointer to a Variant representing the key.
 * @param r_valid A pointer to a boolean which will be set to false if the key doesn't exist.
 *
 * @return true if the key exists; otherwise false.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceVariantHasKey)(FoundryExtensionConstVariantPtr p_self, FoundryExtensionConstVariantPtr p_key, FoundryExtensionBool *r_valid);

/**
 * @name variant_get_object_instance_id
 * @since 4.4
 *
 * Gets the object instance ID from a variant of type FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT.
 *
 * If the variant isn't of type FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT, then zero will be returned.
 * The instance ID will be returned even if the object is no longer valid - use `object_get_instance_by_id()` to check if the object is still valid.
 *
 * @param p_self A pointer to the Variant.
 *
 * @return The instance ID for the contained object.
 */
typedef GDObjectInstanceID (*FoundryExtensionInterfaceVariantGetObjectInstanceId)(FoundryExtensionConstVariantPtr p_self);

/**
 * @name variant_get_type_name
 * @since 4.1
 *
 * Gets the name of a Variant type.
 *
 * @param p_type The Variant type.
 * @param r_name A pointer to a String to store the Variant type name.
 */
typedef void (*FoundryExtensionInterfaceVariantGetTypeName)(FoundryExtensionVariantType p_type, FoundryExtensionUninitializedStringPtr r_name);

/**
 * @name variant_can_convert
 * @since 4.1
 *
 * Checks if Variants can be converted from one type to another.
 *
 * @param p_from The Variant type to convert from.
 * @param p_to The Variant type to convert to.
 *
 * @return true if the conversion is possible; otherwise false.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceVariantCanConvert)(FoundryExtensionVariantType p_from, FoundryExtensionVariantType p_to);

/**
 * @name variant_can_convert_strict
 * @since 4.1
 *
 * Checks if Variant can be converted from one type to another using stricter rules.
 *
 * @param p_from The Variant type to convert from.
 * @param p_to The Variant type to convert to.
 *
 * @return true if the conversion is possible; otherwise false.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceVariantCanConvertStrict)(FoundryExtensionVariantType p_from, FoundryExtensionVariantType p_to);

/**
 * @name get_variant_from_type_constructor
 * @since 4.1
 *
 * Gets a pointer to a function that can create a Variant of the given type from a raw value.
 *
 * @param p_type The Variant type.
 *
 * @return A pointer to a function that can create a Variant of the given type from a raw value.
 */
typedef FoundryExtensionVariantFromTypeConstructorFunc (*FoundryExtensionInterfaceGetVariantFromTypeConstructor)(FoundryExtensionVariantType p_type);

/**
 * @name get_variant_to_type_constructor
 * @since 4.1
 *
 * Gets a pointer to a function that can get the raw value from a Variant of the given type.
 *
 * @param p_type The Variant type.
 *
 * @return A pointer to a function that can get the raw value from a Variant of the given type.
 */
typedef FoundryExtensionTypeFromVariantConstructorFunc (*FoundryExtensionInterfaceGetVariantToTypeConstructor)(FoundryExtensionVariantType p_type);

/**
 * @name variant_get_ptr_internal_getter
 * @since 4.4
 *
 * Provides a function pointer for retrieving a pointer to a variant's internal value.
 *
 * Access to a variant's internal value can be used to modify it in-place, or to retrieve its value without the overhead of variant conversion functions.
 * It is recommended to cache the getter for all variant types in a function table to avoid retrieval overhead upon use.
 *
 * Each function assumes the variant's type has already been determined and matches the function.
 * Invoking the function with a variant of a mismatched type has undefined behavior, and may lead to a segmentation fault.
 *
 * @param p_type The Variant type.
 *
 * @return A pointer to a type-specific function that returns a pointer to the internal value of a variant. Check the implementation of this function (foundry_extension_variant_get_ptr_internal_getter) for pointee type info of each variant type.
 */
typedef FoundryExtensionVariantGetInternalPtrFunc (*FoundryExtensionInterfaceGetVariantGetInternalPtrFunc)(FoundryExtensionVariantType p_type);

/**
 * @name variant_get_ptr_operator_evaluator
 * @since 4.1
 *
 * Gets a pointer to a function that can evaluate the given Variant operator on the given Variant types.
 *
 * @param p_operator The variant operator.
 * @param p_type_a The type of the first Variant.
 * @param p_type_b The type of the second Variant.
 *
 * @return A pointer to a function that can evaluate the given Variant operator on the given Variant types.
 */
typedef FoundryExtensionPtrOperatorEvaluator (*FoundryExtensionInterfaceVariantGetPtrOperatorEvaluator)(FoundryExtensionVariantOperator p_operator, FoundryExtensionVariantType p_type_a, FoundryExtensionVariantType p_type_b);

/**
 * @name variant_get_ptr_builtin_method
 * @since 4.1
 *
 * Gets a pointer to a function that can call a builtin method on a type of Variant.
 *
 * @param p_type The Variant type.
 * @param p_method A pointer to a StringName with the method name.
 * @param p_hash A hash representing the method signature.
 *
 * @return A pointer to a function that can call a builtin method on a type of Variant.
 */
typedef FoundryExtensionPtrBuiltInMethod (*FoundryExtensionInterfaceVariantGetPtrBuiltinMethod)(FoundryExtensionVariantType p_type, FoundryExtensionConstStringNamePtr p_method, FoundryExtensionInt p_hash);

/**
 * @name variant_get_ptr_constructor
 * @since 4.1
 *
 * Gets a pointer to a function that can call one of the constructors for a type of Variant.
 *
 * @param p_type The Variant type.
 * @param p_constructor The index of the constructor.
 *
 * @return A pointer to a function that can call one of the constructors for a type of Variant.
 */
typedef FoundryExtensionPtrConstructor (*FoundryExtensionInterfaceVariantGetPtrConstructor)(FoundryExtensionVariantType p_type, int32_t p_constructor);

/**
 * @name variant_get_ptr_destructor
 * @since 4.1
 *
 * Gets a pointer to a function than can call the destructor for a type of Variant.
 *
 * @param p_type The Variant type.
 *
 * @return A pointer to a function than can call the destructor for a type of Variant.
 */
typedef FoundryExtensionPtrDestructor (*FoundryExtensionInterfaceVariantGetPtrDestructor)(FoundryExtensionVariantType p_type);

/**
 * @name variant_construct
 * @since 4.1
 *
 * Constructs a Variant of the given type, using the first constructor that matches the given arguments.
 *
 * @param p_type The Variant type.
 * @param r_base A pointer to a Variant to store the constructed value.
 * @param p_args A pointer to a C array of Variant pointers representing the arguments for the constructor.
 * @param p_argument_count The number of arguments to pass to the constructor.
 * @param r_error A pointer the structure which will be updated with error information.
 */
typedef void (*FoundryExtensionInterfaceVariantConstruct)(FoundryExtensionVariantType p_type, FoundryExtensionUninitializedVariantPtr r_base, const FoundryExtensionConstVariantPtr *p_args, int32_t p_argument_count, FoundryExtensionCallError *r_error);

/**
 * @name variant_get_ptr_setter
 * @since 4.1
 *
 * Gets a pointer to a function that can call a member's setter on the given Variant type.
 *
 * @param p_type The Variant type.
 * @param p_member A pointer to a StringName with the member name.
 *
 * @return A pointer to a function that can call a member's setter on the given Variant type.
 */
typedef FoundryExtensionPtrSetter (*FoundryExtensionInterfaceVariantGetPtrSetter)(FoundryExtensionVariantType p_type, FoundryExtensionConstStringNamePtr p_member);

/**
 * @name variant_get_ptr_getter
 * @since 4.1
 *
 * Gets a pointer to a function that can call a member's getter on the given Variant type.
 *
 * @param p_type The Variant type.
 * @param p_member A pointer to a StringName with the member name.
 *
 * @return A pointer to a function that can call a member's getter on the given Variant type.
 */
typedef FoundryExtensionPtrGetter (*FoundryExtensionInterfaceVariantGetPtrGetter)(FoundryExtensionVariantType p_type, FoundryExtensionConstStringNamePtr p_member);

/**
 * @name variant_get_ptr_indexed_setter
 * @since 4.1
 *
 * Gets a pointer to a function that can set an index on the given Variant type.
 *
 * @param p_type The Variant type.
 *
 * @return A pointer to a function that can set an index on the given Variant type.
 */
typedef FoundryExtensionPtrIndexedSetter (*FoundryExtensionInterfaceVariantGetPtrIndexedSetter)(FoundryExtensionVariantType p_type);

/**
 * @name variant_get_ptr_indexed_getter
 * @since 4.1
 *
 * Gets a pointer to a function that can get an index on the given Variant type.
 *
 * @param p_type The Variant type.
 *
 * @return A pointer to a function that can get an index on the given Variant type.
 */
typedef FoundryExtensionPtrIndexedGetter (*FoundryExtensionInterfaceVariantGetPtrIndexedGetter)(FoundryExtensionVariantType p_type);

/**
 * @name variant_get_ptr_keyed_setter
 * @since 4.1
 *
 * Gets a pointer to a function that can set a key on the given Variant type.
 *
 * @param p_type The Variant type.
 *
 * @return A pointer to a function that can set a key on the given Variant type.
 */
typedef FoundryExtensionPtrKeyedSetter (*FoundryExtensionInterfaceVariantGetPtrKeyedSetter)(FoundryExtensionVariantType p_type);

/**
 * @name variant_get_ptr_keyed_getter
 * @since 4.1
 *
 * Gets a pointer to a function that can get a key on the given Variant type.
 *
 * @param p_type The Variant type.
 *
 * @return A pointer to a function that can get a key on the given Variant type.
 */
typedef FoundryExtensionPtrKeyedGetter (*FoundryExtensionInterfaceVariantGetPtrKeyedGetter)(FoundryExtensionVariantType p_type);

/**
 * @name variant_get_ptr_keyed_checker
 * @since 4.1
 *
 * Gets a pointer to a function that can check a key on the given Variant type.
 *
 * @param p_type The Variant type.
 *
 * @return A pointer to a function that can check a key on the given Variant type.
 */
typedef FoundryExtensionPtrKeyedChecker (*FoundryExtensionInterfaceVariantGetPtrKeyedChecker)(FoundryExtensionVariantType p_type);

/**
 * @name variant_get_constant_value
 * @since 4.1
 *
 * Gets the value of a constant from the given Variant type.
 *
 * @param p_type The Variant type.
 * @param p_constant A pointer to a StringName with the constant name.
 * @param r_ret A pointer to a Variant to store the value.
 */
typedef void (*FoundryExtensionInterfaceVariantGetConstantValue)(FoundryExtensionVariantType p_type, FoundryExtensionConstStringNamePtr p_constant, FoundryExtensionUninitializedVariantPtr r_ret);

/**
 * @name variant_get_ptr_utility_function
 * @since 4.1
 *
 * Gets a pointer to a function that can call a Variant utility function.
 *
 * @param p_function A pointer to a StringName with the function name.
 * @param p_hash A hash representing the function signature.
 *
 * @return A pointer to a function that can call a Variant utility function.
 */
typedef FoundryExtensionPtrUtilityFunction (*FoundryExtensionInterfaceVariantGetPtrUtilityFunction)(FoundryExtensionConstStringNamePtr p_function, FoundryExtensionInt p_hash);

/**
 * @name string_new_with_latin1_chars
 * @since 4.1
 *
 * Creates a String from a Latin-1 encoded C string.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a Latin-1 encoded C string (null terminated).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithLatin1Chars)(FoundryExtensionUninitializedStringPtr r_dest, const char *p_contents);

/**
 * @name string_new_with_utf8_chars
 * @since 4.1
 *
 * Creates a String from a UTF-8 encoded C string.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a UTF-8 encoded C string (null terminated).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithUtf8Chars)(FoundryExtensionUninitializedStringPtr r_dest, const char *p_contents);

/**
 * @name string_new_with_utf16_chars
 * @since 4.1
 *
 * Creates a String from a UTF-16 encoded C string.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a UTF-16 encoded C string (null terminated).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithUtf16Chars)(FoundryExtensionUninitializedStringPtr r_dest, const char16_t *p_contents);

/**
 * @name string_new_with_utf32_chars
 * @since 4.1
 *
 * Creates a String from a UTF-32 encoded C string.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a UTF-32 encoded C string (null terminated).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithUtf32Chars)(FoundryExtensionUninitializedStringPtr r_dest, const char32_t *p_contents);

/**
 * @name string_new_with_wide_chars
 * @since 4.1
 *
 * Creates a String from a wide C string.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a wide C string (null terminated).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithWideChars)(FoundryExtensionUninitializedStringPtr r_dest, const wchar_t *p_contents);

/**
 * @name string_new_with_latin1_chars_and_len
 * @since 4.1
 *
 * Creates a String from a Latin-1 encoded C string with the given length.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a Latin-1 encoded C string.
 * @param p_size The number of characters (= number of bytes).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithLatin1CharsAndLen)(FoundryExtensionUninitializedStringPtr r_dest, const char *p_contents, FoundryExtensionInt p_size);

/**
 * @name string_new_with_utf8_chars_and_len
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.3. Use `string_new_with_utf8_chars_and_len2` instead.
 *
 * Creates a String from a UTF-8 encoded C string with the given length.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a UTF-8 encoded C string.
 * @param p_size The number of bytes (not code units).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithUtf8CharsAndLen)(FoundryExtensionUninitializedStringPtr r_dest, const char *p_contents, FoundryExtensionInt p_size);

/**
 * @name string_new_with_utf8_chars_and_len2
 * @since 4.3
 *
 * Creates a String from a UTF-8 encoded C string with the given length.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a UTF-8 encoded C string.
 * @param p_size The number of bytes (not code units).
 *
 * @return Error code signifying if the operation successful.
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceStringNewWithUtf8CharsAndLen2)(FoundryExtensionUninitializedStringPtr r_dest, const char *p_contents, FoundryExtensionInt p_size);

/**
 * @name string_new_with_utf16_chars_and_len
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.3. Use `string_new_with_utf16_chars_and_len2` instead.
 *
 * Creates a String from a UTF-16 encoded C string with the given length.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a UTF-16 encoded C string.
 * @param p_char_count The number of characters (not bytes).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithUtf16CharsAndLen)(FoundryExtensionUninitializedStringPtr r_dest, const char16_t *p_contents, FoundryExtensionInt p_char_count);

/**
 * @name string_new_with_utf16_chars_and_len2
 * @since 4.3
 *
 * Creates a String from a UTF-16 encoded C string with the given length.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a UTF-16 encoded C string.
 * @param p_char_count The number of characters (not bytes).
 * @param p_default_little_endian If true, UTF-16 use little endian.
 *
 * @return Error code signifying if the operation successful.
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceStringNewWithUtf16CharsAndLen2)(FoundryExtensionUninitializedStringPtr r_dest, const char16_t *p_contents, FoundryExtensionInt p_char_count, FoundryExtensionBool p_default_little_endian);

/**
 * @name string_new_with_utf32_chars_and_len
 * @since 4.1
 *
 * Creates a String from a UTF-32 encoded C string with the given length.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a UTF-32 encoded C string.
 * @param p_char_count The number of characters (not bytes).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithUtf32CharsAndLen)(FoundryExtensionUninitializedStringPtr r_dest, const char32_t *p_contents, FoundryExtensionInt p_char_count);

/**
 * @name string_new_with_wide_chars_and_len
 * @since 4.1
 *
 * Creates a String from a wide C string with the given length.
 *
 * @param r_dest A pointer to a Variant to hold the newly created String.
 * @param p_contents A pointer to a wide C string.
 * @param p_char_count The number of characters (not bytes).
 */
typedef void (*FoundryExtensionInterfaceStringNewWithWideCharsAndLen)(FoundryExtensionUninitializedStringPtr r_dest, const wchar_t *p_contents, FoundryExtensionInt p_char_count);

/**
 * @name string_to_latin1_chars
 * @since 4.1
 *
 * Converts a String to a Latin-1 encoded C string.
 *
 * It doesn't write a null terminator.
 *
 * @param p_self A pointer to the String.
 * @param r_text A pointer to the buffer to hold the resulting data. If NULL is passed in, only the length will be computed.
 * @param p_max_write_length The maximum number of characters that can be written to r_text. It has no affect on the return value.
 *
 * @return The resulting encoded string length in characters (not bytes), not including a null terminator.
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceStringToLatin1Chars)(FoundryExtensionConstStringPtr p_self, char *r_text, FoundryExtensionInt p_max_write_length);

/**
 * @name string_to_utf8_chars
 * @since 4.1
 *
 * Converts a String to a UTF-8 encoded C string.
 *
 * It doesn't write a null terminator.
 *
 * @param p_self A pointer to the String.
 * @param r_text A pointer to the buffer to hold the resulting data. If NULL is passed in, only the length will be computed.
 * @param p_max_write_length The maximum number of characters that can be written to r_text. It has no affect on the return value.
 *
 * @return The resulting encoded string length in characters (not bytes), not including a null terminator.
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceStringToUtf8Chars)(FoundryExtensionConstStringPtr p_self, char *r_text, FoundryExtensionInt p_max_write_length);

/**
 * @name string_to_utf16_chars
 * @since 4.1
 *
 * Converts a String to a UTF-16 encoded C string.
 *
 * It doesn't write a null terminator.
 *
 * @param p_self A pointer to the String.
 * @param r_text A pointer to the buffer to hold the resulting data. If NULL is passed in, only the length will be computed.
 * @param p_max_write_length The maximum number of characters that can be written to r_text. It has no affect on the return value.
 *
 * @return The resulting encoded string length in characters (not bytes), not including a null terminator.
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceStringToUtf16Chars)(FoundryExtensionConstStringPtr p_self, char16_t *r_text, FoundryExtensionInt p_max_write_length);

/**
 * @name string_to_utf32_chars
 * @since 4.1
 *
 * Converts a String to a UTF-32 encoded C string.
 *
 * It doesn't write a null terminator.
 *
 * @param p_self A pointer to the String.
 * @param r_text A pointer to the buffer to hold the resulting data. If NULL is passed in, only the length will be computed.
 * @param p_max_write_length The maximum number of characters that can be written to r_text. It has no affect on the return value.
 *
 * @return The resulting encoded string length in characters (not bytes), not including a null terminator.
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceStringToUtf32Chars)(FoundryExtensionConstStringPtr p_self, char32_t *r_text, FoundryExtensionInt p_max_write_length);

/**
 * @name string_to_wide_chars
 * @since 4.1
 *
 * Converts a String to a wide C string.
 *
 * It doesn't write a null terminator.
 *
 * @param p_self A pointer to the String.
 * @param r_text A pointer to the buffer to hold the resulting data. If NULL is passed in, only the length will be computed.
 * @param p_max_write_length The maximum number of characters that can be written to r_text. It has no affect on the return value.
 *
 * @return The resulting encoded string length in characters (not bytes), not including a null terminator.
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceStringToWideChars)(FoundryExtensionConstStringPtr p_self, wchar_t *r_text, FoundryExtensionInt p_max_write_length);

/**
 * @name string_operator_index
 * @since 4.1
 *
 * Gets a pointer to the character at the given index from a String.
 *
 * @param p_self A pointer to the String.
 * @param p_index The index.
 *
 * @return A pointer to the requested character.
 */
typedef char32_t *(*FoundryExtensionInterfaceStringOperatorIndex)(FoundryExtensionStringPtr p_self, FoundryExtensionInt p_index);

/**
 * @name string_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to the character at the given index from a String.
 *
 * @param p_self A pointer to the String.
 * @param p_index The index.
 *
 * @return A const pointer to the requested character.
 */
typedef const char32_t *(*FoundryExtensionInterfaceStringOperatorIndexConst)(FoundryExtensionConstStringPtr p_self, FoundryExtensionInt p_index);

/**
 * @name string_operator_plus_eq_string
 * @since 4.1
 *
 * Appends another String to a String.
 *
 * @param p_self A pointer to the String.
 * @param p_b A pointer to the other String to append.
 */
typedef void (*FoundryExtensionInterfaceStringOperatorPlusEqString)(FoundryExtensionStringPtr p_self, FoundryExtensionConstStringPtr p_b);

/**
 * @name string_operator_plus_eq_char
 * @since 4.1
 *
 * Appends a character to a String.
 *
 * @param p_self A pointer to the String.
 * @param p_b A pointer to the character to append.
 */
typedef void (*FoundryExtensionInterfaceStringOperatorPlusEqChar)(FoundryExtensionStringPtr p_self, char32_t p_b);

/**
 * @name string_operator_plus_eq_cstr
 * @since 4.1
 *
 * Appends a Latin-1 encoded C string to a String.
 *
 * @param p_self A pointer to the String.
 * @param p_b A pointer to a Latin-1 encoded C string (null terminated).
 */
typedef void (*FoundryExtensionInterfaceStringOperatorPlusEqCstr)(FoundryExtensionStringPtr p_self, const char *p_b);

/**
 * @name string_operator_plus_eq_wcstr
 * @since 4.1
 *
 * Appends a wide C string to a String.
 *
 * @param p_self A pointer to the String.
 * @param p_b A pointer to a wide C string (null terminated).
 */
typedef void (*FoundryExtensionInterfaceStringOperatorPlusEqWcstr)(FoundryExtensionStringPtr p_self, const wchar_t *p_b);

/**
 * @name string_operator_plus_eq_c32str
 * @since 4.1
 *
 * Appends a UTF-32 encoded C string to a String.
 *
 * @param p_self A pointer to the String.
 * @param p_b A pointer to a UTF-32 encoded C string (null terminated).
 */
typedef void (*FoundryExtensionInterfaceStringOperatorPlusEqC32str)(FoundryExtensionStringPtr p_self, const char32_t *p_b);

/**
 * @name string_resize
 * @since 4.2
 *
 * Resizes the underlying string data to the given number of characters.
 *
 * Space needs to be allocated for the null terminating character ('\0') which
 * also must be added manually, in order for all string functions to work correctly.
 *
 * Warning: This is an error-prone operation - only use it if there's no other
 * efficient way to accomplish your goal.
 *
 * @param p_self A pointer to the String.
 * @param p_resize The new length for the String.
 *
 * @return Error code signifying if the operation successful.
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceStringResize)(FoundryExtensionStringPtr p_self, FoundryExtensionInt p_resize);

/**
 * @name string_name_new_with_latin1_chars
 * @since 4.2
 *
 * Creates a StringName from a Latin-1 encoded C string.
 *
 * If `p_is_static` is true, then:
 * - The StringName will reuse the `p_contents` buffer instead of copying it.
 * - You must guarantee that the buffer remains valid for the duration of the application (e.g. string literal).
 * - You must not call a destructor for this StringName. Incrementing the initial reference once should achieve this.
 *
 * `p_is_static` is purely an optimization and can easily introduce undefined behavior if used wrong. In case of doubt, set it to false.
 *
 * @param r_dest A pointer to uninitialized storage, into which the newly created StringName is constructed.
 * @param p_contents A pointer to a C string (null terminated and Latin-1 or ASCII encoded).
 * @param p_is_static Whether the StringName reuses the buffer directly (see above).
 */
typedef void (*FoundryExtensionInterfaceStringNameNewWithLatin1Chars)(FoundryExtensionUninitializedStringNamePtr r_dest, const char *p_contents, FoundryExtensionBool p_is_static);

/**
 * @name string_name_new_with_utf8_chars
 * @since 4.2
 *
 * Creates a StringName from a UTF-8 encoded C string.
 *
 * @param r_dest A pointer to uninitialized storage, into which the newly created StringName is constructed.
 * @param p_contents A pointer to a C string (null terminated and UTF-8 encoded).
 */
typedef void (*FoundryExtensionInterfaceStringNameNewWithUtf8Chars)(FoundryExtensionUninitializedStringNamePtr r_dest, const char *p_contents);

/**
 * @name string_name_new_with_utf8_chars_and_len
 * @since 4.2
 *
 * Creates a StringName from a UTF-8 encoded string with a given number of characters.
 *
 * @param r_dest A pointer to uninitialized storage, into which the newly created StringName is constructed.
 * @param p_contents A pointer to a C string (null terminated and UTF-8 encoded).
 * @param p_size The number of bytes (not UTF-8 code points).
 */
typedef void (*FoundryExtensionInterfaceStringNameNewWithUtf8CharsAndLen)(FoundryExtensionUninitializedStringNamePtr r_dest, const char *p_contents, FoundryExtensionInt p_size);

/**
 * @name xml_parser_open_buffer
 * @since 4.1
 *
 * Opens a raw XML buffer on an XMLParser instance.
 *
 * @param p_instance A pointer to an XMLParser object.
 * @param p_buffer A pointer to the buffer.
 * @param p_size The size of the buffer.
 *
 * @return A Foundry error code (ex. OK, ERR_INVALID_DATA, etc).
 *
 * @see XMLParser::open_buffer()
 */
typedef FoundryExtensionInt (*FoundryExtensionInterfaceXmlParserOpenBuffer)(FoundryExtensionObjectPtr p_instance, const uint8_t *p_buffer, size_t p_size);

/**
 * @name file_access_store_buffer
 * @since 4.1
 *
 * Stores the given buffer using an instance of FileAccess.
 *
 * @param p_instance A pointer to a FileAccess object.
 * @param p_src A pointer to the buffer.
 * @param p_length The size of the buffer.
 *
 * @see FileAccess::store_buffer()
 */
typedef void (*FoundryExtensionInterfaceFileAccessStoreBuffer)(FoundryExtensionObjectPtr p_instance, const uint8_t *p_src, uint64_t p_length);

/**
 * @name file_access_get_buffer
 * @since 4.1
 *
 * Reads the next p_length bytes into the given buffer using an instance of FileAccess.
 *
 * @param p_instance A pointer to a FileAccess object.
 * @param p_dst A pointer to the buffer to store the data.
 * @param p_length The requested number of bytes to read.
 *
 * @return The actual number of bytes read (may be less than requested).
 */
typedef uint64_t (*FoundryExtensionInterfaceFileAccessGetBuffer)(FoundryExtensionConstObjectPtr p_instance, uint8_t *p_dst, uint64_t p_length);

/**
 * @name image_ptrw
 * @since 4.3
 *
 * Returns writable pointer to internal Image buffer.
 *
 * @param p_instance A pointer to a Image object.
 *
 * @return Pointer to internal Image buffer.
 *
 * @see Image::ptrw()
 */
typedef uint8_t *(*FoundryExtensionInterfaceImagePtrw)(FoundryExtensionObjectPtr p_instance);

/**
 * @name image_ptr
 * @since 4.3
 *
 * Returns read only pointer to internal Image buffer.
 *
 * @param p_instance A pointer to a Image object.
 *
 * @return Pointer to internal Image buffer.
 *
 * @see Image::ptr()
 */
typedef const uint8_t *(*FoundryExtensionInterfaceImagePtr)(FoundryExtensionObjectPtr p_instance);

/**
 * @name worker_thread_pool_add_native_group_task
 * @since 4.1
 *
 * Adds a group task to an instance of WorkerThreadPool.
 *
 * @param p_instance A pointer to a WorkerThreadPool object.
 * @param p_func A pointer to a function to run in the thread pool.
 * @param p_userdata A pointer to arbitrary data which will be passed to p_func.
 * @param p_elements The number of element needed in the group.
 * @param p_tasks The number of tasks needed in the group.
 * @param p_high_priority Whether or not this is a high priority task.
 * @param p_description A pointer to a String with the task description.
 *
 * @return The task group ID.
 *
 * @see WorkerThreadPool::add_group_task()
 */
typedef int64_t (*FoundryExtensionInterfaceWorkerThreadPoolAddNativeGroupTask)(FoundryExtensionObjectPtr p_instance, FoundryExtensionWorkerThreadPoolGroupTask p_func, void *p_userdata, int32_t p_elements, int32_t p_tasks, FoundryExtensionBool p_high_priority, FoundryExtensionConstStringPtr p_description);

/**
 * @name worker_thread_pool_add_native_task
 * @since 4.1
 *
 * Adds a task to an instance of WorkerThreadPool.
 *
 * @param p_instance A pointer to a WorkerThreadPool object.
 * @param p_func A pointer to a function to run in the thread pool.
 * @param p_userdata A pointer to arbitrary data which will be passed to p_func.
 * @param p_high_priority Whether or not this is a high priority task.
 * @param p_description A pointer to a String with the task description.
 *
 * @return The task ID.
 */
typedef int64_t (*FoundryExtensionInterfaceWorkerThreadPoolAddNativeTask)(FoundryExtensionObjectPtr p_instance, FoundryExtensionWorkerThreadPoolTask p_func, void *p_userdata, FoundryExtensionBool p_high_priority, FoundryExtensionConstStringPtr p_description);

/**
 * @name packed_byte_array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a byte in a PackedByteArray.
 *
 * @param p_self A pointer to a PackedByteArray object.
 * @param p_index The index of the byte to get.
 *
 * @return A pointer to the requested byte.
 */
typedef uint8_t *(*FoundryExtensionInterfacePackedByteArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_byte_array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a byte in a PackedByteArray.
 *
 * @param p_self A const pointer to a PackedByteArray object.
 * @param p_index The index of the byte to get.
 *
 * @return A const pointer to the requested byte.
 */
typedef const uint8_t *(*FoundryExtensionInterfacePackedByteArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_float32_array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a 32-bit float in a PackedFloat32Array.
 *
 * @param p_self A pointer to a PackedFloat32Array object.
 * @param p_index The index of the float to get.
 *
 * @return A pointer to the requested 32-bit float.
 */
typedef float *(*FoundryExtensionInterfacePackedFloat32ArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_float32_array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a 32-bit float in a PackedFloat32Array.
 *
 * @param p_self A const pointer to a PackedFloat32Array object.
 * @param p_index The index of the float to get.
 *
 * @return A const pointer to the requested 32-bit float.
 */
typedef const float *(*FoundryExtensionInterfacePackedFloat32ArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_float64_array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a 64-bit float in a PackedFloat64Array.
 *
 * @param p_self A pointer to a PackedFloat64Array object.
 * @param p_index The index of the float to get.
 *
 * @return A pointer to the requested 64-bit float.
 */
typedef double *(*FoundryExtensionInterfacePackedFloat64ArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_float64_array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a 64-bit float in a PackedFloat64Array.
 *
 * @param p_self A const pointer to a PackedFloat64Array object.
 * @param p_index The index of the float to get.
 *
 * @return A const pointer to the requested 64-bit float.
 */
typedef const double *(*FoundryExtensionInterfacePackedFloat64ArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_int32_array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a 32-bit integer in a PackedInt32Array.
 *
 * @param p_self A pointer to a PackedInt32Array object.
 * @param p_index The index of the integer to get.
 *
 * @return A pointer to the requested 32-bit integer.
 */
typedef int32_t *(*FoundryExtensionInterfacePackedInt32ArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_int32_array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a 32-bit integer in a PackedInt32Array.
 *
 * @param p_self A const pointer to a PackedInt32Array object.
 * @param p_index The index of the integer to get.
 *
 * @return A const pointer to the requested 32-bit integer.
 */
typedef const int32_t *(*FoundryExtensionInterfacePackedInt32ArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_int64_array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a 64-bit integer in a PackedInt64Array.
 *
 * @param p_self A pointer to a PackedInt64Array object.
 * @param p_index The index of the integer to get.
 *
 * @return A pointer to the requested 64-bit integer.
 */
typedef int64_t *(*FoundryExtensionInterfacePackedInt64ArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_int64_array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a 64-bit integer in a PackedInt64Array.
 *
 * @param p_self A const pointer to a PackedInt64Array object.
 * @param p_index The index of the integer to get.
 *
 * @return A const pointer to the requested 64-bit integer.
 */
typedef const int64_t *(*FoundryExtensionInterfacePackedInt64ArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_string_array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a string in a PackedStringArray.
 *
 * @param p_self A pointer to a PackedStringArray object.
 * @param p_index The index of the String to get.
 *
 * @return A pointer to the requested String.
 */
typedef FoundryExtensionStringPtr (*FoundryExtensionInterfacePackedStringArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_string_array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a string in a PackedStringArray.
 *
 * @param p_self A const pointer to a PackedStringArray object.
 * @param p_index The index of the String to get.
 *
 * @return A const pointer to the requested String.
 */
typedef FoundryExtensionStringPtr (*FoundryExtensionInterfacePackedStringArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_vector2_array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a Vector2 in a PackedVector2Array.
 *
 * @param p_self A pointer to a PackedVector2Array object.
 * @param p_index The index of the Vector2 to get.
 *
 * @return A pointer to the requested Vector2.
 */
typedef FoundryExtensionTypePtr (*FoundryExtensionInterfacePackedVector2ArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_vector2_array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a Vector2 in a PackedVector2Array.
 *
 * @param p_self A const pointer to a PackedVector2Array object.
 * @param p_index The index of the Vector2 to get.
 *
 * @return A const pointer to the requested Vector2.
 */
typedef FoundryExtensionTypePtr (*FoundryExtensionInterfacePackedVector2ArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_vector3_array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a Vector3 in a PackedVector3Array.
 *
 * @param p_self A pointer to a PackedVector3Array object.
 * @param p_index The index of the Vector3 to get.
 *
 * @return A pointer to the requested Vector3.
 */
typedef FoundryExtensionTypePtr (*FoundryExtensionInterfacePackedVector3ArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_vector3_array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a Vector3 in a PackedVector3Array.
 *
 * @param p_self A const pointer to a PackedVector3Array object.
 * @param p_index The index of the Vector3 to get.
 *
 * @return A const pointer to the requested Vector3.
 */
typedef FoundryExtensionTypePtr (*FoundryExtensionInterfacePackedVector3ArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_vector4_array_operator_index
 * @since 4.3
 *
 * Gets a pointer to a Vector4 in a PackedVector4Array.
 *
 * @param p_self A pointer to a PackedVector4Array object.
 * @param p_index The index of the Vector4 to get.
 *
 * @return A pointer to the requested Vector4.
 */
typedef FoundryExtensionTypePtr (*FoundryExtensionInterfacePackedVector4ArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_vector4_array_operator_index_const
 * @since 4.3
 *
 * Gets a const pointer to a Vector4 in a PackedVector4Array.
 *
 * @param p_self A const pointer to a PackedVector4Array object.
 * @param p_index The index of the Vector4 to get.
 *
 * @return A const pointer to the requested Vector4.
 */
typedef FoundryExtensionTypePtr (*FoundryExtensionInterfacePackedVector4ArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_color_array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a color in a PackedColorArray.
 *
 * @param p_self A pointer to a PackedColorArray object.
 * @param p_index The index of the Color to get.
 *
 * @return A pointer to the requested Color.
 */
typedef FoundryExtensionTypePtr (*FoundryExtensionInterfacePackedColorArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name packed_color_array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a color in a PackedColorArray.
 *
 * @param p_self A const pointer to a PackedColorArray object.
 * @param p_index The index of the Color to get.
 *
 * @return A const pointer to the requested Color.
 */
typedef FoundryExtensionTypePtr (*FoundryExtensionInterfacePackedColorArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name array_operator_index
 * @since 4.1
 *
 * Gets a pointer to a Variant in an Array.
 *
 * @param p_self A pointer to an Array object.
 * @param p_index The index of the Variant to get.
 *
 * @return A pointer to the requested Variant.
 */
typedef FoundryExtensionVariantPtr (*FoundryExtensionInterfaceArrayOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name array_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a Variant in an Array.
 *
 * @param p_self A const pointer to an Array object.
 * @param p_index The index of the Variant to get.
 *
 * @return A const pointer to the requested Variant.
 */
typedef FoundryExtensionVariantPtr (*FoundryExtensionInterfaceArrayOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionInt p_index);

/**
 * @name array_ref
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.5. Removed from interface. Use copy constructor instead.
 *
 * Sets an Array to be a reference to another Array object.
 *
 * @param p_self A pointer to the Array object to update.
 * @param p_from A pointer to the Array object to reference.
 */
typedef void (*FoundryExtensionInterfaceArrayRef)(FoundryExtensionTypePtr p_self, FoundryExtensionConstTypePtr p_from);

/**
 * @name array_set_typed
 * @since 4.1
 *
 * Makes an Array into a typed Array.
 *
 * @param p_self A pointer to the Array.
 * @param p_type The type of Variant the Array will store.
 * @param p_class_name A pointer to a StringName with the name of the object (if p_type is FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT).
 * @param p_script A pointer to a Script object (if p_type is FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT and the base class is extended by a script).
 */
typedef void (*FoundryExtensionInterfaceArraySetTyped)(FoundryExtensionTypePtr p_self, FoundryExtensionVariantType p_type, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstVariantPtr p_script);

/**
 * @name array_set_typed_by_descriptor
 * @since 4.7
 *
 * Makes an Array into a typed Array using a recursive container type descriptor.
 *
 * @param p_self A pointer to the Array.
 * @param p_element_type_descriptor A pointer to a Variant containing the element type descriptor.
 *
 * @return True if the descriptor was valid and the Array was updated.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceArraySetTypedByDescriptor)(FoundryExtensionTypePtr p_self, FoundryExtensionConstVariantPtr p_element_type_descriptor);

/**
 * @name array_get_typed_element_type_descriptor
 * @since 4.7
 *
 * Gets the recursive element type descriptor for an Array.
 *
 * @param p_self A const pointer to the Array.
 * @param r_element_type_descriptor A pointer to a Variant that will receive the element type descriptor.
 */
typedef void (*FoundryExtensionInterfaceArrayGetTypedElementTypeDescriptor)(FoundryExtensionConstTypePtr p_self, FoundryExtensionUninitializedVariantPtr r_element_type_descriptor);

/**
 * @name dictionary_operator_index
 * @since 4.1
 *
 * Gets a pointer to a Variant in a Dictionary with the given key.
 *
 * @param p_self A pointer to a Dictionary object.
 * @param p_key A pointer to a Variant representing the key.
 *
 * @return A pointer to a Variant representing the value at the given key.
 */
typedef FoundryExtensionVariantPtr (*FoundryExtensionInterfaceDictionaryOperatorIndex)(FoundryExtensionTypePtr p_self, FoundryExtensionConstVariantPtr p_key);

/**
 * @name dictionary_operator_index_const
 * @since 4.1
 *
 * Gets a const pointer to a Variant in a Dictionary with the given key.
 *
 * @param p_self A const pointer to a Dictionary object.
 * @param p_key A pointer to a Variant representing the key.
 *
 * @return A const pointer to a Variant representing the value at the given key.
 */
typedef FoundryExtensionVariantPtr (*FoundryExtensionInterfaceDictionaryOperatorIndexConst)(FoundryExtensionConstTypePtr p_self, FoundryExtensionConstVariantPtr p_key);

/**
 * @name dictionary_set_typed
 * @since 4.4
 *
 * Makes a Dictionary into a typed Dictionary.
 *
 * @param p_self A pointer to the Dictionary.
 * @param p_key_type The type of Variant the Dictionary key will store.
 * @param p_key_class_name A pointer to a StringName with the name of the object (if p_key_type is FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT).
 * @param p_key_script A pointer to a Script object (if p_key_type is FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT and the base class is extended by a script).
 * @param p_value_type The type of Variant the Dictionary value will store.
 * @param p_value_class_name A pointer to a StringName with the name of the object (if p_value_type is FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT).
 * @param p_value_script A pointer to a Script object (if p_value_type is FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT and the base class is extended by a script).
 */
typedef void (*FoundryExtensionInterfaceDictionarySetTyped)(FoundryExtensionTypePtr p_self, FoundryExtensionVariantType p_key_type, FoundryExtensionConstStringNamePtr p_key_class_name, FoundryExtensionConstVariantPtr p_key_script, FoundryExtensionVariantType p_value_type, FoundryExtensionConstStringNamePtr p_value_class_name, FoundryExtensionConstVariantPtr p_value_script);

/**
 * @name dictionary_set_typed_by_descriptor
 * @since 4.7
 *
 * Makes a Dictionary into a typed Dictionary using recursive container type descriptors.
 *
 * @param p_self A pointer to the Dictionary.
 * @param p_key_type_descriptor A pointer to a Variant containing the key type descriptor.
 * @param p_value_type_descriptor A pointer to a Variant containing the value type descriptor.
 *
 * @return True if both descriptors were valid and the Dictionary was updated.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceDictionarySetTypedByDescriptor)(FoundryExtensionTypePtr p_self, FoundryExtensionConstVariantPtr p_key_type_descriptor, FoundryExtensionConstVariantPtr p_value_type_descriptor);

/**
 * @name dictionary_get_typed_key_type_descriptor
 * @since 4.7
 *
 * Gets the recursive key type descriptor for a Dictionary.
 *
 * @param p_self A const pointer to the Dictionary.
 * @param r_key_type_descriptor A pointer to a Variant that will receive the key type descriptor.
 */
typedef void (*FoundryExtensionInterfaceDictionaryGetTypedKeyTypeDescriptor)(FoundryExtensionConstTypePtr p_self, FoundryExtensionUninitializedVariantPtr r_key_type_descriptor);

/**
 * @name dictionary_get_typed_value_type_descriptor
 * @since 4.7
 *
 * Gets the recursive value type descriptor for a Dictionary.
 *
 * @param p_self A const pointer to the Dictionary.
 * @param r_value_type_descriptor A pointer to a Variant that will receive the value type descriptor.
 */
typedef void (*FoundryExtensionInterfaceDictionaryGetTypedValueTypeDescriptor)(FoundryExtensionConstTypePtr p_self, FoundryExtensionUninitializedVariantPtr r_value_type_descriptor);

/**
 * @name object_method_bind_call
 * @since 4.1
 *
 * Calls a method on an Object.
 *
 * @param p_method_bind A pointer to the MethodBind representing the method on the Object's class.
 * @param p_instance A pointer to the Object.
 * @param p_args A pointer to a C array of Variants representing the arguments.
 * @param p_arg_count The number of arguments.
 * @param r_ret A pointer to Variant which will receive the return value.
 * @param r_error A pointer to a FoundryExtensionCallError struct that will receive error information.
 */
typedef void (*FoundryExtensionInterfaceObjectMethodBindCall)(FoundryExtensionMethodBindPtr p_method_bind, FoundryExtensionObjectPtr p_instance, const FoundryExtensionConstVariantPtr *p_args, FoundryExtensionInt p_arg_count, FoundryExtensionUninitializedVariantPtr r_ret, FoundryExtensionCallError *r_error);

/**
 * @name object_method_bind_ptrcall
 * @since 4.1
 *
 * Calls a method on an Object (using a "ptrcall").
 *
 * @param p_method_bind A pointer to the MethodBind representing the method on the Object's class.
 * @param p_instance A pointer to the Object.
 * @param p_args A pointer to a C array representing the arguments.
 * @param r_ret A pointer to the Object that will receive the return value.
 */
typedef void (*FoundryExtensionInterfaceObjectMethodBindPtrcall)(FoundryExtensionMethodBindPtr p_method_bind, FoundryExtensionObjectPtr p_instance, const FoundryExtensionConstTypePtr *p_args, FoundryExtensionTypePtr r_ret);

/**
 * @name object_destroy
 * @since 4.1
 *
 * Destroys an Object.
 *
 * @param p_o A pointer to the Object.
 */
typedef void (*FoundryExtensionInterfaceObjectDestroy)(FoundryExtensionObjectPtr p_o);

/**
 * @name global_get_singleton
 * @since 4.1
 *
 * Gets a global singleton by name.
 *
 * @param p_name A pointer to a StringName with the singleton name.
 *
 * @return A pointer to the singleton Object.
 */
typedef FoundryExtensionObjectPtr (*FoundryExtensionInterfaceGlobalGetSingleton)(FoundryExtensionConstStringNamePtr p_name);

/**
 * @name object_get_instance_binding
 * @since 4.1
 *
 * Gets a pointer representing an Object's instance binding.
 *
 * @param p_o A pointer to the Object.
 * @param p_token A token the library received by the FoundryExtension's entry point function.
 * @param p_callbacks A pointer to a FoundryExtensionInstanceBindingCallbacks struct.
 *
 * @return A pointer to the instance binding.
 */
typedef void *(*FoundryExtensionInterfaceObjectGetInstanceBinding)(FoundryExtensionObjectPtr p_o, void *p_token, const FoundryExtensionInstanceBindingCallbacks *p_callbacks);

/**
 * @name object_set_instance_binding
 * @since 4.1
 *
 * Sets an Object's instance binding.
 *
 * @param p_o A pointer to the Object.
 * @param p_token A token the library received by the FoundryExtension's entry point function.
 * @param p_binding A pointer to the instance binding.
 * @param p_callbacks A pointer to a FoundryExtensionInstanceBindingCallbacks struct.
 */
typedef void (*FoundryExtensionInterfaceObjectSetInstanceBinding)(FoundryExtensionObjectPtr p_o, void *p_token, void *p_binding, const FoundryExtensionInstanceBindingCallbacks *p_callbacks);

/**
 * @name object_free_instance_binding
 * @since 4.2
 *
 * Free an Object's instance binding.
 *
 * @param p_o A pointer to the Object.
 * @param p_token A token the library received by the FoundryExtension's entry point function.
 */
typedef void (*FoundryExtensionInterfaceObjectFreeInstanceBinding)(FoundryExtensionObjectPtr p_o, void *p_token);

/**
 * @name object_set_instance
 * @since 4.1
 *
 * Sets an extension class instance on a Object.
 *
 * `p_classname` should be a registered extension class and should extend the `p_o` Object's class.
 *
 * @param p_o A pointer to the Object.
 * @param p_classname A pointer to a StringName with the registered extension class's name.
 * @param p_instance A pointer to the extension class instance.
 */
typedef void (*FoundryExtensionInterfaceObjectSetInstance)(FoundryExtensionObjectPtr p_o, FoundryExtensionConstStringNamePtr p_classname, FoundryExtensionClassInstancePtr p_instance);

/**
 * @name object_get_class_name
 * @since 4.1
 *
 * Gets the class name of an Object.
 *
 * If the FoundryExtension wraps the Foundry object in an abstraction specific to its class, this is the
 * function that should be used to determine which wrapper to use.
 *
 * @param p_object A pointer to the Object.
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param r_class_name A pointer to a String to receive the class name.
 *
 * @return true if successful in getting the class name; otherwise false.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceObjectGetClassName)(FoundryExtensionConstObjectPtr p_object, FoundryExtensionClassLibraryPtr p_library, FoundryExtensionUninitializedStringNamePtr r_class_name);

/**
 * @name object_cast_to
 * @since 4.1
 *
 * Casts an Object to a different type.
 *
 * @param p_object A pointer to the Object.
 * @param p_class_tag A pointer uniquely identifying a built-in class in the ClassDB.
 *
 * @return Returns a pointer to the Object, or NULL if it can't be cast to the requested type.
 */
typedef FoundryExtensionObjectPtr (*FoundryExtensionInterfaceObjectCastTo)(FoundryExtensionConstObjectPtr p_object, void *p_class_tag);

/**
 * @name object_get_instance_from_id
 * @since 4.1
 *
 * Gets an Object by its instance ID.
 *
 * @param p_instance_id The instance ID.
 *
 * @return A pointer to the Object.
 */
typedef FoundryExtensionObjectPtr (*FoundryExtensionInterfaceObjectGetInstanceFromId)(GDObjectInstanceID p_instance_id);

/**
 * @name object_get_instance_id
 * @since 4.1
 *
 * Gets the instance ID from an Object.
 *
 * @param p_object A pointer to the Object.
 *
 * @return The instance ID.
 */
typedef GDObjectInstanceID (*FoundryExtensionInterfaceObjectGetInstanceId)(FoundryExtensionConstObjectPtr p_object);

/**
 * @name object_has_script_method
 * @since 4.3
 *
 * Checks if this object has a script with the given method.
 *
 * @param p_object A pointer to the Object.
 * @param p_method A pointer to a StringName identifying the method.
 *
 * @return true if the object has a script and that script has a method with the given name. Returns false if the object has no script.
 */
typedef FoundryExtensionBool (*FoundryExtensionInterfaceObjectHasScriptMethod)(FoundryExtensionConstObjectPtr p_object, FoundryExtensionConstStringNamePtr p_method);

/**
 * @name object_call_script_method
 * @since 4.3
 *
 * Call the given script method on this object.
 *
 * @param p_object A pointer to the Object.
 * @param p_method A pointer to a StringName identifying the method.
 * @param p_args A pointer to a C array of Variant.
 * @param p_argument_count The number of arguments.
 * @param r_return A pointer a Variant which will be assigned the return value.
 * @param r_error A pointer the structure which will hold error information.
 */
typedef void (*FoundryExtensionInterfaceObjectCallScriptMethod)(FoundryExtensionObjectPtr p_object, FoundryExtensionConstStringNamePtr p_method, const FoundryExtensionConstVariantPtr *p_args, FoundryExtensionInt p_argument_count, FoundryExtensionUninitializedVariantPtr r_return, FoundryExtensionCallError *r_error);

/**
 * @name ref_get_object
 * @since 4.1
 *
 * Gets the Object from a reference.
 *
 * @param p_ref A pointer to the reference.
 *
 * @return A pointer to the Object from the reference or NULL.
 */
typedef FoundryExtensionObjectPtr (*FoundryExtensionInterfaceRefGetObject)(FoundryExtensionConstRefPtr p_ref);

/**
 * @name ref_set_object
 * @since 4.1
 *
 * Sets the Object referred to by a reference.
 *
 * @param p_ref A pointer to the reference.
 * @param p_object A pointer to the Object to refer to.
 */
typedef void (*FoundryExtensionInterfaceRefSetObject)(FoundryExtensionRefPtr p_ref, FoundryExtensionObjectPtr p_object);

/**
 * @name script_instance_create
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.2. Use `script_instance_create3` instead.
 *
 * Creates a script instance that contains the given info and instance data.
 *
 * @param p_info A pointer to a FoundryExtensionScriptInstanceInfo struct.
 * @param p_instance_data A pointer to a data representing the script instance in the FoundryExtension. This will be passed to all the function pointers on p_info.
 *
 * @return A pointer to a ScriptInstanceExtension object.
 */
typedef FoundryExtensionScriptInstancePtr (*FoundryExtensionInterfaceScriptInstanceCreate)(const FoundryExtensionScriptInstanceInfo *p_info, FoundryExtensionScriptInstanceDataPtr p_instance_data);

/**
 * @name script_instance_create2
 * @since 4.2
 * @deprecated Deprecated in Foundry 4.3. Use `script_instance_create3` instead.
 *
 * Creates a script instance that contains the given info and instance data.
 *
 * @param p_info A pointer to a FoundryExtensionScriptInstanceInfo2 struct.
 * @param p_instance_data A pointer to a data representing the script instance in the FoundryExtension. This will be passed to all the function pointers on p_info.
 *
 * @return A pointer to a ScriptInstanceExtension object.
 */
typedef FoundryExtensionScriptInstancePtr (*FoundryExtensionInterfaceScriptInstanceCreate2)(const FoundryExtensionScriptInstanceInfo2 *p_info, FoundryExtensionScriptInstanceDataPtr p_instance_data);

/**
 * @name script_instance_create3
 * @since 4.3
 *
 * Creates a script instance that contains the given info and instance data.
 *
 * @param p_info A pointer to a FoundryExtensionScriptInstanceInfo3 struct.
 * @param p_instance_data A pointer to a data representing the script instance in the FoundryExtension. This will be passed to all the function pointers on p_info.
 *
 * @return A pointer to a ScriptInstanceExtension object.
 */
typedef FoundryExtensionScriptInstancePtr (*FoundryExtensionInterfaceScriptInstanceCreate3)(const FoundryExtensionScriptInstanceInfo3 *p_info, FoundryExtensionScriptInstanceDataPtr p_instance_data);

/**
 * @name placeholder_script_instance_create
 * @since 4.2
 *
 * Creates a placeholder script instance for a given script and instance.
 *
 * This interface is optional as a custom placeholder could also be created with script_instance_create().
 *
 * @param p_language A pointer to a ScriptLanguage.
 * @param p_script A pointer to a Script.
 * @param p_owner A pointer to an Object.
 *
 * @return A pointer to a PlaceHolderScriptInstance object.
 */
typedef FoundryExtensionScriptInstancePtr (*FoundryExtensionInterfacePlaceHolderScriptInstanceCreate)(FoundryExtensionObjectPtr p_language, FoundryExtensionObjectPtr p_script, FoundryExtensionObjectPtr p_owner);

/**
 * @name placeholder_script_instance_update
 * @since 4.2
 *
 * Updates a placeholder script instance with the given properties and values.
 *
 * The passed in placeholder must be an instance of PlaceHolderScriptInstance
 * such as the one returned by placeholder_script_instance_create().
 *
 * @param p_placeholder A pointer to a PlaceHolderScriptInstance.
 * @param p_properties A pointer to an Array of Dictionary representing PropertyInfo.
 * @param p_values A pointer to a Dictionary mapping StringName to Variant values.
 */
typedef void (*FoundryExtensionInterfacePlaceHolderScriptInstanceUpdate)(FoundryExtensionScriptInstancePtr p_placeholder, FoundryExtensionConstTypePtr p_properties, FoundryExtensionConstTypePtr p_values);

/**
 * @name object_get_script_instance
 * @since 4.2
 *
 * Get the script instance data attached to this object.
 *
 * @param p_object A pointer to the Object.
 * @param p_language A pointer to the language expected for this script instance.
 *
 * @return A FoundryExtensionScriptInstanceDataPtr that was attached to this object as part of script_instance_create.
 */
typedef FoundryExtensionScriptInstanceDataPtr (*FoundryExtensionInterfaceObjectGetScriptInstance)(FoundryExtensionConstObjectPtr p_object, FoundryExtensionObjectPtr p_language);

/**
 * @name object_set_script_instance
 * @since 4.5
 *
 * Set the script instance data attached to this object.
 *
 * @param p_object A pointer to the Object.
 * @param p_script_instance A pointer to the script instance data to attach to this object.
 */
typedef void (*FoundryExtensionInterfaceObjectSetScriptInstance)(FoundryExtensionObjectPtr p_object, FoundryExtensionScriptInstanceDataPtr p_script_instance);

/**
 * @name callable_custom_create
 * @since 4.2
 * @deprecated Deprecated in Foundry 4.3. Use `callable_custom_create2` instead.
 *
 * Creates a custom Callable object from a function pointer.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param r_callable A pointer that will receive the new Callable.
 * @param p_callable_custom_info The info required to construct a Callable.
 */
typedef void (*FoundryExtensionInterfaceCallableCustomCreate)(FoundryExtensionUninitializedTypePtr r_callable, FoundryExtensionCallableCustomInfo *p_callable_custom_info);

/**
 * @name callable_custom_create2
 * @since 4.3
 *
 * Creates a custom Callable object from a function pointer.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param r_callable A pointer that will receive the new Callable.
 * @param p_callable_custom_info The info required to construct a Callable.
 */
typedef void (*FoundryExtensionInterfaceCallableCustomCreate2)(FoundryExtensionUninitializedTypePtr r_callable, FoundryExtensionCallableCustomInfo2 *p_callable_custom_info);

/**
 * @name callable_custom_get_userdata
 * @since 4.2
 *
 * Retrieves the userdata pointer from a custom Callable.
 *
 * If the Callable is not a custom Callable or the token does not match the one provided to callable_custom_create() via FoundryExtensionCallableCustomInfo then NULL will be returned.
 *
 * @param p_callable A pointer to a Callable.
 * @param p_token A pointer to an address that uniquely identifies the FoundryExtension.
 *
 * @return The userdata pointer given when creating this custom Callable.
 */
typedef void *(*FoundryExtensionInterfaceCallableCustomGetUserData)(FoundryExtensionConstTypePtr p_callable, void *p_token);

/**
 * @name classdb_construct_object
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.4. Use `classdb_construct_object2` instead.
 *
 * Constructs an Object of the requested class.
 *
 * The passed class must be a built-in Foundry class, or an already-registered extension class. In both cases, object_set_instance() should be called to fully initialize the object.
 *
 * @param p_classname A pointer to a StringName with the class name.
 *
 * @return A pointer to the newly created Object.
 */
typedef FoundryExtensionObjectPtr (*FoundryExtensionInterfaceClassdbConstructObject)(FoundryExtensionConstStringNamePtr p_classname);

/**
 * @name classdb_construct_object2
 * @since 4.4
 *
 * Constructs an Object of the requested class.
 *
 * The passed class must be a built-in Foundry class, or an already-registered extension class. In both cases, object_set_instance() should be called to fully initialize the object.
 *
 * "NOTIFICATION_POSTINITIALIZE" must be sent after construction.
 *
 * @param p_classname A pointer to a StringName with the class name.
 *
 * @return A pointer to the newly created Object.
 */
typedef FoundryExtensionObjectPtr (*FoundryExtensionInterfaceClassdbConstructObject2)(FoundryExtensionConstStringNamePtr p_classname);

/**
 * @name classdb_get_method_bind
 * @since 4.1
 *
 * Gets a pointer to the MethodBind in ClassDB for the given class, method and hash.
 *
 * @param p_classname A pointer to a StringName with the class name.
 * @param p_methodname A pointer to a StringName with the method name.
 * @param p_hash A hash representing the function signature.
 *
 * @return A pointer to the MethodBind from ClassDB.
 */
typedef FoundryExtensionMethodBindPtr (*FoundryExtensionInterfaceClassdbGetMethodBind)(FoundryExtensionConstStringNamePtr p_classname, FoundryExtensionConstStringNamePtr p_methodname, FoundryExtensionInt p_hash);

/**
 * @name classdb_get_class_tag
 * @since 4.1
 *
 * Gets a pointer uniquely identifying the given built-in class in the ClassDB.
 *
 * @param p_classname A pointer to a StringName with the class name.
 *
 * @return A pointer uniquely identifying the built-in class in the ClassDB.
 */
typedef void *(*FoundryExtensionInterfaceClassdbGetClassTag)(FoundryExtensionConstStringNamePtr p_classname);

/**
 * @name classdb_register_extension_class
 * @since 4.1
 * @deprecated Deprecated in Foundry 4.2. Use `classdb_register_extension_class5` instead.
 *
 * Registers an extension class in the ClassDB.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_parent_class_name A pointer to a StringName with the parent class name.
 * @param p_extension_funcs A pointer to a FoundryExtensionClassCreationInfo struct.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClass)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstStringNamePtr p_parent_class_name, const FoundryExtensionClassCreationInfo *p_extension_funcs);

/**
 * @name classdb_register_extension_class2
 * @since 4.2
 * @deprecated Deprecated in Foundry 4.3. Use `classdb_register_extension_class5` instead.
 *
 * Registers an extension class in the ClassDB.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_parent_class_name A pointer to a StringName with the parent class name.
 * @param p_extension_funcs A pointer to a FoundryExtensionClassCreationInfo2 struct.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClass2)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstStringNamePtr p_parent_class_name, const FoundryExtensionClassCreationInfo2 *p_extension_funcs);

/**
 * @name classdb_register_extension_class3
 * @since 4.3
 * @deprecated Deprecated in Foundry 4.4. Use `classdb_register_extension_class5` instead.
 *
 * Registers an extension class in the ClassDB.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_parent_class_name A pointer to a StringName with the parent class name.
 * @param p_extension_funcs A pointer to a FoundryExtensionClassCreationInfo3 struct.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClass3)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstStringNamePtr p_parent_class_name, const FoundryExtensionClassCreationInfo3 *p_extension_funcs);

/**
 * @name classdb_register_extension_class4
 * @since 4.4
 * @deprecated Deprecated in Foundry 4.5. Use `classdb_register_extension_class5` instead.
 *
 * Registers an extension class in the ClassDB.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_parent_class_name A pointer to a StringName with the parent class name.
 * @param p_extension_funcs A pointer to a FoundryExtensionClassCreationInfo4 struct.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClass4)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstStringNamePtr p_parent_class_name, const FoundryExtensionClassCreationInfo4 *p_extension_funcs);

/**
 * @name classdb_register_extension_class5
 * @since 4.5
 *
 * Registers an extension class in the ClassDB.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_parent_class_name A pointer to a StringName with the parent class name.
 * @param p_extension_funcs A pointer to a FoundryExtensionClassCreationInfo5 struct.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClass5)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstStringNamePtr p_parent_class_name, const FoundryExtensionClassCreationInfo5 *p_extension_funcs);

/**
 * @name classdb_register_extension_class_method
 * @since 4.1
 *
 * Registers a method on an extension class in the ClassDB.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_method_info A pointer to a FoundryExtensionClassMethodInfo struct.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClassMethod)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, const FoundryExtensionClassMethodInfo *p_method_info);

/**
 * @name classdb_register_extension_class_virtual_method
 * @since 4.3
 *
 * Registers a virtual method on an extension class in ClassDB, that can be implemented by scripts or other extensions.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_method_info A pointer to a FoundryExtensionClassMethodInfo struct.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClassVirtualMethod)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, const FoundryExtensionClassVirtualMethodInfo *p_method_info);

/**
 * @name classdb_register_extension_class_integer_constant
 * @since 4.1
 *
 * Registers an integer constant on an extension class in the ClassDB.
 *
 * Note about registering bitfield values (if p_is_bitfield is true): even though p_constant_value is signed, language bindings are
 * advised to treat bitfields as uint64_t, since this is generally clearer and can prevent mistakes like using -1 for setting all bits.
 * Language APIs should thus provide an abstraction that registers bitfields (uint64_t) separately from regular constants (int64_t).
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_enum_name A pointer to a StringName with the enum name.
 * @param p_constant_name A pointer to a StringName with the constant name.
 * @param p_constant_value The constant value.
 * @param p_is_bitfield Whether or not this constant is part of a bitfield.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClassIntegerConstant)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstStringNamePtr p_enum_name, FoundryExtensionConstStringNamePtr p_constant_name, FoundryExtensionInt p_constant_value, FoundryExtensionBool p_is_bitfield);

/**
 * @name classdb_register_extension_class_property
 * @since 4.1
 *
 * Registers a property on an extension class in the ClassDB.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_info A pointer to a FoundryExtensionPropertyInfo struct.
 * @param p_setter A pointer to a StringName with the name of the setter method.
 * @param p_getter A pointer to a StringName with the name of the getter method.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClassProperty)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, const FoundryExtensionPropertyInfo *p_info, FoundryExtensionConstStringNamePtr p_setter, FoundryExtensionConstStringNamePtr p_getter);

/**
 * @name classdb_register_extension_class_property_indexed
 * @since 4.2
 *
 * Registers an indexed property on an extension class in the ClassDB.
 *
 * Provided struct can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_info A pointer to a FoundryExtensionPropertyInfo struct.
 * @param p_setter A pointer to a StringName with the name of the setter method.
 * @param p_getter A pointer to a StringName with the name of the getter method.
 * @param p_index The index to pass as the first argument to the getter and setter methods.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClassPropertyIndexed)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, const FoundryExtensionPropertyInfo *p_info, FoundryExtensionConstStringNamePtr p_setter, FoundryExtensionConstStringNamePtr p_getter, FoundryExtensionInt p_index);

/**
 * @name classdb_register_extension_class_property_group
 * @since 4.1
 *
 * Registers a property group on an extension class in the ClassDB.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_group_name A pointer to a String with the group name.
 * @param p_prefix A pointer to a String with the prefix used by properties in this group.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClassPropertyGroup)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstStringPtr p_group_name, FoundryExtensionConstStringPtr p_prefix);

/**
 * @name classdb_register_extension_class_property_subgroup
 * @since 4.1
 *
 * Registers a property subgroup on an extension class in the ClassDB.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_subgroup_name A pointer to a String with the subgroup name.
 * @param p_prefix A pointer to a String with the prefix used by properties in this subgroup.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClassPropertySubgroup)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstStringPtr p_subgroup_name, FoundryExtensionConstStringPtr p_prefix);

/**
 * @name classdb_register_extension_class_signal
 * @since 4.1
 *
 * Registers a signal on an extension class in the ClassDB.
 *
 * Provided structs can be safely freed once the function returns.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 * @param p_signal_name A pointer to a StringName with the signal name.
 * @param p_argument_info A pointer to a FoundryExtensionPropertyInfo struct.
 * @param p_argument_count The number of arguments the signal receives.
 */
typedef void (*FoundryExtensionInterfaceClassdbRegisterExtensionClassSignal)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name, FoundryExtensionConstStringNamePtr p_signal_name, const FoundryExtensionPropertyInfo *p_argument_info, FoundryExtensionInt p_argument_count);

/**
 * @name classdb_unregister_extension_class
 * @since 4.1
 *
 * Unregisters an extension class in the ClassDB.
 *
 * Unregistering a parent class before a class that inherits it will result in failure. Inheritors must be unregistered first.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_class_name A pointer to a StringName with the class name.
 */
typedef void (*FoundryExtensionInterfaceClassdbUnregisterExtensionClass)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionConstStringNamePtr p_class_name);

/**
 * @name get_library_path
 * @since 4.1
 *
 * Gets the path to the current FoundryExtension library.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param r_path A pointer to a String which will receive the path.
 */
typedef void (*FoundryExtensionInterfaceGetLibraryPath)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionUninitializedStringPtr r_path);

/**
 * @name editor_add_plugin
 * @since 4.1
 *
 * Adds an editor plugin.
 *
 * It's safe to call during initialization.
 *
 * @param p_class_name A pointer to a StringName with the name of a class (descending from EditorPlugin) which is already registered with ClassDB.
 */
typedef void (*FoundryExtensionInterfaceEditorAddPlugin)(FoundryExtensionConstStringNamePtr p_class_name);

/**
 * @name editor_remove_plugin
 * @since 4.1
 *
 * Removes an editor plugin.
 *
 * @param p_class_name A pointer to a StringName with the name of a class that was previously added as an editor plugin.
 */
typedef void (*FoundryExtensionInterfaceEditorRemovePlugin)(FoundryExtensionConstStringNamePtr p_class_name);

/**
 * @name editor_help_load_xml_from_utf8_chars
 * @since 4.3
 *
 * Loads new XML-formatted documentation data in the editor.
 *
 * The provided pointer can be immediately freed once the function returns.
 *
 * @param p_data A pointer to a UTF-8 encoded C string (null terminated).
 */
typedef void (*FoundryExtensionsInterfaceEditorHelpLoadXmlFromUtf8Chars)(const char *p_data);

/**
 * @name editor_help_load_xml_from_utf8_chars_and_len
 * @since 4.3
 *
 * Loads new XML-formatted documentation data in the editor.
 *
 * The provided pointer can be immediately freed once the function returns.
 *
 * @param p_data A pointer to a UTF-8 encoded C string.
 * @param p_size The number of bytes (not code units).
 */
typedef void (*FoundryExtensionsInterfaceEditorHelpLoadXmlFromUtf8CharsAndLen)(const char *p_data, FoundryExtensionInt p_size);

/**
 * @name editor_register_get_classes_used_callback
 * @since 4.5
 *
 * Registers a callback that Foundry can call to get the list of all classes (from ClassDB) that may be used by the calling FoundryExtension.
 *
 * This is used by the editor to generate a build profile (in "Tools" > "Engine Compilation Configuration Editor..." > "Detect from project"),
 * in order to recompile Foundry with only the classes used.
 * In the provided callback, the FoundryExtension should provide the list of classes that _may_ be used statically, thus the time of invocation shouldn't matter.
 * If a FoundryExtension doesn't register a callback, Foundry will assume that it could be using any classes.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_callback The callback to retrieve the list of classes used.
 */
typedef void (*FoundryExtensionInterfaceEditorRegisterGetClassesUsedCallback)(FoundryExtensionClassLibraryPtr p_library, FoundryExtensionEditorGetClassesUsedCallback p_callback);

/**
 * @name register_main_loop_callbacks
 * @since 4.5
 *
 * Registers callbacks to be called at different phases of the main loop.
 *
 * @param p_library A pointer the library received by the FoundryExtension's entry point function.
 * @param p_callbacks A pointer to the structure that contains the callbacks.
 */
typedef void (*FoundryExtensionInterfaceRegisterMainLoopCallbacks)(FoundryExtensionClassLibraryPtr p_library, const FoundryExtensionMainLoopCallbacks *p_callbacks);

#ifdef __cplusplus
}
#endif
