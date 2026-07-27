#include "foundry_java_fake_extension_host.h"

#include "foundry_extension_interface.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>

extern "C" FoundryExtensionBool foundry_java_library_init(
    FoundryExtensionInterfaceGetProcAddress p_get_proc_address,
    FoundryExtensionClassLibraryPtr p_library,
    FoundryExtensionInitialization *r_initialization);

#ifdef FOUNDRY_JAVA_FAKE_HOST_CONTRACT_TEST
extern "C" FoundryExtensionBool
foundry_java_library_init(FoundryExtensionInterfaceGetProcAddress,
                          FoundryExtensionClassLibraryPtr,
                          FoundryExtensionInitialization *) {
  return 0;
}
#endif

namespace foundry_java_test_host {
namespace {

constexpr const char *CORE_CLASS = "FoundryJavaTestCore";
constexpr const char *SCENE_CLASS = "FoundryJavaTestScene";

struct AbiText {
  std::string value;
};

struct FakeObject;

struct VariantValue {
  FoundryExtensionVariantType type = FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
  FoundryExtensionBool boolean = 0;
  std::int64_t integer = 0;
  double floating = 0.0;
  std::string text;
  FakeObject *object = nullptr;
};

struct FakeCallableState {
  FoundryExtensionCallableCustomInfo2 info{};

  ~FakeCallableState() {
    if (info.free_func != nullptr) {
      info.free_func(info.callable_userdata);
    }
  }
};

struct FakeCallable {
  std::shared_ptr<FakeCallableState> state =
      std::make_shared<FakeCallableState>();
};

struct PropertySnapshot {
  FoundryExtensionVariantType type = FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
  std::string name;
  std::string class_name;
  std::uint32_t hint = 0;
  std::string hint_string;
  std::uint32_t usage = 0;
};

struct MethodSnapshot {
  std::string name;
  void *userdata = nullptr;
  FoundryExtensionClassMethodCall call = nullptr;
  FoundryExtensionClassMethodPtrCall ptrcall = nullptr;
  std::uint32_t flags = 0;
  bool has_return = false;
  PropertySnapshot return_value;
  FoundryExtensionClassMethodArgumentMetadata return_metadata =
      FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_NONE;
  std::vector<PropertySnapshot> arguments;
  std::vector<FoundryExtensionClassMethodArgumentMetadata> argument_metadata;
};

struct PropertyRegistrationSnapshot {
  PropertySnapshot info;
  std::string setter;
  std::string getter;
  bool indexed = false;
  FoundryExtensionInt index = -1;
};

struct SignalSnapshot {
  std::string name;
  std::vector<PropertySnapshot> arguments;
};

struct ConstantSnapshot {
  std::string enum_name;
  std::string name;
  FoundryExtensionInt value = 0;
  bool bitfield = false;
};

struct ClassSnapshot {
  std::string name;
  std::string parent;
  std::string icon_path;
  FoundryExtensionClassCreationInfo5 creation{};
  std::unordered_map<std::string, MethodSnapshot> methods;
  std::vector<PropertyRegistrationSnapshot> properties;
  std::vector<SignalSnapshot> signals;
  std::vector<ConstantSnapshot> constants;
};

struct FakeObject {
  GDObjectInstanceID id = 0;
  std::string class_name;
  FoundryExtensionClassInstancePtr extension_instance = nullptr;
  FoundryExtensionClassFreeInstance free_instance = nullptr;
  void *class_userdata = nullptr;
  void *binding_token = nullptr;
  void *binding = nullptr;
  const FoundryExtensionInstanceBindingCallbacks *binding_callbacks = nullptr;
};

struct FakeHostState {
  std::mutex mutex;
  std::unordered_map<std::string, ClassSnapshot> classes;
  std::unordered_map<GDObjectInstanceID, FakeObject *> objects;
  std::unordered_map<std::string, std::uint64_t> registration_counts;
  std::unordered_map<std::string, std::uint64_t> unregistration_counts;
  std::vector<std::string> registration_order;
  std::vector<std::string> unregistration_order;
  std::vector<std::string> errors;
  std::map<std::string, std::uintptr_t> class_tags;
  GDObjectInstanceID next_object_id = 1;
};

FakeHostState host_state;
std::atomic<std::size_t> live_text_values{0};
std::atomic<std::size_t> live_variant_values{0};
std::atomic<std::size_t> live_callable_values{0};

template <typename T> T *read_pointer_slot(const void *storage) {
  if (storage == nullptr) {
    return nullptr;
  }
  T *result = nullptr;
  std::memcpy(&result, storage, sizeof(result));
  return result;
}

template <typename T> void write_pointer_slot(void *storage, T *value) {
  if (storage != nullptr) {
    std::memcpy(storage, &value, sizeof(value));
  }
}

std::string text_value(const void *storage) {
  const AbiText *text = read_pointer_slot<AbiText>(storage);
  return text == nullptr ? std::string() : text->value;
}

void initialize_text(void *storage, std::string value) {
  auto *text = new AbiText{std::move(value)};
  live_text_values.fetch_add(1, std::memory_order_relaxed);
  write_pointer_slot(storage, text);
}

void destroy_text(FoundryExtensionTypePtr storage) {
  AbiText *text = read_pointer_slot<AbiText>(storage);
  if (text == nullptr) {
    return;
  }
  write_pointer_slot<AbiText>(storage, nullptr);
  delete text;
  live_text_values.fetch_sub(1, std::memory_order_relaxed);
}

VariantValue *variant_value(const void *storage) {
  return read_pointer_slot<VariantValue>(storage);
}

void initialize_variant(void *storage, FoundryExtensionVariantType type,
                        const VariantValue *source = nullptr) {
  auto *value =
      source == nullptr ? new VariantValue() : new VariantValue(*source);
  if (source == nullptr) {
    value->type = type;
  }
  live_variant_values.fetch_add(1, std::memory_order_relaxed);
  write_pointer_slot(storage, value);
}

void destroy_variant(FoundryExtensionVariantPtr storage) {
  VariantValue *value = variant_value(storage);
  if (value == nullptr) {
    return;
  }
  write_pointer_slot<VariantValue>(storage, nullptr);
  delete value;
  live_variant_values.fetch_sub(1, std::memory_order_relaxed);
}

PropertySnapshot snapshot_property(const FoundryExtensionPropertyInfo *info) {
  PropertySnapshot snapshot;
  if (info == nullptr) {
    return snapshot;
  }
  snapshot.type = info->type;
  snapshot.name = text_value(info->name);
  snapshot.class_name = text_value(info->class_name);
  snapshot.hint = info->hint;
  snapshot.hint_string = text_value(info->hint_string);
  snapshot.usage = info->usage;
  return snapshot;
}

void reset_capture() {
  std::lock_guard lock(host_state.mutex);
  host_state.classes.clear();
  host_state.registration_counts.clear();
  host_state.unregistration_counts.clear();
  host_state.registration_order.clear();
  host_state.unregistration_order.clear();
  host_state.errors.clear();
  host_state.class_tags.clear();
  host_state.next_object_id = 1;
}

std::size_t live_object_count() {
  std::lock_guard lock(host_state.mutex);
  return host_state.objects.size();
}

ClassSnapshot class_snapshot(const std::string &name) {
  std::lock_guard lock(host_state.mutex);
  const auto found = host_state.classes.find(name);
  return found == host_state.classes.end() ? ClassSnapshot{} : found->second;
}

std::vector<std::string> registration_order_snapshot() {
  std::lock_guard lock(host_state.mutex);
  return host_state.registration_order;
}

std::vector<std::string> unregistration_order_snapshot() {
  std::lock_guard lock(host_state.mutex);
  return host_state.unregistration_order;
}

std::vector<std::string> error_snapshot() {
  std::lock_guard lock(host_state.mutex);
  return host_state.errors;
}

bool registered_classes_empty() {
  std::lock_guard lock(host_state.mutex);
  return host_state.classes.empty();
}

std::uint64_t registration_count(const std::string &name) {
  std::lock_guard lock(host_state.mutex);
  return host_state.registration_counts[name];
}

std::uint64_t unregistration_count(const std::string &name) {
  std::lock_guard lock(host_state.mutex);
  return host_state.unregistration_counts[name];
}

FoundryExtensionClassInstancePtr
extension_instance(FoundryExtensionObjectPtr object) {
  if (object == nullptr) {
    return nullptr;
  }
  std::lock_guard lock(host_state.mutex);
  auto *fake_object = static_cast<FakeObject *>(object);
  const auto found = host_state.objects.find(fake_object->id);
  return found == host_state.objects.end() ? nullptr
                                           : found->second->extension_instance;
}

void set_call_error(FoundryExtensionCallError *error,
                    FoundryExtensionCallErrorType type) {
  if (error != nullptr) {
    error->error = type;
    error->argument = 0;
    error->expected = 0;
  }
}

void *fake_mem_alloc2(std::size_t bytes, FoundryExtensionBool) {
  return std::malloc(bytes);
}

void *fake_mem_realloc2(void *memory, std::size_t bytes, FoundryExtensionBool) {
  return std::realloc(memory, bytes);
}

void fake_mem_free2(void *memory, FoundryExtensionBool) { std::free(memory); }

void fake_print_error(const char *description, const char *, const char *,
                      std::int32_t, FoundryExtensionBool) {
  std::lock_guard lock(host_state.mutex);
  host_state.errors.emplace_back(description == nullptr ? "" : description);
}

std::uint64_t fake_get_native_struct_size(FoundryExtensionConstStringNamePtr) {
  return 0;
}

void fake_variant_new_copy(FoundryExtensionUninitializedVariantPtr destination,
                           FoundryExtensionConstVariantPtr source) {
  initialize_variant(destination, FOUNDRY_EXTENSION_VARIANT_TYPE_NIL,
                     variant_value(source));
}

void fake_variant_new_nil(FoundryExtensionUninitializedVariantPtr destination) {
  initialize_variant(destination, FOUNDRY_EXTENSION_VARIANT_TYPE_NIL);
}

void fake_variant_destroy(FoundryExtensionVariantPtr value) {
  destroy_variant(value);
}

void fake_variant_call(FoundryExtensionVariantPtr,
                       FoundryExtensionConstStringNamePtr,
                       const FoundryExtensionConstVariantPtr *,
                       FoundryExtensionInt,
                       FoundryExtensionUninitializedVariantPtr result,
                       FoundryExtensionCallError *error) {
  fake_variant_new_nil(result);
  set_call_error(error, FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD);
}

void fake_variant_construct(FoundryExtensionVariantType type,
                            FoundryExtensionUninitializedVariantPtr result,
                            const FoundryExtensionConstVariantPtr *arguments,
                            std::int32_t argument_count,
                            FoundryExtensionCallError *error) {
  if (argument_count == 1 && arguments != nullptr &&
      variant_value(arguments[0]) != nullptr) {
    initialize_variant(result, type, variant_value(arguments[0]));
  } else {
    initialize_variant(result, type);
  }
  set_call_error(error, FOUNDRY_EXTENSION_CALL_OK);
}

FoundryExtensionVariantType
fake_variant_get_type(FoundryExtensionConstVariantPtr value) {
  const VariantValue *variant = variant_value(value);
  return variant == nullptr ? FOUNDRY_EXTENSION_VARIANT_TYPE_NIL
                            : variant->type;
}

void variant_from_bool(FoundryExtensionUninitializedVariantPtr destination,
                       FoundryExtensionTypePtr source) {
  initialize_variant(destination, FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL);
  variant_value(destination)->boolean =
      source == nullptr ? 0 : *static_cast<FoundryExtensionBool *>(source);
}

void variant_from_int(FoundryExtensionUninitializedVariantPtr destination,
                      FoundryExtensionTypePtr source) {
  initialize_variant(destination, FOUNDRY_EXTENSION_VARIANT_TYPE_INT);
  variant_value(destination)->integer =
      source == nullptr ? 0 : *static_cast<std::int64_t *>(source);
}

void variant_from_float(FoundryExtensionUninitializedVariantPtr destination,
                        FoundryExtensionTypePtr source) {
  initialize_variant(destination, FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT);
  variant_value(destination)->floating =
      source == nullptr ? 0.0 : *static_cast<double *>(source);
}

void variant_from_string(FoundryExtensionUninitializedVariantPtr destination,
                         FoundryExtensionTypePtr source) {
  initialize_variant(destination, FOUNDRY_EXTENSION_VARIANT_TYPE_STRING);
  variant_value(destination)->text = text_value(source);
}

void variant_from_string_name(
    FoundryExtensionUninitializedVariantPtr destination,
    FoundryExtensionTypePtr source) {
  initialize_variant(destination, FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME);
  variant_value(destination)->text = text_value(source);
}

void variant_from_object(FoundryExtensionUninitializedVariantPtr destination,
                         FoundryExtensionTypePtr source) {
  initialize_variant(destination, FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT);
  variant_value(destination)->object =
      source == nullptr ? nullptr : *static_cast<FakeObject **>(source);
}

void variant_to_bool(FoundryExtensionUninitializedTypePtr destination,
                     FoundryExtensionVariantPtr source) {
  const VariantValue *value = variant_value(source);
  *static_cast<FoundryExtensionBool *>(destination) =
      value == nullptr ? 0 : value->boolean;
}

void variant_to_int(FoundryExtensionUninitializedTypePtr destination,
                    FoundryExtensionVariantPtr source) {
  const VariantValue *value = variant_value(source);
  *static_cast<std::int64_t *>(destination) =
      value == nullptr ? 0 : value->integer;
}

void variant_to_float(FoundryExtensionUninitializedTypePtr destination,
                      FoundryExtensionVariantPtr source) {
  const VariantValue *value = variant_value(source);
  *static_cast<double *>(destination) =
      value == nullptr ? 0.0 : value->floating;
}

void variant_to_string(FoundryExtensionUninitializedTypePtr destination,
                       FoundryExtensionVariantPtr source) {
  const VariantValue *value = variant_value(source);
  initialize_text(destination, value == nullptr ? std::string() : value->text);
}

void variant_to_string_name(FoundryExtensionUninitializedTypePtr destination,
                            FoundryExtensionVariantPtr source) {
  variant_to_string(destination, source);
}

void variant_to_object(FoundryExtensionUninitializedTypePtr destination,
                       FoundryExtensionVariantPtr source) {
  const VariantValue *value = variant_value(source);
  *static_cast<FakeObject **>(destination) =
      value == nullptr ? nullptr : value->object;
}

FoundryExtensionVariantFromTypeConstructorFunc
fake_get_variant_from_type_constructor(FoundryExtensionVariantType type) {
  switch (type) {
  case FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL:
    return &variant_from_bool;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_INT:
    return &variant_from_int;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT:
    return &variant_from_float;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_STRING:
    return &variant_from_string;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME:
    return &variant_from_string_name;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT:
    return &variant_from_object;
  default:
    return nullptr;
  }
}

FoundryExtensionTypeFromVariantConstructorFunc
fake_get_variant_to_type_constructor(FoundryExtensionVariantType type) {
  switch (type) {
  case FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL:
    return &variant_to_bool;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_INT:
    return &variant_to_int;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT:
    return &variant_to_float;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_STRING:
    return &variant_to_string;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME:
    return &variant_to_string_name;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_OBJECT:
    return &variant_to_object;
  default:
    return nullptr;
  }
}

void *variant_internal_bool(FoundryExtensionVariantPtr source) {
  VariantValue *value = variant_value(source);
  return value == nullptr ? nullptr : &value->boolean;
}

void *variant_internal_int(FoundryExtensionVariantPtr source) {
  VariantValue *value = variant_value(source);
  return value == nullptr ? nullptr : &value->integer;
}

void *variant_internal_float(FoundryExtensionVariantPtr source) {
  VariantValue *value = variant_value(source);
  return value == nullptr ? nullptr : &value->floating;
}

FoundryExtensionVariantGetInternalPtrFunc
fake_variant_get_internal(FoundryExtensionVariantType type) {
  switch (type) {
  case FOUNDRY_EXTENSION_VARIANT_TYPE_BOOL:
    return &variant_internal_bool;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_INT:
    return &variant_internal_int;
  case FOUNDRY_EXTENSION_VARIANT_TYPE_FLOAT:
    return &variant_internal_float;
  default:
    return nullptr;
  }
}

void text_default_constructor(FoundryExtensionUninitializedTypePtr destination,
                              const FoundryExtensionConstTypePtr *) {
  initialize_text(destination, {});
}

void text_copy_constructor(FoundryExtensionUninitializedTypePtr destination,
                           const FoundryExtensionConstTypePtr *arguments) {
  initialize_text(destination, arguments == nullptr ? std::string()
                                                    : text_value(arguments[0]));
}

void callable_default_constructor(
    FoundryExtensionUninitializedTypePtr destination,
    const FoundryExtensionConstTypePtr *) {
  auto *callable = new FakeCallable();
  live_callable_values.fetch_add(1, std::memory_order_relaxed);
  write_pointer_slot(destination, callable);
}

void callable_copy_constructor(FoundryExtensionUninitializedTypePtr destination,
                               const FoundryExtensionConstTypePtr *arguments) {
  const FakeCallable *source =
      arguments == nullptr ? nullptr
                           : read_pointer_slot<FakeCallable>(arguments[0]);
  auto *callable =
      source == nullptr ? new FakeCallable() : new FakeCallable(*source);
  live_callable_values.fetch_add(1, std::memory_order_relaxed);
  write_pointer_slot(destination, callable);
}

void destroy_callable(FoundryExtensionTypePtr storage) {
  FakeCallable *callable = read_pointer_slot<FakeCallable>(storage);
  if (callable == nullptr) {
    return;
  }
  write_pointer_slot<FakeCallable>(storage, nullptr);
  delete callable;
  live_callable_values.fetch_sub(1, std::memory_order_relaxed);
}

FoundryExtensionPtrConstructor
fake_variant_get_ptr_constructor(FoundryExtensionVariantType type,
                                 std::int32_t constructor) {
  if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING) {
    return constructor == 0                       ? &text_default_constructor
           : constructor == 1 || constructor == 2 ? &text_copy_constructor
                                                  : nullptr;
  }
  if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME) {
    return constructor == 0   ? &text_default_constructor
           : constructor == 1 ? &text_copy_constructor
                              : nullptr;
  }
  if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE) {
    return constructor == 0   ? &callable_default_constructor
           : constructor == 1 ? &callable_copy_constructor
                              : nullptr;
  }
  return nullptr;
}

FoundryExtensionPtrDestructor
fake_variant_get_ptr_destructor(FoundryExtensionVariantType type) {
  if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING ||
      type == FOUNDRY_EXTENSION_VARIANT_TYPE_STRING_NAME) {
    return &destroy_text;
  }
  if (type == FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE) {
    return &destroy_callable;
  }
  return nullptr;
}

void fake_variant_get_named(FoundryExtensionConstVariantPtr,
                            FoundryExtensionConstStringNamePtr,
                            FoundryExtensionUninitializedVariantPtr result,
                            FoundryExtensionBool *valid) {
  fake_variant_new_nil(result);
  if (valid != nullptr) {
    *valid = 0;
  }
}

void fake_variant_set_named(FoundryExtensionVariantPtr,
                            FoundryExtensionConstStringNamePtr,
                            FoundryExtensionConstVariantPtr,
                            FoundryExtensionBool *valid) {
  if (valid != nullptr) {
    *valid = 0;
  }
}

void fake_variant_get_keyed(FoundryExtensionConstVariantPtr,
                            FoundryExtensionConstVariantPtr,
                            FoundryExtensionUninitializedVariantPtr result,
                            FoundryExtensionBool *valid) {
  fake_variant_new_nil(result);
  if (valid != nullptr) {
    *valid = 0;
  }
}

void fake_variant_set_keyed(FoundryExtensionVariantPtr,
                            FoundryExtensionConstVariantPtr,
                            FoundryExtensionConstVariantPtr,
                            FoundryExtensionBool *valid) {
  if (valid != nullptr) {
    *valid = 0;
  }
}

void fake_variant_get_indexed(FoundryExtensionConstVariantPtr,
                              FoundryExtensionInt,
                              FoundryExtensionUninitializedVariantPtr result,
                              FoundryExtensionBool *valid,
                              FoundryExtensionBool *out_of_bounds) {
  fake_variant_new_nil(result);
  if (valid != nullptr) {
    *valid = 0;
  }
  if (out_of_bounds != nullptr) {
    *out_of_bounds = 1;
  }
}

void fake_variant_set_indexed(FoundryExtensionVariantPtr, FoundryExtensionInt,
                              FoundryExtensionConstVariantPtr,
                              FoundryExtensionBool *valid,
                              FoundryExtensionBool *out_of_bounds) {
  if (valid != nullptr) {
    *valid = 0;
  }
  if (out_of_bounds != nullptr) {
    *out_of_bounds = 1;
  }
}

FoundryExtensionBool
fake_variant_iter_init(FoundryExtensionConstVariantPtr,
                       FoundryExtensionUninitializedVariantPtr iterator,
                       FoundryExtensionBool *valid) {
  fake_variant_new_nil(iterator);
  if (valid != nullptr) {
    *valid = 0;
  }
  return 0;
}

FoundryExtensionBool fake_variant_iter_next(FoundryExtensionConstVariantPtr,
                                            FoundryExtensionVariantPtr,
                                            FoundryExtensionBool *valid) {
  if (valid != nullptr) {
    *valid = 0;
  }
  return 0;
}

void fake_variant_iter_get(FoundryExtensionConstVariantPtr,
                           FoundryExtensionVariantPtr,
                           FoundryExtensionUninitializedVariantPtr result,
                           FoundryExtensionBool *valid) {
  fake_variant_new_nil(result);
  if (valid != nullptr) {
    *valid = 0;
  }
}

void fake_variant_evaluate(FoundryExtensionVariantOperator,
                           FoundryExtensionConstVariantPtr,
                           FoundryExtensionConstVariantPtr,
                           FoundryExtensionUninitializedVariantPtr result,
                           FoundryExtensionBool *valid) {
  fake_variant_new_nil(result);
  if (valid != nullptr) {
    *valid = 0;
  }
}

void fake_variant_get_constant_value(
    FoundryExtensionVariantType, FoundryExtensionConstStringNamePtr,
    FoundryExtensionUninitializedVariantPtr result) {
  fake_variant_new_nil(result);
}

FoundryExtensionInt fake_string_new_with_utf8_chars_and_len2(
    FoundryExtensionUninitializedStringPtr destination, const char *contents,
    FoundryExtensionInt size) {
  if (size < 0 || (contents == nullptr && size != 0)) {
    initialize_text(destination, {});
    return 1;
  }
  initialize_text(destination,
                  contents == nullptr
                      ? std::string()
                      : std::string(contents, static_cast<std::size_t>(size)));
  return 0;
}

FoundryExtensionInt
fake_string_to_utf8_chars(FoundryExtensionConstStringPtr source,
                          char *destination, FoundryExtensionInt maximum) {
  const std::string value = text_value(source);
  if (destination != nullptr && maximum > 0) {
    const std::size_t count =
        std::min(value.size(), static_cast<std::size_t>(maximum));
    std::memcpy(destination, value.data(), count);
  }
  return static_cast<FoundryExtensionInt>(value.size());
}

void fake_string_name_new_with_utf8_chars_and_len(
    FoundryExtensionUninitializedStringNamePtr destination,
    const char *contents, FoundryExtensionInt size) {
  initialize_text(destination,
                  contents == nullptr || size <= 0
                      ? std::string()
                      : std::string(contents, static_cast<std::size_t>(size)));
}

void fake_object_method_bind_call(
    FoundryExtensionMethodBindPtr, FoundryExtensionObjectPtr,
    const FoundryExtensionConstVariantPtr *, FoundryExtensionInt,
    FoundryExtensionUninitializedVariantPtr result,
    FoundryExtensionCallError *error) {
  fake_variant_new_nil(result);
  set_call_error(error, FOUNDRY_EXTENSION_CALL_OK);
}

void fake_object_method_bind_ptrcall(FoundryExtensionMethodBindPtr,
                                     FoundryExtensionObjectPtr,
                                     const FoundryExtensionConstTypePtr *,
                                     FoundryExtensionTypePtr) {}

void fake_object_destroy(FoundryExtensionObjectPtr object) {
  if (object == nullptr) {
    return;
  }
  auto *fake_object = static_cast<FakeObject *>(object);
  FoundryExtensionClassInstancePtr instance = nullptr;
  FoundryExtensionClassFreeInstance free_instance = nullptr;
  void *class_userdata = nullptr;
  void *binding_token = nullptr;
  void *binding = nullptr;
  const FoundryExtensionInstanceBindingCallbacks *binding_callbacks = nullptr;
  {
    std::lock_guard lock(host_state.mutex);
    const auto found = host_state.objects.find(fake_object->id);
    if (found == host_state.objects.end()) {
      return;
    }
    host_state.objects.erase(found);
    instance = fake_object->extension_instance;
    free_instance = fake_object->free_instance;
    class_userdata = fake_object->class_userdata;
    binding_token = fake_object->binding_token;
    binding = fake_object->binding;
    binding_callbacks = fake_object->binding_callbacks;
    fake_object->extension_instance = nullptr;
    fake_object->binding = nullptr;
  }
  if (instance != nullptr && free_instance != nullptr) {
    free_instance(class_userdata, instance);
  }
  if (binding != nullptr && binding_callbacks != nullptr &&
      binding_callbacks->free_callback != nullptr) {
    binding_callbacks->free_callback(binding_token, object, binding);
  }
  delete fake_object;
}

FoundryExtensionObjectPtr
fake_global_get_singleton(FoundryExtensionConstStringNamePtr) {
  return nullptr;
}

void *fake_object_get_instance_binding(
    FoundryExtensionObjectPtr object, void *token,
    const FoundryExtensionInstanceBindingCallbacks *callbacks) {
  if (object == nullptr) {
    return nullptr;
  }
  auto *fake_object = static_cast<FakeObject *>(object);
  std::lock_guard lock(host_state.mutex);
  if (fake_object->binding == nullptr && callbacks != nullptr &&
      callbacks->create_callback != nullptr) {
    fake_object->binding = callbacks->create_callback(token, fake_object);
    fake_object->binding_token = token;
    fake_object->binding_callbacks = callbacks;
  }
  return fake_object->binding;
}

void fake_object_set_instance_binding(
    FoundryExtensionObjectPtr object, void *token, void *binding,
    const FoundryExtensionInstanceBindingCallbacks *callbacks) {
  if (object == nullptr) {
    return;
  }
  auto *fake_object = static_cast<FakeObject *>(object);
  std::lock_guard lock(host_state.mutex);
  fake_object->binding_token = token;
  fake_object->binding = binding;
  fake_object->binding_callbacks = callbacks;
}

void fake_object_free_instance_binding(FoundryExtensionObjectPtr object,
                                       void *token) {
  if (object == nullptr) {
    return;
  }
  auto *fake_object = static_cast<FakeObject *>(object);
  void *binding = nullptr;
  const FoundryExtensionInstanceBindingCallbacks *callbacks = nullptr;
  {
    std::lock_guard lock(host_state.mutex);
    if (fake_object->binding_token != token) {
      return;
    }
    binding = fake_object->binding;
    callbacks = fake_object->binding_callbacks;
    fake_object->binding = nullptr;
  }
  if (binding != nullptr && callbacks != nullptr &&
      callbacks->free_callback != nullptr) {
    callbacks->free_callback(token, object, binding);
  }
}

void fake_object_set_instance(FoundryExtensionObjectPtr object,
                              FoundryExtensionConstStringNamePtr class_name,
                              FoundryExtensionClassInstancePtr instance) {
  if (object == nullptr) {
    return;
  }
  const std::string name = text_value(class_name);
  auto *fake_object = static_cast<FakeObject *>(object);
  std::lock_guard lock(host_state.mutex);
  fake_object->class_name = name;
  fake_object->extension_instance = instance;
  const auto found = host_state.classes.find(name);
  if (found != host_state.classes.end()) {
    fake_object->free_instance = found->second.creation.free_instance_func;
    fake_object->class_userdata = found->second.creation.class_userdata;
  }
}

FoundryExtensionBool
fake_object_get_class_name(FoundryExtensionConstObjectPtr object,
                           FoundryExtensionClassLibraryPtr,
                           FoundryExtensionUninitializedStringNamePtr result) {
  if (object == nullptr) {
    return 0;
  }
  initialize_text(result, static_cast<const FakeObject *>(object)->class_name);
  return 1;
}

FoundryExtensionObjectPtr
fake_object_cast_to(FoundryExtensionConstObjectPtr object, void *class_tag) {
  return object == nullptr || class_tag == nullptr
             ? nullptr
             : const_cast<FoundryExtensionObjectPtr>(object);
}

FoundryExtensionObjectPtr
fake_object_get_instance_from_id(GDObjectInstanceID id) {
  std::lock_guard lock(host_state.mutex);
  const auto found = host_state.objects.find(id);
  return found == host_state.objects.end() ? nullptr : found->second;
}

GDObjectInstanceID
fake_object_get_instance_id(FoundryExtensionConstObjectPtr object) {
  return object == nullptr ? 0 : static_cast<const FakeObject *>(object)->id;
}

void fake_callable_custom_create2(
    FoundryExtensionUninitializedTypePtr destination,
    FoundryExtensionCallableCustomInfo2 *info) {
  auto *callable = new FakeCallable();
  if (info != nullptr) {
    callable->state->info = *info;
  }
  live_callable_values.fetch_add(1, std::memory_order_relaxed);
  write_pointer_slot(destination, callable);
}

void *fake_callable_custom_get_userdata(FoundryExtensionConstTypePtr callable,
                                        void *token) {
  const FakeCallable *value = read_pointer_slot<FakeCallable>(callable);
  return value == nullptr || value->state->info.token != token
             ? nullptr
             : value->state->info.callable_userdata;
}

FoundryExtensionObjectPtr
fake_classdb_construct_object2(FoundryExtensionConstStringNamePtr class_name) {
  auto *object = new FakeObject();
  {
    std::lock_guard lock(host_state.mutex);
    object->id = host_state.next_object_id++;
    object->class_name = text_value(class_name);
    host_state.objects.emplace(object->id, object);
  }
  return object;
}

FoundryExtensionMethodBindPtr
fake_classdb_get_method_bind(FoundryExtensionConstStringNamePtr class_name,
                             FoundryExtensionConstStringNamePtr method_name,
                             FoundryExtensionInt) {
  static const std::uintptr_t notification_method = 1;
  return text_value(class_name) == "Object" &&
                 text_value(method_name) == "notification"
             ? &notification_method
             : nullptr;
}

void *
fake_classdb_get_class_tag(FoundryExtensionConstStringNamePtr class_name) {
  const std::string name = text_value(class_name);
  std::lock_guard lock(host_state.mutex);
  std::uintptr_t &tag = host_state.class_tags[name];
  if (tag == 0) {
    tag = host_state.class_tags.size();
  }
  return &tag;
}

void fake_classdb_register_extension_class5(
    FoundryExtensionClassLibraryPtr,
    FoundryExtensionConstStringNamePtr class_name,
    FoundryExtensionConstStringNamePtr parent_class_name,
    const FoundryExtensionClassCreationInfo5 *creation) {
  const std::string name = text_value(class_name);
  ClassSnapshot snapshot;
  snapshot.name = name;
  snapshot.parent = text_value(parent_class_name);
  if (creation != nullptr) {
    snapshot.creation = *creation;
    snapshot.icon_path = text_value(creation->icon_path);
    snapshot.creation.icon_path = nullptr;
  }
  std::lock_guard lock(host_state.mutex);
  host_state.registration_order.push_back(name);
  host_state.registration_counts[name]++;
  host_state.classes[name] = std::move(snapshot);
}

void fake_classdb_register_extension_class_method(
    FoundryExtensionClassLibraryPtr,
    FoundryExtensionConstStringNamePtr class_name,
    const FoundryExtensionClassMethodInfo *info) {
  if (info == nullptr) {
    return;
  }
  MethodSnapshot snapshot;
  snapshot.name = text_value(info->name);
  snapshot.userdata = info->method_userdata;
  snapshot.call = info->call_func;
  snapshot.ptrcall = info->ptrcall_func;
  snapshot.flags = info->method_flags;
  snapshot.has_return = info->has_return_value != 0;
  snapshot.return_value = snapshot_property(info->return_value_info);
  snapshot.return_metadata = info->return_value_metadata;
  for (std::uint32_t index = 0; index < info->argument_count; index++) {
    snapshot.arguments.push_back(
        snapshot_property(&info->arguments_info[index]));
    snapshot.argument_metadata.push_back(
        info->arguments_metadata == nullptr
            ? FOUNDRY_EXTENSION_METHOD_ARGUMENT_METADATA_NONE
            : info->arguments_metadata[index]);
  }
  const std::string owner = text_value(class_name);
  std::lock_guard lock(host_state.mutex);
  host_state.classes[owner].methods[snapshot.name] = std::move(snapshot);
}

void fake_classdb_register_extension_class_integer_constant(
    FoundryExtensionClassLibraryPtr,
    FoundryExtensionConstStringNamePtr class_name,
    FoundryExtensionConstStringNamePtr enum_name,
    FoundryExtensionConstStringNamePtr constant_name,
    FoundryExtensionInt constant_value, FoundryExtensionBool is_bitfield) {
  ConstantSnapshot snapshot;
  snapshot.enum_name = text_value(enum_name);
  snapshot.name = text_value(constant_name);
  snapshot.value = constant_value;
  snapshot.bitfield = is_bitfield != 0;
  const std::string owner = text_value(class_name);
  std::lock_guard lock(host_state.mutex);
  host_state.classes[owner].constants.push_back(std::move(snapshot));
}

void register_property(FoundryExtensionConstStringNamePtr class_name,
                       const FoundryExtensionPropertyInfo *info,
                       FoundryExtensionConstStringNamePtr setter,
                       FoundryExtensionConstStringNamePtr getter, bool indexed,
                       FoundryExtensionInt index) {
  PropertyRegistrationSnapshot snapshot;
  snapshot.info = snapshot_property(info);
  snapshot.setter = text_value(setter);
  snapshot.getter = text_value(getter);
  snapshot.indexed = indexed;
  snapshot.index = index;
  const std::string owner = text_value(class_name);
  std::lock_guard lock(host_state.mutex);
  host_state.classes[owner].properties.push_back(std::move(snapshot));
}

void fake_classdb_register_extension_class_property(
    FoundryExtensionClassLibraryPtr,
    FoundryExtensionConstStringNamePtr class_name,
    const FoundryExtensionPropertyInfo *info,
    FoundryExtensionConstStringNamePtr setter,
    FoundryExtensionConstStringNamePtr getter) {
  register_property(class_name, info, setter, getter, false, -1);
}

void fake_classdb_register_extension_class_property_indexed(
    FoundryExtensionClassLibraryPtr,
    FoundryExtensionConstStringNamePtr class_name,
    const FoundryExtensionPropertyInfo *info,
    FoundryExtensionConstStringNamePtr setter,
    FoundryExtensionConstStringNamePtr getter, FoundryExtensionInt index) {
  register_property(class_name, info, setter, getter, true, index);
}

void fake_classdb_register_extension_class_property_group(
    FoundryExtensionClassLibraryPtr, FoundryExtensionConstStringNamePtr,
    FoundryExtensionConstStringPtr, FoundryExtensionConstStringPtr) {}

void fake_classdb_register_extension_class_property_subgroup(
    FoundryExtensionClassLibraryPtr, FoundryExtensionConstStringNamePtr,
    FoundryExtensionConstStringPtr, FoundryExtensionConstStringPtr) {}

void fake_classdb_register_extension_class_signal(
    FoundryExtensionClassLibraryPtr,
    FoundryExtensionConstStringNamePtr class_name,
    FoundryExtensionConstStringNamePtr signal_name,
    const FoundryExtensionPropertyInfo *argument_info,
    FoundryExtensionInt argument_count) {
  SignalSnapshot snapshot;
  snapshot.name = text_value(signal_name);
  for (FoundryExtensionInt index = 0; index < argument_count; index++) {
    snapshot.arguments.push_back(snapshot_property(&argument_info[index]));
  }
  const std::string owner = text_value(class_name);
  std::lock_guard lock(host_state.mutex);
  host_state.classes[owner].signals.push_back(std::move(snapshot));
}

void fake_classdb_unregister_extension_class(
    FoundryExtensionClassLibraryPtr,
    FoundryExtensionConstStringNamePtr class_name) {
  const std::string name = text_value(class_name);
  std::lock_guard lock(host_state.mutex);
  host_state.unregistration_order.push_back(name);
  host_state.unregistration_counts[name]++;
  host_state.classes.erase(name);
}

template <typename Function> struct TypedDefaultStub;

template <typename Result, typename... Arguments>
struct TypedDefaultStub<Result (*)(Arguments...)> {
  static Result call(Arguments...) {
    if constexpr (!std::is_void_v<Result>) {
      return Result{};
    }
  }
};

template <typename Function>
FoundryExtensionInterfaceFunctionPtr erase_function(Function function) {
  static_assert(sizeof(Function) ==
                sizeof(FoundryExtensionInterfaceFunctionPtr));
  FoundryExtensionInterfaceFunctionPtr result = nullptr;
  std::memcpy(&result, &function, sizeof(result));
  return result;
}

struct ServiceEntry {
  const char *name;
  FoundryExtensionInterfaceFunctionPtr function;
};

#define FOUNDRY_JAVA_TEST_HOST_SERVICE(name, type, function)                   \
  ServiceEntry { name, erase_function(static_cast<type>(function)) }
#define FOUNDRY_JAVA_TEST_HOST_DEFAULT_SERVICE(name, type)                     \
  FOUNDRY_JAVA_TEST_HOST_SERVICE(name, type, &TypedDefaultStub<type>::call)

const std::array<ServiceEntry, 60> &service_entries() {
  static const std::array<ServiceEntry, 60> entries = {{
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "mem_alloc2", FoundryExtensionInterfaceMemAlloc2, &fake_mem_alloc2),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("mem_realloc2",
                                     FoundryExtensionInterfaceMemRealloc2,
                                     &fake_mem_realloc2),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "mem_free2", FoundryExtensionInterfaceMemFree2, &fake_mem_free2),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("print_error",
                                     FoundryExtensionInterfacePrintError,
                                     &fake_print_error),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "get_native_struct_size",
          FoundryExtensionInterfaceGetNativeStructSize,
          &fake_get_native_struct_size),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_new_copy",
                                     FoundryExtensionInterfaceVariantNewCopy,
                                     &fake_variant_new_copy),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_new_nil",
                                     FoundryExtensionInterfaceVariantNewNil,
                                     &fake_variant_new_nil),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_destroy",
                                     FoundryExtensionInterfaceVariantDestroy,
                                     &fake_variant_destroy),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_call",
                                     FoundryExtensionInterfaceVariantCall,
                                     &fake_variant_call),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_construct",
                                     FoundryExtensionInterfaceVariantConstruct,
                                     &fake_variant_construct),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_get_type",
                                     FoundryExtensionInterfaceVariantGetType,
                                     &fake_variant_get_type),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "get_variant_from_type_constructor",
          FoundryExtensionInterfaceGetVariantFromTypeConstructor,
          &fake_get_variant_from_type_constructor),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "get_variant_to_type_constructor",
          FoundryExtensionInterfaceGetVariantToTypeConstructor,
          &fake_get_variant_to_type_constructor),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "variant_get_ptr_internal_getter",
          FoundryExtensionInterfaceGetVariantGetInternalPtrFunc,
          &fake_variant_get_internal),
      FOUNDRY_JAVA_TEST_HOST_DEFAULT_SERVICE(
          "variant_get_ptr_builtin_method",
          FoundryExtensionInterfaceVariantGetPtrBuiltinMethod),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "variant_get_ptr_constructor",
          FoundryExtensionInterfaceVariantGetPtrConstructor,
          &fake_variant_get_ptr_constructor),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "variant_get_ptr_destructor",
          FoundryExtensionInterfaceVariantGetPtrDestructor,
          &fake_variant_get_ptr_destructor),
      FOUNDRY_JAVA_TEST_HOST_DEFAULT_SERVICE(
          "variant_get_ptr_getter",
          FoundryExtensionInterfaceVariantGetPtrGetter),
      FOUNDRY_JAVA_TEST_HOST_DEFAULT_SERVICE(
          "variant_get_ptr_setter",
          FoundryExtensionInterfaceVariantGetPtrSetter),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_get_named",
                                     FoundryExtensionInterfaceVariantGetNamed,
                                     &fake_variant_get_named),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_set_named",
                                     FoundryExtensionInterfaceVariantSetNamed,
                                     &fake_variant_set_named),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_get_keyed",
                                     FoundryExtensionInterfaceVariantGetKeyed,
                                     &fake_variant_get_keyed),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_set_keyed",
                                     FoundryExtensionInterfaceVariantSetKeyed,
                                     &fake_variant_set_keyed),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_get_indexed",
                                     FoundryExtensionInterfaceVariantGetIndexed,
                                     &fake_variant_get_indexed),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_set_indexed",
                                     FoundryExtensionInterfaceVariantSetIndexed,
                                     &fake_variant_set_indexed),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_iter_init",
                                     FoundryExtensionInterfaceVariantIterInit,
                                     &fake_variant_iter_init),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_iter_next",
                                     FoundryExtensionInterfaceVariantIterNext,
                                     &fake_variant_iter_next),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_iter_get",
                                     FoundryExtensionInterfaceVariantIterGet,
                                     &fake_variant_iter_get),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("variant_evaluate",
                                     FoundryExtensionInterfaceVariantEvaluate,
                                     &fake_variant_evaluate),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "variant_get_constant_value",
          FoundryExtensionInterfaceVariantGetConstantValue,
          &fake_variant_get_constant_value),
      FOUNDRY_JAVA_TEST_HOST_DEFAULT_SERVICE(
          "variant_get_ptr_utility_function",
          FoundryExtensionInterfaceVariantGetPtrUtilityFunction),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "string_new_with_utf8_chars_and_len2",
          FoundryExtensionInterfaceStringNewWithUtf8CharsAndLen2,
          &fake_string_new_with_utf8_chars_and_len2),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("string_to_utf8_chars",
                                     FoundryExtensionInterfaceStringToUtf8Chars,
                                     &fake_string_to_utf8_chars),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "string_name_new_with_utf8_chars_and_len",
          FoundryExtensionInterfaceStringNameNewWithUtf8CharsAndLen,
          &fake_string_name_new_with_utf8_chars_and_len),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "object_method_bind_call",
          FoundryExtensionInterfaceObjectMethodBindCall,
          &fake_object_method_bind_call),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "object_method_bind_ptrcall",
          FoundryExtensionInterfaceObjectMethodBindPtrcall,
          &fake_object_method_bind_ptrcall),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("object_destroy",
                                     FoundryExtensionInterfaceObjectDestroy,
                                     &fake_object_destroy),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "global_get_singleton", FoundryExtensionInterfaceGlobalGetSingleton,
          &fake_global_get_singleton),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "object_get_instance_binding",
          FoundryExtensionInterfaceObjectGetInstanceBinding,
          &fake_object_get_instance_binding),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "object_set_instance_binding",
          FoundryExtensionInterfaceObjectSetInstanceBinding,
          &fake_object_set_instance_binding),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "object_free_instance_binding",
          FoundryExtensionInterfaceObjectFreeInstanceBinding,
          &fake_object_free_instance_binding),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("object_set_instance",
                                     FoundryExtensionInterfaceObjectSetInstance,
                                     &fake_object_set_instance),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "object_get_class_name", FoundryExtensionInterfaceObjectGetClassName,
          &fake_object_get_class_name),
      FOUNDRY_JAVA_TEST_HOST_SERVICE("object_cast_to",
                                     FoundryExtensionInterfaceObjectCastTo,
                                     &fake_object_cast_to),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "object_get_instance_from_id",
          FoundryExtensionInterfaceObjectGetInstanceFromId,
          &fake_object_get_instance_from_id),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "object_get_instance_id",
          FoundryExtensionInterfaceObjectGetInstanceId,
          &fake_object_get_instance_id),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "callable_custom_create2",
          FoundryExtensionInterfaceCallableCustomCreate2,
          &fake_callable_custom_create2),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "callable_custom_get_userdata",
          FoundryExtensionInterfaceCallableCustomGetUserData,
          &fake_callable_custom_get_userdata),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_construct_object2",
          FoundryExtensionInterfaceClassdbConstructObject2,
          &fake_classdb_construct_object2),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_get_method_bind",
          FoundryExtensionInterfaceClassdbGetMethodBind,
          &fake_classdb_get_method_bind),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_get_class_tag", FoundryExtensionInterfaceClassdbGetClassTag,
          &fake_classdb_get_class_tag),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_register_extension_class5",
          FoundryExtensionInterfaceClassdbRegisterExtensionClass5,
          &fake_classdb_register_extension_class5),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_register_extension_class_method",
          FoundryExtensionInterfaceClassdbRegisterExtensionClassMethod,
          &fake_classdb_register_extension_class_method),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_register_extension_class_integer_constant",
          FoundryExtensionInterfaceClassdbRegisterExtensionClassIntegerConstant,
          &fake_classdb_register_extension_class_integer_constant),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_register_extension_class_property",
          FoundryExtensionInterfaceClassdbRegisterExtensionClassProperty,
          &fake_classdb_register_extension_class_property),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_register_extension_class_property_indexed",
          FoundryExtensionInterfaceClassdbRegisterExtensionClassPropertyIndexed,
          &fake_classdb_register_extension_class_property_indexed),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_register_extension_class_property_group",
          FoundryExtensionInterfaceClassdbRegisterExtensionClassPropertyGroup,
          &fake_classdb_register_extension_class_property_group),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_register_extension_class_property_subgroup",
          FoundryExtensionInterfaceClassdbRegisterExtensionClassPropertySubgroup,
          &fake_classdb_register_extension_class_property_subgroup),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_register_extension_class_signal",
          FoundryExtensionInterfaceClassdbRegisterExtensionClassSignal,
          &fake_classdb_register_extension_class_signal),
      FOUNDRY_JAVA_TEST_HOST_SERVICE(
          "classdb_unregister_extension_class",
          FoundryExtensionInterfaceClassdbUnregisterExtensionClass,
          &fake_classdb_unregister_extension_class),
  }};
  return entries;
}

#undef FOUNDRY_JAVA_TEST_HOST_DEFAULT_SERVICE
#undef FOUNDRY_JAVA_TEST_HOST_SERVICE

FoundryExtensionInterfaceFunctionPtr get_proc_address(const char *name,
                                                      bool omit_unregister) {
  if (name == nullptr) {
    return nullptr;
  }
  for (const ServiceEntry &entry : service_entries()) {
    if (std::strcmp(entry.name, name) == 0) {
      if (omit_unregister &&
          std::strcmp(name, "classdb_unregister_extension_class") == 0) {
        return nullptr;
      }
      return entry.function;
    }
  }
  return nullptr;
}

FoundryExtensionInterfaceFunctionPtr get_proc_address_full(const char *name) {
  return get_proc_address(name, false);
}

FoundryExtensionInterfaceFunctionPtr
get_proc_address_missing_unregister(const char *name) {
  return get_proc_address(name, true);
}

bool fail_closed_probe() {
  FoundryExtensionInitialization rejected{};
  return foundry_java_library_init(&get_proc_address_missing_unregister,
                                   &host_state, &rejected) == 0 &&
         rejected.userdata == nullptr && rejected.initialize == nullptr &&
         rejected.deinitialize == nullptr;
}

bool inactive_entry_probe() {
  const std::size_t error_count_before = error_snapshot().size();
  FoundryExtensionInitialization rejected{};
  const bool entry_rejected =
      foundry_java_library_init(&get_proc_address_full, &host_state,
                                &rejected) == 0 &&
      rejected.userdata == nullptr && rejected.initialize == nullptr &&
      rejected.deinitialize == nullptr;
  const std::vector<std::string> errors = error_snapshot();
  return entry_rejected && errors.size() > error_count_before &&
         errors.back() == "Foundry Java JNI bootstrap is not ready.";
}

template <std::size_t Size> struct RawStorage {
  alignas(std::max_align_t) std::array<std::byte, Size> bytes{};
  void *data() { return bytes.data(); }
  const void *data() const { return bytes.data(); }
};

struct RawVariant final : RawStorage<64> {
  ~RawVariant() {
    if (variant_value(data()) != nullptr) {
      fake_variant_destroy(data());
    }
  }

  RawVariant() = default;
  RawVariant(const RawVariant &) = delete;
  RawVariant &operator=(const RawVariant &) = delete;
};

struct RawString final : RawStorage<32> {
  ~RawString() {
    if (read_pointer_slot<AbiText>(data()) != nullptr) {
      destroy_text(data());
    }
  }

  RawString() = default;
  RawString(const RawString &) = delete;
  RawString &operator=(const RawString &) = delete;
};

struct RawStringName final : RawStorage<32> {
  ~RawStringName() {
    if (read_pointer_slot<AbiText>(data()) != nullptr) {
      destroy_text(data());
    }
  }

  RawStringName() = default;
  RawStringName(const RawStringName &) = delete;
  RawStringName &operator=(const RawStringName &) = delete;
};

void make_int_variant(RawVariant &storage, std::int64_t value) {
  variant_from_int(storage.data(), &value);
}

std::int64_t read_int_variant(const RawVariant &storage) {
  const VariantValue *value = variant_value(storage.data());
  return value == nullptr || value->type != FOUNDRY_EXTENSION_VARIANT_TYPE_INT
             ? 0
             : value->integer;
}

bool is_nil_variant(const RawVariant &storage) {
  return variant_value(storage.data()) != nullptr &&
         fake_variant_get_type(storage.data()) ==
             FOUNDRY_EXTENSION_VARIANT_TYPE_NIL;
}

void destroy_if_variant(RawVariant &storage) {
  if (variant_value(storage.data()) != nullptr) {
    fake_variant_destroy(storage.data());
  }
}

struct CallbackEvidence {
  std::int64_t callback_result = 0;
  bool thread_attached = false;
  bool exception_contained = false;
  bool exception_default_is_nil = false;
  bool stale_callback_rejected = false;
  bool all_contract_checks = false;
};

bool has_constant_contract(const ClassSnapshot &core) {
  return std::any_of(core.constants.begin(), core.constants.end(),
                     [](const ConstantSnapshot &constant) {
                       return constant.name == "ANSWER" &&
                              constant.value == 42 && !constant.bitfield;
                     });
}

bool has_property_contract(const ClassSnapshot &core) {
  return std::any_of(core.properties.begin(), core.properties.end(),
                     [](const PropertyRegistrationSnapshot &property) {
                       return property.info.name == "value" &&
                              property.info.type ==
                                  FOUNDRY_EXTENSION_VARIANT_TYPE_INT &&
                              !property.indexed;
                     });
}

bool has_signal_contract(const ClassSnapshot &core) {
  return std::any_of(core.signals.begin(), core.signals.end(),
                     [](const SignalSnapshot &signal) {
                       return signal.name == "ping" &&
                              signal.arguments.size() == 1 &&
                              signal.arguments[0].type ==
                                  FOUNDRY_EXTENSION_VARIANT_TYPE_INT;
                     });
}

CallbackEvidence exercise_callbacks(const ClassSnapshot &core) {
  CallbackEvidence evidence;
  const auto round_trip = core.methods.find("round_trip");
  const auto throwing = core.methods.find("throwing_probe");
  const bool metadata_valid =
      core.name == CORE_CLASS && core.parent == "Node" &&
      round_trip != core.methods.end() && round_trip->second.call != nullptr &&
      round_trip->second.arguments.size() == 1 &&
      round_trip->second.arguments[0].type ==
          FOUNDRY_EXTENSION_VARIANT_TYPE_INT &&
      round_trip->second.has_return &&
      round_trip->second.return_value.type ==
          FOUNDRY_EXTENSION_VARIANT_TYPE_INT &&
      throwing != core.methods.end() && throwing->second.call != nullptr &&
      throwing->second.arguments.empty() && has_constant_contract(core) &&
      has_property_contract(core) && has_signal_contract(core) &&
      core.creation.create_instance_func != nullptr &&
      core.creation.to_string_func != nullptr &&
      core.creation.set_func != nullptr && core.creation.get_func != nullptr &&
      core.creation.get_virtual_call_data_func != nullptr &&
      core.creation.call_virtual_with_data_func != nullptr;
  if (!metadata_valid) {
    return evidence;
  }

  FoundryExtensionObjectPtr object =
      core.creation.create_instance_func(core.creation.class_userdata, 1);
  FoundryExtensionClassInstancePtr instance = extension_instance(object);
  if (object == nullptr || instance == nullptr) {
    if (object != nullptr) {
      fake_object_destroy(object);
    }
    return evidence;
  }

  RawVariant argument;
  RawVariant result;
  make_int_variant(argument, 41);
  const FoundryExtensionConstVariantPtr arguments[] = {argument.data()};
  FoundryExtensionCallError round_trip_error{};
  round_trip->second.call(round_trip->second.userdata, instance, arguments, 1,
                          result.data(), &round_trip_error);
  const bool round_trip_valid =
      round_trip_error.error == FOUNDRY_EXTENSION_CALL_OK &&
      read_int_variant(result) == 42;
  evidence.callback_result = read_int_variant(result);
  evidence.thread_attached = round_trip_valid;
  destroy_if_variant(argument);
  destroy_if_variant(result);

  RawVariant exception_result;
  FoundryExtensionCallError exception_error{};
  throwing->second.call(throwing->second.userdata, instance, nullptr, 0,
                        exception_result.data(), &exception_error);
  evidence.exception_contained =
      exception_error.error == FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD;
  evidence.exception_default_is_nil = is_nil_variant(exception_result);
  destroy_if_variant(exception_result);

  RawStringName property_name;
  fake_string_name_new_with_utf8_chars_and_len(property_name.data(), "value",
                                               5);
  RawVariant initial_property;
  const bool initial_get =
      core.creation.get_func(instance, property_name.data(),
                             initial_property.data()) != 0 &&
      read_int_variant(initial_property) == 7;
  destroy_if_variant(initial_property);
  RawVariant property_value;
  make_int_variant(property_value, 99);
  const bool set_property =
      core.creation.set_func(instance, property_name.data(),
                             property_value.data()) != 0;
  destroy_if_variant(property_value);
  RawVariant updated_property;
  const bool updated_get =
      core.creation.get_func(instance, property_name.data(),
                             updated_property.data()) != 0 &&
      read_int_variant(updated_property) == 99;
  destroy_if_variant(updated_property);
  destroy_text(property_name.data());

  RawStringName virtual_name;
  fake_string_name_new_with_utf8_chars_and_len(virtual_name.data(), "_process",
                                               8);
  void *virtual_data = core.creation.get_virtual_call_data_func(
      core.creation.class_userdata, virtual_name.data(), 0);
  std::int64_t virtual_argument = 21;
  std::int64_t virtual_result = 0;
  const FoundryExtensionConstTypePtr virtual_arguments[] = {&virtual_argument};
  if (virtual_data != nullptr) {
    core.creation.call_virtual_with_data_func(instance, virtual_name.data(),
                                              virtual_data, virtual_arguments,
                                              &virtual_result);
  }
  const bool virtual_valid = virtual_data != nullptr && virtual_result == 42;
  destroy_text(virtual_name.data());

  RawString rendered;
  FoundryExtensionBool rendered_valid = 0;
  core.creation.to_string_func(instance, &rendered_valid, rendered.data());
  const bool to_string_valid =
      rendered_valid != 0 &&
      text_value(rendered.data()) == "Task6FakeExtension";
  destroy_text(rendered.data());

  const FoundryExtensionClassInstancePtr stale_instance = instance;
  fake_object_destroy(object);
  RawVariant stale_argument;
  RawVariant stale_result;
  make_int_variant(stale_argument, 41);
  const FoundryExtensionConstVariantPtr stale_arguments[] = {
      stale_argument.data()};
  FoundryExtensionCallError stale_error{};
  round_trip->second.call(round_trip->second.userdata, stale_instance,
                          stale_arguments, 1, stale_result.data(),
                          &stale_error);
  evidence.stale_callback_rejected =
      stale_error.error == FOUNDRY_EXTENSION_CALL_ERROR_INVALID_METHOD &&
      is_nil_variant(stale_result);
  destroy_if_variant(stale_argument);
  destroy_if_variant(stale_result);

  evidence.all_contract_checks =
      round_trip_valid && evidence.exception_contained &&
      evidence.exception_default_is_nil && initial_get && set_property &&
      updated_get && virtual_valid && to_string_valid &&
      evidence.stale_callback_rejected && live_object_count() == 0;
  if (!evidence.all_contract_checks) {
    evidence.callback_result = 0;
  }
  return evidence;
}

std::string json_string_array(const std::vector<std::string> &values) {
  std::ostringstream json;
  json << '[';
  for (std::size_t index = 0; index < values.size(); index++) {
    if (index != 0) {
      json << ',';
    }
    json << '"' << values[index] << '"';
  }
  json << ']';
  return json.str();
}

const char *json_bool(bool value) { return value ? "true" : "false"; }

std::string registration_counts_json(bool unregistration) {
  std::ostringstream json;
  json << "{\"" << CORE_CLASS << "\":"
       << (unregistration ? unregistration_count(CORE_CLASS)
                          : registration_count(CORE_CLASS))
       << ",\"" << SCENE_CLASS << "\":"
       << (unregistration ? unregistration_count(SCENE_CLASS)
                          : registration_count(SCENE_CLASS))
       << '}';
  return json.str();
}

} // namespace

std::string pre_entry_evidence_v1() {
  reset_capture();
  const bool fail_closed = fail_closed_probe();
  const bool clean = live_object_count() == 0 &&
                     live_text_values.load(std::memory_order_relaxed) == 0 &&
                     live_variant_values.load(std::memory_order_relaxed) == 0 &&
                     live_callable_values.load(std::memory_order_relaxed) == 0;
  std::ostringstream json;
  json << "{\"schema_version\":1,\"bridge_ready\":"
       << json_bool(fail_closed && clean)
       << ",\"entry_active\":false,\"live_contexts\":0,"
          "\"registered_classes\":0}";
  return json.str();
}

std::string run_lifecycle_v1(int run_index) {
  reset_capture();
  std::vector<std::string> events;
  const bool fail_closed = fail_closed_probe();
  FoundryExtensionInitialization initialization{};
  const bool raw_entry_accepted =
      foundry_java_library_init(&get_proc_address_full, &host_state,
                                &initialization) != 0;
  const bool no_preinitialize_side_effects =
      registration_order_snapshot().empty() && live_object_count() == 0;
  const bool entry_accepted = fail_closed && raw_entry_accepted &&
                              no_preinitialize_side_effects &&
                              initialization.userdata != nullptr &&
                              initialization.initialize != nullptr &&
                              initialization.deinitialize != nullptr &&
                              initialization.minimum_initialization_level ==
                                  FOUNDRY_EXTENSION_INITIALIZATION_CORE;
  if (entry_accepted) {
    events.emplace_back("foundry_extension_entry");
  }

  std::vector<std::string> initialize_attempts;
  if (raw_entry_accepted) {
    initialize_attempts.emplace_back("CORE");
    initialization.initialize(initialization.userdata,
                              FOUNDRY_EXTENSION_INITIALIZATION_CORE);
    initialize_attempts.emplace_back("CORE");
    initialization.initialize(initialization.userdata,
                              FOUNDRY_EXTENSION_INITIALIZATION_CORE);
  }
  const ClassSnapshot core = class_snapshot(CORE_CLASS);
  const std::vector<std::string> core_registration_order =
      registration_order_snapshot();
  const bool core_initialized =
      entry_accepted && core.name == CORE_CLASS &&
      registration_count(CORE_CLASS) == 1 &&
      core_registration_order == std::vector<std::string>{CORE_CLASS};
  if (core_initialized) {
    events.emplace_back("core_initialize");
  }

  if (raw_entry_accepted) {
    initialize_attempts.emplace_back("SCENE");
    initialization.initialize(initialization.userdata,
                              FOUNDRY_EXTENSION_INITIALIZATION_SCENE);
    initialize_attempts.emplace_back("SCENE");
    initialization.initialize(initialization.userdata,
                              FOUNDRY_EXTENSION_INITIALIZATION_SCENE);
  }
  const ClassSnapshot scene = class_snapshot(SCENE_CLASS);
  const std::vector<std::string> registration_order =
      registration_order_snapshot();
  const bool scene_initialized =
      core_initialized && scene.name == SCENE_CLASS && scene.parent == "Node" &&
      registration_count(SCENE_CLASS) == 1 &&
      registration_order == std::vector<std::string>{CORE_CLASS, SCENE_CLASS};
  if (scene_initialized) {
    events.emplace_back("scene_initialize");
  }

  CallbackEvidence callback_evidence;
  if (core_initialized && scene_initialized) {
    std::thread callback_thread([&callback_evidence, core] {
      callback_evidence = exercise_callbacks(core);
    });
    callback_thread.join();
  }
  if (callback_evidence.all_contract_checks) {
    events.emplace_back("callback_dispatch");
  }

  std::vector<std::string> deinitialize_attempts;
  if (raw_entry_accepted) {
    deinitialize_attempts.emplace_back("SCENE");
    initialization.deinitialize(initialization.userdata,
                                FOUNDRY_EXTENSION_INITIALIZATION_SCENE);
    deinitialize_attempts.emplace_back("SCENE");
    initialization.deinitialize(initialization.userdata,
                                FOUNDRY_EXTENSION_INITIALIZATION_SCENE);
  }
  const std::vector<std::string> scene_unregistration_order =
      unregistration_order_snapshot();
  const bool scene_deinitialized =
      scene_initialized && unregistration_count(SCENE_CLASS) == 1 &&
      scene_unregistration_order == std::vector<std::string>{SCENE_CLASS};
  if (scene_deinitialized) {
    events.emplace_back("scene_deinitialize");
  }

  if (raw_entry_accepted) {
    deinitialize_attempts.emplace_back("CORE");
    initialization.deinitialize(initialization.userdata,
                                FOUNDRY_EXTENSION_INITIALIZATION_CORE);
    deinitialize_attempts.emplace_back("CORE");
    initialization.deinitialize(initialization.userdata,
                                FOUNDRY_EXTENSION_INITIALIZATION_CORE);
  }

  const std::vector<std::string> unregistration_order =
      unregistration_order_snapshot();
  const std::size_t live_instances = live_object_count();
  const std::size_t live_handles =
      live_text_values.load(std::memory_order_relaxed) +
      live_variant_values.load(std::memory_order_relaxed) +
      live_callable_values.load(std::memory_order_relaxed);
  const bool core_deinitialized =
      core_initialized && unregistration_count(CORE_CLASS) == 1 &&
      unregistration_order == std::vector<std::string>{SCENE_CLASS, CORE_CLASS};
  if (core_deinitialized) {
    events.emplace_back("core_deinitialize");
  }
  const bool terminal_state_observed =
      raw_entry_accepted && scene_deinitialized && core_deinitialized &&
      registered_classes_empty() && live_instances == 0 && live_handles == 0 &&
      inactive_entry_probe();
  if (terminal_state_observed) {
    events.emplace_back("context_invalidate");
  }
  const bool entry_active_after_teardown =
      raw_entry_accepted && !terminal_state_observed;
  // The public FoundryExtension registration ABI does not expose the Java
  // FoundryBindingContext handle. Android-side evidence replaces this explicit
  // placeholder with context.contextHandle() observed by CoreAccess.construct.
  const std::uint64_t context_handle = 0;

  std::ostringstream json;
  json << "{\"schema_version\":1,\"run_index\":" << run_index
       << ",\"entry_accepted\":" << json_bool(entry_accepted)
       << ",\"context_handle\":" << context_handle
       << ",\"events\":" << json_string_array(events)
       << ",\"initialize_attempts\":" << json_string_array(initialize_attempts)
       << ",\"registration_order\":" << json_string_array(registration_order)
       << ",\"registration_counts\":" << registration_counts_json(false)
       << ",\"callback_result\":" << callback_evidence.callback_result
       << ",\"callback_thread_attached\":"
       << json_bool(callback_evidence.thread_attached)
       << ",\"exception_contained\":"
       << json_bool(callback_evidence.exception_contained)
       << ",\"exception_default_is_nil\":"
       << json_bool(callback_evidence.exception_default_is_nil)
       << ",\"stale_instance_callback_rejected\":"
       << json_bool(callback_evidence.stale_callback_rejected)
       << ",\"deinitialize_attempts\":"
       << json_string_array(deinitialize_attempts)
       << ",\"unregistration_order\":"
       << json_string_array(unregistration_order)
       << ",\"unregistration_counts\":" << registration_counts_json(true)
       << ",\"live_instances_after_teardown\":" << live_instances
       << ",\"live_handles_after_teardown\":" << live_handles
       << ",\"entry_active_after_teardown\":"
       << json_bool(entry_active_after_teardown) << '}';
  return json.str();
}

#ifdef FOUNDRY_JAVA_FAKE_HOST_CONTRACT_TEST
int run_fake_host_contract_tests() {
  const std::string pre_entry = pre_entry_evidence_v1();
  if (pre_entry.find("\"registered_classes\":0") == std::string::npos) {
    return 1;
  }
  {
    RawStringName source;
    fake_string_name_new_with_utf8_chars_and_len(source.data(), "round_trip",
                                                 10);
    RawString destination;
    const FoundryExtensionPtrConstructor constructor =
        fake_variant_get_ptr_constructor(FOUNDRY_EXTENSION_VARIANT_TYPE_STRING,
                                         2);
    if (constructor == nullptr) {
      return 2;
    }
    const FoundryExtensionConstTypePtr arguments[] = {source.data()};
    constructor(destination.data(), arguments);
    if (text_value(destination.data()) != "round_trip") {
      return 3;
    }
  }
  if (live_text_values.load(std::memory_order_relaxed) != 0) {
    return 4;
  }
  RawStorage<32> original_callable;
  RawStorage<32> copied_callable;
  int callable_free_count = 0;
  FoundryExtensionCallableCustomInfo2 callable_info{};
  callable_info.callable_userdata = &callable_free_count;
  callable_info.token = &callable_free_count;
  callable_info.free_func = [](void *userdata) {
    (*static_cast<int *>(userdata))++;
  };
  fake_callable_custom_create2(original_callable.data(), &callable_info);
  const FoundryExtensionPtrConstructor copy_constructor =
      fake_variant_get_ptr_constructor(FOUNDRY_EXTENSION_VARIANT_TYPE_CALLABLE,
                                       1);
  if (copy_constructor == nullptr) {
    destroy_callable(original_callable.data());
    return 5;
  }
  const FoundryExtensionConstTypePtr callable_arguments[] = {
      original_callable.data()};
  copy_constructor(copied_callable.data(), callable_arguments);
  destroy_callable(original_callable.data());
  const int free_count_after_first_destroy = callable_free_count;
  destroy_callable(copied_callable.data());
  if (free_count_after_first_destroy != 0 || callable_free_count != 1) {
    return 6;
  }
  if (live_callable_values.load(std::memory_order_relaxed) != 0) {
    return 7;
  }
  {
    RawVariant unconstructed;
    if (is_nil_variant(unconstructed)) {
      return 8;
    }
    RawVariant constructed_nil;
    fake_variant_new_nil(constructed_nil.data());
    if (!is_nil_variant(constructed_nil)) {
      return 9;
    }
  }
  if (live_variant_values.load(std::memory_order_relaxed) != 0) {
    return 10;
  }
  const std::string rejected_lifecycle = run_lifecycle_v1(1);
  if (rejected_lifecycle.find("\"events\":[]") == std::string::npos) {
    return 11;
  }
  return rejected_lifecycle.find("\"entry_active_after_teardown\":false") !=
                 std::string::npos
             ? 0
             : 12;
}
#endif

} // namespace foundry_java_test_host

#ifdef FOUNDRY_JAVA_FAKE_HOST_CONTRACT_TEST
int main() { return foundry_java_test_host::run_fake_host_contract_tests(); }
#endif
