#include "foundry_java_runtime.h"
#include "foundry_java_abi_layout.h"
#include "foundry_java_interface.h"
#include "foundry_java_transport.h"

#include <algorithm>
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
#include <unordered_map>
#include <unordered_set>
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
int ref_init_count = 0;
int ref_unreference_count = 0;
bool ref_hashes_valid = true;
foundry_java::NativeTransport *reentrant_transport = nullptr;
bool ref_cleanup_reentered = false;
FoundryExtensionVariantType copied_variant_type = FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
int variant_copy_count = 0;
int variant_destroy_count = 0;
std::array<int, FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX> variant_construct_counts{};
std::array<int, FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX> variant_inspect_counts{};
std::array<int, FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX> native_value_destroy_counts{};
bool typed_fixture_mismatch = false;
bool force_call_error = false;
bool force_keyed_get_failure = false;
int method_bind_call_count = 0;
int method_bind_ptrcall_count = 0;
int builtin_method_count = 0;
int builtin_constructor_count = 0;
int operator_count = 0;
int member_get_count = 0;
int member_set_count = 0;
int constant_count = 0;
int utility_count = 0;
std::vector<std::uint64_t> builtin_method_argument_values;
std::vector<std::uint64_t> utility_argument_values;
std::vector<FoundryExtensionConstTypePtr> builtin_method_argument_pointers;
std::vector<FoundryExtensionConstTypePtr> utility_argument_pointers;
int named_get_count = 0;
int named_set_count = 0;
int keyed_get_count = 0;
int keyed_set_count = 0;
int indexed_get_count = 0;
int indexed_set_count = 0;
int iter_init_count = 0;
int iter_next_count = 0;
int iter_get_count = 0;
int callable_call_count = 0;
int construct_object_count = 0;
int postinitialize_count = 0;
int singleton_count = 0;
int nil_construct_count = 0;
int signal_construct_count = 0;
int rid_default_construct_count = 0;
int callable_custom_create_count = 0;
FoundryExtensionInt callable_reported_argument_count = -2;
std::vector<FoundryExtensionCallableCustomInfo2> callable_custom_infos;

struct FakeCallableBox {
	FoundryExtensionCallableCustomInfo2 info{};
	int references = 1;
};
constexpr std::uint64_t fake_callable_magic = 0xcafec011ab1e;

struct FakeTextBox {
	std::string text;
	int references = 1;
};
constexpr std::uint64_t fake_text_magic = 0x7e87ca11ab1e;
void release_fake_text(FakeTextBox *box);

struct FakeOpaqueBox {
	FoundryExtensionVariantType type = FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
	std::vector<std::byte> bytes;
};
constexpr std::uint64_t fake_opaque_magic = 0x0fa9eab1e;
std::unordered_map<const void *, FakeTextBox *> fake_text_native_values;
std::unordered_map<const void *, FakeCallableBox *> fake_callable_native_values;

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
		FoundryExtensionUninitializedStringNamePtr destination,
		const char *text,
		FoundryExtensionInt length) {
	native_string_name_construct_count++;
	auto *box = new FakeTextBox;
	box->text.assign(text, static_cast<std::size_t>(length));
	*static_cast<FakeTextBox **>(destination) = box;
	fake_text_native_values[destination] = box;
}

void fake_string_name_destroy(FoundryExtensionTypePtr value) {
	native_string_name_destroy_count++;
	auto **slot = static_cast<FakeTextBox **>(value);
	release_fake_text(*slot);
	*slot = nullptr;
	fake_text_native_values.erase(value);
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

FoundryExtensionBool fake_object_class_name(
		FoundryExtensionConstObjectPtr object,
		FoundryExtensionClassLibraryPtr,
		FoundryExtensionUninitializedStringNamePtr destination) {
	if (object != reinterpret_cast<FoundryExtensionConstObjectPtr>(0x1234)) {
		return 0;
	}
	fake_string_name_from_utf8_and_len(destination, "Node", 4);
	return 1;
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

void fake_ref_reentrant_ptrcall(
		FoundryExtensionMethodBindPtr method,
		FoundryExtensionObjectPtr object,
		const FoundryExtensionConstTypePtr *arguments,
		FoundryExtensionTypePtr result) {
	fake_ref_ptrcall(method, object, arguments, result);
	if (reinterpret_cast<std::uintptr_t>(method) == 2 &&
			reentrant_transport != nullptr) {
		(void)reentrant_transport->track_object(
				45, 3, object, "Node", false);
		ref_cleanup_reentered = true;
	}
}

FoundryExtensionMethodBindPtr fake_ref_instantiate_method_bind(
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionInt hash) {
	if (hash == 4023243586) {
		return reinterpret_cast<FoundryExtensionMethodBindPtr>(10);
	}
	if (hash == 2240911060) {
		ref_method_lookup_count++;
		return reinterpret_cast<FoundryExtensionMethodBindPtr>(
				ref_method_lookup_count == 1 ? 11 : 12);
	}
	return nullptr;
}

void fake_ref_instantiate_ptrcall(
		FoundryExtensionMethodBindPtr method,
		FoundryExtensionObjectPtr,
		const FoundryExtensionConstTypePtr *,
		FoundryExtensionTypePtr result) {
	const auto index = reinterpret_cast<std::uintptr_t>(method);
	if (index == 10) {
		postinitialize_count++;
	} else if (index == 11) {
		ref_init_count++;
		*static_cast<FoundryExtensionBool *>(result) = 1;
	} else if (index == 12) {
		ref_unreference_count++;
		*static_cast<FoundryExtensionBool *>(result) = 1;
	}
}

FoundryExtensionVariantType fake_variant_get_type(FoundryExtensionConstVariantPtr value) {
	const auto raw = static_cast<const std::uint64_t *>(value)[0];
	return raw < FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX ?
			static_cast<FoundryExtensionVariantType>(raw) :
			copied_variant_type;
}

void fake_variant_new_copy(
		FoundryExtensionUninitializedVariantPtr destination,
		FoundryExtensionConstVariantPtr source) {
	variant_copy_count++;
	auto *target = static_cast<std::uint64_t *>(destination);
	const auto *origin = static_cast<const std::uint64_t *>(source);
	std::memcpy(target, origin, 24);
	if (origin[2] == fake_opaque_magic) {
		const auto *source_box = reinterpret_cast<const FakeOpaqueBox *>(origin[1]);
		auto *copy = new FakeOpaqueBox(*source_box);
		target[1] = reinterpret_cast<std::uint64_t>(copy);
	} else if (origin[2] == fake_callable_magic) {
		reinterpret_cast<FakeCallableBox *>(origin[1])->references++;
	} else if (origin[2] == fake_text_magic) {
		reinterpret_cast<FakeTextBox *>(origin[1])->references++;
	}
}

void release_fake_callable(FakeCallableBox *box) {
	if (box == nullptr || --box->references != 0) {
		return;
	}
	if (box->info.free_func != nullptr) {
		box->info.free_func(box->info.callable_userdata);
	}
	delete box;
}

void release_fake_text(FakeTextBox *box) {
	if (box != nullptr && --box->references == 0) {
		delete box;
	}
}

void fake_variant_destroy(FoundryExtensionVariantPtr value) {
	variant_destroy_count++;
	auto *words = static_cast<std::uint64_t *>(value);
	if (words[0] == FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE &&
			words[2] == fake_callable_magic) {
		release_fake_callable(reinterpret_cast<FakeCallableBox *>(words[1]));
		words[1] = 0;
	} else if ((words[0] == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING ||
					   words[0] == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME ||
					   words[0] == FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH) &&
			words[2] == fake_text_magic) {
		release_fake_text(reinterpret_cast<FakeTextBox *>(words[1]));
		words[1] = 0;
	} else if (words[2] == fake_opaque_magic) {
		delete reinterpret_cast<FakeOpaqueBox *>(words[1]);
		words[1] = 0;
	}
}

void fake_variant_new_nil(FoundryExtensionUninitializedVariantPtr destination) {
	nil_construct_count++;
	static_cast<std::uint64_t *>(destination)[0] = FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
}

void fake_variant_construct(
		FoundryExtensionVariantType type,
		FoundryExtensionUninitializedVariantPtr destination,
		const FoundryExtensionConstVariantPtr *arguments,
		int32_t argument_count,
		FoundryExtensionCallError *error) {
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL && argument_count == 2) {
		signal_construct_count++;
	} else if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_RID && argument_count == 0) {
		rid_default_construct_count++;
	}
	auto *words = static_cast<std::uint64_t *>(destination);
	words[0] = type;
	if ((type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING ||
				type == FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH) &&
			argument_count == 1) {
		const auto *source = static_cast<const std::uint64_t *>(arguments[0]);
		if (source[2] == fake_text_magic) {
			auto *box = reinterpret_cast<FakeTextBox *>(source[1]);
			box->references++;
			words[1] = source[1];
			words[2] = fake_text_magic;
		}
	}
	error->error = FOUNDRY_EXTENSION_CALL_OK;
}

template <FoundryExtensionVariantType Type>
void fake_opaque_variant_from_native(
		FoundryExtensionUninitializedVariantPtr destination,
		FoundryExtensionTypePtr source) {
	variant_construct_counts.at(Type)++;
	auto *words = static_cast<std::uint64_t *>(destination);
	words[0] = static_cast<std::uint64_t>(Type);
	const auto *category = foundry_java::variant_category(Type);
	auto *box = new FakeOpaqueBox;
	box->type = Type;
	box->bytes.resize(foundry_java::abi_layout_size(category->native_name));
	std::memcpy(box->bytes.data(), source, box->bytes.size());
	words[1] = reinterpret_cast<std::uint64_t>(box);
	words[2] = fake_opaque_magic;
}

template <FoundryExtensionVariantType Type>
void fake_variant_from_native(
		FoundryExtensionUninitializedVariantPtr destination,
		FoundryExtensionTypePtr source) {
	fake_opaque_variant_from_native<Type>(destination, source);
}

template <FoundryExtensionVariantType Type>
void fake_text_variant_from_native(
		FoundryExtensionUninitializedVariantPtr destination,
		FoundryExtensionTypePtr source) {
	variant_construct_counts.at(Type)++;
	auto *words = static_cast<std::uint64_t *>(destination);
	const auto text = fake_text_native_values.find(source);
	if (text == fake_text_native_values.end()) {
		fake_opaque_variant_from_native<Type>(destination, source);
		variant_construct_counts.at(Type)--;
		return;
	}
	auto *box = text->second;
	box->references++;
	words[0] = Type;
	words[1] = reinterpret_cast<std::uint64_t>(box);
	words[2] = fake_text_magic;
}

template <>
void fake_variant_from_native<FOUNDRY_EXTENSION_VARIANT_TYPE_STRING>(
		FoundryExtensionUninitializedVariantPtr destination,
		FoundryExtensionTypePtr source) {
	fake_text_variant_from_native<FOUNDRY_EXTENSION_VARIANT_TYPE_STRING>(destination, source);
}

template <>
void fake_variant_from_native<FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME>(
		FoundryExtensionUninitializedVariantPtr destination,
		FoundryExtensionTypePtr source) {
	fake_text_variant_from_native<FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME>(destination, source);
}

template <>
void fake_variant_from_native<FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE>(
		FoundryExtensionUninitializedVariantPtr destination,
		FoundryExtensionTypePtr source) {
	variant_construct_counts.at(FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE)++;
	auto *words = static_cast<std::uint64_t *>(destination);
	const auto callable = fake_callable_native_values.find(source);
	if (callable == fake_callable_native_values.end()) {
		fake_opaque_variant_from_native<FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE>(
				destination,
				source);
		variant_construct_counts.at(FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE)--;
		return;
	}
	auto *box = callable->second;
	words[0] = FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE;
	words[1] = reinterpret_cast<std::uint64_t>(box);
	box->references++;
	words[2] = fake_callable_magic;
}

FoundryExtensionVariantFromTypeConstructorFunc fake_get_variant_from_native(
		FoundryExtensionVariantType type) {
#define FOUNDRY_JAVA_FAKE_FROM_CASE(suffix) \
	case FOUNDRY_EXTENSION_VARIANT_TYPE_##suffix: \
		return &fake_variant_from_native<FOUNDRY_EXTENSION_VARIANT_TYPE_##suffix>
	switch (type) {
		FOUNDRY_JAVA_FAKE_FROM_CASE(BOOL);
		FOUNDRY_JAVA_FAKE_FROM_CASE(INT);
		FOUNDRY_JAVA_FAKE_FROM_CASE(FLOAT);
		FOUNDRY_JAVA_FAKE_FROM_CASE(STRING);
		FOUNDRY_JAVA_FAKE_FROM_CASE(VECTOR2);
		FOUNDRY_JAVA_FAKE_FROM_CASE(VECTOR2I);
		FOUNDRY_JAVA_FAKE_FROM_CASE(RECT2);
		FOUNDRY_JAVA_FAKE_FROM_CASE(RECT2I);
		FOUNDRY_JAVA_FAKE_FROM_CASE(VECTOR3);
		FOUNDRY_JAVA_FAKE_FROM_CASE(VECTOR3I);
		FOUNDRY_JAVA_FAKE_FROM_CASE(TRANSFORM2D);
		FOUNDRY_JAVA_FAKE_FROM_CASE(VECTOR4);
		FOUNDRY_JAVA_FAKE_FROM_CASE(VECTOR4I);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PLANE);
		FOUNDRY_JAVA_FAKE_FROM_CASE(QUATERNION);
		FOUNDRY_JAVA_FAKE_FROM_CASE(AABB);
		FOUNDRY_JAVA_FAKE_FROM_CASE(BASIS);
		FOUNDRY_JAVA_FAKE_FROM_CASE(TRANSFORM3D);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PROJECTION);
		FOUNDRY_JAVA_FAKE_FROM_CASE(COLOR);
		FOUNDRY_JAVA_FAKE_FROM_CASE(STRING_NAME);
		FOUNDRY_JAVA_FAKE_FROM_CASE(NODE_PATH);
		FOUNDRY_JAVA_FAKE_FROM_CASE(RID);
		FOUNDRY_JAVA_FAKE_FROM_CASE(OBJECT);
		FOUNDRY_JAVA_FAKE_FROM_CASE(CALLABLE);
		FOUNDRY_JAVA_FAKE_FROM_CASE(SIGNAL);
		FOUNDRY_JAVA_FAKE_FROM_CASE(DICTIONARY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_BYTE_ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_INT32_ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_INT64_ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_FLOAT32_ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_FLOAT64_ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_STRING_ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_VECTOR2_ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_VECTOR3_ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_COLOR_ARRAY);
		FOUNDRY_JAVA_FAKE_FROM_CASE(PACKED_VECTOR4_ARRAY);
		default:
			return nullptr;
	}
#undef FOUNDRY_JAVA_FAKE_FROM_CASE
}

template <FoundryExtensionVariantType Type>
void fake_opaque_from_variant(
		FoundryExtensionUninitializedTypePtr destination,
		FoundryExtensionVariantPtr source) {
	variant_inspect_counts.at(Type)++;
	const auto *words = static_cast<const std::uint64_t *>(source);
	if (words[2] == fake_opaque_magic) {
		const auto *box = reinterpret_cast<const FakeOpaqueBox *>(words[1]);
		typed_fixture_mismatch = typed_fixture_mismatch || box->type != Type;
		std::memcpy(destination, box->bytes.data(), box->bytes.size());
	} else {
		*static_cast<std::uint64_t *>(destination) = words[1];
	}
}

template <FoundryExtensionVariantType Type>
void fake_native_from_variant(
		FoundryExtensionUninitializedTypePtr destination,
		FoundryExtensionVariantPtr source) {
	fake_opaque_from_variant<Type>(destination, source);
}

template <FoundryExtensionVariantType Type>
void fake_text_from_variant(
		FoundryExtensionUninitializedTypePtr destination,
		FoundryExtensionVariantPtr source) {
	variant_inspect_counts.at(Type)++;
	const auto *words = static_cast<const std::uint64_t *>(source);
	if (words[2] != fake_text_magic) {
		fake_opaque_from_variant<Type>(destination, source);
		variant_inspect_counts.at(Type)--;
		return;
	}
	auto *box = reinterpret_cast<FakeTextBox *>(words[1]);
	box->references++;
	*static_cast<FakeTextBox **>(destination) = box;
	fake_text_native_values[destination] = box;
}

template <>
void fake_native_from_variant<FOUNDRY_EXTENSION_VARIANT_TYPE_STRING>(
		FoundryExtensionUninitializedTypePtr destination,
		FoundryExtensionVariantPtr source) {
	fake_text_from_variant<FOUNDRY_EXTENSION_VARIANT_TYPE_STRING>(destination, source);
}

template <>
void fake_native_from_variant<FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME>(
		FoundryExtensionUninitializedTypePtr destination,
		FoundryExtensionVariantPtr source) {
	fake_text_from_variant<FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME>(destination, source);
}

FoundryExtensionTypeFromVariantConstructorFunc fake_get_variant_to_native(
		FoundryExtensionVariantType type) {
#define FOUNDRY_JAVA_FAKE_TO_CASE(suffix) \
	case FOUNDRY_EXTENSION_VARIANT_TYPE_##suffix: \
		return &fake_native_from_variant<FOUNDRY_EXTENSION_VARIANT_TYPE_##suffix>
	switch (type) {
		FOUNDRY_JAVA_FAKE_TO_CASE(BOOL);
		FOUNDRY_JAVA_FAKE_TO_CASE(INT);
		FOUNDRY_JAVA_FAKE_TO_CASE(FLOAT);
		FOUNDRY_JAVA_FAKE_TO_CASE(STRING);
		FOUNDRY_JAVA_FAKE_TO_CASE(VECTOR2);
		FOUNDRY_JAVA_FAKE_TO_CASE(VECTOR2I);
		FOUNDRY_JAVA_FAKE_TO_CASE(RECT2);
		FOUNDRY_JAVA_FAKE_TO_CASE(RECT2I);
		FOUNDRY_JAVA_FAKE_TO_CASE(VECTOR3);
		FOUNDRY_JAVA_FAKE_TO_CASE(VECTOR3I);
		FOUNDRY_JAVA_FAKE_TO_CASE(TRANSFORM2D);
		FOUNDRY_JAVA_FAKE_TO_CASE(VECTOR4);
		FOUNDRY_JAVA_FAKE_TO_CASE(VECTOR4I);
		FOUNDRY_JAVA_FAKE_TO_CASE(PLANE);
		FOUNDRY_JAVA_FAKE_TO_CASE(QUATERNION);
		FOUNDRY_JAVA_FAKE_TO_CASE(AABB);
		FOUNDRY_JAVA_FAKE_TO_CASE(BASIS);
		FOUNDRY_JAVA_FAKE_TO_CASE(TRANSFORM3D);
		FOUNDRY_JAVA_FAKE_TO_CASE(PROJECTION);
		FOUNDRY_JAVA_FAKE_TO_CASE(COLOR);
		FOUNDRY_JAVA_FAKE_TO_CASE(STRING_NAME);
		FOUNDRY_JAVA_FAKE_TO_CASE(NODE_PATH);
		FOUNDRY_JAVA_FAKE_TO_CASE(RID);
		FOUNDRY_JAVA_FAKE_TO_CASE(OBJECT);
		FOUNDRY_JAVA_FAKE_TO_CASE(CALLABLE);
		FOUNDRY_JAVA_FAKE_TO_CASE(SIGNAL);
		FOUNDRY_JAVA_FAKE_TO_CASE(DICTIONARY);
		FOUNDRY_JAVA_FAKE_TO_CASE(ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_BYTE_ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_INT32_ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_INT64_ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_FLOAT32_ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_FLOAT64_ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_STRING_ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_VECTOR2_ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_VECTOR3_ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_COLOR_ARRAY);
		FOUNDRY_JAVA_FAKE_TO_CASE(PACKED_VECTOR4_ARRAY);
		default:
			return nullptr;
	}
#undef FOUNDRY_JAVA_FAKE_TO_CASE
}

void fake_native_value_destroy(FoundryExtensionTypePtr value) {
	const auto type = static_cast<FoundryExtensionVariantType>(
			*static_cast<std::uint64_t *>(value));
	native_value_destroy_counts.at(type)++;
}

void fake_callable_destroy(FoundryExtensionTypePtr value) {
	auto **slot = static_cast<FakeCallableBox **>(value);
	release_fake_callable(*slot);
	*slot = nullptr;
	fake_callable_native_values.erase(value);
	native_value_destroy_counts.at(FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE)++;
}

FoundryExtensionPtrDestructor fake_all_variant_destructors(FoundryExtensionVariantType type) {
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE) {
		return &fake_callable_destroy;
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING ||
			type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME) {
		return &fake_string_name_destroy;
	}
	return &fake_native_value_destroy;
}

FoundryExtensionInt fake_string_from_utf8(
		FoundryExtensionUninitializedStringPtr destination,
		const char *text,
		FoundryExtensionInt length) {
	auto *box = new FakeTextBox;
	box->text.assign(text, static_cast<std::size_t>(length));
	*static_cast<FakeTextBox **>(destination) = box;
	fake_text_native_values[destination] = box;
	return 0;
}

FoundryExtensionInt fake_string_to_utf8(
		FoundryExtensionConstStringPtr source,
		char *destination,
		FoundryExtensionInt capacity) {
	auto *box = *static_cast<FakeTextBox *const *>(source);
	if (destination == nullptr || capacity == 0) {
		return static_cast<FoundryExtensionInt>(box->text.size());
	}
	const auto length = std::min<std::size_t>(
			box->text.size(),
			static_cast<std::size_t>(capacity));
	std::memcpy(destination, box->text.data(), length);
	return static_cast<FoundryExtensionInt>(length);
}

void fake_callable_custom_create(
		FoundryExtensionUninitializedTypePtr destination,
		FoundryExtensionCallableCustomInfo2 *info) {
	callable_custom_create_count++;
	FoundryExtensionBool valid = 0;
	callable_reported_argument_count =
			info->get_argument_count_func(info->callable_userdata, &valid);
	if (!valid) {
		callable_reported_argument_count = -2;
	}
	auto *box = new FakeCallableBox;
	box->info = *info;
	callable_custom_infos.push_back(*info);
	*static_cast<FakeCallableBox **>(destination) = box;
	fake_callable_native_values[destination] = box;
}

FoundryExtensionMethodBindPtr fake_dispatch_method_bind(
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionInt hash) {
	return reinterpret_cast<FoundryExtensionMethodBindPtr>(
			hash == 4023243586 ? 0x99 : 0x44);
}

void fake_dispatch_method_call(
		FoundryExtensionMethodBindPtr,
		FoundryExtensionObjectPtr,
		const FoundryExtensionConstVariantPtr *,
		FoundryExtensionInt,
		FoundryExtensionUninitializedVariantPtr result,
		FoundryExtensionCallError *error) {
	method_bind_call_count++;
	static_cast<std::uint64_t *>(result)[0] = FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
	error->error = force_call_error ?
			FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD :
			FOUNDRY_EXTENSION_CALL_OK;
}

void fake_dispatch_method_ptrcall(
		FoundryExtensionMethodBindPtr method,
		FoundryExtensionObjectPtr,
		const FoundryExtensionConstTypePtr *,
		FoundryExtensionTypePtr) {
	if (method == reinterpret_cast<FoundryExtensionMethodBindPtr>(0x99)) {
		postinitialize_count++;
	} else {
		method_bind_ptrcall_count++;
	}
}

void fake_builtin_method(
		FoundryExtensionTypePtr,
		const FoundryExtensionConstTypePtr *arguments,
		FoundryExtensionTypePtr,
		int32_t argument_count) {
	builtin_method_count++;
	builtin_method_argument_values.clear();
	builtin_method_argument_pointers.clear();
	for (int32_t index = 0; index < argument_count; index++) {
		builtin_method_argument_pointers.push_back(arguments[index]);
		builtin_method_argument_values.push_back(
				*static_cast<const std::uint64_t *>(arguments[index]));
	}
}

FoundryExtensionPtrBuiltInMethod fake_get_builtin_method(
		FoundryExtensionVariantType,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionInt) {
	return &fake_builtin_method;
}

void fake_builtin_constructor(
		FoundryExtensionUninitializedTypePtr,
		const FoundryExtensionConstTypePtr *) {
	builtin_constructor_count++;
}

FoundryExtensionPtrConstructor fake_get_builtin_constructor(
		FoundryExtensionVariantType,
		int32_t) {
	return &fake_builtin_constructor;
}

void fake_member_getter(
		FoundryExtensionConstTypePtr,
		FoundryExtensionTypePtr) {
	member_get_count++;
}

FoundryExtensionPtrGetter fake_get_member_getter(
		FoundryExtensionVariantType,
		FoundryExtensionConstStringNamePtr) {
	return &fake_member_getter;
}

void fake_member_setter(
		FoundryExtensionTypePtr,
		FoundryExtensionConstTypePtr) {
	member_set_count++;
}

FoundryExtensionPtrSetter fake_get_member_setter(
		FoundryExtensionVariantType,
		FoundryExtensionConstStringNamePtr) {
	return &fake_member_setter;
}

void fake_variant_evaluate(
		FoundryExtensionVariantOperator,
		FoundryExtensionConstVariantPtr,
		FoundryExtensionConstVariantPtr,
		FoundryExtensionUninitializedVariantPtr,
		FoundryExtensionBool *valid) {
	operator_count++;
	*valid = 1;
}

void fake_variant_constant(
		FoundryExtensionVariantType,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionUninitializedVariantPtr) {
	constant_count++;
}

void fake_utility(
		FoundryExtensionTypePtr,
		const FoundryExtensionConstTypePtr *arguments,
		int32_t argument_count) {
	utility_count++;
	utility_argument_values.clear();
	utility_argument_pointers.clear();
	for (int32_t index = 0; index < argument_count; index++) {
		utility_argument_pointers.push_back(arguments[index]);
		utility_argument_values.push_back(
				*static_cast<const std::uint64_t *>(arguments[index]));
	}
}

FoundryExtensionPtrUtilityFunction fake_get_utility(
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionInt) {
	return &fake_utility;
}

void fake_named_get(
		FoundryExtensionConstVariantPtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionUninitializedVariantPtr,
		FoundryExtensionBool *valid) {
	named_get_count++;
	*valid = 1;
}

void fake_named_set(
		FoundryExtensionVariantPtr,
		FoundryExtensionConstStringNamePtr,
		FoundryExtensionConstVariantPtr,
		FoundryExtensionBool *valid) {
	named_set_count++;
	*valid = 1;
}

void fake_variant_call(
		FoundryExtensionVariantPtr callable,
		FoundryExtensionConstStringNamePtr,
		const FoundryExtensionConstVariantPtr *arguments,
		FoundryExtensionInt argument_count,
		FoundryExtensionUninitializedVariantPtr result,
		FoundryExtensionCallError *error) {
	callable_call_count++;
	auto *words = static_cast<std::uint64_t *>(callable);
	if (words[0] == FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE &&
			words[1] != 0 &&
			words[2] == fake_callable_magic) {
		auto *box = reinterpret_cast<FakeCallableBox *>(words[1]);
		box->info.call_func(
				box->info.callable_userdata,
				arguments,
				argument_count,
				result,
				error);
		return;
	}
	error->error = FOUNDRY_EXTENSION_CALL_OK;
}

void fake_keyed_get(
		FoundryExtensionConstVariantPtr,
		FoundryExtensionConstVariantPtr,
		FoundryExtensionUninitializedVariantPtr,
		FoundryExtensionBool *valid) {
	keyed_get_count++;
	*valid = !force_keyed_get_failure;
}

void fake_keyed_set(
		FoundryExtensionVariantPtr,
		FoundryExtensionConstVariantPtr,
		FoundryExtensionConstVariantPtr,
		FoundryExtensionBool *valid) {
	keyed_set_count++;
	*valid = 1;
}

void fake_indexed_get(
		FoundryExtensionConstVariantPtr,
		FoundryExtensionInt,
		FoundryExtensionUninitializedVariantPtr,
		FoundryExtensionBool *valid,
		FoundryExtensionBool *out_of_bounds) {
	indexed_get_count++;
	*valid = 1;
	*out_of_bounds = 0;
}

void fake_indexed_set(
		FoundryExtensionVariantPtr,
		FoundryExtensionInt,
		FoundryExtensionConstVariantPtr,
		FoundryExtensionBool *valid,
		FoundryExtensionBool *out_of_bounds) {
	indexed_set_count++;
	*valid = 1;
	*out_of_bounds = 0;
}

FoundryExtensionBool fake_iter_init(
		FoundryExtensionConstVariantPtr,
		FoundryExtensionUninitializedVariantPtr iterator,
		FoundryExtensionBool *valid) {
	iter_init_count++;
	*valid = 1;
	static_cast<std::uint64_t *>(iterator)[0] = FOUNDRY_EXTENSION_VARIANT_TYPE_INT;
	return 1;
}

FoundryExtensionBool fake_iter_next(
		FoundryExtensionConstVariantPtr,
		FoundryExtensionVariantPtr,
		FoundryExtensionBool *valid) {
	iter_next_count++;
	*valid = 1;
	return 0;
}

void fake_iter_get(
		FoundryExtensionConstVariantPtr,
		FoundryExtensionVariantPtr,
		FoundryExtensionUninitializedVariantPtr value,
		FoundryExtensionBool *valid) {
	iter_get_count++;
	*valid = 1;
	static_cast<std::uint64_t *>(value)[0] = FOUNDRY_EXTENSION_VARIANT_TYPE_INT;
}

FoundryExtensionObjectPtr fake_construct_object(FoundryExtensionConstStringNamePtr) {
	construct_object_count++;
	return reinterpret_cast<FoundryExtensionObjectPtr>(0x1234);
}

FoundryExtensionObjectPtr fake_global_singleton(FoundryExtensionConstStringNamePtr) {
	singleton_count++;
	return reinterpret_cast<FoundryExtensionObjectPtr>(0x1234);
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

void test_jni_transition_tickets_commit_and_release_loader_ownership_exactly() {
	using State = foundry_java::JniTransitionState;
	int loader_creations = 0;
	int loader_releases = 0;
	std::unordered_set<State::Token> owned_loaders;
	const auto create_loader = [&] {
		const State::Token loader = static_cast<State::Token>(++loader_creations);
		owned_loaders.insert(loader);
		return loader;
	};
	const auto release_loader = [&](State::Token loader) {
		expect(loader != 0, "released loader token must be nonzero");
		expect(
				owned_loaders.erase(loader) == 1,
				"every loader token must transfer and release exactly once");
		loader_releases++;
	};
	const auto current_loader = [](const State &state) {
		return state.pin_class_loader([](State::Token loader) { return loader; });
	};

	State state;
	const State::Token initial_loader = create_loader();
	expect(state.install(71, initial_loader), "JNI transition state must install exactly once");
	State::Token java_vm = 0;
	const State::Ticket first_bootstrap = state.reserve_bootstrap(java_vm);
	expect(
			first_bootstrap != 0 && java_vm == 71,
			"bootstrap reservation must authenticate the installed VM");
	const State::Token rejected_candidate = create_loader();
	State::Token previous_loader = 0;
	expect(
			!state.publish_bootstrap(
					first_bootstrap, nullptr, rejected_candidate, previous_loader),
			"bootstrap cannot commit a candidate loader without a runtime");
	expect(
			current_loader(state) == initial_loader,
			"failed bootstrap publish must preserve the installed loader");
	expect(
			!state.cancel_bootstrap(first_bootstrap + 1),
			"a stale bootstrap ticket cannot cancel its owner");
	expect(
			state.cancel_bootstrap(first_bootstrap),
			"the owning bootstrap ticket must cancel after validation failure");
	release_loader(rejected_candidate);

	auto callbacks = std::make_shared<RecordingCallbacks>();
	auto errors = std::make_shared<RecordingLogger>();
	auto runtime = std::make_shared<foundry_java::BridgeRuntime>(callbacks, errors);
	const State::Token committed_candidate = create_loader();
	const State::Ticket second_bootstrap = state.reserve_bootstrap(java_vm);
	expect(second_bootstrap != 0, "bootstrap must retry after its owner cancels");
	expect(
			state.reserve_shutdown(runtime) == 0,
			"shutdown cannot overlap an active bootstrap reservation");
	std::shared_ptr<foundry_java::BridgeRuntime> published_runtime = runtime;
	expect(
			state.publish_bootstrap(
					second_bootstrap,
					published_runtime,
					committed_candidate,
					previous_loader),
			"bootstrap publish must atomically commit runtime and candidate loader");
	expect(
			previous_loader == initial_loader &&
					current_loader(state) == committed_candidate &&
					state.runtime() == runtime && state.ready(),
			"published runtime and loader must become visible as one state transition");
	release_loader(previous_loader);
	expect(
			!state.cancel_bootstrap(second_bootstrap),
			"a consumed bootstrap ticket must be stale");

	std::shared_ptr<foundry_java::BridgeRuntime> shutdown_runtime;
	const State::Ticket failed_shutdown = state.reserve_shutdown(shutdown_runtime);
	expect(
			failed_shutdown != 0 && shutdown_runtime == runtime,
			"shutdown reservation must atomically snapshot the published runtime");
	expect(
			state.reserve_bootstrap(java_vm) == 0,
			"bootstrap cannot overlap a reserved runtime drain");
	State::Token shutdown_vm = 0;
	State::Token shutdown_loader = 0;
	expect(
			!state.finish_shutdown(
					failed_shutdown + 1,
					shutdown_runtime,
					shutdown_vm,
					shutdown_loader),
			"a stale shutdown ticket cannot clear runtime or loader state");
	expect(
			state.cancel_shutdown(failed_shutdown, shutdown_runtime) && state.ready(),
			"a failed drain must cancel without changing published ownership");

	const State::Ticket retry_shutdown = state.reserve_shutdown(shutdown_runtime);
	expect(retry_shutdown != 0, "shutdown must retry after a failed drain cancels");
	expect(
			state.finish_shutdown(
					retry_shutdown,
					shutdown_runtime,
					shutdown_vm,
					shutdown_loader),
			"the owning shutdown ticket must atomically clear runtime and loader");
	expect(
			shutdown_vm == 71 && shutdown_loader == committed_candidate &&
					state.runtime() == nullptr && current_loader(state) == 0,
			"shutdown must return the exact committed loader for JNI deletion");
	release_loader(shutdown_loader);
	state.clear_java_vm();
	expect(state.java_vm() == 0, "unloaded, drained JNI state must clear its VM");

	State null_runtime_state;
	const State::Token null_runtime_loader = create_loader();
	expect(
			null_runtime_state.install(72, null_runtime_loader),
			"null-runtime shutdown fixture must install");
	std::shared_ptr<foundry_java::BridgeRuntime> null_runtime;
	const State::Ticket null_shutdown =
			null_runtime_state.reserve_shutdown(null_runtime);
	expect(
			null_shutdown != 0 && null_runtime == nullptr,
			"shutdown must reserve even before a runtime is published");
	expect(
			null_runtime_state.reserve_bootstrap(java_vm) == 0,
			"null-runtime shutdown must exclude bootstrap through loader clearing");
	expect(
			null_runtime_state.finish_shutdown(
					null_shutdown,
					null_runtime,
					shutdown_vm,
					shutdown_loader),
			"null-runtime shutdown owner must clear the initial loader");
	release_loader(shutdown_loader);

	expect(
			loader_creations == 4 && loader_releases == 4 && owned_loaders.empty(),
			"every initial and candidate loader must have one exact release owner");
}

void test_context_identity_reentrancy_and_exception_containment() {
	auto callbacks = std::make_shared<RecordingCallbacks>();
	auto logger = std::make_shared<RecordingLogger>();
	foundry_java::BridgeRuntime runtime(callbacks, logger);
	callbacks->runtime = &runtime;

	expect(
			runtime.create_native_context() == 0,
			"producer context admission must reject before native services are installed");
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

void test_shutdown_waits_for_native_operations_then_tears_down_resources() {
	auto callbacks = std::make_shared<RecordingCallbacks>();
	auto errors = std::make_shared<RecordingLogger>();
	foundry_java::BridgeRuntime runtime(callbacks, errors);
	const auto context = runtime.create_context();
	std::atomic<bool> resources_torn_down = false;
	std::atomic<bool> shutdown_finished = false;
	runtime.set_context_teardown(
			[&callbacks, &resources_torn_down](foundry_java::ContextHandle torn_down_context, std::uint64_t generation) {
				expect(torn_down_context != 0 && generation != 0, "teardown must receive authenticated identity");
				expect(
						callbacks->last_context == torn_down_context &&
								callbacks->deinitialize_count == 1 &&
								callbacks->invalidate_count == 1,
						"Java cleanup must complete before final native resource teardown");
				resources_torn_down = true;
			});
	auto operation = runtime.acquire_operation(context);
	expect(operation && operation.generation() != 0, "live context must admit native operation");

	std::thread shutdown([&] {
		expect(
				runtime.shutdown_context(context, FOUNDRY_EXTENSION_INITIALIZATION_CORE),
				"context shutdown must succeed");
		shutdown_finished = true;
	});
	while (runtime.acquire_operation(context)) {
		std::this_thread::yield();
	}
	expect(!shutdown_finished && !resources_torn_down, "shutdown must drain the admitted operation first");
	operation = {};
	shutdown.join();
	expect(
			resources_torn_down && shutdown_finished,
			"resource teardown must run after operation drain and Java cleanup");
}

void test_native_operation_can_finish_on_a_different_thread() {
	auto callbacks = std::make_shared<RecordingCallbacks>();
	auto errors = std::make_shared<RecordingLogger>();
	foundry_java::BridgeRuntime runtime(callbacks, errors);
	const auto context = runtime.create_context();
	auto operation = runtime.acquire_operation(context);
	expect(static_cast<bool>(operation), "live context must admit transferable operation");
	std::thread finisher([lease = std::move(operation)]() mutable { lease = {}; });
	finisher.join();
	expect(
			runtime.shutdown_context(context, FOUNDRY_EXTENSION_INITIALIZATION_CORE),
			"cross-thread operation completion must drain without termination or stale ownership");
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

void test_handles_authenticate_themselves_retain_same_identity_and_release_without_type() {
	foundry_java::NativeHandleStore handles;
	int destroyed = 0;
	auto value = foundry_java::NativeValue::storage(sizeof(std::uint64_t));
	const auto handle = handles.insert(
			71,
			9,
			foundry_java::HandleKind::CALLABLE,
			"CALLABLE",
			std::move(value),
			true,
			[&destroyed](foundry_java::HandleRecord &) { destroyed++; });

	auto inspected = handles.inspect(handle, 71, 9);
	expect(
			inspected && inspected.record().kind == foundry_java::HandleKind::CALLABLE &&
					inspected.record().expected_type == "CALLABLE",
			"generic inspection must return the authenticated stored identity");
	inspected = {};
	expect(handles.retain(handle, 71, 9) == handle, "retain must preserve the exact handle identity");
	expect(handles.release(handle, 71, 9), "first generic release must decrement retention");
	expect(
			static_cast<bool>(handles.inspect(handle, 71, 9)),
			"retained handle must remain live after one release");
	expect(handles.release(handle, 71, 9), "final generic release must succeed without caller type");
	expect(!handles.inspect(handle, 71, 9) && destroyed == 1, "final release must destroy exactly once");
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
	const auto rejected = handles.insert(
			22,
			4,
			foundry_java::HandleKind::NATIVE_STRUCTURE,
			"PhysicsServer3DExtensionMotionResult",
			foundry_java::NativeValue::storage(128),
			true,
			[&](foundry_java::HandleRecord &) { destroy_count++; });
	expect(rejected == 0, "torn-down context generation must reject new handle admission");
	expect(destroy_count == 2, "rejected owned admission must destroy its value exactly once");

	const auto raced = handles.insert(
			23,
			5,
			foundry_java::HandleKind::VARIANT,
			"INTEGER",
			foundry_java::NativeValue::storage(24),
			true,
			[&](foundry_java::HandleRecord &) { destroy_count++; });
	std::atomic<bool> start = false;
	std::thread release([&] {
		while (!start) {
			std::this_thread::yield();
		}
		handles.release(
				raced,
				23,
				5,
				foundry_java::HandleKind::VARIANT,
				"INTEGER");
	});
	std::thread raced_teardown([&] {
		while (!start) {
			std::this_thread::yield();
		}
		handles.teardown(23, 5);
	});
	start = true;
	release.join();
	raced_teardown.join();
	expect(destroy_count == 3, "release-vs-teardown race must destroy owned storage exactly once");
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
	services->object_get_class_name = &fake_object_class_name;
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
	const auto pointer_qualified =
			transport.create_native_structure(31, 5, "const Glyph*");
	expect(pointer_qualified != 0, "pointer-qualified native structure token must normalize");
	expect(
			static_cast<bool>(transport.handles().acquire(
					pointer_qualified,
					31,
					5,
					foundry_java::HandleKind::NATIVE_STRUCTURE,
					"Glyph")),
			"pointer-qualified native structure must store the exact base native name");
	expect(
			transport.handles().release(
					pointer_qualified,
					31,
					5,
					foundry_java::HandleKind::NATIVE_STRUCTURE,
					"Glyph"),
			"normalized pointer-qualified native structure must release");

	native_object_destroy_count = 0;
	requested_object_id = 0;
	const auto object = transport.track_object(
			31,
			5,
			reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"Resource",
			true);
	expect(object != 0, "object transport must return an opaque instance-ID handle");
	const auto duplicate_owned = transport.track_object(
			31,
			5,
			reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"Resource",
			true);
	expect(
			duplicate_owned == object,
			"repeated owned tracking of one instance ID must reuse its canonical handle");
	expect(
			native_object_destroy_count == 0,
			"canonicalizing the same owned pointer must not destroy the live allocation");
	auto object_lease = transport.acquire_object(object, 31, 5, "Resource");
	expect(
			object_lease.object == reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"object lookup must reacquire the pointer from its instance ID");
	expect(requested_object_id == 91, "object lookup must use the stored unsigned instance ID");
	object_lease = {};
	expect(
			transport.handles().release(object, 31, 5, foundry_java::HandleKind::OBJECT, "Resource"),
			"owned object handle must release");
	expect(
			native_object_destroy_count == 1,
			"canonical owned object release must destroy the allocation exactly once");
}

void test_dispatch_families_and_ref_counted_ownership() {
	foundry_java::NativeDispatch dispatch;
	dispatch.kind = foundry_java::DispatchKind::CLASS_METHOD;
	dispatch.argument_native_types = { "Variant" };
	dispatch.return_native_type = "Variant";
	expect(
			foundry_java::dispatch_family(dispatch) == foundry_java::DispatchFamily::CLASS_VARIANT_CALL,
			"Variant-only class methods must use object_method_bind_call");
	dispatch.argument_native_types = { "Glyph*" };
	expect(
			foundry_java::dispatch_family(dispatch) == foundry_java::DispatchFamily::CLASS_PTRCALL,
			"native pointer/structure class methods must use object_method_bind_ptrcall");
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
	const auto borrowed_ref = transport.track_object(
			44,
			2,
			reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"Object",
			false);
	const auto ref_handle = transport.retain_ref_counted(
			44,
			2,
			reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"Resource");
	expect(
			ref_handle != 0 && ref_handle == borrowed_ref,
			"RefCounted ownership must promote a pre-canonicalized borrowed token");
	expect(ref_method_lookup_count == 2, "reference and unreference MethodBinds must resolve exactly once");
	expect(ref_hashes_valid, "reference MethodBinds must use compatibility hash 2240911060");
	expect(ref_reference_count == 1, "retain must invoke RefCounted.reference");
	expect(
			transport.release_handle(ref_handle, 44, 2),
			"retained RefCounted handle must release");
	expect(ref_unreference_count == 1, "release must invoke RefCounted.unreference exactly once");
	expect(native_object_destroy_count == 1, "true unreference result must destroy the object exactly once");

	auto failing_services = std::make_shared<foundry_java::BridgeServices>(*services);
	failing_services->object_method_bind_ptrcall = &fake_ref_reentrant_ptrcall;
	foundry_java::NativeTransport failing_transport(failing_services);
	(void)failing_transport.handles().teardown(45, 3);
	ref_method_lookup_count = 0;
	ref_reference_count = 0;
	ref_unreference_count = 0;
	native_object_destroy_count = 0;
	ref_cleanup_reentered = false;
	reentrant_transport = &failing_transport;
	bool ownership_consumed = false;

	const auto failed = failing_transport.retain_ref_counted(
			45,
			3,
			reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"Resource",
			false,
			&ownership_consumed);

	reentrant_transport = nullptr;
	expect(failed == 0 && ownership_consumed, "closed-generation insertion must consume cleanup");
	expect(ref_reference_count == 1, "failed insertion must acquire one native reference");
	expect(ref_unreference_count == 1, "failed insertion must unreference exactly once");
	expect(native_object_destroy_count == 1, "failed insertion must destroy at most once");
	expect(ref_cleanup_reentered, "failure cleanup must permit reentrant object tracking");
}

void test_ref_counted_instantiation_initializes_and_unreferences() {
	auto services = std::make_shared<foundry_java::BridgeServices>();
	services->string_name_new_with_utf8_chars_and_len = &fake_string_name_from_utf8_and_len;
	services->variant_get_ptr_destructor = &fake_transport_variant_destructor;
	services->classdb_construct_object2 = &fake_construct_object;
	services->classdb_get_class_tag = &fake_ref_counted_class_tag;
	services->object_cast_to = &fake_object_cast_to;
	services->classdb_get_method_bind = &fake_ref_instantiate_method_bind;
	services->object_method_bind_ptrcall = &fake_ref_instantiate_ptrcall;
	services->object_get_instance_id = &fake_object_instance_id;
	services->object_get_instance_from_id = &fake_object_from_id;
	services->object_destroy = &fake_object_destroy;
	foundry_java::NativeTransport transport(services);
	construct_object_count = 0;
	postinitialize_count = 0;
	ref_method_lookup_count = 0;
	ref_init_count = 0;
	ref_unreference_count = 0;
	native_object_destroy_count = 0;

	const auto resource = transport.instantiate(45, 3, "Resource");

	expect(resource != 0, "RefCounted construction must return an adopted object token");
	expect(construct_object_count == 1, "RefCounted construction must construct once");
	expect(postinitialize_count == 1, "RefCounted construction must postinitialize once");
	expect(ref_init_count == 1, "fresh RefCounted construction must call init_ref exactly once");
	expect(
			transport.release_handle(resource, 45, 3),
			"adopted RefCounted token must release");
	expect(ref_unreference_count == 1, "adopted RefCounted token must unreference exactly once");
	expect(
			native_object_destroy_count == 1,
			"true RefCounted unreference result must destroy exactly once");
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
		static_cast<std::uint64_t *>(static_cast<void *>(source_storage))[0] =
				category.abi_type;
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

void test_category_specific_conversion_and_executable_dispatch() {
	auto services = std::make_shared<foundry_java::BridgeServices>();
	services->variant_get_type = &fake_variant_get_type;
	services->variant_new_copy = &fake_variant_new_copy;
	services->variant_new_nil = &fake_variant_new_nil;
	services->variant_construct = &fake_variant_construct;
	services->variant_destroy = &fake_variant_destroy;
	services->get_variant_from_type_constructor = &fake_get_variant_from_native;
	services->get_variant_to_type_constructor = &fake_get_variant_to_native;
	services->variant_get_ptr_destructor = &fake_all_variant_destructors;
	services->string_new_with_utf8_chars_and_len2 = &fake_string_from_utf8;
	services->string_to_utf8_chars = &fake_string_to_utf8;
	services->string_name_new_with_utf8_chars_and_len = &fake_string_name_from_utf8_and_len;
	services->classdb_get_method_bind = &fake_dispatch_method_bind;
	services->object_method_bind_call = &fake_dispatch_method_call;
	services->object_method_bind_ptrcall = &fake_dispatch_method_ptrcall;
	services->variant_get_ptr_builtin_method = &fake_get_builtin_method;
	services->variant_get_ptr_constructor = &fake_get_builtin_constructor;
	services->variant_get_ptr_getter = &fake_get_member_getter;
	services->variant_get_ptr_setter = &fake_get_member_setter;
	services->variant_evaluate = &fake_variant_evaluate;
	services->variant_get_constant_value = &fake_variant_constant;
	services->variant_get_ptr_utility_function = &fake_get_utility;
	services->variant_get_named = &fake_named_get;
	services->variant_set_named = &fake_named_set;
	services->variant_get_keyed = &fake_keyed_get;
	services->variant_set_keyed = &fake_keyed_set;
	services->variant_get_indexed = &fake_indexed_get;
	services->variant_set_indexed = &fake_indexed_set;
	services->variant_iter_init = &fake_iter_init;
	services->variant_iter_next = &fake_iter_next;
	services->variant_iter_get = &fake_iter_get;
	services->variant_call = &fake_variant_call;
	services->callable_custom_create2 = &fake_callable_custom_create;
	services->classdb_construct_object2 = &fake_construct_object;
	services->global_get_singleton = &fake_global_singleton;
	services->object_get_instance_id = &fake_object_instance_id;
	services->object_get_instance_from_id = &fake_object_from_id;
	services->object_get_class_name = &fake_object_class_name;
	services->object_destroy = &fake_object_destroy;
	foundry_java::NativeTransport transport(services);

	variant_construct_counts.fill(0);
	variant_inspect_counts.fill(0);
	variant_destroy_count = 0;
	typed_fixture_mismatch = false;
	for (const auto &category : foundry_java::variant_categories()) {
		const std::size_t byte_size = foundry_java::abi_layout_size(category.native_name);
		foundry_java::NativeValue source = foundry_java::NativeValue::storage(
				std::max<std::size_t>(sizeof(std::uint64_t), byte_size));
		auto *source_bytes = static_cast<std::byte *>(source.data());
		for (std::size_t index = 0; index < byte_size; index++) {
			source_bytes[index] = static_cast<std::byte>(
					(static_cast<unsigned>(category.abi_type) * 17 + index) & 0xff);
		}
		const auto handle = transport.construct_variant(
				81,
				12,
				category.abi_type,
				category.abi_type == FOUNDRY_EXTENSION_VARIANT_TYPE_NIL ?
						nullptr :
						source.data());
		expect(handle != 0, "each Variant category must use a real construction route");
		foundry_java::NativeValue decoded = foundry_java::NativeValue::storage(
				std::max<std::size_t>(sizeof(std::uint64_t), byte_size));
		const auto inspected = transport.inspect_variant(
				handle,
				81,
				12,
				category.abi_type,
				decoded.data());
		expect(inspected.ok, "each Variant category must use a real inspection route");
		if (category.abi_type != FOUNDRY_EXTENSION_VARIANT_TYPE_NIL) {
			expect(
					std::memcmp(source.data(), decoded.data(), byte_size) == 0,
					"category-specific conversion must preserve every exact native-layout byte");
			expect(
					variant_construct_counts.at(category.abi_type) == 1,
					"category-specific constructor must execute exactly once");
			expect(
					variant_inspect_counts.at(category.abi_type) == 1,
					"category-specific inspector must execute exactly once");
		}
		expect(
				transport.handles().release(
						handle,
						81,
						12,
						foundry_java::HandleKind::VARIANT,
						std::string(category.java_name)),
				"constructed Variant must release");
	}
	expect(!typed_fixture_mismatch, "every category must cross its exact typed trampoline");
	expect(variant_destroy_count == 39, "every constructed Variant must destroy exactly once");
	for (const auto text_type : {
				 FOUNDRY_EXTENSION_VARIANT_TYPE_STRING,
				 FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME,
				 FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH,
		 }) {
		const auto text_handle =
				transport.construct_text_variant(81, 12, text_type, "café/Node");
		std::string decoded_text;
		expect(text_handle != 0, "text category must construct from UTF-8");
		expect(
				transport.inspect_text_variant(
						text_handle,
						81,
						12,
						text_type,
						decoded_text)
						.ok &&
						decoded_text == "café/Node",
				"text category must inspect back to the exact UTF-8 bytes");
		const auto *category = foundry_java::variant_category(text_type);
		expect(
				transport.handles().release(
						text_handle,
						81,
						12,
						foundry_java::HandleKind::VARIANT,
						std::string(category->java_name)),
				"text category Variant must release exactly once");
	}
	std::uint64_t local_native_value = 7;
	signal_construct_count = rid_default_construct_count = 0;
	expect(
			transport.construct_variant(
					81,
					12,
					FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL,
					&local_native_value,
					foundry_java::ValueBackend::JAVA_LOCAL) == 0,
			"Java-local Signal must reject without touching a native constructor");
	expect(signal_construct_count == 0, "Java-local Signal rejection must be zero-service");
	expect(
			transport.construct_variant(
					81,
					12,
					FOUNDRY_EXTENSION_VARIANT_TYPE_RID,
					&local_native_value,
					foundry_java::ValueBackend::JAVA_LOCAL) == 0,
			"nonzero Java-local RID must reject undocumented raw layout");
	local_native_value = 0;
	const auto local_zero_rid = transport.construct_variant(
			81,
			12,
			FOUNDRY_EXTENSION_VARIANT_TYPE_RID,
			&local_native_value,
			foundry_java::ValueBackend::JAVA_LOCAL);
	expect(local_zero_rid != 0 && rid_default_construct_count == 1, "local zero RID must use default construction");
	expect(
			transport.handles().release(
					local_zero_rid,
					81,
					12,
					foundry_java::HandleKind::VARIANT,
					"RID"),
			"default RID must release");
	foundry_java::NativeValue native_rid_payload =
			foundry_java::NativeValue::storage(foundry_java::abi_layout_size("RID"));
	std::memset(native_rid_payload.data(), 0x3c, native_rid_payload.byte_size);
	const auto native_rid = transport.construct_variant(
			81,
			12,
			FOUNDRY_EXTENSION_VARIANT_TYPE_RID,
			native_rid_payload.data());
	const auto copied_rid = transport.copy_native_backed_variant(
			81,
			12,
			native_rid,
			FOUNDRY_EXTENSION_VARIANT_TYPE_RID);
	expect(
			native_rid != 0 && copied_rid != 0 &&
					transport.handles().release(
							native_rid,
							81,
							12,
							foundry_java::HandleKind::VARIANT,
							"RID"),
			"native-backed nonzero RID must copy before original release");
	foundry_java::NativeValue decoded_rid =
			foundry_java::NativeValue::storage(foundry_java::abi_layout_size("RID"));
	expect(
			transport.inspect_variant(
					copied_rid,
					81,
					12,
					FOUNDRY_EXTENSION_VARIANT_TYPE_RID,
					decoded_rid.data())
					.ok &&
					std::memcmp(
							native_rid_payload.data(),
							decoded_rid.data(),
							native_rid_payload.byte_size) == 0,
			"native-backed RID copy must preserve nonzero payload after original release");
	expect(
			transport.handles().release(
					copied_rid,
					81,
					12,
					foundry_java::HandleKind::VARIANT,
					"RID"),
			"native-backed RID copy must release independently");
	expect(
			foundry_java::normalize_native_type("enum::Side").abi_type ==
					FOUNDRY_EXTENSION_VARIANT_TYPE_INT,
			"enum token must normalize to raw int64");
	expect(
			foundry_java::normalize_native_type("typedarray::Node").abi_type ==
					FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY,
			"typed array token must normalize to Array");
	expect(
			foundry_java::normalize_native_type("Node").kind ==
					foundry_java::NativeTypeKind::OBJECT,
			"engine class token must normalize to OBJECT");

	std::max_align_t variant_storage[4]{};
	std::max_align_t native_storage[4]{};
	foundry_java::DispatchCall call;
	call.object = reinterpret_cast<FoundryExtensionObjectPtr>(0x1234);
	call.receiver_variant = variant_storage;
	call.receiver_native = native_storage;
	call.variant_arguments = { variant_storage };
	call.native_arguments = { native_storage };
	call.variant_result = variant_storage;
	call.native_result = native_storage;

	foundry_java::NativeDispatch dispatch;
	dispatch.identity = "Node.call";
	dispatch.kind = foundry_java::DispatchKind::CLASS_METHOD;
	dispatch.owner_native_type = "Node";
	dispatch.native_name = "call";
	dispatch.compatibility_hash = 1;
	dispatch.argument_native_types = { "Variant" };
	dispatch.minimum_argument_count = 1;
	dispatch.return_native_type = "Variant";
	expect(transport.execute(dispatch, call).ok, "Variant MethodBind route must execute");

	dispatch.identity = "Node.get_index";
	dispatch.native_name = "get_index";
	dispatch.argument_native_types = { "Glyph*" };
	dispatch.return_native_type = "void";
	expect(transport.execute(dispatch, call).ok, "ptrcall MethodBind route must execute");
	dispatch.argument_native_types = { "int", "int" };
	dispatch.minimum_argument_count = 1;
	dispatch.return_native_type = "int";
	call.variant_arguments = { variant_storage };
	expect(
			transport.execute(dispatch, call).ok,
			"omitted optional arguments must execute through Variant MethodBind call");

	dispatch.kind = foundry_java::DispatchKind::CLASS_PROPERTY;
	dispatch.native_name = "position";
	dispatch.argument_native_types = { "Vector2" };
	dispatch.minimum_argument_count = 0;
	dispatch.getter_identity = "Node.position:get";
	dispatch.getter_native_name = "get_position";
	dispatch.getter_compatibility_hash = 1;
	dispatch.setter_identity = "Node.position:set";
	dispatch.setter_native_name = "set_position";
	dispatch.setter_compatibility_hash = 2;
	call.property_set = false;
	expect(transport.execute(dispatch, call).ok, "named property getter must execute");
	call.property_set = true;
	expect(transport.execute(dispatch, call).ok, "named property setter must execute");

	dispatch.kind = foundry_java::DispatchKind::CLASS_SIGNAL;
	dispatch.native_name = "changed";
	dispatch.argument_native_types.clear();
	dispatch.minimum_argument_count = 0;
	call.property_set = false;
	call.native_arguments.clear();
	signal_construct_count = 0;
	expect(transport.execute(dispatch, call).ok, "class Signal must construct from Object and StringName");
	expect(signal_construct_count == 1, "class Signal must use variant_construct exactly once");

	dispatch.kind = foundry_java::DispatchKind::BUILTIN_METHOD;
	dispatch.owner_native_type = "Vector2";
	dispatch.native_name = "length";
	dispatch.argument_native_types.clear();
	dispatch.minimum_argument_count = 0;
	dispatch.return_native_type = "float";
	call.receiver_native_type = "Vector2";
	call.variant_arguments.clear();
	call.native_arguments.clear();
	expect(transport.execute(dispatch, call).ok, "built-in method route must execute");

	dispatch.kind = foundry_java::DispatchKind::BUILTIN_CONSTRUCTOR;
	dispatch.constructor_index = 0;
	expect(transport.execute(dispatch, call).ok, "built-in constructor route must execute");

	dispatch.kind = foundry_java::DispatchKind::BUILTIN_OPERATOR;
	call.variant_operator = FOUNDRY_EXTENSION_VARIANT_OP_ADD;
	dispatch.argument_native_types = { "Vector2" };
	dispatch.minimum_argument_count = 1;
	call.variant_arguments = { variant_storage };
	expect(transport.execute(dispatch, call).ok, "operator route must execute");
	call.variant_operator = FOUNDRY_EXTENSION_VARIANT_OP_NEGATE;
	dispatch.argument_native_types.clear();
	dispatch.minimum_argument_count = 0;
	call.variant_arguments.clear();
	nil_construct_count = 0;
	expect(transport.execute(dispatch, call).ok, "unary operator route must execute");
	expect(nil_construct_count == 1, "unary operator must construct a real Nil RHS");

	dispatch.kind = foundry_java::DispatchKind::BUILTIN_MEMBER;
	dispatch.native_name = "x";
	call.property_set = false;
	expect(transport.execute(dispatch, call).ok, "built-in member getter must execute");
	call.property_set = true;
	dispatch.argument_native_types = { "float" };
	call.native_arguments = { native_storage };
	expect(
			!transport.execute(dispatch, call).ok,
			"built-in member setter must reject because frozen member dispatch is getter-only");

	dispatch.kind = foundry_java::DispatchKind::BUILTIN_CONSTANT;
	dispatch.native_name = "ZERO";
	dispatch.argument_native_types.clear();
	call.native_arguments.clear();
	expect(transport.execute(dispatch, call).ok, "built-in constant route must execute");

	dispatch.kind = foundry_java::DispatchKind::UTILITY_FUNCTION;
	dispatch.native_name = "snapped";
	expect(transport.execute(dispatch, call).ok, "utility route must execute");

	expect(method_bind_call_count == 2, "Variant MethodBind must cover direct and defaulted calls");
	expect(method_bind_ptrcall_count == 1, "ptrcall MethodBind must call once");
	expect(named_get_count == 1 && named_set_count == 1, "property accessors must both execute");
	expect(builtin_method_count == 1, "built-in method must execute once");
	expect(builtin_constructor_count == 1, "built-in constructor must execute once");
	expect(operator_count == 2, "binary and unary operators must each execute once");
	expect(member_get_count == 1 && member_set_count == 0, "member route must be getter-only");
	expect(constant_count == 1, "constant route must execute once");
	expect(utility_count == 1, "utility route must execute once");
	auto *saved_variant_result = call.variant_result;
	call.variant_result = nullptr;
	dispatch.kind = foundry_java::DispatchKind::CLASS_METHOD;
	dispatch.owner_native_type = "Node";
	dispatch.native_name = "consume";
	dispatch.argument_native_types = { "Glyph*" };
	dispatch.minimum_argument_count = 1;
	dispatch.return_native_type = "void";
	call.native_arguments = { native_storage };
	const int ptrcall_before_invalid_void = method_bind_ptrcall_count;
	expect(
			!transport.execute(dispatch, call).ok &&
					method_bind_ptrcall_count == ptrcall_before_invalid_void,
			"missing void Variant result must reject before MethodBind ptrcall side effects");
	dispatch.kind = foundry_java::DispatchKind::BUILTIN_METHOD;
	dispatch.owner_native_type = "Vector2";
	dispatch.native_name = "void_method";
	dispatch.argument_native_types.clear();
	dispatch.minimum_argument_count = 0;
	call.native_arguments.clear();
	const int builtin_before_invalid_void = builtin_method_count;
	expect(
			!transport.execute(dispatch, call).ok &&
					builtin_method_count == builtin_before_invalid_void,
			"missing void Variant result must reject before built-in side effects");
	dispatch.kind = foundry_java::DispatchKind::UTILITY_FUNCTION;
	dispatch.native_name = "void_utility";
	const int utility_before_invalid_void = utility_count;
	expect(
			!transport.execute(dispatch, call).ok &&
					utility_count == utility_before_invalid_void,
			"missing void Variant result must reject before utility side effects");
	call.variant_result = saved_variant_result;

	dispatch.kind = foundry_java::DispatchKind::CLASS_METHOD;
	dispatch.owner_native_type = "Node";
	dispatch.native_name = "call";
	dispatch.compatibility_hash = 1;
	dispatch.argument_native_types = { "Variant" };
	dispatch.minimum_argument_count = 1;
	dispatch.return_native_type = "Variant";
	call.object = reinterpret_cast<FoundryExtensionObjectPtr>(0x1234);
	call.variant_arguments = { variant_storage };
	const int destroy_before_call_failure = variant_destroy_count;
	force_call_error = true;
	expect(!transport.execute(dispatch, call).ok, "MethodBind call failure must be contained");
	force_call_error = false;
	expect(
			variant_destroy_count == destroy_before_call_failure + 1,
			"failed MethodBind call must destroy its placement-constructed result");

	callable_call_count = 0;
	expect(
			transport.invoke_callable(variant_storage, {}, variant_storage).ok,
			"generic Callable invocation must execute through variant_call");
	expect(callable_call_count == 1, "generic Callable must execute exactly once");
	callable_custom_create_count = 0;
	callable_custom_infos.clear();
	int local_callable_calls = 0;
	auto callable_lifetime = std::make_shared<int>(9);
	std::weak_ptr<int> callable_lifetime_probe = callable_lifetime;
	const auto local_callable = transport.construct_local_callable(
			81,
			12,
			[&local_callable_calls, callable_lifetime](
					const FoundryExtensionConstVariantPtr *,
					FoundryExtensionInt,
					FoundryExtensionVariantPtr,
					FoundryExtensionCallError *error) {
				local_callable_calls += *callable_lifetime;
				error->error = FOUNDRY_EXTENSION_CALL_OK;
			},
			0x777,
			0);
	callable_lifetime.reset();
	expect(local_callable != 0 && callable_custom_create_count == 1, "local Callable must use custom_create2");
	expect(callable_reported_argument_count == 0, "local Callable must preserve fixed arity");
	{
		auto lease = transport.handles().acquire(
				local_callable,
				81,
				12,
				foundry_java::HandleKind::VARIANT,
				"CALLABLE");
		expect(static_cast<bool>(lease), "local Callable Variant must be acquirable");
		expect(
				transport
						.invoke_callable(
								const_cast<void *>(lease.record().value.data()), {}, variant_storage)
						.ok,
				"local Callable must round-trip through generic invocation");
	}
	expect(local_callable_calls == 9, "local Callable callback must execute with live userdata");
	const auto copied_callable = transport.copy_native_backed_variant(
			81,
			12,
			local_callable,
			FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE);
	expect(copied_callable != 0, "native-backed Callable must copy for decode/re-encode");
	expect(
			transport.handles().release(
					local_callable,
					81,
					12,
					foundry_java::HandleKind::VARIANT,
					"CALLABLE"),
			"original local Callable must release");
	expect(
			!callable_lifetime_probe.expired(),
			"Callable userdata must survive while a native-backed copy remains");
	{
		auto lease = transport.handles().acquire(
				copied_callable,
				81,
				12,
				foundry_java::HandleKind::VARIANT,
				"CALLABLE");
		expect(
				lease &&
						transport.invoke_callable(
								const_cast<void *>(lease.record().value.data()),
								{},
								variant_storage)
								.ok,
				"native-backed Callable copy must remain invokable after original release");
	}
	expect(local_callable_calls == 18, "Callable copy must preserve the same callback userdata");
	expect(
			transport.handles().release(
					copied_callable,
					81,
					12,
					foundry_java::HandleKind::VARIANT,
					"CALLABLE"),
			"native-backed Callable copy must release");
	expect(callable_lifetime_probe.expired(), "custom Callable userdata must free exactly at final release");
	const auto same_identity_a =
			transport.construct_local_callable(81, 12, [](auto...) {}, 0x12345678, -1);
	const auto same_identity_b =
			transport.construct_local_callable(81, 12, [](auto...) {}, 0x12345678, -1);
	const auto distinct_identity =
			transport.construct_local_callable(81, 12, [](auto...) {}, 0x12345679, -1);
	expect(
			same_identity_a != 0 && same_identity_b != 0 && distinct_identity != 0 &&
					callable_custom_infos.size() >= 4,
			"local Callable identity fixtures must construct");
	const auto &identity_a = callable_custom_infos[callable_custom_infos.size() - 3];
	const auto &identity_b = callable_custom_infos[callable_custom_infos.size() - 2];
	const auto &identity_other = callable_custom_infos.back();
	expect(
			identity_a.token != nullptr && identity_a.token == identity_b.token &&
					identity_a.hash_func(identity_a.callable_userdata) ==
							identity_b.hash_func(identity_b.callable_userdata) &&
					identity_a.equal_func(
							identity_a.callable_userdata, identity_b.callable_userdata) &&
					!identity_a.equal_func(
							identity_a.callable_userdata, identity_other.callable_userdata),
			"local Callable equality must use stable Java identity under one extension token");
	expect(
			transport.release_handle(same_identity_a, 81, 12) &&
					transport.release_handle(same_identity_b, 81, 12) &&
					transport.release_handle(distinct_identity, 81, 12),
			"local Callable identity fixtures must release");

	keyed_get_count = keyed_set_count = indexed_get_count = indexed_set_count = 0;
	iter_init_count = iter_next_count = iter_get_count = 0;
	expect(
			transport.collection_get_keyed(variant_storage, variant_storage, variant_storage).ok,
			"Dictionary keyed get must execute");
	expect(
			transport.collection_set_keyed(variant_storage, variant_storage, variant_storage).ok,
			"Dictionary keyed set must execute");
	expect(
			transport.collection_get_indexed(variant_storage, 0, variant_storage).ok,
			"Array/packed indexed get must execute");
	expect(
			transport.collection_set_indexed(variant_storage, 0, variant_storage).ok,
			"Array/packed indexed set must execute");
	int visited = 0;
	copied_variant_type = FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY;
	static_cast<std::uint64_t *>(static_cast<void *>(variant_storage))[0] =
			FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY;
	expect(
			transport.collection_iterate(
					variant_storage,
					FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY,
					[&visited](
							FoundryExtensionConstVariantPtr,
							FoundryExtensionConstVariantPtr) {
						visited++;
						return true;
					})
					.ok,
			"Dictionary/Array iteration must execute");
	expect(
			keyed_get_count == 2 && keyed_set_count == 1 &&
					indexed_get_count == 1 && indexed_set_count == 1,
			"keyed and indexed routes must each execute once");
	expect(
			iter_init_count == 1 && iter_get_count == 1 && iter_next_count == 1 && visited == 1,
			"iteration must construct, read, advance, and visit exactly once");
	const int destroy_before_key_failure = variant_destroy_count;
	force_keyed_get_failure = true;
	expect(
			!transport.collection_get_keyed(
					   variant_storage,
					   variant_storage,
					   variant_storage)
					 .ok,
			"invalid keyed get must fail");
	force_keyed_get_failure = false;
	expect(
			variant_destroy_count == destroy_before_key_failure + 1,
			"invalid keyed get must destroy its placement-constructed result");
	const int destroy_before_visitor_throw = variant_destroy_count;
	copied_variant_type = FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY;
	static_cast<std::uint64_t *>(static_cast<void *>(variant_storage))[0] =
			FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY;
	expect(
			!transport.collection_iterate(
					   variant_storage,
					   FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY,
					   [](FoundryExtensionConstVariantPtr,
							   FoundryExtensionConstVariantPtr) -> bool {
						   throw std::runtime_error("visitor");
					   })
					 .ok,
			"throwing collection visitor must be contained");
	expect(
			variant_destroy_count == destroy_before_visitor_throw + 3,
			"throwing Dictionary visitor must destroy iterator, key, and value exactly once");

	std::uint64_t collection_payload = 55;
	const auto collection_value = transport.construct_variant(
			81,
			12,
			FOUNDRY_EXTENSION_VARIANT_TYPE_INT,
			&collection_payload);
	expect(collection_value != 0, "collection element Variant must construct");
	const auto dictionary = transport.construct_collection(
			81,
			12,
			FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY,
			{ collection_value },
			{ collection_value });
	const auto array = transport.construct_collection(
			81,
			12,
			FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY,
			{},
			{ collection_value });
	const auto packed = transport.construct_collection(
			81,
			12,
			FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY,
			{},
			{ collection_value });
	expect(
			dictionary != 0 && array != 0 && packed != 0,
			"Dictionary, Array, and packed collections must construct through recursive ABI routes");
	expect(
			transport.handles().release(
					dictionary,
					81,
					12,
					foundry_java::HandleKind::VARIANT,
					"DICTIONARY") &&
					transport.handles().release(
							array,
							81,
							12,
							foundry_java::HandleKind::VARIANT,
							"ARRAY") &&
					transport.handles().release(
							packed,
							81,
							12,
							foundry_java::HandleKind::VARIANT,
							"PACKED_INT64_ARRAY") &&
					transport.handles().release(
							collection_value,
							81,
							12,
							foundry_java::HandleKind::VARIANT,
							"INTEGER"),
			"recursive collection handles must release exactly once");

	const auto borrowed_object = transport.track_object(
			81,
			12,
			reinterpret_cast<FoundryExtensionObjectPtr>(0x1234),
			"Node",
			false);
	const auto object_variant =
			transport.construct_object_variant(81, 12, borrowed_object, "Node");
	std::uint64_t decoded_object_id = 0;
	std::string decoded_object_type;
	expect(
			object_variant != 0 &&
					transport.inspect_object_instance_id(
							object_variant,
							81,
							12,
							decoded_object_id)
							.ok &&
					decoded_object_id == 91 &&
					transport.object_type(
							borrowed_object,
							81,
							12,
							"Node",
							reinterpret_cast<FoundryExtensionClassLibraryPtr>(0x88),
							decoded_object_type)
							.ok &&
					decoded_object_type == "Node",
			"Object Variant must round-trip through an instance-ID-backed object handle");
	expect(
			transport.handles().release(
					object_variant,
					81,
					12,
					foundry_java::HandleKind::VARIANT,
					"OBJECT") &&
					transport.handles().release(
							borrowed_object,
							81,
							12,
							foundry_java::HandleKind::OBJECT,
							"Node"),
			"Object Variant and borrowed object handles must release");

	foundry_java::NativeValue native_signal_payload = foundry_java::NativeValue::storage(
			foundry_java::abi_layout_size("Signal"));
	std::memset(native_signal_payload.data(), 0x66, native_signal_payload.byte_size);
	const auto native_signal = transport.construct_variant(
			81,
			12,
			FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL,
			native_signal_payload.data());
	const auto copied_signal = transport.copy_native_backed_variant(
			81,
			12,
			native_signal,
			FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL);
	expect(copied_signal != 0, "native-backed Signal must copy for decode/re-encode");
	expect(
			transport.handles().release(
					native_signal,
					81,
					12,
					foundry_java::HandleKind::VARIANT,
					"SIGNAL"),
			"original native-backed Signal must release before its copy");
	foundry_java::NativeValue decoded_signal = foundry_java::NativeValue::storage(
			foundry_java::abi_layout_size("Signal"));
	expect(
			transport.inspect_variant(
					copied_signal,
					81,
					12,
					FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL,
					decoded_signal.data())
					.ok &&
					std::memcmp(
							native_signal_payload.data(),
							decoded_signal.data(),
							native_signal_payload.byte_size) == 0,
			"native-backed Signal copy must preserve payload after original release");
	expect(
			transport.handles().release(
					copied_signal,
					81,
					12,
					foundry_java::HandleKind::VARIANT,
					"SIGNAL"),
			"native-backed Signal copy must release independently");

	construct_object_count = postinitialize_count = singleton_count = 0;
	native_object_destroy_count = 0;
	const auto object = transport.instantiate(81, 12, "Node");
	expect(object != 0, "object construction must produce a typed instance-id handle");
	expect(
			construct_object_count == 1 && postinitialize_count == 1,
			"object construction must send POSTINITIALIZE exactly once");
	expect(
			transport.handles().release(
					object,
					81,
					12,
					foundry_java::HandleKind::OBJECT,
					"Node"),
			"owned constructed object must release");
	expect(native_object_destroy_count == 1, "owned constructed object must destroy exactly once");
	const auto singleton = transport.singleton(81, 12, "Engine");
	expect(singleton != 0 && singleton_count == 1, "singleton route must track the returned instance ID");
	const auto repeated_singleton = transport.singleton(81, 12, "Engine");
	expect(
			repeated_singleton == singleton && singleton_count == 2,
			"repeated singleton lookup must reuse one canonical object token");
	const auto singleton_variant =
			transport.construct_object_variant(81, 12, singleton, "Engine");
	const auto canonical_object_a =
			transport.track_object_variant(singleton_variant, 81, 12);
	const auto canonical_object_b =
			transport.track_object_variant(singleton_variant, 81, 12);
	expect(
			singleton_variant != 0 && canonical_object_a != 0 &&
					canonical_object_a == canonical_object_b,
			"repeated Object decode must reuse one context-bound instance-id handle");
	expect(
			transport.release_handle(singleton_variant, 81, 12),
			"Object Variant identity fixture must release independently");
	expect(
			transport.handles().release(
					singleton,
					81,
					12,
					foundry_java::HandleKind::OBJECT,
					"Engine"),
			"borrowed singleton handle must release");
	expect(native_object_destroy_count == 1, "borrowed singleton release must not destroy");
}

void test_vararg_native_arguments_reach_ptrcall_families() {
	auto services = std::make_shared<foundry_java::BridgeServices>();
	services->variant_new_nil = &fake_variant_new_nil;
	services->variant_get_ptr_destructor = &fake_all_variant_destructors;
	services->string_name_new_with_utf8_chars_and_len = &fake_string_name_from_utf8_and_len;
	services->variant_get_ptr_builtin_method = &fake_get_builtin_method;
	services->variant_get_ptr_utility_function = &fake_get_utility;
	foundry_java::NativeTransport transport(services);

	std::uint64_t first = 11;
	std::uint64_t second = 22;
	std::uint64_t third = 33;
	std::uint64_t wrongly_unboxed_prefix = 999;
	std::max_align_t receiver[4]{};
	std::max_align_t result[4]{};
	foundry_java::DispatchCall call;
	call.variant_arguments = { &first, &second, &third };
	call.native_arguments = { &wrongly_unboxed_prefix };
	call.variant_result = result;
	call.native_result = result;

	foundry_java::NativeDispatch dispatch;
	dispatch.identity = "utility_functions/print";
	dispatch.kind = foundry_java::DispatchKind::UTILITY_FUNCTION;
	dispatch.native_name = "print";
	dispatch.argument_native_types = { "Variant" };
	dispatch.minimum_argument_count = 1;
	dispatch.return_native_type = "void";
	dispatch.vararg = true;
	foundry_java::prepare_native_arguments_for_dispatch(dispatch, call);
	expect(
			call.native_arguments == call.variant_arguments,
			"print-style varargs must replace the typed prefix with raw Variant pointers");
	expect(transport.execute(dispatch, call).ok, "print-style vararg utility must execute");
	expect(
			utility_argument_values == std::vector<std::uint64_t>{ 11, 22, 33 },
			"print-style vararg utility must receive every Variant in order");
	expect(
			utility_argument_pointers == call.native_arguments,
			"print-style ptrcall must preserve every Variant pointer identity");
	expect(
			!foundry_java::validate_dispatch(dispatch, 0, {}).valid,
			"print-style vararg utility must still enforce its declared minimum");

	dispatch.identity = "builtin_classes/Callable/methods/call";
	dispatch.kind = foundry_java::DispatchKind::BUILTIN_METHOD;
	dispatch.owner_native_type = "Callable";
	dispatch.native_name = "call";
	dispatch.argument_native_types.clear();
	dispatch.minimum_argument_count = 0;
	dispatch.return_native_type = "Variant";
	call.receiver_native = receiver;
	call.receiver_native_type = "Callable";
	call.native_arguments.clear();
	foundry_java::prepare_native_arguments_for_dispatch(dispatch, call);
	expect(transport.execute(dispatch, call).ok, "Callable.call varargs must execute");
	expect(
			builtin_method_argument_values == std::vector<std::uint64_t>{ 11, 22, 33 },
			"Callable.call must receive every Variant in order");
	expect(
			builtin_method_argument_pointers == call.native_arguments,
			"Callable.call must preserve every Variant pointer identity");
	call.variant_arguments.clear();
	call.native_arguments.clear();
	foundry_java::prepare_native_arguments_for_dispatch(dispatch, call);
	expect(transport.execute(dispatch, call).ok, "Callable.call must accept zero varargs");
	expect(
			builtin_method_argument_pointers.empty(),
			"zero-argument Callable.call must pass an empty ptrcall argument vector");

	dispatch.identity = "builtin_classes/Callable/methods/rpc_id";
	dispatch.native_name = "rpc_id";
	dispatch.argument_native_types = { "int" };
	dispatch.minimum_argument_count = 1;
	call.variant_arguments = { &first, &second };
	call.native_arguments = { &wrongly_unboxed_prefix };
	foundry_java::prepare_native_arguments_for_dispatch(dispatch, call);
	expect(
			call.native_arguments == call.variant_arguments,
			"Callable.rpc_id declared int prefix must remain raw Variant storage");
	expect(transport.execute(dispatch, call).ok, "Callable.rpc_id fixed-prefix varargs must execute");
	expect(
			builtin_method_argument_pointers == call.native_arguments &&
					builtin_method_argument_values == std::vector<std::uint64_t>{ 11, 22 },
			"Callable.rpc_id must preserve its fixed prefix and trailing Variant pointers");

	dispatch.identity = "builtin_classes/Signal/methods/emit";
	dispatch.owner_native_type = "Signal";
	dispatch.native_name = "emit";
	dispatch.argument_native_types.clear();
	dispatch.minimum_argument_count = 0;
	dispatch.return_native_type = "void";
	call.receiver_native_type = "Signal";
	call.variant_arguments = { &first, &second, &third };
	call.native_arguments.clear();
	foundry_java::prepare_native_arguments_for_dispatch(dispatch, call);
	expect(transport.execute(dispatch, call).ok, "Signal.emit varargs must execute");
	expect(
			builtin_method_argument_values == std::vector<std::uint64_t>{ 11, 22, 33 },
			"Signal.emit must receive every Variant in order");
	expect(
			builtin_method_argument_pointers == call.native_arguments,
			"Signal.emit must preserve every Variant pointer identity");
	call.variant_arguments.clear();
	call.native_arguments.clear();
	foundry_java::prepare_native_arguments_for_dispatch(dispatch, call);
	expect(transport.execute(dispatch, call).ok, "Signal.emit must accept zero varargs");
	expect(
			builtin_method_argument_pointers.empty(),
			"zero-argument Signal.emit must pass an empty ptrcall argument vector");

	dispatch.kind = foundry_java::DispatchKind::UTILITY_FUNCTION;
	dispatch.owner_native_type.clear();
	dispatch.native_name = "fixed";
	dispatch.argument_native_types = { "int" };
	dispatch.minimum_argument_count = 1;
	dispatch.vararg = false;
	call.variant_arguments = { &first, &second };
	call.native_arguments = { &wrongly_unboxed_prefix };
	foundry_java::prepare_native_arguments_for_dispatch(dispatch, call);
	expect(
			call.native_arguments == std::vector<FoundryExtensionConstTypePtr>{ &wrongly_unboxed_prefix },
			"non-vararg dispatch must retain its typed native arguments");
	expect(
			!foundry_java::validate_dispatch(dispatch, call.variant_arguments.size(), {}).valid,
			"surplus non-vararg JNI arguments must reject before ptrcall");
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

bool jni_bridge_install_native_services(
		std::shared_ptr<const BridgeServices>,
		FoundryExtensionClassLibraryPtr) noexcept {
	return true;
}

bool jni_bridge_shutdown() noexcept {
	jni_shutdown_count++;
	return jni_shutdown_result;
}

} // namespace foundry_java

int main() {
	test_jni_transition_tickets_commit_and_release_loader_ownership_exactly();
	test_context_identity_reentrancy_and_exception_containment();
	test_shutdown_waits_for_active_callback_lease();
	test_shutdown_waits_for_native_operations_then_tears_down_resources();
	test_native_operation_can_finish_on_a_different_thread();
	test_shutdown_all_waits_for_concurrent_context_teardown();
	test_extension_entry_validates_and_orders_lifecycle();
	test_generated_abi_layout_is_complete();
	test_bridge_services_resolve_all_or_nothing();
	test_typed_handles_reject_wrong_identity_and_destroy_once();
	test_handles_authenticate_themselves_retain_same_identity_and_release_without_type();
	test_handle_teardown_waits_for_active_lease();
	test_variant_inventory_and_dispatch_validation();
	test_native_structure_and_object_transport();
	test_dispatch_families_and_ref_counted_ownership();
	test_ref_counted_instantiation_initializes_and_unreferences();
	test_all_variant_categories_copy_and_destroy_through_public_abi();
	test_category_specific_conversion_and_executable_dispatch();
	test_vararg_native_arguments_reach_ptrcall_families();
	std::cout << "Foundry Java native runtime tests passed\n";
	return 0;
}
