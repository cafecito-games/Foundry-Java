#include "foundry_java_transport.h"

#include "foundry_java_abi_layout.h"

#include <algorithm>
#include <array>
#include <condition_variable>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <utility>

namespace foundry_java {

const std::array<VariantCategoryInfo, 39> &variant_categories() {
	static constexpr std::array<VariantCategoryInfo, 39> categories = { {
		{ "NIL", FOUNDRY_EXTENSION_VARIANT_TYPE_NIL },
		{ "BOOLEAN", FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL },
		{ "INTEGER", FOUNDRY_EXTENSION_VARIANT_TYPE_INT },
		{ "FLOAT", FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT },
		{ "STRING", FOUNDRY_EXTENSION_VARIANT_TYPE_STRING },
		{ "VECTOR2", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2 },
		{ "VECTOR2I", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2I },
		{ "RECT2", FOUNDRY_EXTENSION_VARIANT_TYPE_RECT2 },
		{ "RECT2I", FOUNDRY_EXTENSION_VARIANT_TYPE_RECT2I },
		{ "VECTOR3", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3 },
		{ "VECTOR3I", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3I },
		{ "TRANSFORM2D", FOUNDRY_EXTENSION_VARIANT_TYPE_TRANSFORM2D },
		{ "VECTOR4", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR4 },
		{ "VECTOR4I", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR4I },
		{ "PLANE", FOUNDRY_EXTENSION_VARIANT_TYPE_PLANE },
		{ "QUATERNION", FOUNDRY_EXTENSION_VARIANT_TYPE_QUATERNION },
		{ "AABB", FOUNDRY_EXTENSION_VARIANT_TYPE_AABB },
		{ "BASIS", FOUNDRY_EXTENSION_VARIANT_TYPE_BASIS },
		{ "TRANSFORM3D", FOUNDRY_EXTENSION_VARIANT_TYPE_TRANSFORM3D },
		{ "PROJECTION", FOUNDRY_EXTENSION_VARIANT_TYPE_PROJECTION },
		{ "COLOR", FOUNDRY_EXTENSION_VARIANT_TYPE_COLOR },
		{ "STRING_NAME", FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME },
		{ "NODE_PATH", FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH },
		{ "RID", FOUNDRY_EXTENSION_VARIANT_TYPE_RID },
		{ "OBJECT", FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT },
		{ "CALLABLE", FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE },
		{ "SIGNAL", FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL },
		{ "DICTIONARY", FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY },
		{ "ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY },
		{ "PACKED_BYTE_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY },
		{ "PACKED_INT32_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY },
		{ "PACKED_INT64_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY },
		{ "PACKED_FLOAT32_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY },
		{ "PACKED_FLOAT64_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY },
		{ "PACKED_STRING_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY },
		{ "PACKED_VECTOR2_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY },
		{ "PACKED_VECTOR3_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY },
		{ "PACKED_COLOR_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY },
		{ "PACKED_VECTOR4_ARRAY", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY },
	} };
	return categories;
}

DispatchValidation validate_dispatch(
		const NativeDispatch &dispatch,
		std::size_t formal_argument_count,
		std::string_view receiver_native_type) {
	if (dispatch.minimum_argument_count > dispatch.argument_native_types.size()) {
		return { false, "invalid_dispatch_metadata" };
	}
	if (formal_argument_count < dispatch.minimum_argument_count) {
		return { false, "argument_count_below_minimum" };
	}
	if (!dispatch.vararg && formal_argument_count > dispatch.argument_native_types.size()) {
		return { false, "argument_count_above_maximum" };
	}

	const bool receiver_required =
			dispatch.kind == DispatchKind::BUILTIN_METHOD ||
			dispatch.kind == DispatchKind::BUILTIN_OPERATOR ||
			dispatch.kind == DispatchKind::BUILTIN_MEMBER;
	if (receiver_required) {
		if (receiver_native_type.empty()) {
			return { false, "missing_builtin_receiver" };
		}
		if (receiver_native_type != dispatch.owner_native_type) {
			return { false, "builtin_receiver_type_mismatch" };
		}
	} else if (!receiver_native_type.empty()) {
		return { false, "unexpected_receiver" };
	}
	return { true, {} };
}

DispatchFamily dispatch_family(const NativeDispatch &dispatch) {
	switch (dispatch.kind) {
		case DispatchKind::CLASS_METHOD: {
			if (dispatch.vararg) {
				return DispatchFamily::CLASS_VARIANT_CALL;
			}
			const bool variant_arguments =
					std::all_of(
							dispatch.argument_native_types.begin(),
							dispatch.argument_native_types.end(),
							[](const std::string &type) { return type == "Variant"; });
			return variant_arguments && (dispatch.return_native_type.empty() || dispatch.return_native_type == "Variant") ?
					DispatchFamily::CLASS_VARIANT_CALL :
					DispatchFamily::CLASS_PTRCALL;
		}
		case DispatchKind::CLASS_PROPERTY:
			return DispatchFamily::CLASS_PROPERTY;
		case DispatchKind::CLASS_SIGNAL:
			return DispatchFamily::CLASS_SIGNAL;
		case DispatchKind::BUILTIN_METHOD:
			return DispatchFamily::BUILTIN_METHOD;
		case DispatchKind::BUILTIN_CONSTRUCTOR:
			return DispatchFamily::BUILTIN_CONSTRUCTOR;
		case DispatchKind::BUILTIN_OPERATOR:
			return DispatchFamily::BUILTIN_OPERATOR;
		case DispatchKind::BUILTIN_MEMBER:
			return DispatchFamily::BUILTIN_MEMBER;
		case DispatchKind::BUILTIN_CONSTANT:
			return DispatchFamily::BUILTIN_CONSTANT;
		case DispatchKind::UTILITY_FUNCTION:
			return DispatchFamily::UTILITY_FUNCTION;
	}
	return DispatchFamily::INVALID;
}

DispatchValidation validate_value_backend(
		FoundryExtensionVariantType type,
		ValueBackend backend) {
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL && backend == ValueBackend::JAVA_LOCAL) {
		return { false, "java_local_signal_unsupported" };
	}
	return { true, {} };
}

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
	auto shared = std::make_shared<SharedHandleRecord>();
	shared->record.context = context;
	shared->record.generation = generation;
	shared->record.kind = kind;
	shared->record.expected_type = std::move(expected_type);
	shared->record.value = std::move(value);
	shared->record.owned = owned;
	shared->record.live = true;
	shared->destroy = std::move(destroy);
	if (context == 0 || generation == 0 || shared->record.expected_type.empty()) {
		drain_and_destroy(shared);
		return 0;
	}

	NativeHandle handle = 0;
	{
		std::lock_guard lock(impl->mutex);
		if (impl->next_handle != 0 && impl->next_handle != std::numeric_limits<NativeHandle>::max()) {
			handle = impl->next_handle++;
			impl->records.emplace(handle, shared);
		}
	}
	if (handle == 0) {
		drain_and_destroy(shared);
	}
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

namespace {

class ScopedStringName final {
public:
	ScopedStringName(const BridgeServices &services, const std::string &text) :
			services(services),
			value(NativeValue::storage(abi_layout_size("StringName"))) {
		if (value.data() == nullptr ||
				services.string_name_new_with_utf8_chars_and_len == nullptr ||
				services.variant_get_ptr_destructor == nullptr) {
			return;
		}
		services.string_name_new_with_utf8_chars_and_len(
				value.data(),
				text.data(),
				static_cast<FoundryExtensionInt>(text.size()));
		value.constructed = true;
	}

	~ScopedStringName() {
		if (!value.constructed) {
			return;
		}
		const FoundryExtensionPtrDestructor destructor =
				services.variant_get_ptr_destructor(FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME);
		if (destructor != nullptr) {
			destructor(value.data());
		}
		value.constructed = false;
	}

	explicit operator bool() const noexcept {
		return value.constructed;
	}

	FoundryExtensionConstStringNamePtr get() const noexcept {
		return value.data();
	}

private:
	const BridgeServices &services;
	NativeValue value;
};

} // namespace

NativeTransport::NativeTransport(std::shared_ptr<const BridgeServices> services) :
		services(std::move(services)) {
}

NativeHandleStore &NativeTransport::handles() noexcept {
	return handle_store;
}

const NativeHandleStore &NativeTransport::handles() const noexcept {
	return handle_store;
}

NativeHandle NativeTransport::create_native_structure(
		ContextHandle context,
		std::uint64_t generation,
		const std::string &expected_type) {
	if (services == nullptr || services->get_native_struct_size == nullptr) {
		return 0;
	}
	ScopedStringName native_type(*services, expected_type);
	if (!native_type) {
		return 0;
	}
	const std::uint64_t byte_size = services->get_native_struct_size(native_type.get());
	if (byte_size == 0 || byte_size > std::numeric_limits<std::size_t>::max()) {
		return 0;
	}
	return handle_store.insert(
			context,
			generation,
			HandleKind::NATIVE_STRUCTURE,
			expected_type,
			NativeValue::storage(static_cast<std::size_t>(byte_size)),
			true,
			{});
}

NativeHandle NativeTransport::track_object(
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionObjectPtr object,
		std::string expected_type,
		bool owned) {
	if (services == nullptr || services->object_get_instance_id == nullptr || object == nullptr) {
		return 0;
	}
	const GDObjectInstanceID instance_id = services->object_get_instance_id(object);
	if (instance_id == 0) {
		return 0;
	}
	NativeValue value;
	value.object_instance_id = static_cast<std::uint64_t>(instance_id);
	auto captured_services = services;
	return handle_store.insert(
			context,
			generation,
			HandleKind::OBJECT,
			std::move(expected_type),
			std::move(value),
			owned,
			[captured_services](HandleRecord &record) {
				if (captured_services->object_get_instance_from_id == nullptr ||
						captured_services->object_destroy == nullptr) {
					return;
				}
				FoundryExtensionObjectPtr live_object =
						captured_services->object_get_instance_from_id(record.value.object_instance_id);
				if (live_object != nullptr) {
					captured_services->object_destroy(live_object);
				}
			});
}

ObjectLease NativeTransport::acquire_object(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		const std::string &expected_type) {
	if (services == nullptr || services->object_get_instance_from_id == nullptr) {
		return {};
	}
	HandleLease lease = handle_store.acquire(handle, context, generation, HandleKind::OBJECT, expected_type);
	if (!lease) {
		return {};
	}
	FoundryExtensionObjectPtr object =
			services->object_get_instance_from_id(lease.record().value.object_instance_id);
	if (object == nullptr) {
		return {};
	}
	return { std::move(lease), object };
}

NativeHandle NativeTransport::retain_ref_counted(
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionObjectPtr object,
		std::string expected_type) {
	if (services == nullptr ||
			services->classdb_get_class_tag == nullptr ||
			services->object_cast_to == nullptr ||
			services->classdb_get_method_bind == nullptr ||
			services->object_method_bind_ptrcall == nullptr ||
			services->object_get_instance_id == nullptr ||
			services->object_get_instance_from_id == nullptr ||
			services->object_destroy == nullptr ||
			object == nullptr) {
		return 0;
	}
	ScopedStringName ref_counted_name(*services, "RefCounted");
	ScopedStringName reference_name(*services, "reference");
	ScopedStringName unreference_name(*services, "unreference");
	if (!ref_counted_name || !reference_name || !unreference_name) {
		return 0;
	}
	void *class_tag = services->classdb_get_class_tag(ref_counted_name.get());
	if (class_tag == nullptr) {
		return 0;
	}
	FoundryExtensionObjectPtr ref_counted = services->object_cast_to(object, class_tag);
	if (ref_counted == nullptr) {
		return 0;
	}
	constexpr FoundryExtensionInt reference_hash = 2240911060;
	const FoundryExtensionMethodBindPtr reference =
			services->classdb_get_method_bind(ref_counted_name.get(), reference_name.get(), reference_hash);
	const FoundryExtensionMethodBindPtr unreference =
			services->classdb_get_method_bind(ref_counted_name.get(), unreference_name.get(), reference_hash);
	if (reference == nullptr || unreference == nullptr) {
		return 0;
	}
	FoundryExtensionBool retained = 0;
	services->object_method_bind_ptrcall(reference, ref_counted, nullptr, &retained);
	if (!retained) {
		return 0;
	}
	const GDObjectInstanceID instance_id = services->object_get_instance_id(ref_counted);
	if (instance_id == 0) {
		FoundryExtensionBool destroy = 0;
		services->object_method_bind_ptrcall(unreference, ref_counted, nullptr, &destroy);
		if (destroy) {
			services->object_destroy(ref_counted);
		}
		return 0;
	}

	NativeValue value;
	value.object_instance_id = static_cast<std::uint64_t>(instance_id);
	auto captured_services = services;
	const NativeHandle handle = handle_store.insert(
			context,
			generation,
			HandleKind::OBJECT,
			std::move(expected_type),
			std::move(value),
			true,
			[captured_services, unreference](HandleRecord &record) {
				FoundryExtensionObjectPtr live_object =
						captured_services->object_get_instance_from_id(record.value.object_instance_id);
				if (live_object == nullptr) {
					return;
				}
				FoundryExtensionBool destroy = 0;
				captured_services->object_method_bind_ptrcall(unreference, live_object, nullptr, &destroy);
				if (destroy) {
					captured_services->object_destroy(live_object);
				}
			});
	if (handle == 0) {
		FoundryExtensionBool destroy = 0;
		services->object_method_bind_ptrcall(unreference, ref_counted, nullptr, &destroy);
		if (destroy) {
			services->object_destroy(ref_counted);
		}
	}
	return handle;
}

NativeHandle NativeTransport::copy_variant(
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionConstVariantPtr source,
		FoundryExtensionVariantType expected_type) {
	if (services == nullptr ||
			services->variant_get_type == nullptr ||
			services->variant_new_copy == nullptr ||
			services->variant_destroy == nullptr ||
			source == nullptr ||
			context == 0 ||
			generation == 0 ||
			services->variant_get_type(source) != expected_type) {
		return 0;
	}
	const auto category = std::find_if(
			variant_categories().begin(),
			variant_categories().end(),
			[expected_type](const VariantCategoryInfo &candidate) {
				return candidate.abi_type == expected_type;
			});
	if (category == variant_categories().end()) {
		return 0;
	}
	NativeValue value = NativeValue::storage(abi_layout_size("Variant"));
	if (value.data() == nullptr) {
		return 0;
	}
	services->variant_new_copy(value.data(), source);
	value.constructed = true;
	auto captured_services = services;
	return handle_store.insert(
			context,
			generation,
			HandleKind::VARIANT,
			std::string(category->java_name),
			std::move(value),
			true,
			[captured_services](HandleRecord &record) {
				if (record.value.constructed) {
					captured_services->variant_destroy(record.value.data());
					record.value.constructed = false;
				}
			});
}

} // namespace foundry_java
