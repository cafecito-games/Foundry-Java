#pragma once

#include "foundry_java_runtime.h"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
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

} // namespace foundry_java
