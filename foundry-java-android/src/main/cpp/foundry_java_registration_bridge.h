#pragma once

#include "foundry_java_interface.h"
#include "foundry_java_registration.h"

#include <functional>
#include <memory>

namespace foundry_java {

class AbiRegistrationServices final : public RegistrationServices {
public:
	using ObjectTypeResolver =
			std::function<bool(const std::string &, std::string &)>;

	AbiRegistrationServices(std::shared_ptr<const BridgeServices> services,
			ObjectTypeResolver object_type_resolver);

	bool
	register_class(FoundryExtensionClassLibraryPtr library,
			const NativeClassRegistration &registration) noexcept override;
	bool register_method(
			FoundryExtensionClassLibraryPtr library, const std::string &class_name,
			const NativeMethodRegistration &registration) noexcept override;
	bool register_integer_constant(
			FoundryExtensionClassLibraryPtr library, const std::string &class_name,
			const NativeConstantRegistration &registration) noexcept override;
	bool register_property_group(FoundryExtensionClassLibraryPtr library,
			const std::string &class_name,
			const std::string &name,
			const std::string &prefix) noexcept override;
	bool register_property_subgroup(FoundryExtensionClassLibraryPtr library,
			const std::string &class_name,
			const std::string &name,
			const std::string &prefix) noexcept override;
	bool register_property(
			FoundryExtensionClassLibraryPtr library, const std::string &class_name,
			const NativePropertyRegistration &registration) noexcept override;
	bool register_indexed_property(
			FoundryExtensionClassLibraryPtr library, const std::string &class_name,
			const NativePropertyRegistration &registration) noexcept override;
	bool register_signal(
			FoundryExtensionClassLibraryPtr library, const std::string &class_name,
			const NativeSignalRegistration &registration) noexcept override;
	bool unregister_class(FoundryExtensionClassLibraryPtr library,
			const std::string &class_name) noexcept override;
	std::string
	string_name(FoundryExtensionConstStringNamePtr name) noexcept override;
	bool resolve_object_type(const std::string &java_type,
			std::string &foundry_type) noexcept override;
	void initialize_nil(FoundryExtensionVariantPtr value) noexcept override;

private:
	std::shared_ptr<const BridgeServices> services;
	ObjectTypeResolver object_type_resolver;
};

} // namespace foundry_java
