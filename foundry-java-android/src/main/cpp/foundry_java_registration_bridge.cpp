#include "foundry_java_registration_bridge.h"

#include "foundry_java_abi_layout.h"
#include "foundry_java_transport.h"

#include <algorithm>
#include <cstring>
#include <utility>
#include <vector>

namespace foundry_java {
namespace {

class ScopedName final {
public:
	ScopedName(const BridgeServices &services, const std::string &text) : value(NativeValue::storage(abi_layout_size("StringName"))) {
		if (value.data() == nullptr ||
				services.string_name_new_with_utf8_chars_and_len == nullptr ||
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

	~ScopedName() {
		if (value.constructed) {
			destructor(value.data());
		}
	}

	explicit operator bool() const noexcept { return value.constructed; }
	FoundryExtensionConstStringNamePtr get() const noexcept {
		return value.data();
	}
	FoundryExtensionStringNamePtr mutable_get() noexcept { return value.data(); }

private:
	NativeValue value;
	FoundryExtensionPtrDestructor destructor = nullptr;
};

class ScopedString final {
public:
	ScopedString(const BridgeServices &services, const std::string &text) : value(NativeValue::storage(abi_layout_size("String"))) {
		if (value.data() == nullptr ||
				services.string_new_with_utf8_chars_and_len2 == nullptr ||
				services.variant_get_ptr_destructor == nullptr) {
			return;
		}
		destructor = services.variant_get_ptr_destructor(
				FOUNDRY_EXTENSION_VARIANT_TYPE_STRING);
		if (destructor == nullptr) {
			return;
		}
		const FoundryExtensionInt error =
				services.string_new_with_utf8_chars_and_len2(
						value.data(), text.data(),
						static_cast<FoundryExtensionInt>(text.size()));
		value.constructed = true;
		valid = error == 0;
	}

	~ScopedString() {
		if (value.constructed) {
			destructor(value.data());
		}
	}

	explicit operator bool() const noexcept { return valid; }
	FoundryExtensionConstStringPtr get() const noexcept { return value.data(); }
	FoundryExtensionStringPtr mutable_get() noexcept { return value.data(); }

private:
	NativeValue value;
	FoundryExtensionPtrDestructor destructor = nullptr;
	bool valid = false;
};

struct ScopedPropertyInfo {
	ScopedPropertyInfo(const BridgeServices &services,
			const std::string &name_text,
			const JavaTransportType &type) : name(services, name_text),
											 class_name(services, type.class_name),
											 hint(services, "") {
		info.type = type.abi_type;
		info.name = name.mutable_get();
		// The engine copies `class_name` unconditionally, so a type without an
		// engine class must still be handed constructed (empty) StringName
		// storage rather than a null pointer.
		info.class_name = class_name.mutable_get();
		info.hint = 0;
		info.hint_string = hint.mutable_get();
		info.usage = 6;
		valid = name && class_name && hint;
	}

	ScopedName name;
	ScopedName class_name;
	ScopedString hint;
	FoundryExtensionPropertyInfo info{};
	bool valid = false;
};

bool ready(const std::shared_ptr<const BridgeServices> &services) {
	return services != nullptr &&
			services->string_name_new_with_utf8_chars_and_len != nullptr &&
			services->string_new_with_utf8_chars_and_len2 != nullptr &&
			services->variant_get_ptr_destructor != nullptr;
}

} // namespace

AbiRegistrationServices::AbiRegistrationServices(
		std::shared_ptr<const BridgeServices> services,
		ObjectTypeResolver object_type_resolver) : services(std::move(services)),
												   object_type_resolver(std::move(object_type_resolver)) {}

bool AbiRegistrationServices::register_class(
		FoundryExtensionClassLibraryPtr library,
		const NativeClassRegistration &registration) noexcept {
	try {
		if (!ready(services) ||
				services->classdb_register_extension_class5 == nullptr) {
			return false;
		}
		ScopedName class_name(*services, registration.class_name);
		ScopedName base_name(*services, registration.base_name);
		if (!class_name || !base_name) {
			return false;
		}
		services->classdb_register_extension_class5(library, class_name.get(),
				base_name.get(),
				&registration.creation_info);
		return true;
	} catch (...) {
		return false;
	}
}

bool AbiRegistrationServices::register_method(
		FoundryExtensionClassLibraryPtr library, const std::string &class_name_text,
		const NativeMethodRegistration &registration) noexcept {
	try {
		if (!ready(services) ||
				services->classdb_register_extension_class_method == nullptr) {
			return false;
		}
		ScopedName class_name(*services, class_name_text);
		ScopedName method_name(*services, registration.name);
		ScopedPropertyInfo return_info(*services, "",
				registration.signature.return_type);
		std::vector<std::unique_ptr<ScopedPropertyInfo>> arguments;
		std::vector<FoundryExtensionPropertyInfo> argument_info;
		std::vector<FoundryExtensionClassMethodArgumentMetadata> argument_metadata;
		for (const JavaTransportType &argument : registration.signature.arguments) {
			auto property =
					std::make_unique<ScopedPropertyInfo>(*services, "", argument);
			if (!property->valid) {
				return false;
			}
			argument_info.push_back(property->info);
			argument_metadata.push_back(argument.metadata);
			arguments.push_back(std::move(property));
		}
		if (!class_name || !method_name ||
				(!registration.signature.return_type.is_void && !return_info.valid)) {
			return false;
		}
		FoundryExtensionClassMethodInfo info{};
		info.name = method_name.mutable_get();
		info.method_userdata = registration.userdata;
		info.call_func = registration.call;
		info.ptrcall_func = nullptr;
		info.method_flags = FOUNDRY_EXTENSION_METHOD_FLAGS_DEFAULT;
		info.has_return_value = registration.signature.return_type.is_void ? 0 : 1;
		info.return_value_info = registration.signature.return_type.is_void
				? nullptr
				: &return_info.info;
		info.return_value_metadata = registration.signature.return_type.metadata;
		info.argument_count = static_cast<std::uint32_t>(argument_info.size());
		info.arguments_info =
				argument_info.empty() ? nullptr : argument_info.data();
		info.arguments_metadata =
				argument_metadata.empty() ? nullptr : argument_metadata.data();
		services->classdb_register_extension_class_method(library, class_name.get(),
				&info);
		return true;
	} catch (...) {
		return false;
	}
}

bool AbiRegistrationServices::register_integer_constant(
		FoundryExtensionClassLibraryPtr library, const std::string &class_name_text,
		const NativeConstantRegistration &registration) noexcept {
	try {
		if (!ready(services) ||
				services->classdb_register_extension_class_integer_constant ==
						nullptr) {
			return false;
		}
		ScopedName class_name(*services, class_name_text);
		ScopedName enum_name(*services, registration.enum_name);
		ScopedName constant_name(*services, registration.name);
		if (!class_name || !enum_name || !constant_name) {
			return false;
		}
		services->classdb_register_extension_class_integer_constant(
				library, class_name.get(), enum_name.get(), constant_name.get(),
				registration.value, registration.bitfield ? 1 : 0);
		return true;
	} catch (...) {
		return false;
	}
}

bool AbiRegistrationServices::register_property_group(
		FoundryExtensionClassLibraryPtr library, const std::string &class_name_text,
		const std::string &name_text, const std::string &prefix_text) noexcept {
	try {
		if (!ready(services) ||
				services->classdb_register_extension_class_property_group == nullptr) {
			return false;
		}
		ScopedName class_name(*services, class_name_text);
		ScopedString name(*services, name_text);
		ScopedString prefix(*services, prefix_text);
		if (!class_name || !name || !prefix) {
			return false;
		}
		services->classdb_register_extension_class_property_group(
				library, class_name.get(), name.get(), prefix.get());
		return true;
	} catch (...) {
		return false;
	}
}

bool AbiRegistrationServices::register_property_subgroup(
		FoundryExtensionClassLibraryPtr library, const std::string &class_name_text,
		const std::string &name_text, const std::string &prefix_text) noexcept {
	try {
		if (!ready(services) ||
				services->classdb_register_extension_class_property_subgroup ==
						nullptr) {
			return false;
		}
		ScopedName class_name(*services, class_name_text);
		ScopedString name(*services, name_text);
		ScopedString prefix(*services, prefix_text);
		if (!class_name || !name || !prefix) {
			return false;
		}
		services->classdb_register_extension_class_property_subgroup(
				library, class_name.get(), name.get(), prefix.get());
		return true;
	} catch (...) {
		return false;
	}
}

namespace {

bool register_property_common(
		const std::shared_ptr<const BridgeServices> &services,
		FoundryExtensionClassLibraryPtr library, const std::string &class_name_text,
		const NativePropertyRegistration &registration, bool indexed) {
	if (!ready(services) ||
			(indexed
							? services->classdb_register_extension_class_property_indexed ==
									nullptr
							: services->classdb_register_extension_class_property == nullptr)) {
		return false;
	}
	ScopedName class_name(*services, class_name_text);
	ScopedName setter(*services, registration.setter);
	ScopedName getter(*services, registration.getter);
	ScopedPropertyInfo property(*services, registration.name, registration.type);
	if (!class_name || !setter || !getter || !property.valid) {
		return false;
	}
	// A read-only property has no setter, but the engine copies the setter name
	// unconditionally and treats an empty StringName as absent, so pass the
	// constructed empty storage instead of a null pointer.
	const auto setter_name = setter.get();
	if (indexed) {
		services->classdb_register_extension_class_property_indexed(
				library, class_name.get(), &property.info, setter_name, getter.get(),
				registration.index);
	} else {
		services->classdb_register_extension_class_property(
				library, class_name.get(), &property.info, setter_name, getter.get());
	}
	return true;
}

} // namespace

bool AbiRegistrationServices::register_property(
		FoundryExtensionClassLibraryPtr library, const std::string &class_name,
		const NativePropertyRegistration &registration) noexcept {
	try {
		return register_property_common(services, library, class_name, registration,
				false);
	} catch (...) {
		return false;
	}
}

bool AbiRegistrationServices::register_indexed_property(
		FoundryExtensionClassLibraryPtr library, const std::string &class_name,
		const NativePropertyRegistration &registration) noexcept {
	try {
		return register_property_common(services, library, class_name, registration,
				true);
	} catch (...) {
		return false;
	}
}

bool AbiRegistrationServices::register_signal(
		FoundryExtensionClassLibraryPtr library, const std::string &class_name_text,
		const NativeSignalRegistration &registration) noexcept {
	try {
		if (!ready(services) ||
				services->classdb_register_extension_class_signal == nullptr) {
			return false;
		}
		ScopedName class_name(*services, class_name_text);
		ScopedName signal_name(*services, registration.name);
		std::vector<std::unique_ptr<ScopedPropertyInfo>> arguments;
		std::vector<FoundryExtensionPropertyInfo> argument_info;
		for (const JavaTransportType &argument : registration.arguments) {
			auto property =
					std::make_unique<ScopedPropertyInfo>(*services, "", argument);
			if (!property->valid) {
				return false;
			}
			argument_info.push_back(property->info);
			arguments.push_back(std::move(property));
		}
		if (!class_name || !signal_name) {
			return false;
		}
		services->classdb_register_extension_class_signal(
				library, class_name.get(), signal_name.get(),
				argument_info.empty() ? nullptr : argument_info.data(),
				static_cast<FoundryExtensionInt>(argument_info.size()));
		return true;
	} catch (...) {
		return false;
	}
}

bool AbiRegistrationServices::unregister_class(
		FoundryExtensionClassLibraryPtr library,
		const std::string &class_name_text) noexcept {
	try {
		if (!ready(services) ||
				services->classdb_unregister_extension_class == nullptr) {
			return false;
		}
		ScopedName class_name(*services, class_name_text);
		if (!class_name) {
			return false;
		}
		services->classdb_unregister_extension_class(library, class_name.get());
		return true;
	} catch (...) {
		return false;
	}
}

std::string AbiRegistrationServices::string_name(
		FoundryExtensionConstStringNamePtr name) noexcept {
	try {
		if (name == nullptr || services == nullptr ||
				services->variant_get_ptr_constructor == nullptr ||
				services->variant_get_ptr_destructor == nullptr ||
				services->string_to_utf8_chars == nullptr) {
			return {};
		}
		const FoundryExtensionPtrConstructor constructor =
				services->variant_get_ptr_constructor(
						FOUNDRY_EXTENSION_VARIANT_TYPE_STRING, 2);
		const FoundryExtensionPtrDestructor destructor =
				services->variant_get_ptr_destructor(
						FOUNDRY_EXTENSION_VARIANT_TYPE_STRING);
		if (constructor == nullptr || destructor == nullptr) {
			return {};
		}
		NativeValue text = NativeValue::storage(abi_layout_size("String"));
		const FoundryExtensionConstTypePtr arguments[] = { name };
		constructor(text.data(), arguments);
		text.constructed = true;
		const FoundryExtensionInt size =
				services->string_to_utf8_chars(text.data(), nullptr, 0);
		std::string result;
		if (size > 0) {
			result.resize(static_cast<std::size_t>(size));
			const FoundryExtensionInt written =
					services->string_to_utf8_chars(text.data(), result.data(), size);
			if (written < 0 || written > size) {
				result.clear();
			} else {
				result.resize(static_cast<std::size_t>(written));
			}
		}
		destructor(text.data());
		text.constructed = false;
		return result;
	} catch (...) {
		return {};
	}
}

bool AbiRegistrationServices::resolve_object_type(
		const std::string &java_type, std::string &foundry_type) noexcept {
	try {
		return object_type_resolver &&
				object_type_resolver(java_type, foundry_type);
	} catch (...) {
		return false;
	}
}

void AbiRegistrationServices::initialize_nil(
		FoundryExtensionVariantPtr value) noexcept {
	if (value != nullptr && services != nullptr &&
			services->variant_new_nil != nullptr) {
		services->variant_new_nil(value);
	}
}

} // namespace foundry_java
