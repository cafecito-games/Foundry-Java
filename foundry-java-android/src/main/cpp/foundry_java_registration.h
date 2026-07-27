#pragma once

#include "foundry_extension_interface.h"

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace foundry_java {

using RegistrationAccessToken = std::uintptr_t;
using RegistrationInstanceToken = std::uintptr_t;

struct JavaTransportType {
	FoundryExtensionVariantType abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
	FoundryExtensionClassMethodArgumentMetadata metadata =
			FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_NONE;
	std::string java_type;
	std::string class_name;
	bool is_void = false;
};

struct JavaMethodSignature {
	JavaTransportType return_type;
	std::vector<JavaTransportType> arguments;
};

struct JavaTypeParseResult {
	JavaTransportType type;
	std::string phase;

	bool ok() const noexcept { return phase.empty(); }
};

struct JavaSignatureParseResult {
	JavaMethodSignature signature;
	std::string phase;

	bool ok() const noexcept { return phase.empty(); }
};

JavaTypeParseResult parse_java_property_type(const std::string &signature);
JavaSignatureParseResult
parse_java_method_signature(const std::string &signature);
const std::array<std::string_view, 9> &required_registration_service_names();

enum class RegistrationMemberKind : std::uint8_t {
	METHOD,
	OVERRIDE,
	CONSTANT,
	PROPERTY,
	SIGNAL,
};

struct RegistrationConstantDetails {
	std::string enum_name;
	std::int64_t value = 0;
	bool bitfield = false;
};

struct RegistrationPropertyDetails {
	std::string getter;
	std::string setter;
	std::int32_t index = -1;
	std::string group_name;
	std::string group_prefix;
	std::string subgroup_name;
	std::string subgroup_prefix;
};

struct RegistrationMemberDescriptor {
	RegistrationMemberKind kind = RegistrationMemberKind::METHOD;
	std::string foundry_name;
	std::string java_name;
	std::string signature;
	std::optional<RegistrationConstantDetails> constant;
	std::optional<RegistrationPropertyDetails> property;
};

struct RegistrationClassDescriptor {
	std::string foundry_name;
	std::string base_name;
	RegistrationAccessToken access = 0;
	std::vector<RegistrationMemberDescriptor> members;
};

struct NativeClassRegistration {
	std::string class_name;
	std::string base_name;
	FoundryExtensionClassCreationInfo5 creation_info{};
};

struct NativeMethodRegistration {
	std::string name;
	std::string java_name;
	JavaMethodSignature signature;
	void *userdata = nullptr;
	FoundryExtensionClassMethodCall call = nullptr;
	bool property_accessor = false;
};

struct NativeConstantRegistration {
	std::string name;
	std::string enum_name;
	std::int64_t value = 0;
	bool bitfield = false;
};

struct NativePropertyRegistration {
	std::string name;
	JavaTransportType type;
	std::string getter;
	std::string setter;
	std::int32_t index = -1;
};

struct NativeSignalRegistration {
	std::string name;
	std::vector<JavaTransportType> arguments;
};

class RegistrationServices {
public:
	virtual ~RegistrationServices() = default;

	virtual bool
	register_class(FoundryExtensionClassLibraryPtr library,
			const NativeClassRegistration &registration) noexcept = 0;
	virtual bool
	register_method(FoundryExtensionClassLibraryPtr library,
			const std::string &class_name,
			const NativeMethodRegistration &registration) noexcept = 0;
	virtual bool register_integer_constant(
			FoundryExtensionClassLibraryPtr library, const std::string &class_name,
			const NativeConstantRegistration &registration) noexcept = 0;
	virtual bool register_property_group(FoundryExtensionClassLibraryPtr library,
			const std::string &class_name,
			const std::string &name,
			const std::string &prefix) noexcept = 0;
	virtual bool register_property_subgroup(
			FoundryExtensionClassLibraryPtr library, const std::string &class_name,
			const std::string &name, const std::string &prefix) noexcept = 0;
	virtual bool register_property(
			FoundryExtensionClassLibraryPtr library, const std::string &class_name,
			const NativePropertyRegistration &registration) noexcept = 0;
	virtual bool register_indexed_property(
			FoundryExtensionClassLibraryPtr library, const std::string &class_name,
			const NativePropertyRegistration &registration) noexcept = 0;
	virtual bool
	register_signal(FoundryExtensionClassLibraryPtr library,
			const std::string &class_name,
			const NativeSignalRegistration &registration) noexcept = 0;
	virtual bool unregister_class(FoundryExtensionClassLibraryPtr library,
			const std::string &class_name) noexcept = 0;
	virtual std::string
	string_name(FoundryExtensionConstStringNamePtr name) noexcept = 0;
	virtual bool resolve_object_type(const std::string &java_type,
			std::string &foundry_type) noexcept = 0;
	virtual void initialize_nil(FoundryExtensionVariantPtr value) noexcept = 0;
};

struct NativeInstance {
	FoundryExtensionObjectPtr object = nullptr;
	RegistrationInstanceToken access_instance = 0;
};

class RegistrationCallbacks {
public:
	virtual ~RegistrationCallbacks() = default;

	virtual void release_access(RegistrationAccessToken access) noexcept = 0;
	virtual NativeInstance create_instance(
			RegistrationAccessToken access, const std::string &class_name,
			const std::string &base_name,
			bool notify_postinitialize,
			FoundryExtensionClassInstancePtr instance_userdata) noexcept = 0;
	virtual void discard_partial_instance(RegistrationAccessToken access,
			NativeInstance instance) noexcept = 0;
	virtual void free_instance(RegistrationAccessToken access,
			RegistrationInstanceToken instance) noexcept = 0;
	virtual bool invoke(RegistrationAccessToken access,
			RegistrationInstanceToken instance,
			const std::string &java_name,
			const FoundryExtensionConstVariantPtr *arguments,
			FoundryExtensionInt argument_count,
			FoundryExtensionVariantPtr result,
			FoundryExtensionCallError *error) noexcept = 0;
	virtual bool invoke_virtual(RegistrationAccessToken access,
			RegistrationInstanceToken instance,
			const std::string &java_name,
			const FoundryExtensionConstTypePtr *arguments,
			FoundryExtensionTypePtr result) noexcept = 0;
	virtual void
	initialize_virtual_default(const JavaTransportType &return_type,
			FoundryExtensionTypePtr result) noexcept = 0;
	virtual bool get_property(RegistrationAccessToken access,
			RegistrationInstanceToken instance,
			const std::string &java_name,
			FoundryExtensionVariantPtr result) noexcept = 0;
	virtual bool set_property(RegistrationAccessToken access,
			RegistrationInstanceToken instance,
			const std::string &java_name,
			FoundryExtensionConstVariantPtr value) noexcept = 0;
	virtual bool to_string(RegistrationAccessToken access,
			RegistrationInstanceToken instance,
			FoundryExtensionStringPtr result) noexcept = 0;
};

enum class RegistrationStatus : std::uint8_t {
	OK,
	INVALID_DESCRIPTOR,
	CONFLICT,
	NATIVE_FAILURE,
	RETRY,
	STALE,
};

struct RegistrationResult {
	RegistrationStatus status = RegistrationStatus::OK;
	std::string phase;

	bool ok() const noexcept { return status == RegistrationStatus::OK; }
};

class RegistrationRegistry final {
public:
	RegistrationRegistry(std::shared_ptr<RegistrationServices> services,
			std::shared_ptr<RegistrationCallbacks> callbacks);
	~RegistrationRegistry();

	RegistrationRegistry(const RegistrationRegistry &) = delete;
	RegistrationRegistry &operator=(const RegistrationRegistry &) = delete;

	RegistrationResult
	register_class(std::uint64_t context, std::uint64_t generation,
			FoundryExtensionClassLibraryPtr library,
			RegistrationClassDescriptor descriptor) noexcept;
	RegistrationResult unregister_class(std::uint64_t context,
			std::uint64_t generation,
			const std::string &class_name) noexcept;
	RegistrationResult
	rollback(std::uint64_t context, std::uint64_t generation,
			const std::vector<std::string> &completed_classes) noexcept;
	RegistrationResult shutdown(std::uint64_t context,
			std::uint64_t generation) noexcept;

	std::size_t active_class_count() const noexcept;
	std::size_t tombstone_count() const noexcept;

private:
	struct Impl;
	std::unique_ptr<Impl> impl;
};

} // namespace foundry_java
