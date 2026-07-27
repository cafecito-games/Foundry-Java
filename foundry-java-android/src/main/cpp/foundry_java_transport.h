#pragma once

#include "foundry_java_interface.h"
#include "foundry_java_runtime.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace foundry_java {

using NativeHandle = std::uint64_t;

enum class HandleKind : std::uint8_t {
	VARIANT,
	NATIVE_STRUCTURE,
	OBJECT,
	CALLABLE,
	SIGNAL,
};

struct VariantCategoryInfo {
	std::string_view java_name;
	std::string_view native_name;
	FoundryExtensionVariantType abi_type;
};

const std::array<VariantCategoryInfo, 39> &variant_categories();
const VariantCategoryInfo *variant_category(FoundryExtensionVariantType type);
const VariantCategoryInfo *variant_category(std::string_view native_name);

enum class NativeTypeKind : std::uint8_t {
	VOID,
	BUILTIN,
	OBJECT,
	NATIVE_STRUCTURE,
};

struct NormalizedNativeType {
	NativeTypeKind kind = NativeTypeKind::OBJECT;
	std::string_view token;
	FoundryExtensionVariantType abi_type = FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
};

NormalizedNativeType normalize_native_type(std::string_view token);

enum class DispatchKind : std::int32_t {
	CLASS_METHOD = 1,
	CLASS_PROPERTY = 2,
	CLASS_SIGNAL = 3,
	BUILTIN_METHOD = 4,
	BUILTIN_CONSTRUCTOR = 5,
	BUILTIN_OPERATOR = 6,
	BUILTIN_MEMBER = 7,
	BUILTIN_CONSTANT = 8,
	UTILITY_FUNCTION = 9,
};

struct NativeDispatch {
	std::string identity;
	DispatchKind kind = DispatchKind::CLASS_METHOD;
	std::string owner_native_type;
	std::string native_name;
	std::int64_t compatibility_hash = 0;
	std::int32_t constructor_index = -1;
	std::vector<std::string> argument_native_types;
	std::size_t minimum_argument_count = 0;
	std::string return_native_type;
	std::string getter_identity;
	std::string getter_native_name;
	std::int64_t getter_compatibility_hash = 0;
	std::string setter_identity;
	std::string setter_native_name;
	std::int64_t setter_compatibility_hash = 0;
	bool vararg = false;
	bool static_call = false;
};

struct DispatchValidation {
	bool valid = false;
	std::string phase;
};

DispatchValidation validate_dispatch(
		const NativeDispatch &dispatch,
		std::size_t formal_argument_count,
		std::string_view receiver_native_type);

enum class DispatchFamily : std::uint8_t {
	INVALID,
	CLASS_VARIANT_CALL,
	CLASS_PTRCALL,
	CLASS_PROPERTY,
	CLASS_SIGNAL,
	BUILTIN_METHOD,
	BUILTIN_CONSTRUCTOR,
	BUILTIN_OPERATOR,
	BUILTIN_MEMBER,
	BUILTIN_CONSTANT,
	UTILITY_FUNCTION,
};

DispatchFamily dispatch_family(const NativeDispatch &dispatch);

enum class ValueBackend : std::uint8_t {
	JAVA_LOCAL,
	NATIVE,
};

DispatchValidation validate_value_backend(
		FoundryExtensionVariantType type,
		ValueBackend backend);

struct NativeValue {
	std::vector<std::max_align_t> words;
	std::size_t byte_size = 0;
	std::uint64_t object_instance_id = 0;
	bool constructed = false;

	static NativeValue storage(std::size_t size);
	void *data() noexcept;
	const void *data() const noexcept;
};

struct TransportResult {
	bool ok = false;
	std::string phase;
	FoundryExtensionCallError call_error{};
};

struct DispatchCall {
	FoundryExtensionObjectPtr object = nullptr;
	FoundryExtensionVariantPtr receiver_variant = nullptr;
	FoundryExtensionTypePtr receiver_native = nullptr;
	std::string receiver_native_type;
	std::vector<FoundryExtensionConstVariantPtr> variant_arguments;
	std::vector<FoundryExtensionConstTypePtr> native_arguments;
	FoundryExtensionUninitializedVariantPtr variant_result = nullptr;
	FoundryExtensionTypePtr native_result = nullptr;
	bool property_set = false;
	FoundryExtensionVariantOperator variant_operator = FOUNDRY_EXTENSION_VARIANT_OP_MAX;
};

void prepare_native_arguments_for_dispatch(
		const NativeDispatch &dispatch,
		DispatchCall &call);

struct CollectionEntry {
	NativeValue key;
	NativeValue value;
};

using LocalCallable = std::function<void(
		const FoundryExtensionConstVariantPtr *,
		FoundryExtensionInt,
		FoundryExtensionVariantPtr,
		FoundryExtensionCallError *)>;

struct HandleRecord {
	ContextHandle context = 0;
	std::uint64_t generation = 0;
	HandleKind kind = HandleKind::VARIANT;
	std::string expected_type;
	NativeValue value;
	bool owned = false;
	bool live = false;
};

struct SharedHandleRecord;

class HandleLease final {
public:
	HandleLease() = default;
	~HandleLease();
	HandleLease(HandleLease &&other) noexcept;
	HandleLease &operator=(HandleLease &&other) noexcept;

	HandleLease(const HandleLease &) = delete;
	HandleLease &operator=(const HandleLease &) = delete;

	explicit operator bool() const noexcept;
	const HandleRecord &record() const;

private:
	explicit HandleLease(std::shared_ptr<SharedHandleRecord> record);
	void reset() noexcept;

	std::shared_ptr<SharedHandleRecord> shared_record;

	friend class NativeHandleStore;
};

class NativeHandleStore final {
public:
	using Destroy = std::function<void(HandleRecord &)>;

	NativeHandleStore();
	~NativeHandleStore();
	NativeHandleStore(const NativeHandleStore &) = delete;
	NativeHandleStore &operator=(const NativeHandleStore &) = delete;

	NativeHandle insert(
			ContextHandle context,
			std::uint64_t generation,
			HandleKind kind,
			std::string expected_type,
			NativeValue value,
			bool owned,
			Destroy destroy);
	HandleLease acquire(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			HandleKind kind,
			const std::string &expected_type);
	HandleLease inspect(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation);
	NativeHandle retain(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation) noexcept;
	bool promote_ownership(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			Destroy destroy) noexcept;
	bool release(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation) noexcept;
	bool release(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			HandleKind kind,
			const std::string &expected_type) noexcept;
	std::size_t teardown(ContextHandle context, std::uint64_t generation) noexcept;
	std::size_t size() const noexcept;

private:
	struct Impl;
	std::unique_ptr<Impl> impl;
};

struct ObjectLease {
	HandleLease handle;
	FoundryExtensionObjectPtr object = nullptr;

	explicit operator bool() const noexcept {
		return static_cast<bool>(handle) && object != nullptr;
	}
};

class NativeTransport final {
public:
	explicit NativeTransport(
			std::shared_ptr<const BridgeServices> services,
			void *extension_token = nullptr);

	NativeHandleStore &handles() noexcept;
	const NativeHandleStore &handles() const noexcept;
	NativeHandle create_native_structure(
			ContextHandle context,
			std::uint64_t generation,
			const std::string &expected_type);
	NativeHandle track_object(
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionObjectPtr object,
			std::string expected_type,
			bool owned,
			bool *created = nullptr);
	ObjectLease acquire_object(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			const std::string &expected_type);
	NativeHandle retain_ref_counted(
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionObjectPtr object,
			std::string expected_type,
			bool initialize = false,
			bool *ownership_consumed = nullptr);
	NativeHandle copy_variant(
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionConstVariantPtr source,
			FoundryExtensionVariantType expected_type);
	NativeHandle construct_variant(
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionVariantType type,
			FoundryExtensionConstTypePtr native_value,
			ValueBackend backend = ValueBackend::NATIVE);
	NativeHandle construct_string_variant(
			ContextHandle context,
			std::uint64_t generation,
			std::string_view utf8);
	NativeHandle construct_text_variant(
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionVariantType type,
			std::string_view utf8);
	NativeHandle construct_local_callable(
			ContextHandle context,
			std::uint64_t generation,
			LocalCallable callable,
			std::uint64_t identity,
			FoundryExtensionInt argument_count = -1);
	TransportResult inspect_variant(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionVariantType type,
			FoundryExtensionUninitializedTypePtr destination);
	TransportResult inspect_string_variant(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			std::string &destination);
	TransportResult inspect_text_variant(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionVariantType type,
			std::string &destination);
	NativeHandle construct_object_variant(
			ContextHandle context,
			std::uint64_t generation,
			NativeHandle object_handle,
			const std::string &expected_object_type);
	TransportResult inspect_object_instance_id(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			std::uint64_t &instance_id);
	TransportResult object_type(
			NativeHandle object_handle,
			ContextHandle context,
			std::uint64_t generation,
			const std::string &expected_object_type,
			FoundryExtensionClassLibraryPtr library,
			std::string &destination);
	NativeHandle copy_native_backed_variant(
			ContextHandle context,
			std::uint64_t generation,
			NativeHandle source_handle,
			FoundryExtensionVariantType type);
	NativeHandle construct_collection(
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionVariantType type,
			const std::vector<NativeHandle> &keys,
			const std::vector<NativeHandle> &values);
	TransportResult execute(
			const NativeDispatch &dispatch,
			const DispatchCall &call);
	TransportResult invoke_callable(
			FoundryExtensionVariantPtr callable,
			const std::vector<FoundryExtensionConstVariantPtr> &arguments,
			FoundryExtensionUninitializedVariantPtr result);
	TransportResult collection_get_named(
			FoundryExtensionConstVariantPtr collection,
			std::string_view name,
			FoundryExtensionUninitializedVariantPtr result);
	TransportResult collection_set_named(
			FoundryExtensionVariantPtr collection,
			std::string_view name,
			FoundryExtensionConstVariantPtr value);
	TransportResult collection_get_keyed(
			FoundryExtensionConstVariantPtr collection,
			FoundryExtensionConstVariantPtr key,
			FoundryExtensionUninitializedVariantPtr result);
	TransportResult collection_set_keyed(
			FoundryExtensionVariantPtr collection,
			FoundryExtensionConstVariantPtr key,
			FoundryExtensionConstVariantPtr value);
	TransportResult collection_get_indexed(
			FoundryExtensionConstVariantPtr collection,
			FoundryExtensionInt index,
			FoundryExtensionUninitializedVariantPtr result);
	TransportResult collection_set_indexed(
			FoundryExtensionVariantPtr collection,
			FoundryExtensionInt index,
			FoundryExtensionConstVariantPtr value);
	TransportResult collection_iterate(
			FoundryExtensionConstVariantPtr collection,
			FoundryExtensionVariantType collection_type,
			const std::function<bool(
					FoundryExtensionConstVariantPtr,
					FoundryExtensionConstVariantPtr)> &visitor);
	NativeHandle instantiate(
			ContextHandle context,
			std::uint64_t generation,
			const std::string &native_class);
	NativeHandle singleton(
			ContextHandle context,
			std::uint64_t generation,
			const std::string &native_name);
	bool is_object_valid(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation);
	bool is_object_assignable(
			FoundryExtensionObjectPtr object,
			const std::string &expected_type);
	TransportResult object_type(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionClassLibraryPtr library,
			std::string &destination);
	bool retain_handle(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation);
	bool release_handle(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation) noexcept;
	NativeHandle track_object_variant(
			NativeHandle variant_handle,
			ContextHandle context,
			std::uint64_t generation,
			bool *created = nullptr);
	TransportResult copy_variant_to(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionUninitializedVariantPtr destination);
	void destroy_native_value(
			FoundryExtensionVariantType type,
			FoundryExtensionTypePtr value) noexcept;

private:
	std::shared_ptr<const BridgeServices> services;
	void *extension_token = nullptr;
	NativeHandleStore handle_store;
	std::mutex object_identity_mutex;
	std::condition_variable object_identity_condition;
	std::unordered_map<std::string, NativeHandle> object_identity_handles;
};

} // namespace foundry_java
