#include "foundry_java_registration.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <condition_variable>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace foundry_java {
namespace {

constexpr std::string_view kTypePrefix = "games.cafecito.foundry.types.";
constexpr std::string_view kRuntimePrefix = "games.cafecito.foundry.runtime.";
constexpr std::string_view kSyntheticPropertyPrefix =
		"__foundry_java_property_";

struct VariantJavaType {
	std::string_view java_name;
	FoundryExtensionVariantType abi_type;
};

constexpr std::array<VariantJavaType, 32> kVariantJavaTypes = { {
		{ "Aabb", FOUNDRY_EXTENSION_VARIANT_TYPE_AABB },
		{ "Basis", FOUNDRY_EXTENSION_VARIANT_TYPE_BASIS },
		{ "Color", FOUNDRY_EXTENSION_VARIANT_TYPE_COLOR },
		{ "FoundryArray", FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY },
		{ "FoundryDictionary", FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY },
		{ "NodePath", FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH },
		{ "PackedByteArray", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY },
		{ "PackedColorArray", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY },
		{ "PackedFloat32Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY },
		{ "PackedFloat64Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY },
		{ "PackedInt32Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY },
		{ "PackedInt64Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY },
		{ "PackedStringArray", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY },
		{ "PackedVector2Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY },
		{ "PackedVector3Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY },
		{ "PackedVector4Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY },
		{ "Plane", FOUNDRY_EXTENSION_VARIANT_TYPE_PLANE },
		{ "Projection", FOUNDRY_EXTENSION_VARIANT_TYPE_PROJECTION },
		{ "Quaternion", FOUNDRY_EXTENSION_VARIANT_TYPE_QUATERNION },
		{ "Rect2", FOUNDRY_EXTENSION_VARIANT_TYPE_RECT2 },
		{ "Rect2i", FOUNDRY_EXTENSION_VARIANT_TYPE_RECT2I },
		{ "Rid", FOUNDRY_EXTENSION_VARIANT_TYPE_RID },
		{ "StringName", FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME },
		{ "Transform2D", FOUNDRY_EXTENSION_VARIANT_TYPE_TRANSFORM2D },
		{ "Transform3D", FOUNDRY_EXTENSION_VARIANT_TYPE_TRANSFORM3D },
		{ "Variant", FOUNDRY_EXTENSION_VARIANT_TYPE_NIL },
		{ "Vector2", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2 },
		{ "Vector2i", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2I },
		{ "Vector3", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3 },
		{ "Vector3i", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3I },
		{ "Vector4", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR4 },
		{ "Vector4i", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR4I },
} };

bool valid_java_qualified_name(const std::string &type) {
	if (type.empty() || type.front() == '.' || type.back() == '.') {
		return false;
	}
	bool component_start = true;
	for (const unsigned char character : type) {
		if (character == '.') {
			if (component_start) {
				return false;
			}
			component_start = true;
			continue;
		}
		if (component_start) {
			if (!(std::isalpha(character) || character == '_' || character == '$')) {
				return false;
			}
			component_start = false;
		} else if (!(std::isalnum(character) || character == '_' ||
						   character == '$')) {
			return false;
		}
	}
	return !component_start;
}

JavaTypeParseResult invalid_type() {
	return { {}, "registration_signature" };
}

bool split_generic_arguments(
		const std::string &arguments, std::vector<std::string> &parts) {
	std::size_t start = 0;
	std::size_t depth = 0;
	for (std::size_t index = 0; index < arguments.size(); index++) {
		const char character = arguments[index];
		if (character == '<') {
			depth++;
		} else if (character == '>') {
			if (depth == 0) {
				return false;
			}
			depth--;
		} else if (character == ',' && depth == 0) {
			if (index == start) {
				return false;
			}
			parts.push_back(arguments.substr(start, index - start));
			start = index + 1;
		}
	}
	if (depth != 0 || start == arguments.size()) {
		return false;
	}
	parts.push_back(arguments.substr(start));
	return true;
}

JavaTypeParseResult parse_java_type(const std::string &type, bool allow_void) {
	if (type.empty() || type.find_first_of(" \t\r\n[]()") != std::string::npos) {
		return invalid_type();
	}
	const std::string array_prefix =
			std::string(kTypePrefix) + "FoundryArray<";
	const std::string dictionary_prefix =
			std::string(kTypePrefix) + "FoundryDictionary<";
	if (type.compare(0, array_prefix.size(), array_prefix) == 0 &&
			type.back() == '>') {
		const std::string element =
				type.substr(array_prefix.size(), type.size() - array_prefix.size() - 1);
		std::vector<std::string> arguments;
		if (!split_generic_arguments(element, arguments) ||
				arguments.size() != 1 ||
				!parse_java_type(arguments.front(), false).ok()) {
			return invalid_type();
		}
		JavaTransportType parsed;
		parsed.java_type = type;
		parsed.abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY;
		return { std::move(parsed), {} };
	}
	if (type.compare(0, dictionary_prefix.size(), dictionary_prefix) == 0 &&
			type.back() == '>') {
		const std::string arguments = type.substr(dictionary_prefix.size(),
				type.size() - dictionary_prefix.size() - 1);
		std::vector<std::string> parts;
		if (!split_generic_arguments(arguments, parts) || parts.size() != 2 ||
				!parse_java_type(parts[0], false).ok() ||
				!parse_java_type(parts[1], false).ok()) {
			return invalid_type();
		}
		JavaTransportType parsed;
		parsed.java_type = type;
		parsed.abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY;
		return { std::move(parsed), {} };
	}
	if (type.find_first_of("<>,") != std::string::npos) {
		return invalid_type();
	}
	JavaTransportType parsed;
	parsed.java_type = type;
	if (type == "void") {
		if (!allow_void) {
			return invalid_type();
		}
		parsed.is_void = true;
		return { std::move(parsed), {} };
	}
	if (type == "boolean") {
		parsed.abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL;
		return { std::move(parsed), {} };
	}
	if (type == "byte" || type == "short" || type == "int" || type == "long" ||
			type == "char") {
		parsed.abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_INT;
		if (type == "byte") {
			parsed.metadata = FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT8;
		} else if (type == "short") {
			parsed.metadata = FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT16;
		} else if (type == "int") {
			parsed.metadata = FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT32;
		} else if (type == "long") {
			parsed.metadata = FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT64;
		} else {
			parsed.metadata =
					FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_CHAR16;
		}
		return { std::move(parsed), {} };
	}
	if (type == "float" || type == "double") {
		parsed.abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT;
		parsed.metadata =
				type == "float"
				? FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_REAL_IS_FLOAT
				: FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_REAL_IS_DOUBLE;
		return { std::move(parsed), {} };
	}
	if (type == "java.lang.String") {
		parsed.abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_STRING;
		return { std::move(parsed), {} };
	}
	if (type == std::string(kRuntimePrefix) + "FoundryCallable") {
		parsed.abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE;
		return { std::move(parsed), {} };
	}
	if (type == std::string(kRuntimePrefix) + "FoundrySignal") {
		parsed.abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL;
		return { std::move(parsed), {} };
	}
	if (type.compare(0, kTypePrefix.size(), kTypePrefix) == 0) {
		const std::string_view simple(type.data() + kTypePrefix.size(),
				type.size() - kTypePrefix.size());
		const auto found =
				std::find_if(kVariantJavaTypes.begin(), kVariantJavaTypes.end(),
						[simple](const VariantJavaType &candidate) {
							return candidate.java_name == simple;
						});
		if (found == kVariantJavaTypes.end()) {
			return invalid_type();
		}
		parsed.abi_type = found->abi_type;
		return { std::move(parsed), {} };
	}
	if (type.compare(0, 5, "java.") == 0 || !valid_java_qualified_name(type)) {
		return invalid_type();
	}
	parsed.abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT;
	parsed.metadata =
			FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_OBJECT_IS_REQUIRED;
	return { std::move(parsed), {} };
}

bool blank(const std::string &value) {
	return value.empty() ||
			std::all_of(value.begin(), value.end(), [](unsigned char character) {
				return std::isspace(character);
			});
}

RegistrationResult
validate_descriptor(const RegistrationClassDescriptor &descriptor) {
	if (blank(descriptor.foundry_name) || blank(descriptor.base_name) ||
			descriptor.access == 0) {
		return { RegistrationStatus::INVALID_DESCRIPTOR, "registration_descriptor" };
	}
	std::unordered_set<std::string> names;
	for (const RegistrationMemberDescriptor &member : descriptor.members) {
		if (blank(member.foundry_name) || blank(member.java_name) ||
				blank(member.signature)) {
			return { RegistrationStatus::INVALID_DESCRIPTOR,
				"registration_descriptor" };
		}
		if (member.foundry_name.compare(
					0,
					kSyntheticPropertyPrefix.size(),
					kSyntheticPropertyPrefix) == 0) {
			return { RegistrationStatus::INVALID_DESCRIPTOR,
				"registration_reserved_member" };
		}
		if (!names.insert(member.foundry_name).second) {
			return { RegistrationStatus::INVALID_DESCRIPTOR,
				"registration_duplicate_member" };
		}
		const bool callable = member.kind == RegistrationMemberKind::METHOD ||
				member.kind == RegistrationMemberKind::OVERRIDE ||
				member.kind == RegistrationMemberKind::SIGNAL;
		if (callable) {
			const JavaSignatureParseResult signature =
					parse_java_method_signature(member.signature);
			if (!signature.ok() || (member.kind == RegistrationMemberKind::SIGNAL && !signature.signature.return_type.is_void)) {
				return { RegistrationStatus::INVALID_DESCRIPTOR,
					"registration_signature" };
			}
		} else {
			const JavaTypeParseResult type =
					parse_java_property_type(member.signature);
			if (!type.ok() ||
					(member.kind == RegistrationMemberKind::CONSTANT &&
							(type.type.abi_type != FOUNDRY_EXTENSION_VARIANT_TYPE_INT ||
									type.type.metadata !=
											FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_INT_IS_INT64))) {
				return { RegistrationStatus::INVALID_DESCRIPTOR,
					"registration_signature" };
			}
		}
		if (member.kind == RegistrationMemberKind::CONSTANT) {
			if (!member.constant || member.property ||
					(member.constant->bitfield && member.constant->enum_name.empty())) {
				return { RegistrationStatus::INVALID_DESCRIPTOR, "registration_details" };
			}
		} else if (member.constant) {
			return { RegistrationStatus::INVALID_DESCRIPTOR, "registration_details" };
		}
		if (member.kind != RegistrationMemberKind::PROPERTY && member.property) {
			return { RegistrationStatus::INVALID_DESCRIPTOR, "registration_details" };
		}
		if (member.property) {
			const RegistrationPropertyDetails &property = *member.property;
			if (blank(property.getter) || property.index < -1 ||
					(property.group_name.empty() && !property.group_prefix.empty()) ||
					(property.subgroup_name.empty() &&
							!property.subgroup_prefix.empty())) {
				return { RegistrationStatus::INVALID_DESCRIPTOR, "registration_details" };
			}
		}
	}
	return {};
}

enum class RecordState : std::uint8_t {
	REGISTERING,
	ACTIVE,
	QUARANTINED,
	RETIRING,
	TOMBSTONED,
};

struct ClassRecord;

enum class MemberCallbackKind : std::uint8_t {
	METHOD,
	OVERRIDE,
	PROPERTY_GET,
	PROPERTY_SET,
};

struct MemberRecord {
	ClassRecord *owner = nullptr;
	MemberCallbackKind callback_kind = MemberCallbackKind::METHOD;
	std::string foundry_name;
	std::string java_name;
	JavaMethodSignature signature;
};

struct InstanceRecord {
	ClassRecord *owner = nullptr;
	RegistrationInstanceToken access_instance = 0;
	RecordState state = RecordState::REGISTERING;
	bool pending_free = false;
};

struct ClassRecord {
	void *registry = nullptr;
	std::uint64_t context = 0;
	std::uint64_t generation = 0;
	FoundryExtensionClassLibraryPtr library = nullptr;
	std::string foundry_name;
	std::string base_name;
	RegistrationAccessToken access = 0;
	RecordState state = RecordState::REGISTERING;
	RecordState state_before_retiring = RecordState::ACTIVE;
	bool native_registered = false;
	std::mutex mutex;
	std::condition_variable callbacks_drained;
	std::size_t active_callbacks = 0;
	std::size_t active_instance_cleanups = 0;
	std::vector<std::unique_ptr<MemberRecord>> members;
	std::unordered_map<std::string, MemberRecord *> overrides;
	std::unordered_map<std::string, MemberRecord *> properties;
	std::unordered_map<FoundryExtensionClassInstancePtr,
			std::unique_ptr<InstanceRecord>>
			instances;
	std::vector<std::unique_ptr<InstanceRecord>> instance_tombstones;
};

struct ContextGeneration {
	std::uint64_t context = 0;
	std::uint64_t generation = 0;

	bool operator==(const ContextGeneration &other) const noexcept {
		return context == other.context && generation == other.generation;
	}
};

struct ContextGenerationHash {
	std::size_t operator()(const ContextGeneration &key) const noexcept {
		const std::size_t context_hash = std::hash<std::uint64_t>{}(key.context);
		const std::size_t generation_hash =
				std::hash<std::uint64_t>{}(key.generation);
		return context_hash ^
				(generation_hash + 0x9e3779b9U + (context_hash << 6U) +
						(context_hash >> 2U));
	}
};

thread_local std::vector<ContextGeneration> registration_attempt_stack;

struct RegistryCore {
	std::shared_ptr<RegistrationServices> services;
	std::shared_ptr<RegistrationCallbacks> callbacks;
	mutable std::mutex mutex;
	std::unordered_map<std::string, std::unique_ptr<ClassRecord>> classes;
	std::vector<std::unique_ptr<ClassRecord>> tombstones;
	std::vector<std::string> registration_order;
	std::condition_variable registrations_drained;
	std::unordered_map<ContextGeneration, std::size_t, ContextGenerationHash>
			active_registrations;
	std::unordered_set<ContextGeneration, ContextGenerationHash> shutting_down;
	std::condition_variable shutdown_finished;
	std::unordered_set<ContextGeneration, ContextGenerationHash>
			shutdown_in_progress;
	std::unordered_map<ContextGeneration, RegistrationResult, ContextGenerationHash>
			shutdown_results;
};

class RegistrationAttempt final {
public:
	RegistrationAttempt() = default;
	RegistrationAttempt(RegistryCore *registry, ContextGeneration key) :
			registry(registry), key(key) {
		registration_attempt_stack.push_back(key);
	}
	RegistrationAttempt(const RegistrationAttempt &) = delete;
	RegistrationAttempt &operator=(const RegistrationAttempt &) = delete;

	~RegistrationAttempt() {
		const auto stack_entry = std::find(registration_attempt_stack.rbegin(),
				registration_attempt_stack.rend(), key);
		if (stack_entry != registration_attempt_stack.rend()) {
			registration_attempt_stack.erase(std::next(stack_entry).base());
		}
		if (registry == nullptr) {
			return;
		}
		std::lock_guard<std::mutex> lock(registry->mutex);
		const auto found = registry->active_registrations.find(key);
		if (found == registry->active_registrations.end()) {
			return;
		}
		if (--found->second == 0) {
			registry->active_registrations.erase(found);
			registry->registrations_drained.notify_all();
		}
	}

private:
	RegistryCore *registry = nullptr;
	ContextGeneration key;
};

void finish_instance_cleanup(ClassRecord *record) {
	std::lock_guard<std::mutex> lock(record->mutex);
	if (record->active_instance_cleanups > 0) {
		record->active_instance_cleanups--;
	}
	if (record->active_callbacks == 0 &&
			record->active_instance_cleanups == 0) {
		record->callbacks_drained.notify_all();
	}
}

std::string synthetic_property_method_name(const std::string &property,
		bool setter) {
	std::ostringstream encoded;
	encoded << "__foundry_java_property_" << (setter ? "set_" : "get_");
	encoded << std::hex << std::setfill('0');
	for (const unsigned char character : property) {
		encoded << std::setw(2) << static_cast<unsigned int>(character);
	}
	return encoded.str();
}

void set_call_error(FoundryExtensionCallError *error,
		FoundryExtensionCallErrorType type) noexcept {
	if (error != nullptr) {
		error->error = type;
		error->argument = 0;
		error->expected = 0;
	}
}

RegistryCore *registry_core(ClassRecord *record) {
	return record == nullptr ? nullptr
							 : static_cast<RegistryCore *>(record->registry);
}

thread_local std::vector<ClassRecord *> callback_stack;
thread_local std::vector<ClassRecord *> instance_cleanup_stack;
thread_local std::vector<ContextGeneration> shutdown_stack;

bool callback_on_this_thread(ClassRecord *record) {
	return std::find(callback_stack.begin(), callback_stack.end(), record) !=
			callback_stack.end();
}

bool instance_cleanup_on_this_thread(ClassRecord *record) {
	return std::find(instance_cleanup_stack.begin(), instance_cleanup_stack.end(),
				   record) != instance_cleanup_stack.end();
}

bool thread_owns_context_lease(const ContextGeneration &key) {
	if (std::find(registration_attempt_stack.begin(),
				registration_attempt_stack.end(),
				key) != registration_attempt_stack.end()) {
		return true;
	}
	const auto matches_key = [&key](const ClassRecord *record) {
		return record != nullptr && record->context == key.context &&
				record->generation == key.generation;
	};
	return std::any_of(callback_stack.begin(), callback_stack.end(), matches_key) ||
			std::any_of(instance_cleanup_stack.begin(),
					instance_cleanup_stack.end(), matches_key);
}

void run_instance_cleanup(
		ClassRecord *record, RegistrationInstanceToken instance) {
	RegistryCore *registry = registry_core(record);
	if (record == nullptr || registry == nullptr || instance == 0) {
		return;
	}
	instance_cleanup_stack.push_back(record);
	registry->callbacks->free_instance(record->access, instance);
	instance_cleanup_stack.pop_back();
	finish_instance_cleanup(record);
}

bool acquire_callback(ClassRecord *record, InstanceRecord *instance = nullptr) {
	if (record == nullptr) {
		return false;
	}
	std::lock_guard<std::mutex> lock(record->mutex);
	if (record->state != RecordState::ACTIVE ||
			(instance != nullptr &&
					(instance->owner != record || instance->state != RecordState::ACTIVE))) {
		return false;
	}
	record->active_callbacks++;
	callback_stack.push_back(record);
	return true;
}

void release_callback(ClassRecord *record) {
	if (record == nullptr) {
		return;
	}
	const auto stack_entry =
			std::find(callback_stack.rbegin(), callback_stack.rend(), record);
	if (stack_entry != callback_stack.rend()) {
		callback_stack.erase(std::next(stack_entry).base());
	}
	std::vector<RegistrationInstanceToken> released_instances;
	RegistryCore *registry = registry_core(record);
	{
		std::lock_guard<std::mutex> lock(record->mutex);
		if (record->active_callbacks > 0) {
			record->active_callbacks--;
		}
		if (record->active_callbacks == 0) {
			for (auto iterator = record->instances.begin();
					iterator != record->instances.end();) {
				InstanceRecord *instance = iterator->second.get();
				if (!instance->pending_free) {
					++iterator;
					continue;
				}
				if (instance->access_instance != 0) {
					released_instances.push_back(instance->access_instance);
					instance->access_instance = 0;
					record->active_instance_cleanups++;
				}
				instance->state = RecordState::TOMBSTONED;
				record->instance_tombstones.push_back(std::move(iterator->second));
				iterator = record->instances.erase(iterator);
			}
			if (record->active_instance_cleanups == 0) {
				record->callbacks_drained.notify_all();
			}
		}
	}
	if (registry != nullptr) {
		for (const RegistrationInstanceToken instance : released_instances) {
			run_instance_cleanup(record, instance);
		}
	}
}

InstanceRecord *instance_record(FoundryExtensionClassInstancePtr instance) {
	return static_cast<InstanceRecord *>(instance);
}

void registration_method_call(void *userdata,
		FoundryExtensionClassInstancePtr instance,
		const FoundryExtensionConstVariantPtr *arguments,
		FoundryExtensionInt argument_count,
		FoundryExtensionVariantPtr result,
		FoundryExtensionCallError *error) {
	auto *member = static_cast<MemberRecord *>(userdata);
	ClassRecord *record = member == nullptr ? nullptr : member->owner;
	RegistryCore *registry = registry_core(record);
	if (registry != nullptr && result != nullptr) {
		registry->services->initialize_nil(result);
	}
	InstanceRecord *native_instance = instance_record(instance);
	if (member == nullptr || registry == nullptr ||
			!acquire_callback(record, native_instance)) {
		set_call_error(error, FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD);
		return;
	}
	set_call_error(error, FOUNDRY_EXTENSION_CALL_OK);
	bool success = false;
	if (member->callback_kind == MemberCallbackKind::PROPERTY_GET) {
		success = registry->callbacks->get_property(
				record->access, native_instance->access_instance, member->java_name,
				result);
	} else if (member->callback_kind == MemberCallbackKind::PROPERTY_SET) {
		const std::size_t value_index =
				member->signature.arguments.size() > 1 ? 1 : 0;
		success = arguments != nullptr &&
				argument_count > static_cast<FoundryExtensionInt>(value_index) &&
				registry->callbacks->set_property(
						record->access, native_instance->access_instance,
						member->java_name, arguments[value_index]);
	} else {
		success = registry->callbacks->invoke(
				record->access, native_instance->access_instance, member->java_name,
				arguments, argument_count, result, error);
	}
	if (!success) {
		set_call_error(error, FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD);
	}
	release_callback(record);
}

FoundryExtensionBool registration_set(FoundryExtensionClassInstancePtr instance,
		FoundryExtensionConstStringNamePtr name,
		FoundryExtensionConstVariantPtr value) {
	InstanceRecord *native_instance = instance_record(instance);
	ClassRecord *record =
			native_instance == nullptr ? nullptr : native_instance->owner;
	RegistryCore *registry = registry_core(record);
	if (registry == nullptr || !acquire_callback(record, native_instance)) {
		return 0;
	}
	const std::string property_name = registry->services->string_name(name);
	MemberRecord *setter = nullptr;
	for (const std::unique_ptr<MemberRecord> &member : record->members) {
		if (member->callback_kind == MemberCallbackKind::PROPERTY_SET &&
				member->foundry_name == property_name) {
			setter = member.get();
			break;
		}
	}
	const bool success =
			setter != nullptr && registry->callbacks->set_property(record->access, native_instance->access_instance, setter->java_name, value);
	release_callback(record);
	return success ? 1 : 0;
}

FoundryExtensionBool registration_get(FoundryExtensionClassInstancePtr instance,
		FoundryExtensionConstStringNamePtr name,
		FoundryExtensionVariantPtr result) {
	InstanceRecord *native_instance = instance_record(instance);
	ClassRecord *record =
			native_instance == nullptr ? nullptr : native_instance->owner;
	RegistryCore *registry = registry_core(record);
	if (registry != nullptr && result != nullptr) {
		registry->services->initialize_nil(result);
	}
	if (registry == nullptr || !acquire_callback(record, native_instance)) {
		return 0;
	}
	const std::string property_name = registry->services->string_name(name);
	const auto found = record->properties.find(property_name);
	const bool success = found != record->properties.end() &&
			registry->callbacks->get_property(
					record->access, native_instance->access_instance,
					found->second->java_name, result);
	release_callback(record);
	return success ? 1 : 0;
}

void registration_to_string(FoundryExtensionClassInstancePtr instance,
		FoundryExtensionBool *valid,
		FoundryExtensionStringPtr result) {
	if (valid != nullptr) {
		*valid = 0;
	}
	InstanceRecord *native_instance = instance_record(instance);
	ClassRecord *record =
			native_instance == nullptr ? nullptr : native_instance->owner;
	RegistryCore *registry = registry_core(record);
	if (registry == nullptr || !acquire_callback(record, native_instance)) {
		return;
	}
	const bool success = registry->callbacks->to_string(
			record->access, native_instance->access_instance, result);
	if (valid != nullptr) {
		*valid = success ? 1 : 0;
	}
	release_callback(record);
}

FoundryExtensionObjectPtr
registration_create_instance(void *userdata,
		FoundryExtensionBool notify_postinitialize) {
	auto *record = static_cast<ClassRecord *>(userdata);
	RegistryCore *registry = registry_core(record);
	if (registry == nullptr || !acquire_callback(record)) {
		return nullptr;
	}
	auto instance = std::make_unique<InstanceRecord>();
	instance->owner = record;
	InstanceRecord *stable_instance = instance.get();
	const NativeInstance created = registry->callbacks->create_instance(
			record->access, record->foundry_name, record->base_name,
			notify_postinitialize != 0, stable_instance);
	if (created.object == nullptr || created.access_instance == 0) {
		if (created.object != nullptr || created.access_instance != 0) {
			registry->callbacks->discard_partial_instance(record->access, created);
		}
		release_callback(record);
		return nullptr;
	}
	stable_instance->access_instance = created.access_instance;
	stable_instance->state = RecordState::ACTIVE;
	bool admitted = false;
	{
		std::lock_guard<std::mutex> lock(record->mutex);
		if (record->state == RecordState::ACTIVE) {
			record->instances.emplace(stable_instance, std::move(instance));
			admitted = true;
		} else {
			stable_instance->state = RecordState::TOMBSTONED;
			record->instance_tombstones.push_back(std::move(instance));
		}
	}
	if (!admitted) {
		registry->callbacks->discard_partial_instance(record->access, created);
		release_callback(record);
		return nullptr;
	}
	release_callback(record);
	return created.object;
}

void registration_free_instance(void *userdata,
		FoundryExtensionClassInstancePtr instance) {
	auto *record = static_cast<ClassRecord *>(userdata);
	RegistryCore *registry = registry_core(record);
	if (record == nullptr || registry == nullptr || instance == nullptr) {
		return;
	}
	RegistrationInstanceToken access_instance = 0;
	{
		std::unique_lock<std::mutex> lock(record->mutex);
		auto found = record->instances.find(instance);
		if (found == record->instances.end() ||
				found->second->state != RecordState::ACTIVE) {
			return;
		}
		found->second->state = RecordState::RETIRING;
		if (record->active_callbacks > 0 && callback_on_this_thread(record)) {
			found->second->pending_free = true;
			return;
		}
		record->callbacks_drained.wait(
				lock, [record] { return record->active_callbacks == 0; });
		found = record->instances.find(instance);
		if (found == record->instances.end()) {
			return;
		}
		access_instance = found->second->access_instance;
		found->second->access_instance = 0;
		if (access_instance != 0) {
			record->active_instance_cleanups++;
		}
		found->second->state = RecordState::TOMBSTONED;
		record->instance_tombstones.push_back(std::move(found->second));
		record->instances.erase(found);
	}
	if (access_instance != 0) {
		run_instance_cleanup(record, access_instance);
	}
}

void *registration_get_virtual_call_data(
		void *userdata, FoundryExtensionConstStringNamePtr name, std::uint32_t) {
	auto *record = static_cast<ClassRecord *>(userdata);
	RegistryCore *registry = registry_core(record);
	if (registry == nullptr || !acquire_callback(record)) {
		return nullptr;
	}
	const std::string virtual_name = registry->services->string_name(name);
	const auto found = record->overrides.find(virtual_name);
	void *result = found == record->overrides.end() ? nullptr : found->second;
	release_callback(record);
	return result;
}

void registration_call_virtual_with_data(
		FoundryExtensionClassInstancePtr instance,
		FoundryExtensionConstStringNamePtr, void *virtual_userdata,
		const FoundryExtensionConstTypePtr *arguments,
		FoundryExtensionTypePtr result) {
	auto *member = static_cast<MemberRecord *>(virtual_userdata);
	InstanceRecord *native_instance = instance_record(instance);
	ClassRecord *record = member == nullptr ? nullptr : member->owner;
	RegistryCore *registry = registry_core(record);
	if (member != nullptr && registry != nullptr) {
		registry->callbacks->initialize_virtual_default(
				member->signature.return_type, result);
	}
	if (registry == nullptr || native_instance == nullptr ||
			member->callback_kind != MemberCallbackKind::OVERRIDE ||
			!acquire_callback(record, native_instance)) {
		return;
	}
	(void)registry->callbacks->invoke_virtual(
			record->access, native_instance->access_instance, member->java_name,
			arguments, result);
	release_callback(record);
}

} // namespace

JavaTypeParseResult parse_java_property_type(const std::string &signature) {
	return parse_java_type(signature, false);
}

const std::array<std::string_view, 9> &required_registration_service_names() {
	static constexpr std::array<std::string_view, 9> names = { {
			"classdb_register_extension_class5",
			"classdb_register_extension_class_method",
			"classdb_register_extension_class_integer_constant",
			"classdb_register_extension_class_property",
			"classdb_register_extension_class_property_indexed",
			"classdb_register_extension_class_property_group",
			"classdb_register_extension_class_property_subgroup",
			"classdb_register_extension_class_signal",
			"classdb_unregister_extension_class",
	} };
	return names;
}

JavaSignatureParseResult
parse_java_method_signature(const std::string &signature) {
	const std::size_t open = signature.find('(');
	if (open == std::string::npos || open == 0 || signature.back() != ')' ||
			signature.find('(', open + 1) != std::string::npos ||
			signature.find(')', open) != signature.size() - 1) {
		return { {}, "registration_signature" };
	}
	const JavaTypeParseResult return_type =
			parse_java_type(signature.substr(0, open), true);
	if (!return_type.ok()) {
		return { {}, return_type.phase };
	}
	JavaMethodSignature parsed;
	parsed.return_type = return_type.type;
	std::size_t cursor = open + 1;
	const std::size_t end = signature.size() - 1;
	while (cursor < end) {
		std::size_t argument_end = cursor;
		std::size_t generic_depth = 0;
		for (; argument_end < end; argument_end++) {
			const char character = signature[argument_end];
			if (character == '<') {
				generic_depth++;
			} else if (character == '>') {
				if (generic_depth == 0) {
					return { {}, "registration_signature" };
				}
				generic_depth--;
			} else if (character == ',' && generic_depth == 0) {
				break;
			}
		}
		if (generic_depth != 0) {
			return { {}, "registration_signature" };
		}
		if (argument_end > end || argument_end == cursor) {
			return { {}, "registration_signature" };
		}
		JavaTypeParseResult argument =
				parse_java_type(signature.substr(cursor, argument_end - cursor), false);
		if (!argument.ok()) {
			return { {}, argument.phase };
		}
		parsed.arguments.push_back(std::move(argument.type));
		if (argument_end == end) {
			cursor = end;
		} else {
			cursor = argument_end + 1;
			if (cursor == end) {
				return { {}, "registration_signature" };
			}
		}
	}
	return { std::move(parsed), {} };
}

struct RegistrationRegistry::Impl : RegistryCore {};

RegistrationRegistry::RegistrationRegistry(
		std::shared_ptr<RegistrationServices> services,
		std::shared_ptr<RegistrationCallbacks> callbacks) : impl(std::make_unique<Impl>()) {
	impl->services = std::move(services);
	impl->callbacks = std::move(callbacks);
}

RegistrationRegistry::~RegistrationRegistry() {
	std::vector<ContextGeneration> contexts;
	{
		std::lock_guard<std::mutex> lock(impl->mutex);
		std::unordered_set<ContextGeneration, ContextGenerationHash> unique;
		for (const auto &entry : impl->classes) {
			const ContextGeneration key{
				entry.second->context,
				entry.second->generation,
			};
			if (unique.insert(key).second) {
				contexts.push_back(key);
			}
		}
	}
	for (const ContextGeneration &key : contexts) {
		(void)shutdown(key.context, key.generation);
	}
	{
		std::lock_guard<std::mutex> lock(impl->mutex);
		if (!impl->classes.empty()) {
			// Native class userdata may still point into this registry after a
			// failed unregister. Preserve it rather than create dangling callbacks.
			(void)impl.release();
		}
	}
}

RegistrationResult RegistrationRegistry::register_class(
		std::uint64_t context, std::uint64_t generation,
		FoundryExtensionClassLibraryPtr library,
		RegistrationClassDescriptor descriptor) noexcept {
	const ContextGeneration context_generation{ context, generation };
	{
		std::lock_guard<std::mutex> lock(impl->mutex);
		if (impl->shutting_down.find(context_generation) !=
				impl->shutting_down.end()) {
			return { RegistrationStatus::STALE, "registration_shutdown" };
		}
		impl->active_registrations[context_generation]++;
	}
	RegistrationAttempt registration_attempt(impl.get(), context_generation);
	const RegistrationResult validation = validate_descriptor(descriptor);
	if (!validation.ok()) {
		return validation;
	}
	std::unordered_map<const RegistrationMemberDescriptor *, JavaMethodSignature>
			prevalidated_signatures;
	std::unordered_map<const RegistrationMemberDescriptor *, JavaTransportType>
			prevalidated_types;
	const auto resolve_type = [&](JavaTransportType &type) {
		if (type.abi_type != FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT) {
			return true;
		}
		std::string foundry_type;
		if (!impl->services->resolve_object_type(type.java_type, foundry_type) ||
				blank(foundry_type)) {
			return false;
		}
		type.class_name = std::move(foundry_type);
		return true;
	};
	for (const RegistrationMemberDescriptor &member : descriptor.members) {
		const bool callable = member.kind == RegistrationMemberKind::METHOD ||
				member.kind == RegistrationMemberKind::OVERRIDE ||
				member.kind == RegistrationMemberKind::SIGNAL;
		if (callable) {
			JavaMethodSignature signature =
					parse_java_method_signature(member.signature).signature;
			if (!resolve_type(signature.return_type)) {
				return { RegistrationStatus::INVALID_DESCRIPTOR, "object_type" };
			}
			for (JavaTransportType &argument : signature.arguments) {
				if (!resolve_type(argument)) {
					return { RegistrationStatus::INVALID_DESCRIPTOR, "object_type" };
				}
			}
			prevalidated_signatures.emplace(&member, std::move(signature));
		} else {
			JavaTransportType type =
					parse_java_property_type(member.signature).type;
			if (!resolve_type(type)) {
				return { RegistrationStatus::INVALID_DESCRIPTOR, "object_type" };
			}
			prevalidated_types.emplace(&member, std::move(type));
		}
	}
	auto record = std::make_unique<ClassRecord>();
	record->registry = impl.get();
	record->context = context;
	record->generation = generation;
	record->library = library;
	record->foundry_name = descriptor.foundry_name;
	record->base_name = descriptor.base_name;
	record->access = descriptor.access;

	std::vector<const RegistrationMemberDescriptor *> methods;
	std::vector<const RegistrationMemberDescriptor *> constants;
	std::vector<const RegistrationMemberDescriptor *> properties;
	std::vector<const RegistrationMemberDescriptor *> signals;
	for (const RegistrationMemberDescriptor &member : descriptor.members) {
		if (member.kind == RegistrationMemberKind::METHOD) {
			methods.push_back(&member);
		} else if (member.kind == RegistrationMemberKind::OVERRIDE) {
			auto native = std::make_unique<MemberRecord>();
			native->owner = record.get();
			native->callback_kind = MemberCallbackKind::OVERRIDE;
			native->foundry_name = member.foundry_name;
			native->java_name = member.java_name;
			native->signature = prevalidated_signatures.at(&member);
			record->overrides.emplace(native->foundry_name, native.get());
			record->members.push_back(std::move(native));
		} else if (member.kind == RegistrationMemberKind::CONSTANT) {
			constants.push_back(&member);
		} else if (member.kind == RegistrationMemberKind::PROPERTY) {
			properties.push_back(&member);
		} else {
			signals.push_back(&member);
		}
	}
	const auto member_order = [](const RegistrationMemberDescriptor *left,
									  const RegistrationMemberDescriptor *right) {
		if (left->foundry_name != right->foundry_name) {
			return left->foundry_name < right->foundry_name;
		}
		if (left->java_name != right->java_name) {
			return left->java_name < right->java_name;
		}
		return left->signature < right->signature;
	};
	std::sort(methods.begin(), methods.end(), member_order);
	std::sort(constants.begin(), constants.end(), member_order);
	std::sort(properties.begin(), properties.end(), member_order);
	std::sort(signals.begin(), signals.end(), member_order);

	ClassRecord *stable_record = record.get();
	{
		std::lock_guard<std::mutex> lock(impl->mutex);
		if (impl->classes.find(record->foundry_name) != impl->classes.end()) {
			return { RegistrationStatus::CONFLICT, "registration_conflict" };
		}
		impl->classes.emplace(record->foundry_name, std::move(record));
	}

	const auto fail = [&](const std::string &phase) {
		if (stable_record->native_registered) {
			if (!impl->services->unregister_class(library,
						stable_record->foundry_name)) {
				std::lock_guard<std::mutex> lock(impl->mutex);
				std::lock_guard<std::mutex> record_lock(stable_record->mutex);
				stable_record->state = RecordState::QUARANTINED;
				if (std::find(impl->registration_order.begin(),
							impl->registration_order.end(),
							stable_record->foundry_name) ==
						impl->registration_order.end()) {
					impl->registration_order.push_back(stable_record->foundry_name);
				}
				return RegistrationResult{
					RegistrationStatus::NATIVE_FAILURE,
					"registration_rollback",
				};
			}
		}
		const RegistrationAccessToken access = stable_record->access;
		stable_record->access = 0;
		if (access != 0) {
			impl->callbacks->release_access(access);
		}
		std::lock_guard<std::mutex> lock(impl->mutex);
		auto found = impl->classes.find(stable_record->foundry_name);
		if (found != impl->classes.end()) {
			{
				std::lock_guard<std::mutex> record_lock(found->second->mutex);
				found->second->state = RecordState::TOMBSTONED;
			}
			impl->tombstones.push_back(std::move(found->second));
			impl->classes.erase(found);
		}
		return RegistrationResult{ RegistrationStatus::NATIVE_FAILURE, phase };
	};

	NativeClassRegistration class_registration;
	class_registration.class_name = stable_record->foundry_name;
	class_registration.base_name = stable_record->base_name;
	class_registration.creation_info.is_virtual = 0;
	class_registration.creation_info.is_abstract = 0;
	class_registration.creation_info.is_exposed = 1;
	class_registration.creation_info.is_runtime = 0;
	class_registration.creation_info.set_func = &registration_set;
	class_registration.creation_info.get_func = &registration_get;
	class_registration.creation_info.to_string_func = &registration_to_string;
	class_registration.creation_info.create_instance_func =
			&registration_create_instance;
	class_registration.creation_info.free_instance_func =
			&registration_free_instance;
	class_registration.creation_info.get_virtual_func = nullptr;
	class_registration.creation_info.get_virtual_call_data_func =
			&registration_get_virtual_call_data;
	class_registration.creation_info.call_virtual_with_data_func =
			&registration_call_virtual_with_data;
	class_registration.creation_info.class_userdata = stable_record;
	stable_record->native_registered = true;
	if (!impl->services->register_class(library, class_registration)) {
		return fail("registration_class");
	}

	const auto register_method = [&](const RegistrationMemberDescriptor &member) {
		auto native = std::make_unique<MemberRecord>();
		native->owner = stable_record;
		native->callback_kind = MemberCallbackKind::METHOD;
		native->foundry_name = member.foundry_name;
		native->java_name = member.java_name;
		native->signature = prevalidated_signatures.at(&member);
		NativeMethodRegistration registration;
		registration.name = native->foundry_name;
		registration.java_name = native->java_name;
		registration.signature = native->signature;
		registration.userdata = native.get();
		registration.call = &registration_method_call;
		if (!impl->services->register_method(library, stable_record->foundry_name,
					registration)) {
			return false;
		}
		stable_record->members.push_back(std::move(native));
		return true;
	};
	for (const RegistrationMemberDescriptor *member : methods) {
		if (!register_method(*member)) {
			return fail("registration_method");
		}
	}
	for (const RegistrationMemberDescriptor *member : constants) {
		const RegistrationConstantDetails &details = *member->constant;
		if (!impl->services->register_integer_constant(
					library, stable_record->foundry_name,
					{ member->foundry_name, details.enum_name, details.value,
							details.bitfield })) {
			return fail("registration_constant");
		}
	}
	std::string current_group;
	std::string current_group_prefix;
	std::string current_subgroup;
	std::string current_subgroup_prefix;
	for (const RegistrationMemberDescriptor *member : properties) {
		RegistrationPropertyDetails details;
		if (member->property) {
			details = *member->property;
		} else {
			details.getter = member->java_name;
		}
		if (details.group_name != current_group ||
				details.group_prefix != current_group_prefix) {
			if (!current_subgroup.empty() || !current_subgroup_prefix.empty()) {
				if (!impl->services->register_property_subgroup(
							library, stable_record->foundry_name, {}, {})) {
					return fail("registration_property_subgroup");
				}
			}
			current_subgroup.clear();
			current_subgroup_prefix.clear();
			if (!impl->services->register_property_group(
						library, stable_record->foundry_name, details.group_name,
						details.group_prefix)) {
				return fail("registration_property_group");
			}
			current_group = details.group_name;
			current_group_prefix = details.group_prefix;
		}
		if (details.subgroup_name != current_subgroup ||
				details.subgroup_prefix != current_subgroup_prefix) {
			if (!impl->services->register_property_subgroup(
						library, stable_record->foundry_name, details.subgroup_name,
						details.subgroup_prefix)) {
				return fail("registration_property_subgroup");
			}
			current_subgroup = details.subgroup_name;
			current_subgroup_prefix = details.subgroup_prefix;
		}
		const JavaTransportType property_type = prevalidated_types.at(member);
		auto getter = std::make_unique<MemberRecord>();
		getter->owner = stable_record;
		getter->callback_kind = MemberCallbackKind::PROPERTY_GET;
		getter->foundry_name = member->foundry_name;
		getter->java_name = details.getter;
		getter->signature.return_type = property_type;
		if (details.index >= 0) {
			getter->signature.arguments.push_back(
					parse_java_property_type("long").type);
		}
		NativeMethodRegistration getter_registration;
		getter_registration.name =
				synthetic_property_method_name(member->foundry_name, false);
		getter_registration.java_name = getter->java_name;
		getter_registration.signature = getter->signature;
		getter_registration.userdata = getter.get();
		getter_registration.call = &registration_method_call;
		getter_registration.property_accessor = true;
		if (!impl->services->register_method(library, stable_record->foundry_name,
					getter_registration)) {
			return fail("registration_property_getter");
		}
		stable_record->properties.emplace(member->foundry_name, getter.get());
		stable_record->members.push_back(std::move(getter));

		std::string setter_name;
		if (!details.setter.empty()) {
			auto setter = std::make_unique<MemberRecord>();
			setter->owner = stable_record;
			setter->callback_kind = MemberCallbackKind::PROPERTY_SET;
			setter->foundry_name = member->foundry_name;
			setter->java_name = details.setter;
			setter->signature.return_type.is_void = true;
			setter->signature.return_type.java_type = "void";
			if (details.index >= 0) {
				setter->signature.arguments.push_back(
						parse_java_property_type("long").type);
			}
			setter->signature.arguments.push_back(property_type);
			NativeMethodRegistration setter_registration;
			setter_registration.name =
					synthetic_property_method_name(member->foundry_name, true);
			setter_registration.java_name = setter->java_name;
			setter_registration.signature = setter->signature;
			setter_registration.userdata = setter.get();
			setter_registration.call = &registration_method_call;
			setter_registration.property_accessor = true;
			setter_name = setter_registration.name;
			if (!impl->services->register_method(library, stable_record->foundry_name,
						setter_registration)) {
				return fail("registration_property_setter");
			}
			stable_record->members.push_back(std::move(setter));
		}
		NativePropertyRegistration registration;
		registration.name = member->foundry_name;
		registration.type = property_type;
		registration.getter = getter_registration.name;
		registration.setter = setter_name;
		registration.index = details.index;
		const bool registered =
				details.index < 0
				? impl->services->register_property(
						  library, stable_record->foundry_name, registration)
				: impl->services->register_indexed_property(
						  library, stable_record->foundry_name, registration);
		if (!registered) {
			return fail("registration_property");
		}
	}
	if (!current_subgroup.empty() || !current_subgroup_prefix.empty()) {
		if (!impl->services->register_property_subgroup(
					library, stable_record->foundry_name, {}, {})) {
			return fail("registration_property_subgroup");
		}
	}
	if (!current_group.empty() || !current_group_prefix.empty()) {
		if (!impl->services->register_property_group(
					library, stable_record->foundry_name, {}, {})) {
			return fail("registration_property_group");
		}
	}
	for (const RegistrationMemberDescriptor *member : signals) {
		const JavaMethodSignature signature = prevalidated_signatures.at(member);
		if (!signature.return_type.is_void ||
				!impl->services->register_signal(
						library, stable_record->foundry_name,
						{ member->foundry_name, signature.arguments })) {
			return fail("registration_signal");
		}
	}
	{
		std::lock_guard<std::mutex> lock(impl->mutex);
		impl->registration_order.push_back(stable_record->foundry_name);
		std::lock_guard<std::mutex> record_lock(stable_record->mutex);
		stable_record->state = RecordState::ACTIVE;
	}
	return {};
}

RegistrationResult
RegistrationRegistry::unregister_class(std::uint64_t context,
		std::uint64_t generation,
		const std::string &class_name) noexcept {
	ClassRecord *record = nullptr;
	{
		std::lock_guard<std::mutex> lock(impl->mutex);
		const auto found = impl->classes.find(class_name);
		if (found == impl->classes.end() || found->second->context != context ||
				found->second->generation != generation) {
			return { RegistrationStatus::STALE, "registration_stale" };
		}
		if (callback_on_this_thread(found->second.get()) ||
				instance_cleanup_on_this_thread(found->second.get())) {
			return { RegistrationStatus::RETRY, "registration_reentrant_cleanup" };
		}
		std::lock_guard<std::mutex> record_lock(found->second->mutex);
		if (found->second->state != RecordState::ACTIVE &&
				found->second->state != RecordState::QUARANTINED) {
			return { RegistrationStatus::STALE, "registration_stale" };
		}
		const RecordState previous_state = found->second->state;
		found->second->state = RecordState::RETIRING;
		found->second->state_before_retiring = previous_state;
		record = found->second.get();
	}
	if (!impl->services->unregister_class(record->library,
				record->foundry_name)) {
		std::lock_guard<std::mutex> lock(record->mutex);
		if (record->state == RecordState::RETIRING) {
			record->state = record->state_before_retiring;
		}
		return { RegistrationStatus::NATIVE_FAILURE, "registration_unregister" };
	}
	std::vector<RegistrationInstanceToken> released_instances;
	{
		std::unique_lock<std::mutex> lock(record->mutex);
		record->callbacks_drained.wait(
				lock, [record] {
					return record->active_callbacks == 0 &&
							record->active_instance_cleanups == 0;
				});
		for (auto iterator = record->instances.begin();
				iterator != record->instances.end();) {
			InstanceRecord *instance = iterator->second.get();
			if (instance->access_instance != 0) {
				released_instances.push_back(instance->access_instance);
				instance->access_instance = 0;
				record->active_instance_cleanups++;
			}
			instance->state = RecordState::TOMBSTONED;
			record->instance_tombstones.push_back(std::move(iterator->second));
			iterator = record->instances.erase(iterator);
		}
	}
	for (const RegistrationInstanceToken instance : released_instances) {
		run_instance_cleanup(record, instance);
	}
	const RegistrationAccessToken access = record->access;
	record->access = 0;
	if (access != 0) {
		impl->callbacks->release_access(access);
	}
	{
		std::lock_guard<std::mutex> lock(impl->mutex);
		auto found = impl->classes.find(class_name);
		if (found == impl->classes.end() || found->second.get() != record) {
			return { RegistrationStatus::STALE, "registration_stale" };
		}
		{
			std::lock_guard<std::mutex> record_lock(record->mutex);
			record->state = RecordState::TOMBSTONED;
		}
		impl->tombstones.push_back(std::move(found->second));
		impl->classes.erase(found);
		impl->registration_order.erase(std::remove(impl->registration_order.begin(),
											   impl->registration_order.end(),
											   class_name),
				impl->registration_order.end());
	}
	return {};
}

RegistrationResult RegistrationRegistry::rollback(
		std::uint64_t context, std::uint64_t generation,
		const std::vector<std::string> &completed_classes) noexcept {
	RegistrationResult first_failure;
	for (auto iterator = completed_classes.rbegin();
			iterator != completed_classes.rend(); ++iterator) {
		const RegistrationResult result =
				unregister_class(context, generation, *iterator);
		if (!result.ok() && first_failure.ok()) {
			first_failure = result;
		}
	}
	return first_failure;
}

RegistrationResult
RegistrationRegistry::shutdown(std::uint64_t context,
		std::uint64_t generation) noexcept {
	const ContextGeneration key{ context, generation };
	std::vector<std::string> classes;
	{
		std::unique_lock<std::mutex> lock(impl->mutex);
		if (thread_owns_context_lease(key)) {
			return { RegistrationStatus::RETRY,
				"registration_reentrant_cleanup" };
		}
		impl->shutting_down.insert(key);
		impl->registrations_drained.wait(lock, [&] {
			return impl->active_registrations.find(key) ==
					impl->active_registrations.end();
		});
		if (impl->shutdown_in_progress.find(key) !=
				impl->shutdown_in_progress.end()) {
			if (std::find(shutdown_stack.begin(), shutdown_stack.end(), key) !=
					shutdown_stack.end()) {
				return { RegistrationStatus::RETRY,
					"registration_reentrant_cleanup" };
			}
			impl->shutdown_finished.wait(lock, [&] {
				return impl->shutdown_in_progress.find(key) ==
						impl->shutdown_in_progress.end();
			});
			return impl->shutdown_results.at(key);
		}
		impl->shutdown_in_progress.insert(key);
		for (const std::string &name : impl->registration_order) {
			const auto found = impl->classes.find(name);
			if (found != impl->classes.end() && found->second->context == context &&
					found->second->generation == generation) {
				classes.push_back(name);
			}
		}
	}
	shutdown_stack.push_back(key);
	const RegistrationResult result = rollback(context, generation, classes);
	shutdown_stack.pop_back();
	{
		std::lock_guard<std::mutex> lock(impl->mutex);
		impl->shutdown_results[key] = result;
		impl->shutdown_in_progress.erase(key);
		impl->shutdown_finished.notify_all();
	}
	return result;
}

std::size_t RegistrationRegistry::active_class_count() const noexcept {
	std::lock_guard<std::mutex> lock(impl->mutex);
	return impl->classes.size();
}

std::size_t RegistrationRegistry::tombstone_count() const noexcept {
	std::lock_guard<std::mutex> lock(impl->mutex);
	return impl->tombstones.size();
}

} // namespace foundry_java
