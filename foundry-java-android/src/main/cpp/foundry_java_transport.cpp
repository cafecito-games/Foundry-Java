#include "foundry_java_transport.h"

#include "foundry_java_abi_layout.h"

#include <algorithm>
#include <array>
#include <condition_variable>
#include <limits>
#include <mutex>
#include <set>
#include <stdexcept>
#include <unordered_map>
#include <utility>

namespace foundry_java {

const std::array<VariantCategoryInfo, 39> &variant_categories() {
	static constexpr std::array<VariantCategoryInfo, 39> categories = { {
		{ "NIL", "Nil", FOUNDRY_EXTENSION_VARIANT_TYPE_NIL },
		{ "BOOLEAN", "bool", FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL },
		{ "INTEGER", "int", FOUNDRY_EXTENSION_VARIANT_TYPE_INT },
		{ "FLOAT", "float", FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT },
		{ "STRING", "String", FOUNDRY_EXTENSION_VARIANT_TYPE_STRING },
		{ "VECTOR2", "Vector2", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2 },
		{ "VECTOR2I", "Vector2i", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR2I },
		{ "RECT2", "Rect2", FOUNDRY_EXTENSION_VARIANT_TYPE_RECT2 },
		{ "RECT2I", "Rect2i", FOUNDRY_EXTENSION_VARIANT_TYPE_RECT2I },
		{ "VECTOR3", "Vector3", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3 },
		{ "VECTOR3I", "Vector3i", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR3I },
		{ "TRANSFORM2D", "Transform2D", FOUNDRY_EXTENSION_VARIANT_TYPE_TRANSFORM2D },
		{ "VECTOR4", "Vector4", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR4 },
		{ "VECTOR4I", "Vector4i", FOUNDRY_EXTENSION_VARIANT_TYPE_VECTOR4I },
		{ "PLANE", "Plane", FOUNDRY_EXTENSION_VARIANT_TYPE_PLANE },
		{ "QUATERNION", "Quaternion", FOUNDRY_EXTENSION_VARIANT_TYPE_QUATERNION },
		{ "AABB", "AABB", FOUNDRY_EXTENSION_VARIANT_TYPE_AABB },
		{ "BASIS", "Basis", FOUNDRY_EXTENSION_VARIANT_TYPE_BASIS },
		{ "TRANSFORM3D", "Transform3D", FOUNDRY_EXTENSION_VARIANT_TYPE_TRANSFORM3D },
		{ "PROJECTION", "Projection", FOUNDRY_EXTENSION_VARIANT_TYPE_PROJECTION },
		{ "COLOR", "Color", FOUNDRY_EXTENSION_VARIANT_TYPE_COLOR },
		{ "STRING_NAME", "StringName", FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME },
		{ "NODE_PATH", "NodePath", FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH },
		{ "RID", "RID", FOUNDRY_EXTENSION_VARIANT_TYPE_RID },
		{ "OBJECT", "Object", FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT },
		{ "CALLABLE", "Callable", FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE },
		{ "SIGNAL", "Signal", FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL },
		{ "DICTIONARY", "Dictionary", FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY },
		{ "ARRAY", "Array", FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY },
		{ "PACKED_BYTE_ARRAY", "PackedByteArray", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY },
		{ "PACKED_INT32_ARRAY", "PackedInt32Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY },
		{ "PACKED_INT64_ARRAY", "PackedInt64Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_INT64_ARRAY },
		{ "PACKED_FLOAT32_ARRAY", "PackedFloat32Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_FLOAT32_ARRAY },
		{ "PACKED_FLOAT64_ARRAY", "PackedFloat64Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_FLOAT64_ARRAY },
		{ "PACKED_STRING_ARRAY", "PackedStringArray", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_STRING_ARRAY },
		{ "PACKED_VECTOR2_ARRAY", "PackedVector2Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR2_ARRAY },
		{ "PACKED_VECTOR3_ARRAY", "PackedVector3Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR3_ARRAY },
		{ "PACKED_COLOR_ARRAY", "PackedColorArray", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_COLOR_ARRAY },
		{ "PACKED_VECTOR4_ARRAY", "PackedVector4Array", FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY },
	} };
	return categories;
}

const VariantCategoryInfo *variant_category(FoundryExtensionVariantType type) {
	const auto found = std::find_if(
			variant_categories().begin(),
			variant_categories().end(),
			[type](const VariantCategoryInfo &candidate) { return candidate.abi_type == type; });
	return found == variant_categories().end() ? nullptr : &*found;
}

const VariantCategoryInfo *variant_category(std::string_view native_name) {
	const auto found = std::find_if(
			variant_categories().begin(),
			variant_categories().end(),
			[native_name](const VariantCategoryInfo &candidate) {
				return candidate.native_name == native_name;
			});
	return found == variant_categories().end() ? nullptr : &*found;
}

NormalizedNativeType normalize_native_type(std::string_view token) {
	const auto starts_with = [token](std::string_view prefix) {
		return token.size() >= prefix.size() && token.substr(0, prefix.size()) == prefix;
	};
	if (token.empty() || token == "void") {
		return { NativeTypeKind::VOID, "void", FOUNDRY_EXTENSION_VARIANT_TYPE_NIL };
	}
	if (starts_with("enum::") || starts_with("bitfield::")) {
		return { NativeTypeKind::BUILTIN, "int", FOUNDRY_EXTENSION_VARIANT_TYPE_INT };
	}
	if (starts_with("typedarray::")) {
		return { NativeTypeKind::BUILTIN, "Array", FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY };
	}
	if (starts_with("typeddictionary::")) {
		return { NativeTypeKind::BUILTIN, "Dictionary", FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY };
	}
	if (const VariantCategoryInfo *category = variant_category(token)) {
		return { NativeTypeKind::BUILTIN, category->native_name, category->abi_type };
	}
	if (token == "Variant") {
		return { NativeTypeKind::BUILTIN, token, FOUNDRY_EXTENSION_VARIANT_TYPE_VARIANT_MAX };
	}
	std::string_view base = token;
	const bool pointer_token = token.find('*') != std::string_view::npos;
	if (starts_with("const ")) {
		base.remove_prefix(6);
	}
	while (!base.empty() && (base.back() == '*' || base.back() == ' ')) {
		base.remove_suffix(1);
	}
	static constexpr std::array<std::string_view, 14> native_structures = {
		"AudioFrame",
		"CaretInfo",
		"Glyph",
		"ObjectID",
		"PhysicsServer2DExtensionMotionResult",
		"PhysicsServer2DExtensionRayResult",
		"PhysicsServer2DExtensionShapeRestInfo",
		"PhysicsServer2DExtensionShapeResult",
		"PhysicsServer3DExtensionMotionCollision",
		"PhysicsServer3DExtensionMotionResult",
		"PhysicsServer3DExtensionRayResult",
		"PhysicsServer3DExtensionShapeRestInfo",
		"PhysicsServer3DExtensionShapeResult",
		"ScriptLanguageExtensionProfilingInfo",
	};
	if (std::find(native_structures.begin(), native_structures.end(), base) !=
			native_structures.end()) {
		return { NativeTypeKind::NATIVE_STRUCTURE, base, FOUNDRY_EXTENSION_VARIANT_TYPE_NIL };
	}
	if (pointer_token) {
		return { NativeTypeKind::NATIVE_STRUCTURE, token, FOUNDRY_EXTENSION_VARIANT_TYPE_NIL };
	}
	return { NativeTypeKind::OBJECT, token, FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT };
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
			const bool has_native_pointer = std::any_of(
					dispatch.argument_native_types.begin(),
					dispatch.argument_native_types.end(),
					[](const std::string &type) {
						const NativeTypeKind kind = normalize_native_type(type).kind;
						return kind == NativeTypeKind::NATIVE_STRUCTURE ||
								kind == NativeTypeKind::OBJECT;
					}) ||
					normalize_native_type(dispatch.return_native_type).kind ==
							NativeTypeKind::NATIVE_STRUCTURE ||
					normalize_native_type(dispatch.return_native_type).kind ==
							NativeTypeKind::OBJECT;
			return has_native_pointer ?
					DispatchFamily::CLASS_PTRCALL :
					DispatchFamily::CLASS_VARIANT_CALL;
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
	std::size_t retain_count = 1;
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
	std::set<std::pair<ContextHandle, std::uint64_t>> closed_generations;
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
		if (impl->closed_generations.count({ context, generation }) == 0 &&
				impl->next_handle != 0 &&
				impl->next_handle != std::numeric_limits<NativeHandle>::max()) {
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

HandleLease NativeHandleStore::inspect(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation) {
	std::lock_guard lock(impl->mutex);
	const auto found = impl->records.find(handle);
	if (found == impl->records.end()) {
		return {};
	}
	const auto &shared = found->second;
	std::lock_guard record_lock(shared->mutex);
	if (!shared->accepting || !shared->record.live ||
			shared->record.context != context ||
			shared->record.generation != generation) {
		return {};
	}
	shared->active_leases++;
	return HandleLease(shared);
}

NativeHandle NativeHandleStore::retain(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation) noexcept {
	std::lock_guard lock(impl->mutex);
	const auto found = impl->records.find(handle);
	if (found == impl->records.end()) {
		return 0;
	}
	const auto &shared = found->second;
	std::lock_guard record_lock(shared->mutex);
	if (!shared->accepting || !shared->record.live ||
			shared->record.context != context ||
			shared->record.generation != generation ||
			shared->retain_count == std::numeric_limits<std::size_t>::max()) {
		return 0;
	}
	shared->retain_count++;
	return handle;
}

bool NativeHandleStore::promote_ownership(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		Destroy destroy) noexcept {
	std::lock_guard lock(impl->mutex);
	const auto found = impl->records.find(handle);
	if (found == impl->records.end()) {
		return false;
	}
	const auto &shared = found->second;
	std::lock_guard record_lock(shared->mutex);
	if (!shared->accepting || !shared->record.live ||
			shared->record.context != context ||
			shared->record.generation != generation ||
			shared->record.kind != HandleKind::OBJECT ||
			shared->record.owned) {
		return false;
	}
	shared->record.owned = true;
	shared->destroy = std::move(destroy);
	return true;
}

bool NativeHandleStore::release(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation) noexcept {
	std::shared_ptr<SharedHandleRecord> shared;
	{
		std::lock_guard lock(impl->mutex);
		const auto found = impl->records.find(handle);
		if (found == impl->records.end()) {
			return false;
		}
		shared = found->second;
		std::lock_guard record_lock(shared->mutex);
		if (!shared->accepting || !shared->record.live ||
				shared->record.context != context ||
				shared->record.generation != generation ||
				shared->retain_count == 0) {
			return false;
		}
		shared->retain_count--;
		if (shared->retain_count != 0) {
			return true;
		}
		shared->accepting = false;
		impl->records.erase(found);
	}
	drain_and_destroy(shared);
	return true;
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
		if (shared->retain_count == 0) {
			return false;
		}
		shared->retain_count--;
		if (shared->retain_count != 0) {
			return true;
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
		impl->closed_generations.emplace(context, generation);
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
			value(NativeValue::storage(abi_layout_size("StringName"))) {
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
				value.data(),
				text.data(),
				static_cast<FoundryExtensionInt>(text.size()));
		value.constructed = true;
	}

	~ScopedStringName() {
		if (!value.constructed) {
			return;
		}
		destructor(value.data());
		value.constructed = false;
	}

	explicit operator bool() const noexcept {
		return value.constructed;
	}

	FoundryExtensionConstStringNamePtr get() const noexcept {
		return value.data();
	}

private:
	NativeValue value;
	FoundryExtensionPtrDestructor destructor = nullptr;
};

} // namespace

NativeTransport::NativeTransport(
		std::shared_ptr<const BridgeServices> services,
		void *extension_token) :
		services(std::move(services)),
		extension_token(extension_token == nullptr ? this : extension_token) {
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
	const NormalizedNativeType normalized = normalize_native_type(expected_type);
	if (normalized.kind != NativeTypeKind::NATIVE_STRUCTURE) {
		return 0;
	}
	if (normalized.token.find('*') != std::string_view::npos) {
		return handle_store.insert(
				context,
				generation,
				HandleKind::NATIVE_STRUCTURE,
				std::string(normalized.token),
				NativeValue::storage(sizeof(void *)),
				true,
				{});
	}
	ScopedStringName native_type(*services, std::string(normalized.token));
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
			std::string(normalized.token),
			NativeValue::storage(static_cast<std::size_t>(byte_size)),
			true,
			{});
}

NativeHandle NativeTransport::track_object(
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionObjectPtr object,
		std::string expected_type,
		bool owned,
		bool *created) {
	if (created != nullptr) {
		*created = false;
	}
	if (object == nullptr) {
		return 0;
	}
	if (services == nullptr ||
			services->object_get_instance_id == nullptr ||
			services->object_get_instance_from_id == nullptr ||
			(owned && services->object_destroy == nullptr)) {
		if (owned && services != nullptr && services->object_destroy != nullptr) {
			services->object_destroy(object);
		}
		return 0;
	}
	const GDObjectInstanceID instance_id = services->object_get_instance_id(object);
	if (instance_id == 0) {
		if (owned && services->object_destroy != nullptr) {
			services->object_destroy(object);
		}
		return 0;
	}
	const std::string identity_key =
			std::to_string(context) + ":" +
			std::to_string(generation) + ":" +
			std::to_string(static_cast<std::uint64_t>(instance_id));
	NativeHandle existing_handle = 0;
	bool promote_existing = false;
	{
		std::unique_lock identity_lock(object_identity_mutex);
		object_identity_condition.wait(identity_lock, [&] {
			auto found = object_identity_handles.find(identity_key);
			return found == object_identity_handles.end() || found->second != 0;
		});
		auto existing_identity = object_identity_handles.find(identity_key);
		if (existing_identity != object_identity_handles.end()) {
			HandleLease existing =
					handle_store.inspect(existing_identity->second, context, generation);
			if (existing && existing.record().kind == HandleKind::OBJECT) {
				existing_handle = existing_identity->second;
				promote_existing = owned && !existing.record().owned;
				if (!promote_existing) {
					// Instance-ID canonicalization proves object is the same engine allocation
					// already represented by existing_handle. Destroying an "extra" owned
					// pointer here would invalidate that canonical live handle.
					return existing_handle;
				}
				existing = {};
				existing_identity->second = 0;
			} else {
				object_identity_handles.erase(existing_identity);
			}
		}
		if (existing_handle == 0) {
			object_identity_handles.emplace(identity_key, 0);
		}
	}
	if (promote_existing) {
		auto captured_services = services;
		const bool promoted = handle_store.promote_ownership(
				existing_handle,
				context,
				generation,
				[captured_services](HandleRecord &record) {
					FoundryExtensionObjectPtr live_object =
							captured_services->object_get_instance_from_id(
									record.value.object_instance_id);
					if (live_object != nullptr) {
						captured_services->object_destroy(live_object);
					}
				});
		{
			std::lock_guard identity_lock(object_identity_mutex);
			if (promoted) {
				object_identity_handles[identity_key] = existing_handle;
			} else {
				object_identity_handles.erase(identity_key);
			}
		}
		object_identity_condition.notify_all();
		if (!promoted) {
			services->object_destroy(object);
			return 0;
		}
		return existing_handle;
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
			false,
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
	bool ready = handle != 0;
	if (ready && owned) {
		ready = handle_store.promote_ownership(
				handle,
				context,
				generation,
				[captured_services](HandleRecord &record) {
					FoundryExtensionObjectPtr live_object =
							captured_services->object_get_instance_from_id(
									record.value.object_instance_id);
					if (live_object != nullptr) {
						captured_services->object_destroy(live_object);
					}
				});
	}
	if (ready) {
		if (created != nullptr) {
			*created = true;
		}
	}
	{
		std::lock_guard identity_lock(object_identity_mutex);
		if (!ready) {
			object_identity_handles.erase(identity_key);
		} else {
			object_identity_handles[identity_key] = handle;
		}
	}
	object_identity_condition.notify_all();
	if (!ready) {
		if (handle != 0) {
			(void)handle_store.release(handle, context, generation);
		}
		if (owned) {
			services->object_destroy(object);
		}
		return 0;
	}
	return handle;
}

ObjectLease NativeTransport::acquire_object(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		const std::string &expected_type) {
	if (services == nullptr || services->object_get_instance_from_id == nullptr) {
		return {};
	}
	HandleLease lease = handle_store.inspect(handle, context, generation);
	if (!lease || lease.record().kind != HandleKind::OBJECT) {
		return {};
	}
	const bool exact_type = lease.record().expected_type == expected_type;
	FoundryExtensionObjectPtr object =
			services->object_get_instance_from_id(lease.record().value.object_instance_id);
	if (object == nullptr || (!exact_type && !is_object_assignable(object, expected_type))) {
		return {};
	}
	return { std::move(lease), object };
}

NativeHandle NativeTransport::retain_ref_counted(
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionObjectPtr object,
		std::string expected_type,
		bool initialize,
		bool *ownership_consumed) {
	if (ownership_consumed != nullptr) {
		*ownership_consumed = false;
	}
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
	ScopedStringName reference_name(*services, initialize ? "init_ref" : "reference");
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
	if (ownership_consumed != nullptr) {
		*ownership_consumed = true;
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
	const std::string identity_key =
			std::to_string(context) + ":" +
			std::to_string(generation) + ":" +
			std::to_string(static_cast<std::uint64_t>(instance_id));
	NativeHandle existing_handle = 0;
	bool promote_existing = false;
	{
		std::unique_lock identity_lock(object_identity_mutex);
		object_identity_condition.wait(identity_lock, [&] {
			auto found = object_identity_handles.find(identity_key);
			return found == object_identity_handles.end() || found->second != 0;
		});
		auto existing_identity = object_identity_handles.find(identity_key);
		if (existing_identity != object_identity_handles.end()) {
			HandleLease existing =
					handle_store.inspect(existing_identity->second, context, generation);
			if (existing && existing.record().kind == HandleKind::OBJECT) {
				existing_handle = existing_identity->second;
				promote_existing = !existing.record().owned;
				if (promote_existing) {
					existing = {};
					existing_identity->second = 0;
				}
			} else {
				object_identity_handles.erase(existing_identity);
			}
		}
		if (existing_handle == 0) {
			object_identity_handles.emplace(identity_key, 0);
		}
	}
	auto captured_services = services;
	auto ref_counted_cleanup =
			[captured_services, unreference](HandleRecord &record) {
				FoundryExtensionObjectPtr live_object =
						captured_services->object_get_instance_from_id(
								record.value.object_instance_id);
				if (live_object == nullptr) {
					return;
				}
				FoundryExtensionBool destroy = 0;
				captured_services->object_method_bind_ptrcall(
						unreference, live_object, nullptr, &destroy);
				if (destroy) {
					captured_services->object_destroy(live_object);
				}
			};
	if (existing_handle != 0) {
		bool transferred = false;
		if (promote_existing) {
			transferred = handle_store.promote_ownership(
					existing_handle,
					context,
					generation,
					ref_counted_cleanup);
			{
				std::lock_guard identity_lock(object_identity_mutex);
				if (transferred) {
					object_identity_handles[identity_key] = existing_handle;
				} else {
					object_identity_handles.erase(identity_key);
				}
			}
			object_identity_condition.notify_all();
		}
		if (!transferred) {
			FoundryExtensionBool destroy = 0;
			services->object_method_bind_ptrcall(
					unreference, ref_counted, nullptr, &destroy);
			if (destroy) {
				services->object_destroy(ref_counted);
			}
		}
		return transferred || !promote_existing ? existing_handle : 0;
	}

	NativeValue value;
	value.object_instance_id = static_cast<std::uint64_t>(instance_id);
	const NativeHandle handle = handle_store.insert(
			context,
			generation,
			HandleKind::OBJECT,
			std::move(expected_type),
			std::move(value),
			false,
			ref_counted_cleanup);
	const bool ready =
			handle != 0 &&
			handle_store.promote_ownership(
					handle, context, generation, ref_counted_cleanup);
	{
		std::lock_guard identity_lock(object_identity_mutex);
		if (!ready) {
			object_identity_handles.erase(identity_key);
		} else {
			object_identity_handles[identity_key] = handle;
		}
	}
	object_identity_condition.notify_all();
	if (!ready) {
		if (handle != 0) {
			(void)handle_store.release(handle, context, generation);
		}
		FoundryExtensionBool destroy = 0;
		services->object_method_bind_ptrcall(
				unreference, ref_counted, nullptr, &destroy);
		if (destroy) {
			services->object_destroy(ref_counted);
		}
		return 0;
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

NativeHandle NativeTransport::construct_variant(
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionVariantType type,
		FoundryExtensionConstTypePtr native_value,
		ValueBackend backend) {
	const VariantCategoryInfo *category = variant_category(type);
	if (services == nullptr ||
			category == nullptr ||
			services->variant_destroy == nullptr ||
			context == 0 ||
			generation == 0) {
		return 0;
	}
	if (!validate_value_backend(type, backend).valid ||
			(type == FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE &&
					backend == ValueBackend::JAVA_LOCAL)) {
		return 0;
	}
	const bool local_default_rid =
			type == FOUNDRY_EXTENSION_VARIANT_TYPE_RID &&
			backend == ValueBackend::JAVA_LOCAL &&
			native_value != nullptr &&
			*static_cast<const std::uint64_t *>(native_value) == 0;
	if (type != FOUNDRY_EXTENSION_VARIANT_TYPE_NIL &&
			native_value == nullptr &&
			!local_default_rid) {
		return 0;
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_RID &&
			backend == ValueBackend::JAVA_LOCAL &&
			!local_default_rid) {
		return 0;
	}
	NativeValue value = NativeValue::storage(abi_layout_size("Variant"));
	if (value.data() == nullptr) {
		return 0;
	}
	if (local_default_rid) {
		if (services->variant_construct == nullptr) {
			return 0;
		}
		FoundryExtensionCallError error{};
		services->variant_construct(type, value.data(), nullptr, 0, &error);
		value.constructed = true;
		if (error.error != FOUNDRY_EXTENSION_CALL_OK) {
			services->variant_destroy(value.data());
			value.constructed = false;
			return 0;
		}
	} else if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_NIL) {
		if (services->variant_new_nil == nullptr) {
			return 0;
		}
		services->variant_new_nil(value.data());
	} else {
		if (services->get_variant_from_type_constructor == nullptr) {
			return 0;
		}
		const FoundryExtensionVariantFromTypeConstructorFunc constructor =
				services->get_variant_from_type_constructor(type);
		if (constructor == nullptr) {
			return 0;
		}
		constructor(value.data(), const_cast<FoundryExtensionTypePtr>(native_value));
	}
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

NativeHandle NativeTransport::construct_string_variant(
		ContextHandle context,
		std::uint64_t generation,
		std::string_view utf8) {
	if (services == nullptr ||
			services->string_new_with_utf8_chars_and_len2 == nullptr ||
			services->variant_get_ptr_destructor == nullptr) {
		return 0;
	}
	NativeValue string = NativeValue::storage(abi_layout_size("String"));
	if (string.data() == nullptr) {
		return 0;
	}
	const FoundryExtensionPtrDestructor destructor =
			services->variant_get_ptr_destructor(FOUNDRY_EXTENSION_VARIANT_TYPE_STRING);
	if (destructor == nullptr) {
		return 0;
	}
	const FoundryExtensionInt error = services->string_new_with_utf8_chars_and_len2(
			string.data(),
			utf8.data(),
			static_cast<FoundryExtensionInt>(utf8.size()));
	string.constructed = true;
	if (error != 0) {
		destructor(string.data());
		string.constructed = false;
		return 0;
	}
	const NativeHandle handle = construct_variant(
			context,
			generation,
			FOUNDRY_EXTENSION_VARIANT_TYPE_STRING,
			string.data());
	destructor(string.data());
	string.constructed = false;
	return handle;
}

NativeHandle NativeTransport::construct_text_variant(
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionVariantType type,
		std::string_view utf8) {
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING) {
		return construct_string_variant(context, generation, utf8);
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME) {
		if (services == nullptr) {
			return 0;
		}
		ScopedStringName string_name(*services, std::string(utf8));
		return string_name ?
				construct_variant(context, generation, type, string_name.get()) :
				0;
	}
	if (type != FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH ||
			services == nullptr ||
			services->variant_construct == nullptr ||
			services->variant_destroy == nullptr) {
		return 0;
	}
	const NativeHandle source = construct_string_variant(context, generation, utf8);
	if (source == 0) {
		return 0;
	}
	HandleLease source_lease = handle_store.acquire(
			source,
			context,
			generation,
			HandleKind::VARIANT,
			"STRING");
	if (!source_lease) {
		handle_store.release(source, context, generation, HandleKind::VARIANT, "STRING");
		return 0;
	}
	NativeValue node_path = NativeValue::storage(abi_layout_size("Variant"));
	const FoundryExtensionConstVariantPtr arguments[] = { source_lease.record().value.data() };
	FoundryExtensionCallError error{};
	services->variant_construct(type, node_path.data(), arguments, 1, &error);
	node_path.constructed = true;
	source_lease = {};
	handle_store.release(source, context, generation, HandleKind::VARIANT, "STRING");
	if (error.error != FOUNDRY_EXTENSION_CALL_OK) {
		services->variant_destroy(node_path.data());
		return 0;
	}
	auto captured_services = services;
	return handle_store.insert(
			context,
			generation,
			HandleKind::VARIANT,
			"NODE_PATH",
			std::move(node_path),
			true,
			[captured_services](HandleRecord &record) {
				if (record.value.constructed) {
					captured_services->variant_destroy(record.value.data());
					record.value.constructed = false;
				}
			});
}

namespace {

struct CallableState {
	LocalCallable callable;
	std::uint64_t identity = 0;
	FoundryExtensionInt argument_count = -1;
};

void local_callable_call(
		void *userdata,
		const FoundryExtensionConstVariantPtr *arguments,
		FoundryExtensionInt argument_count,
		FoundryExtensionVariantPtr result,
		FoundryExtensionCallError *error) {
	auto *state = static_cast<CallableState *>(userdata);
	if (state == nullptr || !state->callable) {
		if (error != nullptr) {
			error->error = FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD;
		}
		return;
	}
	try {
		state->callable(arguments, argument_count, result, error);
	} catch (...) {
		if (error != nullptr) {
			error->error = FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD;
		}
	}
}

FoundryExtensionBool local_callable_valid(void *userdata) {
	return userdata != nullptr;
}

void local_callable_free(void *userdata) {
	delete static_cast<CallableState *>(userdata);
}

std::uint32_t local_callable_hash(void *userdata) {
	auto *state = static_cast<CallableState *>(userdata);
	if (state == nullptr) {
		return 0;
	}
	return static_cast<std::uint32_t>(state->identity ^ (state->identity >> 32));
}

FoundryExtensionBool local_callable_equal(void *left, void *right) {
	auto *left_state = static_cast<CallableState *>(left);
	auto *right_state = static_cast<CallableState *>(right);
	return left_state != nullptr && right_state != nullptr &&
					left_state->identity == right_state->identity ?
			1 :
			0;
}

FoundryExtensionInt local_callable_argument_count(void *userdata, FoundryExtensionBool *valid) {
	auto *state = static_cast<CallableState *>(userdata);
	if (valid != nullptr) {
		*valid = state != nullptr;
	}
	return state == nullptr ? -1 : state->argument_count;
}

TransportResult success() {
	return { true, {}, { FOUNDRY_EXTENSION_CALL_OK, 0, 0 } };
}

TransportResult failure(std::string phase) {
	return { false, std::move(phase), { FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD, 0, 0 } };
}

} // namespace

NativeHandle NativeTransport::construct_local_callable(
		ContextHandle context,
		std::uint64_t generation,
		LocalCallable callable,
		std::uint64_t identity,
		FoundryExtensionInt argument_count) {
	if (services == nullptr ||
			services->callable_custom_create2 == nullptr ||
			services->variant_get_ptr_destructor == nullptr ||
			!callable ||
			identity == 0 ||
			extension_token == nullptr) {
		return 0;
	}
	auto state = std::make_unique<CallableState>();
	state->callable = std::move(callable);
	state->identity = identity;
	state->argument_count = argument_count;
	NativeValue native_callable = NativeValue::storage(abi_layout_size("Callable"));
	if (native_callable.data() == nullptr) {
		return 0;
	}
	const FoundryExtensionPtrDestructor destructor =
			services->variant_get_ptr_destructor(FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE);
	if (destructor == nullptr) {
		return 0;
	}
	FoundryExtensionCallableCustomInfo2 info{};
	info.callable_userdata = state.get();
	info.token = extension_token;
	info.call_func = &local_callable_call;
	info.is_valid_func = &local_callable_valid;
	info.free_func = &local_callable_free;
	info.hash_func = &local_callable_hash;
	info.equal_func = &local_callable_equal;
	info.get_argument_count_func = &local_callable_argument_count;
	services->callable_custom_create2(native_callable.data(), &info);
	native_callable.constructed = true;
	state.release();
	const NativeHandle handle = construct_variant(
			context,
			generation,
			FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE,
			native_callable.data());
	destructor(native_callable.data());
	native_callable.constructed = false;
	return handle;
}

TransportResult NativeTransport::inspect_variant(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionVariantType type,
		FoundryExtensionUninitializedTypePtr destination) {
	const VariantCategoryInfo *category = variant_category(type);
	if (services == nullptr || category == nullptr || destination == nullptr) {
		return failure("invalid_variant_inspection");
	}
	HandleLease lease = handle_store.acquire(
			handle,
			context,
			generation,
			HandleKind::VARIANT,
			std::string(category->java_name));
	if (!lease) {
		return failure("invalid_variant_handle");
	}
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_NIL) {
		return success();
	}
	if (services->get_variant_to_type_constructor == nullptr) {
		return failure("missing_variant_inspector");
	}
	const FoundryExtensionTypeFromVariantConstructorFunc constructor =
			services->get_variant_to_type_constructor(type);
	if (constructor == nullptr) {
		return failure("missing_variant_inspector");
	}
	constructor(
			destination,
			const_cast<FoundryExtensionVariantPtr>(lease.record().value.data()));
	return success();
}

TransportResult NativeTransport::inspect_string_variant(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		std::string &destination) {
	if (services == nullptr ||
			services->string_to_utf8_chars == nullptr ||
			services->variant_get_ptr_destructor == nullptr) {
		return failure("missing_string_conversion");
	}
	const FoundryExtensionPtrDestructor destructor =
			services->variant_get_ptr_destructor(FOUNDRY_EXTENSION_VARIANT_TYPE_STRING);
	if (destructor == nullptr) {
		return failure("missing_string_destructor");
	}
	NativeValue string = NativeValue::storage(abi_layout_size("String"));
	TransportResult inspected = inspect_variant(
			handle,
			context,
			generation,
			FOUNDRY_EXTENSION_VARIANT_TYPE_STRING,
			string.data());
	if (!inspected.ok) {
		return inspected;
	}
	string.constructed = true;
	const FoundryExtensionInt length = services->string_to_utf8_chars(string.data(), nullptr, 0);
	if (length < 0) {
		destructor(string.data());
		string.constructed = false;
		return failure("string_utf8_size_failed");
	}
	try {
		destination.resize(static_cast<std::size_t>(length));
	} catch (...) {
		destructor(string.data());
		string.constructed = false;
		return failure("string_destination_allocation_failed");
	}
	if (length > 0) {
		const FoundryExtensionInt written =
				services->string_to_utf8_chars(string.data(), destination.data(), length);
		if (written != length) {
			destructor(string.data());
			string.constructed = false;
			return failure("string_utf8_copy_failed");
		}
	}
	destructor(string.data());
	string.constructed = false;
	return success();
}

TransportResult NativeTransport::inspect_text_variant(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionVariantType type,
		std::string &destination) {
	if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING) {
		return inspect_string_variant(handle, context, generation, destination);
	}
	const VariantCategoryInfo *category = variant_category(type);
	if (services == nullptr ||
			category == nullptr ||
			(type != FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME &&
					type != FOUNDRY_EXTENSION_VARIANT_TYPE_NODE_PATH) ||
			services->variant_construct == nullptr ||
			services->variant_destroy == nullptr) {
		return failure("invalid_text_variant");
	}
	HandleLease source = handle_store.acquire(
			handle,
			context,
			generation,
			HandleKind::VARIANT,
			std::string(category->java_name));
	if (!source) {
		return failure("invalid_variant_handle");
	}
	NativeValue string_variant = NativeValue::storage(abi_layout_size("Variant"));
	const FoundryExtensionConstVariantPtr arguments[] = { source.record().value.data() };
	FoundryExtensionCallError error{};
	services->variant_construct(
			FOUNDRY_EXTENSION_VARIANT_TYPE_STRING,
			string_variant.data(),
			arguments,
			1,
			&error);
	string_variant.constructed = true;
	if (error.error != FOUNDRY_EXTENSION_CALL_OK) {
		services->variant_destroy(string_variant.data());
		return { false, "text_to_string_failed", error };
	}
	auto captured_services = services;
	const NativeHandle temporary = handle_store.insert(
			context,
			generation,
			HandleKind::VARIANT,
			"STRING",
			std::move(string_variant),
			true,
			[captured_services](HandleRecord &record) {
				if (record.value.constructed) {
					captured_services->variant_destroy(record.value.data());
					record.value.constructed = false;
				}
			});
	if (temporary == 0) {
		return failure("temporary_string_handle_failed");
	}
	TransportResult result = inspect_string_variant(
			temporary,
			context,
			generation,
			destination);
	handle_store.release(temporary, context, generation, HandleKind::VARIANT, "STRING");
	return result;
}

NativeHandle NativeTransport::construct_object_variant(
		ContextHandle context,
		std::uint64_t generation,
		NativeHandle object_handle,
		const std::string &expected_object_type) {
	ObjectLease object = acquire_object(
			object_handle,
			context,
			generation,
			expected_object_type);
	if (!object) {
		return 0;
	}
	FoundryExtensionObjectPtr pointer = object.object;
	return construct_variant(
			context,
			generation,
			FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT,
			&pointer);
}

TransportResult NativeTransport::inspect_object_instance_id(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		std::uint64_t &instance_id) {
	if (services == nullptr || services->object_get_instance_id == nullptr) {
		return failure("missing_object_instance_id");
	}
	FoundryExtensionObjectPtr object = nullptr;
	TransportResult result = inspect_variant(
			handle,
			context,
			generation,
			FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT,
			&object);
	if (!result.ok) {
		return result;
	}
	if (object == nullptr) {
		return failure("null_object_variant");
	}
	const GDObjectInstanceID id = services->object_get_instance_id(object);
	if (id == 0) {
		return failure("invalid_object_instance");
	}
	instance_id = static_cast<std::uint64_t>(id);
	return success();
}

TransportResult NativeTransport::object_type(
		NativeHandle object_handle,
		ContextHandle context,
		std::uint64_t generation,
		const std::string &expected_object_type,
		FoundryExtensionClassLibraryPtr library,
		std::string &destination) {
	if (services == nullptr ||
			services->object_get_class_name == nullptr ||
			services->variant_get_ptr_destructor == nullptr) {
		return failure("missing_object_type_service");
	}
	const FoundryExtensionPtrDestructor destructor =
			services->variant_get_ptr_destructor(
					FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME);
	if (destructor == nullptr) {
		return failure("missing_string_name_destructor");
	}
	ObjectLease object = acquire_object(
			object_handle,
			context,
			generation,
			expected_object_type);
	if (!object) {
		return failure("invalid_object_handle");
	}
	NativeValue class_name = NativeValue::storage(abi_layout_size("StringName"));
	const FoundryExtensionBool valid =
			services->object_get_class_name(object.object, library, class_name.data());
	if (!valid) {
		return failure("object_type_lookup_failed");
	}
	class_name.constructed = true;
	const NativeHandle class_name_variant = construct_variant(
			context,
			generation,
			FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME,
			class_name.data());
	destructor(class_name.data());
	class_name.constructed = false;
	if (class_name_variant == 0) {
		return failure("object_type_conversion_failed");
	}
	TransportResult result = inspect_text_variant(
			class_name_variant,
			context,
			generation,
			FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME,
			destination);
	handle_store.release(
			class_name_variant,
			context,
			generation,
			HandleKind::VARIANT,
			"STRING_NAME");
	return result;
}

NativeHandle NativeTransport::copy_native_backed_variant(
		ContextHandle context,
		std::uint64_t generation,
		NativeHandle source_handle,
		FoundryExtensionVariantType type) {
	if (type != FOUNDRY_EXTENSION_VARIANT_TYPE_RID &&
			type != FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE &&
			type != FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL) {
		return 0;
	}
	const VariantCategoryInfo *category = variant_category(type);
	if (category == nullptr) {
		return 0;
	}
	HandleLease source = handle_store.acquire(
			source_handle,
			context,
			generation,
			HandleKind::VARIANT,
			std::string(category->java_name));
	return source ?
			copy_variant(
					context,
					generation,
					source.record().value.data(),
					type) :
			0;
}

NativeHandle NativeTransport::construct_collection(
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionVariantType type,
		const std::vector<NativeHandle> &keys,
		const std::vector<NativeHandle> &values) {
	const bool dictionary = type == FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY;
	const bool array = type == FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY;
	const bool packed =
			type >= FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY &&
			type <= FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY;
	if (services == nullptr ||
			services->variant_construct == nullptr ||
			services->variant_destroy == nullptr ||
			(!dictionary && !array && !packed) ||
			(dictionary && keys.size() != values.size()) ||
			(!dictionary && !keys.empty())) {
		return 0;
	}
	if (packed) {
		const NativeHandle source_array = construct_collection(
				context,
				generation,
				FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY,
				{},
				values);
		if (source_array == 0) {
			return 0;
		}
		HandleLease source = handle_store.acquire(
				source_array,
				context,
				generation,
				HandleKind::VARIANT,
				"ARRAY");
		if (!source) {
			handle_store.release(
					source_array,
					context,
					generation,
					HandleKind::VARIANT,
					"ARRAY");
			return 0;
		}
		NativeValue packed_variant = NativeValue::storage(abi_layout_size("Variant"));
		const FoundryExtensionConstVariantPtr arguments[] = { source.record().value.data() };
		FoundryExtensionCallError error{};
		services->variant_construct(type, packed_variant.data(), arguments, 1, &error);
		packed_variant.constructed = true;
		source = {};
		handle_store.release(
				source_array,
				context,
				generation,
				HandleKind::VARIANT,
				"ARRAY");
		if (error.error != FOUNDRY_EXTENSION_CALL_OK) {
			services->variant_destroy(packed_variant.data());
			return 0;
		}
		const VariantCategoryInfo *category = variant_category(type);
		auto captured_services = services;
		return handle_store.insert(
				context,
				generation,
				HandleKind::VARIANT,
				std::string(category->java_name),
				std::move(packed_variant),
				true,
				[captured_services](HandleRecord &record) {
					if (record.value.constructed) {
						captured_services->variant_destroy(record.value.data());
						record.value.constructed = false;
					}
				});
	}

	const auto acquire_any_variant = [&](NativeHandle handle) {
		HandleLease lease;
		for (const VariantCategoryInfo &category : variant_categories()) {
			lease = handle_store.acquire(
					handle,
					context,
					generation,
					HandleKind::VARIANT,
					std::string(category.java_name));
			if (lease) {
				break;
			}
		}
		return lease;
	};

	std::vector<HandleLease> key_leases;
	std::vector<HandleLease> value_leases;
	key_leases.reserve(keys.size());
	value_leases.reserve(values.size());
	for (NativeHandle key : keys) {
		HandleLease lease = acquire_any_variant(key);
		if (!lease) {
			return 0;
		}
		key_leases.push_back(std::move(lease));
	}
	for (NativeHandle value : values) {
		HandleLease lease = acquire_any_variant(value);
		if (!lease) {
			return 0;
		}
		value_leases.push_back(std::move(lease));
	}

	NativeValue collection = NativeValue::storage(abi_layout_size("Variant"));
	FoundryExtensionCallError error{};
	services->variant_construct(type, collection.data(), nullptr, 0, &error);
	collection.constructed = true;
	if (error.error != FOUNDRY_EXTENSION_CALL_OK) {
		services->variant_destroy(collection.data());
		return 0;
	}
	if (dictionary) {
		if (services->variant_set_keyed == nullptr) {
			services->variant_destroy(collection.data());
			return 0;
		}
		for (std::size_t index = 0; index < values.size(); index++) {
			FoundryExtensionBool valid = 0;
			services->variant_set_keyed(
					collection.data(),
					key_leases[index].record().value.data(),
					value_leases[index].record().value.data(),
					&valid);
			if (!valid) {
				services->variant_destroy(collection.data());
				return 0;
			}
		}
	} else {
		if (services->variant_call == nullptr) {
			services->variant_destroy(collection.data());
			return 0;
		}
		ScopedStringName append(*services, "append");
		if (!append) {
			services->variant_destroy(collection.data());
			return 0;
		}
		for (HandleLease &value : value_leases) {
			NativeValue call_result = NativeValue::storage(abi_layout_size("Variant"));
			const FoundryExtensionConstVariantPtr arguments[] = { value.record().value.data() };
			error = {};
			services->variant_call(
					collection.data(),
					append.get(),
					arguments,
					1,
					call_result.data(),
					&error);
			call_result.constructed = true;
			services->variant_destroy(call_result.data());
			call_result.constructed = false;
			if (error.error != FOUNDRY_EXTENSION_CALL_OK) {
				services->variant_destroy(collection.data());
				return 0;
			}
		}
	}
	const VariantCategoryInfo *category = variant_category(type);
	auto captured_services = services;
	return handle_store.insert(
			context,
			generation,
			HandleKind::VARIANT,
			std::string(category->java_name),
			std::move(collection),
			true,
			[captured_services](HandleRecord &record) {
				if (record.value.constructed) {
					captured_services->variant_destroy(record.value.data());
					record.value.constructed = false;
				}
			});
}

TransportResult NativeTransport::execute(
		const NativeDispatch &dispatch,
		const DispatchCall &call) {
	if (services == nullptr) {
		return failure("missing_bridge_services");
	}
	DispatchFamily family = dispatch_family(dispatch);
	if (family == DispatchFamily::CLASS_PTRCALL &&
			call.variant_arguments.size() >= dispatch.minimum_argument_count &&
			call.variant_arguments.size() < dispatch.argument_native_types.size()) {
		family = DispatchFamily::CLASS_VARIANT_CALL;
	}
	const std::size_t argument_count =
			family == DispatchFamily::CLASS_VARIANT_CALL ||
					family == DispatchFamily::BUILTIN_OPERATOR ?
			call.variant_arguments.size() :
			family == DispatchFamily::CLASS_PROPERTY ?
			(call.property_set ? 1 : 0) :
			family == DispatchFamily::CLASS_SIGNAL ?
			0 :
			call.native_arguments.size();
	const bool builtin_receiver =
			dispatch.kind == DispatchKind::BUILTIN_METHOD ||
			dispatch.kind == DispatchKind::BUILTIN_OPERATOR ||
			dispatch.kind == DispatchKind::BUILTIN_MEMBER;
	const DispatchValidation validation = validate_dispatch(
			dispatch,
			argument_count,
			builtin_receiver ? std::string_view(call.receiver_native_type) : std::string_view{});
	if (!validation.valid) {
		return failure(validation.phase);
	}
	ScopedStringName owner(*services, dispatch.owner_native_type);
	ScopedStringName name(*services, dispatch.native_name);
	if (!name || (!dispatch.owner_native_type.empty() && !owner)) {
		return failure("string_name_construction_failed");
	}

	switch (family) {
		case DispatchFamily::CLASS_VARIANT_CALL:
		case DispatchFamily::CLASS_PTRCALL: {
			if (services->classdb_get_method_bind == nullptr) {
				return failure("missing_method_bind_lookup");
			}
			const FoundryExtensionMethodBindPtr method = services->classdb_get_method_bind(
					owner.get(),
					name.get(),
					dispatch.compatibility_hash);
			if (method == nullptr) {
				return failure("method_bind_not_found");
			}
			FoundryExtensionObjectPtr receiver = dispatch.static_call ? nullptr : call.object;
			if (!dispatch.static_call && receiver == nullptr) {
				return failure("missing_class_receiver");
			}
			if (family == DispatchFamily::CLASS_VARIANT_CALL) {
				if (services->object_method_bind_call == nullptr || call.variant_result == nullptr) {
					return failure("invalid_method_bind_call");
				}
				FoundryExtensionCallError error{};
				services->object_method_bind_call(
						method,
						receiver,
						call.variant_arguments.data(),
						static_cast<FoundryExtensionInt>(call.variant_arguments.size()),
						call.variant_result,
						&error);
				if (error.error != FOUNDRY_EXTENSION_CALL_OK) {
					if (services->variant_destroy != nullptr) {
						services->variant_destroy(call.variant_result);
					}
					return { false, "method_bind_call_failed", error };
				}
				return success();
			}
			if (services->object_method_bind_ptrcall == nullptr) {
				return failure("invalid_method_bind_ptrcall");
			}
			if (dispatch.return_native_type != "void" && call.native_result == nullptr) {
				return failure("missing_method_bind_ptrcall_result");
			}
			if (dispatch.return_native_type == "void" &&
					(call.variant_result == nullptr || services->variant_new_nil == nullptr)) {
				return failure("missing_void_nil_constructor");
			}
			services->object_method_bind_ptrcall(
					method,
					receiver,
					call.native_arguments.data(),
					dispatch.return_native_type == "void" ? nullptr : call.native_result);
			if (dispatch.return_native_type == "void") {
				services->variant_new_nil(call.variant_result);
			}
			return success();
		}
		case DispatchFamily::CLASS_PROPERTY: {
			if (call.receiver_variant == nullptr || call.variant_result == nullptr) {
				return failure("invalid_named_access");
			}
			const auto valid_accessor = [](const std::string &identity,
											 const std::string &native_name,
											 std::int64_t hash) {
				return !native_name.empty() &&
						((!identity.empty() && hash >= 0) ||
								(identity.empty() && hash == -1));
			};
			if (call.property_set) {
				if (!valid_accessor(
							dispatch.setter_identity,
							dispatch.setter_native_name,
							dispatch.setter_compatibility_hash)) {
					return failure("invalid_property_setter_metadata");
				}
			} else if (!valid_accessor(
							   dispatch.getter_identity,
							   dispatch.getter_native_name,
							   dispatch.getter_compatibility_hash)) {
				return failure("invalid_property_getter_metadata");
			}
			FoundryExtensionBool valid = 0;
			if (call.property_set) {
				if (services->variant_set_named == nullptr || call.variant_arguments.empty()) {
					return failure("invalid_named_set");
				}
				services->variant_set_named(
						call.receiver_variant,
						name.get(),
						call.variant_arguments.front(),
						&valid);
			} else {
				if (services->variant_get_named == nullptr) {
					return failure("invalid_named_get");
				}
				services->variant_get_named(
						call.receiver_variant,
						name.get(),
						call.variant_result,
						&valid);
			}
			if (!valid) {
				if (!call.property_set && services->variant_destroy != nullptr) {
					services->variant_destroy(call.variant_result);
				}
				return failure("named_access_failed");
			}
			if (call.property_set) {
				if (services->variant_new_nil == nullptr) {
					return failure("missing_property_nil_constructor");
				}
				services->variant_new_nil(call.variant_result);
			}
			return success();
		}
		case DispatchFamily::CLASS_SIGNAL: {
			if (services->variant_construct == nullptr ||
					services->get_variant_from_type_constructor == nullptr ||
					services->variant_destroy == nullptr ||
					call.receiver_variant == nullptr ||
					call.variant_result == nullptr) {
				return failure("invalid_signal_construction");
			}
			const FoundryExtensionVariantFromTypeConstructorFunc string_name_constructor =
					services->get_variant_from_type_constructor(
							FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME);
			if (string_name_constructor == nullptr) {
				return failure("missing_string_name_variant_constructor");
			}
			NativeValue signal_name = NativeValue::storage(abi_layout_size("Variant"));
			string_name_constructor(
					signal_name.data(),
					const_cast<FoundryExtensionStringNamePtr>(name.get()));
			signal_name.constructed = true;
			const FoundryExtensionConstVariantPtr arguments[] = {
				call.receiver_variant,
				signal_name.data(),
			};
			FoundryExtensionCallError error{};
			services->variant_construct(
					FOUNDRY_EXTENSION_VARIANT_TYPE_SIGNAL,
					call.variant_result,
					arguments,
					2,
					&error);
			services->variant_destroy(signal_name.data());
			signal_name.constructed = false;
			if (error.error != FOUNDRY_EXTENSION_CALL_OK) {
				services->variant_destroy(call.variant_result);
				return { false, "signal_construction_failed", error };
			}
			return success();
		}
		case DispatchFamily::BUILTIN_METHOD: {
			const VariantCategoryInfo *owner_type = variant_category(dispatch.owner_native_type);
			if (owner_type == nullptr || services->variant_get_ptr_builtin_method == nullptr) {
				return failure("builtin_method_lookup_failed");
			}
			if (call.receiver_native == nullptr) {
				return failure("missing_builtin_receiver");
			}
			if (dispatch.return_native_type != "void" && call.native_result == nullptr) {
				return failure("missing_builtin_result");
			}
			if (dispatch.return_native_type == "void" &&
					(call.variant_result == nullptr || services->variant_new_nil == nullptr)) {
				return failure("missing_void_nil_constructor");
			}
			const FoundryExtensionPtrBuiltInMethod method =
					services->variant_get_ptr_builtin_method(
							owner_type->abi_type,
							name.get(),
							dispatch.compatibility_hash);
			if (method == nullptr) {
				return failure("builtin_method_not_found");
			}
			method(
					dispatch.static_call ? nullptr : call.receiver_native,
					call.native_arguments.data(),
					dispatch.return_native_type == "void" ? nullptr : call.native_result,
					static_cast<int32_t>(call.native_arguments.size()));
			if (dispatch.return_native_type == "void") {
				services->variant_new_nil(call.variant_result);
			}
			return success();
		}
		case DispatchFamily::BUILTIN_CONSTRUCTOR: {
			const VariantCategoryInfo *owner_type = variant_category(dispatch.owner_native_type);
			if (owner_type == nullptr ||
					services->variant_get_ptr_constructor == nullptr ||
					call.native_result == nullptr) {
				return failure("builtin_constructor_lookup_failed");
			}
			const FoundryExtensionPtrConstructor constructor =
					services->variant_get_ptr_constructor(owner_type->abi_type, dispatch.constructor_index);
			if (constructor == nullptr) {
				return failure("builtin_constructor_not_found");
			}
			constructor(call.native_result, call.native_arguments.data());
			return success();
		}
		case DispatchFamily::BUILTIN_OPERATOR: {
			if (services->variant_evaluate == nullptr ||
					services->variant_destroy == nullptr ||
					call.receiver_variant == nullptr ||
					call.variant_result == nullptr ||
					call.variant_operator == FOUNDRY_EXTENSION_VARIANT_OP_MAX) {
				return failure("invalid_builtin_operator");
			}
			FoundryExtensionBool valid = 0;
			NativeValue nil_right;
			FoundryExtensionConstVariantPtr right = nullptr;
			if (call.variant_arguments.empty()) {
				if (services->variant_new_nil == nullptr || services->variant_destroy == nullptr) {
					return failure("missing_unary_nil_constructor");
				}
				nil_right = NativeValue::storage(abi_layout_size("Variant"));
				services->variant_new_nil(nil_right.data());
				nil_right.constructed = true;
				right = nil_right.data();
			} else {
				right = call.variant_arguments.front();
			}
			services->variant_evaluate(
					call.variant_operator,
					call.receiver_variant,
					right,
					call.variant_result,
					&valid);
			if (nil_right.constructed) {
				services->variant_destroy(nil_right.data());
				nil_right.constructed = false;
			}
			if (!valid) {
				services->variant_destroy(call.variant_result);
				return failure("builtin_operator_failed");
			}
			return success();
		}
		case DispatchFamily::BUILTIN_MEMBER: {
			const VariantCategoryInfo *owner_type = variant_category(dispatch.owner_native_type);
			if (owner_type == nullptr || call.receiver_native == nullptr) {
				return failure("invalid_builtin_member");
			}
			if (call.property_set) {
				return failure("builtin_member_set_unsupported");
			}
			if (services->variant_get_ptr_getter == nullptr || call.native_result == nullptr) {
				return failure("builtin_member_getter_lookup_failed");
			}
			const FoundryExtensionPtrGetter getter =
					services->variant_get_ptr_getter(owner_type->abi_type, name.get());
			if (getter == nullptr) {
				return failure("builtin_member_getter_not_found");
			}
			getter(call.receiver_native, call.native_result);
			return success();
		}
		case DispatchFamily::BUILTIN_CONSTANT: {
			const VariantCategoryInfo *owner_type = variant_category(dispatch.owner_native_type);
			if (owner_type == nullptr ||
					services->variant_get_constant_value == nullptr ||
					call.variant_result == nullptr) {
				return failure("builtin_constant_lookup_failed");
			}
			services->variant_get_constant_value(owner_type->abi_type, name.get(), call.variant_result);
			return success();
		}
		case DispatchFamily::UTILITY_FUNCTION: {
			if (services->variant_get_ptr_utility_function == nullptr) {
				return failure("utility_lookup_failed");
			}
			const FoundryExtensionPtrUtilityFunction utility =
					services->variant_get_ptr_utility_function(name.get(), dispatch.compatibility_hash);
			if (utility == nullptr) {
				return failure("utility_not_found");
			}
			if (dispatch.return_native_type != "void" && call.native_result == nullptr) {
				return failure("missing_utility_result");
			}
			if (dispatch.return_native_type == "void" &&
					(call.variant_result == nullptr || services->variant_new_nil == nullptr)) {
				return failure("missing_void_nil_constructor");
			}
			utility(
					dispatch.return_native_type == "void" ? nullptr : call.native_result,
					call.native_arguments.data(),
					static_cast<int32_t>(call.native_arguments.size()));
			if (dispatch.return_native_type == "void") {
				services->variant_new_nil(call.variant_result);
			}
			return success();
		}
		case DispatchFamily::INVALID:
			return failure("invalid_dispatch_kind");
	}
	return failure("invalid_dispatch_kind");
}

TransportResult NativeTransport::invoke_callable(
		FoundryExtensionVariantPtr callable,
		const std::vector<FoundryExtensionConstVariantPtr> &arguments,
		FoundryExtensionUninitializedVariantPtr result) {
	if (services == nullptr ||
			services->variant_call == nullptr ||
			services->variant_destroy == nullptr ||
			callable == nullptr ||
			result == nullptr) {
		return failure("invalid_callable_invocation");
	}
	ScopedStringName call_name(*services, "call");
	if (!call_name) {
		return failure("string_name_construction_failed");
	}
	FoundryExtensionCallError error{};
	services->variant_call(
			callable,
			call_name.get(),
			arguments.data(),
			static_cast<FoundryExtensionInt>(arguments.size()),
			result,
			&error);
	if (error.error != FOUNDRY_EXTENSION_CALL_OK) {
		services->variant_destroy(result);
		return { false, "callable_invocation_failed", error };
	}
	return success();
}

TransportResult NativeTransport::collection_get_named(
		FoundryExtensionConstVariantPtr collection,
		std::string_view name,
		FoundryExtensionUninitializedVariantPtr result) {
	if (services == nullptr || services->variant_get_named == nullptr ||
			services->variant_destroy == nullptr ||
			collection == nullptr || result == nullptr) {
		return failure("invalid_named_get");
	}
	ScopedStringName key(*services, std::string(name));
	if (!key) {
		return failure("string_name_construction_failed");
	}
	FoundryExtensionBool valid = 0;
	services->variant_get_named(collection, key.get(), result, &valid);
	if (!valid) {
		services->variant_destroy(result);
		return failure("named_get_failed");
	}
	return success();
}

TransportResult NativeTransport::collection_set_named(
		FoundryExtensionVariantPtr collection,
		std::string_view name,
		FoundryExtensionConstVariantPtr value) {
	if (services == nullptr || services->variant_set_named == nullptr ||
			collection == nullptr || value == nullptr) {
		return failure("invalid_named_set");
	}
	ScopedStringName key(*services, std::string(name));
	if (!key) {
		return failure("string_name_construction_failed");
	}
	FoundryExtensionBool valid = 0;
	services->variant_set_named(collection, key.get(), value, &valid);
	return valid ? success() : failure("named_set_failed");
}

TransportResult NativeTransport::collection_get_keyed(
		FoundryExtensionConstVariantPtr collection,
		FoundryExtensionConstVariantPtr key,
		FoundryExtensionUninitializedVariantPtr result) {
	if (services == nullptr || services->variant_get_keyed == nullptr ||
			services->variant_destroy == nullptr ||
			collection == nullptr || key == nullptr || result == nullptr) {
		return failure("invalid_keyed_get");
	}
	FoundryExtensionBool valid = 0;
	services->variant_get_keyed(collection, key, result, &valid);
	if (!valid) {
		services->variant_destroy(result);
		return failure("keyed_get_failed");
	}
	return success();
}

TransportResult NativeTransport::collection_set_keyed(
		FoundryExtensionVariantPtr collection,
		FoundryExtensionConstVariantPtr key,
		FoundryExtensionConstVariantPtr value) {
	if (services == nullptr || services->variant_set_keyed == nullptr ||
			collection == nullptr || key == nullptr || value == nullptr) {
		return failure("invalid_keyed_set");
	}
	FoundryExtensionBool valid = 0;
	services->variant_set_keyed(collection, key, value, &valid);
	return valid ? success() : failure("keyed_set_failed");
}

TransportResult NativeTransport::collection_get_indexed(
		FoundryExtensionConstVariantPtr collection,
		FoundryExtensionInt index,
		FoundryExtensionUninitializedVariantPtr result) {
	if (services == nullptr || services->variant_get_indexed == nullptr ||
			services->variant_destroy == nullptr ||
			collection == nullptr || result == nullptr) {
		return failure("invalid_indexed_get");
	}
	FoundryExtensionBool valid = 0;
	FoundryExtensionBool out_of_bounds = 0;
	services->variant_get_indexed(collection, index, result, &valid, &out_of_bounds);
	if (!valid || out_of_bounds) {
		services->variant_destroy(result);
		return failure("indexed_get_failed");
	}
	return success();
}

TransportResult NativeTransport::collection_set_indexed(
		FoundryExtensionVariantPtr collection,
		FoundryExtensionInt index,
		FoundryExtensionConstVariantPtr value) {
	if (services == nullptr || services->variant_set_indexed == nullptr ||
			collection == nullptr || value == nullptr) {
		return failure("invalid_indexed_set");
	}
	FoundryExtensionBool valid = 0;
	FoundryExtensionBool out_of_bounds = 0;
	services->variant_set_indexed(collection, index, value, &valid, &out_of_bounds);
	return valid && !out_of_bounds ? success() : failure("indexed_set_failed");
}

TransportResult NativeTransport::collection_iterate(
		FoundryExtensionConstVariantPtr collection,
		FoundryExtensionVariantType collection_type,
		const std::function<bool(
				FoundryExtensionConstVariantPtr,
				FoundryExtensionConstVariantPtr)> &visitor) {
	if (services == nullptr ||
			services->variant_iter_init == nullptr ||
			services->variant_iter_next == nullptr ||
			services->variant_iter_get == nullptr ||
			services->variant_get_type == nullptr ||
			(collection_type == FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY &&
					services->variant_get_keyed == nullptr) ||
			services->variant_destroy == nullptr ||
			collection == nullptr ||
			!visitor) {
		return failure("invalid_collection_iteration");
	}
	const bool supported_collection =
			collection_type == FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY ||
			collection_type == FOUNDRY_EXTENSION_VARIANT_TYPE_ARRAY ||
			(collection_type >= FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_BYTE_ARRAY &&
					collection_type <= FOUNDRY_EXTENSION_VARIANT_TYPE_PACKED_VECTOR4_ARRAY);
	if (!supported_collection || services->variant_get_type(collection) != collection_type) {
		return failure("collection_type_mismatch");
	}
	NativeValue iterator = NativeValue::storage(abi_layout_size("Variant"));
	FoundryExtensionBool valid = 0;
	FoundryExtensionBool has_value =
			services->variant_iter_init(collection, iterator.data(), &valid);
	iterator.constructed = true;
	if (!valid) {
		services->variant_destroy(iterator.data());
		iterator.constructed = false;
		return failure("collection_iter_init_failed");
	}
	while (has_value) {
		NativeValue value = NativeValue::storage(abi_layout_size("Variant"));
		services->variant_iter_get(collection, iterator.data(), value.data(), &valid);
		value.constructed = true;
		if (!valid) {
			services->variant_destroy(value.data());
			value.constructed = false;
			services->variant_destroy(iterator.data());
			iterator.constructed = false;
			return failure("collection_iter_get_failed");
		}
		NativeValue dictionary_value;
		FoundryExtensionConstVariantPtr visit_key = iterator.data();
		FoundryExtensionConstVariantPtr visit_value = value.data();
		if (collection_type == FOUNDRY_EXTENSION_VARIANT_TYPE_DICTIONARY) {
			dictionary_value = NativeValue::storage(abi_layout_size("Variant"));
			services->variant_get_keyed(
					collection,
					value.data(),
					dictionary_value.data(),
					&valid);
			dictionary_value.constructed = true;
			if (!valid) {
				services->variant_destroy(dictionary_value.data());
				services->variant_destroy(value.data());
				services->variant_destroy(iterator.data());
				return failure("collection_dictionary_value_failed");
			}
			visit_key = value.data();
			visit_value = dictionary_value.data();
		}
		bool keep_going = false;
		try {
			keep_going = visitor(visit_key, visit_value);
		} catch (...) {
			if (dictionary_value.constructed) {
				services->variant_destroy(dictionary_value.data());
				dictionary_value.constructed = false;
			}
			services->variant_destroy(value.data());
			value.constructed = false;
			services->variant_destroy(iterator.data());
			iterator.constructed = false;
			return failure("collection_visitor_failed");
		}
		if (dictionary_value.constructed) {
			services->variant_destroy(dictionary_value.data());
			dictionary_value.constructed = false;
		}
		services->variant_destroy(value.data());
		value.constructed = false;
		if (!keep_going) {
			break;
		}
		has_value = services->variant_iter_next(collection, iterator.data(), &valid);
		if (!valid) {
			services->variant_destroy(iterator.data());
			return failure("collection_iter_next_failed");
		}
	}
	services->variant_destroy(iterator.data());
	iterator.constructed = false;
	return success();
}

NativeHandle NativeTransport::instantiate(
		ContextHandle context,
		std::uint64_t generation,
		const std::string &native_class) {
	if (services == nullptr ||
			services->classdb_construct_object2 == nullptr ||
			services->classdb_get_method_bind == nullptr ||
			services->object_method_bind_ptrcall == nullptr ||
			services->object_destroy == nullptr ||
			services->object_get_instance_id == nullptr ||
			services->object_get_instance_from_id == nullptr) {
		return 0;
	}
	ScopedStringName class_name(*services, native_class);
	ScopedStringName object_name(*services, "Object");
	ScopedStringName notification_name(*services, "notification");
	if (!class_name || !object_name || !notification_name) {
		return 0;
	}
	FoundryExtensionObjectPtr object = services->classdb_construct_object2(class_name.get());
	if (object == nullptr) {
		return 0;
	}
	const FoundryExtensionMethodBindPtr notification = services->classdb_get_method_bind(
			object_name.get(),
			notification_name.get(),
			4023243586);
	if (notification == nullptr) {
		if (services->object_destroy != nullptr) {
			services->object_destroy(object);
		}
		return 0;
	}
	const std::int32_t postinitialize = 0;
	const FoundryExtensionBool reversed = 0;
	const FoundryExtensionConstTypePtr arguments[] = { &postinitialize, &reversed };
	services->object_method_bind_ptrcall(notification, object, arguments, nullptr);
	if (services->classdb_get_class_tag != nullptr &&
			services->object_cast_to != nullptr) {
		ScopedStringName ref_counted_name(*services, "RefCounted");
		void *ref_counted_tag =
				ref_counted_name ? services->classdb_get_class_tag(ref_counted_name.get()) : nullptr;
		if (ref_counted_tag != nullptr &&
				services->object_cast_to(object, ref_counted_tag) != nullptr) {
			bool ownership_consumed = false;
			const NativeHandle ref_counted =
					retain_ref_counted(
							context,
							generation,
							object,
							native_class,
							true,
							&ownership_consumed);
			if (ref_counted == 0 && !ownership_consumed) {
				services->object_destroy(object);
			}
			return ref_counted;
		}
	}
	return track_object(context, generation, object, native_class, true);
}

NativeHandle NativeTransport::singleton(
		ContextHandle context,
		std::uint64_t generation,
		const std::string &native_name) {
	if (services == nullptr || services->global_get_singleton == nullptr) {
		return 0;
	}
	ScopedStringName name(*services, native_name);
	if (!name) {
		return 0;
	}
	return track_object(
			context,
			generation,
			services->global_get_singleton(name.get()),
			native_name,
			false);
}

bool NativeTransport::is_object_valid(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation) {
	HandleLease lease = handle_store.inspect(handle, context, generation);
	return lease &&
			lease.record().kind == HandleKind::OBJECT &&
			services != nullptr &&
			services->object_get_instance_from_id != nullptr &&
			services->object_get_instance_from_id(lease.record().value.object_instance_id) != nullptr;
}

bool NativeTransport::is_object_assignable(
		FoundryExtensionObjectPtr object,
		const std::string &expected_type) {
	if (object == nullptr || services == nullptr ||
			services->classdb_get_class_tag == nullptr ||
			services->object_cast_to == nullptr ||
			normalize_native_type(expected_type).kind != NativeTypeKind::OBJECT) {
		return false;
	}
	ScopedStringName type(*services, expected_type);
	if (!type) {
		return false;
	}
	void *tag = services->classdb_get_class_tag(type.get());
	return tag != nullptr && services->object_cast_to(object, tag) != nullptr;
}

TransportResult NativeTransport::object_type(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionClassLibraryPtr library,
		std::string &destination) {
	HandleLease lease = handle_store.inspect(handle, context, generation);
	if (!lease || lease.record().kind != HandleKind::OBJECT) {
		return failure("invalid_object_handle");
	}
	const std::string expected_type = lease.record().expected_type;
	lease = {};
	return object_type(handle, context, generation, expected_type, library, destination);
}

bool NativeTransport::retain_handle(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation) {
	HandleLease lease = handle_store.inspect(handle, context, generation);
	if (!lease) {
		return false;
	}
	if (lease.record().kind != HandleKind::OBJECT) {
		lease = {};
		return handle_store.retain(handle, context, generation) == handle;
	}
	if (lease.record().owned) {
		lease = {};
		return handle_store.retain(handle, context, generation) == handle;
	}
	if (services == nullptr ||
			services->classdb_get_class_tag == nullptr ||
			services->object_cast_to == nullptr ||
			services->classdb_get_method_bind == nullptr ||
			services->object_method_bind_ptrcall == nullptr ||
			services->object_get_instance_from_id == nullptr) {
		return false;
	}
	ScopedStringName ref_counted_name(*services, "RefCounted");
	ScopedStringName reference_name(*services, "reference");
	ScopedStringName unreference_name(*services, "unreference");
	if (!ref_counted_name || !reference_name || !unreference_name) {
		return false;
	}
	void *class_tag = services->classdb_get_class_tag(ref_counted_name.get());
	FoundryExtensionObjectPtr object = services->object_get_instance_from_id(
			lease.record().value.object_instance_id);
	FoundryExtensionObjectPtr ref_counted =
			class_tag == nullptr || object == nullptr ? nullptr : services->object_cast_to(object, class_tag);
	constexpr FoundryExtensionInt reference_hash = 2240911060;
	FoundryExtensionMethodBindPtr reference =
			ref_counted == nullptr ? nullptr :
									 services->classdb_get_method_bind(
											 ref_counted_name.get(), reference_name.get(), reference_hash);
	FoundryExtensionMethodBindPtr unreference =
			ref_counted == nullptr ? nullptr :
									 services->classdb_get_method_bind(
											 ref_counted_name.get(), unreference_name.get(), reference_hash);
	if (reference == nullptr || unreference == nullptr) {
		return false;
	}
	FoundryExtensionBool retained = 0;
	services->object_method_bind_ptrcall(reference, ref_counted, nullptr, &retained);
	if (!retained) {
		return false;
	}
	auto captured_services = services;
	lease = {};
	if (handle_store.promote_ownership(
				handle,
				context,
				generation,
				[captured_services, unreference](HandleRecord &record) {
					FoundryExtensionObjectPtr live =
							captured_services->object_get_instance_from_id(
									record.value.object_instance_id);
					if (live == nullptr) {
						return;
					}
					FoundryExtensionBool destroy = 0;
					captured_services->object_method_bind_ptrcall(
							unreference, live, nullptr, &destroy);
					if (destroy) {
						captured_services->object_destroy(live);
					}
				})) {
		return true;
	}
	FoundryExtensionBool destroy = 0;
	services->object_method_bind_ptrcall(unreference, ref_counted, nullptr, &destroy);
	if (destroy) {
		services->object_destroy(ref_counted);
	}
	return false;
}

bool NativeTransport::release_handle(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation) noexcept {
	std::uint64_t object_instance_id = 0;
	{
		HandleLease lease = handle_store.inspect(handle, context, generation);
		if (lease && lease.record().kind == HandleKind::OBJECT) {
			object_instance_id = lease.record().value.object_instance_id;
		}
	}
	const bool released = handle_store.release(handle, context, generation);
	const bool still_live =
			static_cast<bool>(handle_store.inspect(handle, context, generation));
	if (released && !still_live && object_instance_id != 0) {
		const std::string identity_key =
				std::to_string(context) + ":" +
				std::to_string(generation) + ":" +
				std::to_string(object_instance_id);
		std::lock_guard lock(object_identity_mutex);
		auto found = object_identity_handles.find(identity_key);
		if (found != object_identity_handles.end() && found->second == handle) {
			object_identity_handles.erase(found);
		}
	}
	return released;
}

NativeHandle NativeTransport::track_object_variant(
		NativeHandle variant_handle,
		ContextHandle context,
		std::uint64_t generation,
		bool *created) {
	FoundryExtensionObjectPtr object = nullptr;
	if (!inspect_variant(
				variant_handle,
				context,
				generation,
				FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT,
				&object)
				 .ok ||
			object == nullptr) {
		return 0;
	}
	return track_object(context, generation, object, "Object", false, created);
}

TransportResult NativeTransport::copy_variant_to(
		NativeHandle handle,
		ContextHandle context,
		std::uint64_t generation,
		FoundryExtensionUninitializedVariantPtr destination) {
	if (destination == nullptr || services == nullptr || services->variant_new_copy == nullptr) {
		return failure("missing_variant_copy_output");
	}
	HandleLease lease = handle_store.inspect(handle, context, generation);
	if (!lease || lease.record().kind != HandleKind::VARIANT) {
		return failure("invalid_variant_handle");
	}
	services->variant_new_copy(destination, lease.record().value.data());
	return success();
}

void NativeTransport::destroy_native_value(
		FoundryExtensionVariantType type,
		FoundryExtensionTypePtr value) noexcept {
	if (services == nullptr || services->variant_get_ptr_destructor == nullptr || value == nullptr) {
		return;
	}
	const FoundryExtensionPtrDestructor destructor = services->variant_get_ptr_destructor(type);
	if (destructor != nullptr) {
		destructor(value);
	}
}

} // namespace foundry_java
