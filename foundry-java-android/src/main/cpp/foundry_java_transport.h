#pragma once

#include "foundry_java_interface.h"
#include "foundry_java_runtime.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <string_view>
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
	FoundryExtensionVariantType abi_type;
};

const std::array<VariantCategoryInfo, 39> &variant_categories();

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
	HandleRecord &record();

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
	explicit NativeTransport(std::shared_ptr<const BridgeServices> services);

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
			bool owned);
	ObjectLease acquire_object(
			NativeHandle handle,
			ContextHandle context,
			std::uint64_t generation,
			const std::string &expected_type);
	NativeHandle retain_ref_counted(
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionObjectPtr object,
			std::string expected_type);
	NativeHandle copy_variant(
			ContextHandle context,
			std::uint64_t generation,
			FoundryExtensionConstVariantPtr source,
			FoundryExtensionVariantType expected_type);

private:
	std::shared_ptr<const BridgeServices> services;
	NativeHandleStore handle_store;
};

} // namespace foundry_java
