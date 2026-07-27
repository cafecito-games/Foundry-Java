#include "foundry_java_transport.h"

#include <condition_variable>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <utility>

namespace foundry_java {

NativeValue NativeValue::storage(std::size_t size) {
	NativeValue value;
	value.byte_size = size;
	const std::size_t word_size = sizeof(std::max_align_t);
	value.words.resize((size + word_size - 1) / word_size);
	return value;
}

void *NativeValue::data() noexcept {
	return words.empty() ? nullptr : words.data();
}

const void *NativeValue::data() const noexcept {
	return words.empty() ? nullptr : words.data();
}

struct SharedHandleRecord {
	std::mutex mutex;
	std::condition_variable drained;
	HandleRecord record;
	NativeHandleStore::Destroy destroy;
	std::size_t active_leases = 0;
	bool accepting = true;
	bool destroyed = false;
};

namespace {

bool matches(
		const HandleRecord &record,
		ContextHandle context,
		std::uint64_t generation,
		HandleKind kind,
		const std::string &expected_type) {
	return record.live &&
			record.context == context &&
			record.generation == generation &&
			record.kind == kind &&
			record.expected_type == expected_type;
}

void drain_and_destroy(const std::shared_ptr<SharedHandleRecord> &shared) noexcept {
	NativeHandleStore::Destroy destroy;
	bool owned = false;
	{
		std::unique_lock lock(shared->mutex);
		shared->drained.wait(lock, [&] { return shared->active_leases == 0; });
		if (shared->destroyed) {
			return;
		}
		shared->record.live = false;
		shared->destroyed = true;
		destroy = std::move(shared->destroy);
		owned = shared->record.owned;
	}
	if (owned && destroy) {
		try {
			destroy(shared->record);
		} catch (...) {
		}
	}
}

} // namespace

HandleLease::HandleLease(std::shared_ptr<SharedHandleRecord> record) :
		shared_record(std::move(record)) {
}

HandleLease::~HandleLease() {
	reset();
}

HandleLease::HandleLease(HandleLease &&other) noexcept :
		shared_record(std::move(other.shared_record)) {
}

HandleLease &HandleLease::operator=(HandleLease &&other) noexcept {
	if (this != &other) {
		reset();
		shared_record = std::move(other.shared_record);
	}
	return *this;
}

HandleLease::operator bool() const noexcept {
	return shared_record != nullptr;
}

const HandleRecord &HandleLease::record() const {
	if (shared_record == nullptr) {
		throw std::logic_error("empty Foundry Java handle lease");
	}
	return shared_record->record;
}

HandleRecord &HandleLease::record() {
	if (shared_record == nullptr) {
		throw std::logic_error("empty Foundry Java handle lease");
	}
	return shared_record->record;
}

void HandleLease::reset() noexcept {
	if (shared_record == nullptr) {
		return;
	}
	{
		std::lock_guard lock(shared_record->mutex);
		shared_record->active_leases--;
		if (shared_record->active_leases == 0) {
			shared_record->drained.notify_all();
		}
	}
	shared_record.reset();
}

struct NativeHandleStore::Impl {
	mutable std::mutex mutex;
	std::unordered_map<NativeHandle, std::shared_ptr<SharedHandleRecord>> records;
	NativeHandle next_handle = 1;
};

NativeHandleStore::NativeHandleStore() :
		impl(std::make_unique<Impl>()) {
}

NativeHandleStore::~NativeHandleStore() {
	std::vector<std::shared_ptr<SharedHandleRecord>> records;
	{
		std::lock_guard lock(impl->mutex);
		records.reserve(impl->records.size());
		for (auto &[handle, shared] : impl->records) {
			(void)handle;
			std::lock_guard record_lock(shared->mutex);
			shared->accepting = false;
			records.push_back(std::move(shared));
		}
		impl->records.clear();
	}
	for (const auto &record : records) {
		drain_and_destroy(record);
	}
}

NativeHandle NativeHandleStore::insert(
		ContextHandle context,
		std::uint64_t generation,
		HandleKind kind,
		std::string expected_type,
		NativeValue value,
		bool owned,
		Destroy destroy) {
	if (context == 0 || generation == 0 || expected_type.empty()) {
		return 0;
	}
	auto shared = std::make_shared<SharedHandleRecord>();
	shared->record.context = context;
	shared->record.generation = generation;
	shared->record.kind = kind;
	shared->record.expected_type = std::move(expected_type);
	shared->record.value = std::move(value);
	shared->record.owned = owned;
	shared->record.live = true;
	shared->destroy = std::move(destroy);

	std::lock_guard lock(impl->mutex);
	if (impl->next_handle == 0 || impl->next_handle == std::numeric_limits<NativeHandle>::max()) {
		return 0;
	}
	const NativeHandle handle = impl->next_handle++;
	impl->records.emplace(handle, std::move(shared));
	return handle;
}

HandleLease NativeHandleStore::acquire(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		HandleKind kind,
		const std::string &expected_type) {
	std::lock_guard lock(impl->mutex);
	const auto found = impl->records.find(handle);
	if (found == impl->records.end()) {
		return {};
	}
	const auto &shared = found->second;
	std::lock_guard record_lock(shared->mutex);
	if (!shared->accepting || !matches(shared->record, context, generation, kind, expected_type)) {
		return {};
	}
	shared->active_leases++;
	return HandleLease(shared);
}

bool NativeHandleStore::release(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		HandleKind kind,
		const std::string &expected_type) noexcept {
	std::shared_ptr<SharedHandleRecord> shared;
	{
		std::lock_guard lock(impl->mutex);
		const auto found = impl->records.find(handle);
		if (found == impl->records.end()) {
			return false;
		}
		shared = found->second;
		std::lock_guard record_lock(shared->mutex);
		if (!shared->accepting || !matches(shared->record, context, generation, kind, expected_type)) {
			return false;
		}
		shared->accepting = false;
		impl->records.erase(found);
	}
	drain_and_destroy(shared);
	return true;
}

std::size_t NativeHandleStore::teardown(ContextHandle context, std::uint64_t generation) noexcept {
	std::vector<std::shared_ptr<SharedHandleRecord>> removed;
	{
		std::lock_guard lock(impl->mutex);
		for (auto iterator = impl->records.begin(); iterator != impl->records.end();) {
			const auto &shared = iterator->second;
			std::lock_guard record_lock(shared->mutex);
			if (shared->record.context == context &&
					shared->record.generation == generation &&
					shared->record.live) {
				shared->accepting = false;
				removed.push_back(shared);
				iterator = impl->records.erase(iterator);
			} else {
				++iterator;
			}
		}
	}
	for (const auto &shared : removed) {
		drain_and_destroy(shared);
	}
	return removed.size();
}

std::size_t NativeHandleStore::size() const noexcept {
	std::lock_guard lock(impl->mutex);
	return impl->records.size();
}

} // namespace foundry_java
