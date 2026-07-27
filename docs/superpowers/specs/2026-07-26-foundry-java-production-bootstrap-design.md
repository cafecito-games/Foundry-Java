# Foundry-Java Production Bootstrap and Transport Design

**Status:** Approved; API-23 packaging amendment approved 2026-07-26

## Context

Foundry-Java already packages the fixed `FoundryJava.foundryextension`, a
four-ABI `libfoundry_java.so`, generated registry providers, and a deterministic
application bootstrap. The remaining production gap is lifecycle wiring:
instrumentation calls `FoundryJavaInitializer.initialize()` directly, while an
exported Android application has no pre-Activity path that primes the JNI
bridge. The current bridge also installs only lifecycle callbacks. It does not
expose a production `FoundryEngine`, register a live `FoundryBindingContext`, or
invoke generated class descriptors through the FoundryExtension interface.

The Java half of native child Foundry #1251 closes that gap. The separate
Foundry engine/export integration and device conformance PR remains ordered
after Workstream 11.

Foundry-Android remains a read-only source donor. This work neither modifies it
nor packages, links, loads, or republishes `libfoundry_android.so`.

## 2026-07-26 API-23 and Base-Manifest Amendment

The host-neutral Java-17 runtime intentionally uses `java.util.function`,
streams, and recent `java.util` APIs. Rewriting every runtime path to avoid
those APIs would duplicate collection logic, alter the public coordinator
constructor, and contradict the selected Java-17 runtime design. The
application plugin therefore enables core-library desugaring and adds a strict
`com.android.tools:desugar_jdk_libs:2.1.5` dependency for every Android
application consumer. The Android library uses the same pin for isolated lint
and test builds. A conflicting consumer request must still resolve exactly
2.1.5.

This is a consumer build invariant, not a new runtime API.
`FoundryRegistryCoordinator` retains its approved
`LongFunction<? extends FoundryEngine>` constructor; no Android-only factory
type is added to the host-neutral public surface.

The base Android AAR manifest remains provider-free so zero-module consumers
stay inert. Only the application variant's generated manifest declares the
literal generated provider and `<applicationId>.foundry-java-startup`
authority. Repository verification rejects an `<application>` or `<provider>`
in the packaged base manifest.

Compatibility proof must build a minSdk-23 minified application from the actual
release AAR and runtime JAR, challenge the strict pin with an older requested
desugar version, prove `j$/util` rewriting in packaged DEX, and prove the stable
provider, generated provider/bootstrap, and module registry retain their direct
names and entry points. The same fixture must reuse configuration cache.

## 2026-07-27 Native Transport and ABI Amendment

The public producer ABI remains the authoritative Foundry source
`core/extension/foundry_extension_interface.json` and the corresponding
Foundry-Java snapshot `api/current/foundry_extension_interface.h`. Foundry-Java
does not add, rename, or infer producer entry points. In particular, there is no
`object_is_instance_valid`, `string_name_destroy`,
`variant_call_named|builtin|operator|utility`, or public Ref storage API.
Foundry-Java uses the legacy public-header typedef
`FoundryExtensionInterfaceGetVariantGetInternalPtrFunc` exactly as emitted.

Entry resolves one immutable `BridgeServices` value before publishing
`entry_active`. Resolution is all-or-nothing and reports the first exact missing
name. The Task 4/5 table contains:

- `mem_alloc2`, `mem_realloc2`, `mem_free2`, `print_error`, and
  `get_native_struct_size`;
- `variant_new_copy`, `variant_new_nil`, `variant_destroy`,
  `variant_call`, `variant_construct`, `variant_get_type`,
  `get_variant_from_type_constructor`,
  `get_variant_to_type_constructor`, `variant_get_ptr_internal_getter`,
  `variant_get_ptr_builtin_method`, `variant_get_ptr_constructor`,
  `variant_get_ptr_destructor`, `variant_get_ptr_getter`,
  `variant_get_ptr_setter`, `variant_get_named`, `variant_set_named`,
  `variant_get_keyed`, `variant_set_keyed`, `variant_get_indexed`,
  `variant_set_indexed`, `variant_iter_init`, `variant_iter_next`,
  `variant_iter_get`, `variant_evaluate`, `variant_get_constant_value`, and
  `variant_get_ptr_utility_function`;
- `string_new_with_utf8_chars_and_len2`, `string_to_utf8_chars`, and
  `string_name_new_with_utf8_chars_and_len`;
- `object_method_bind_call`, `object_method_bind_ptrcall`, `object_destroy`,
  `global_get_singleton`, `object_get_instance_binding`,
  `object_set_instance_binding`, `object_free_instance_binding`,
  `object_set_instance`, `object_get_class_name`, `object_cast_to`,
  `object_get_instance_from_id`, and `object_get_instance_id`;
- `callable_custom_create2`, `callable_custom_get_userdata`,
  `classdb_construct_object2`, `classdb_get_method_bind`, and
  `classdb_get_class_tag`; and
- the Task 5 registration functions
  `classdb_register_extension_class5`,
  `classdb_register_extension_class_method`,
  `classdb_register_extension_class_integer_constant`,
  `classdb_register_extension_class_property`,
  `classdb_register_extension_class_property_indexed`,
  `classdb_register_extension_class_property_group`,
  `classdb_register_extension_class_property_subgroup`,
  `classdb_register_extension_class_signal`, and
  `classdb_unregister_extension_class`.

Task 5 implements script overrides through the v5 class creation
`get_virtual_call_data`, `call_virtual_with_data`, and
`to_string_func` callbacks. It does not resolve or call
`classdb_register_extension_class_virtual_method`.

The stable runtime owns this literal record:

```java
public record FoundryNativeDispatch(
        String identity,
        Kind kind,
        String ownerNativeType,
        String nativeName,
        long compatibilityHash,
        int constructorIndex,
        List<String> argumentNativeTypes,
        String returnNativeType,
        String getterIdentity,
        String getterNativeName,
        long getterCompatibilityHash,
        String setterIdentity,
        String setterNativeName,
        long setterCompatibilityHash,
        boolean vararg,
        boolean staticCall) {
    public enum Kind {
        CLASS_METHOD,
        CLASS_PROPERTY,
        CLASS_SIGNAL,
        BUILTIN_METHOD,
        BUILTIN_CONSTRUCTOR,
        BUILTIN_OPERATOR,
        BUILTIN_MEMBER,
        BUILTIN_CONSTANT,
        UTILITY_FUNCTION
    }
}
```

Empty accessor identity/name values and `-1` hash/index values mean not
applicable; the record constructor validates the legal fields for each kind.
`argumentNativeTypes.size()` is the exact arity for fixed calls and the minimum
fixed arity for varargs. The API generator emits one immutable
`GeneratedNativeDispatch` table keyed by the exact structural identity already
passed to `FoundryEngine.call`, and instantiates only this stable runtime record.
A consumer-generated class never appears in an Android AAR native method
descriptor. Property entries preserve exact structural accessor identities,
native names, and compatibility hashes so overloads cannot alias. Unknown
identities, kinds, arities, and typed native-handle mismatches fail in Java
before JNI. `FoundryEngine.call(long, long, String, List<Variant>)` remains
unchanged; `FoundryNativeEngine` performs the generated lookup behind that
stable signature.

`FoundryNativeEngine` freezes these private static versioned native methods and
descriptors:

```text
nativeCallV1
  (JJLgames/cafecito/foundry/runtime/FoundryNativeDispatch;[Lgames/cafecito/foundry/types/Variant;)Lgames/cafecito/foundry/runtime/FoundryEngine$CallResult;
nativeDecodeVariantV1 (JJ)Lgames/cafecito/foundry/types/Variant;
nativeEncodeVariantV1 (JLgames/cafecito/foundry/types/Variant;)J
nativeIsObjectValidV1 (JJ)Z
nativeObjectTypeV1 (JJ)Ljava/lang/String;
nativeInstantiateV1 (JLjava/lang/String;)J
nativeRetainV1 (JJ)V
nativeReleaseV1 (JJ)V
nativeSingletonV1 (JLjava/lang/String;)J
nativeReportCallbackExceptionV1 (JJLjava/lang/Throwable;)V
nativeRegisterExtensionClassV1
  (JLgames/cafecito/foundry/runtime/FoundryClassDescriptor;)V
nativeUnregisterExtensionClassV1 (JLjava/lang/String;)V
```

The corresponding export names are exactly:

```text
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeCallV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeDecodeVariantV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeEncodeVariantV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeIsObjectValidV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeObjectTypeV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeInstantiateV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeRetainV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeReleaseV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeSingletonV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeReportCallbackExceptionV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeRegisterExtensionClassV1
Java_games_cafecito_foundry_java_FoundryNativeEngine_nativeUnregisterExtensionClassV1
```

The register and unregister seams are frozen in Task 4 even though Task 5
supplies their descriptor-registration bodies.

Dispatch follows the public ABI:

- class methods use `classdb_get_method_bind`; static ClassDB calls pass a null
  receiver;
- MethodBind calls use `object_method_bind_ptrcall` when the generated signature
  contains native pointers/structures and `object_method_bind_call` otherwise;
- built-in methods and constructors use the pointer built-in/constructor
  lookups, generic operators use `variant_evaluate`, utilities use
  `variant_get_ptr_utility_function`, built-in constants use
  `variant_get_constant_value`, and generic properties use named get/set;
- `instantiate` uses `classdb_construct_object2`, then invokes
  `Object.notification` with `POSTINITIALIZE` through the exact compatibility
  hash `4023243586`; and
- object bridge handles are unsigned 64-bit instance IDs, never addresses.

Generated ABI layout data includes both `float_32` and `float_64`
`builtin_class_sizes`. Current Android single-precision storage is checked as:
32-bit `String`/`StringName`/`Object` = 4 bytes and `Variant` = 24 bytes;
64-bit `String`/`StringName`/`Object` = 8 bytes and `Variant` = 24 bytes.
All constructed values use max-aligned generated storage and a constructed-bit
guard. `String` and `StringName` are destroyed by requesting their
`FoundryExtensionPtrDestructor` from `variant_get_ptr_destructor`; no imaginary
direct destructor is used.

Opaque native-structure handles are records containing context, generation,
kind, expected generated native type token, liveness, ownership, and native
value/storage. Every lookup validates all fields before interface use. Native
structure storage is sized by `get_native_struct_size` using the generated type
token's exact native type name: construct a `StringName` from that generated
name, pass the constructed `StringName` to `get_native_struct_size`, then run
the resolved `StringName` destructor. Object records retain an instance ID;
lookup reacquires the pointer with `object_get_instance_from_id`. Ref ownership
never fabricates `Ref<T>` storage: after
`classdb_get_class_tag`/`object_cast_to` validates `RefCounted`, retain and
release invoke exact `RefCounted.reference`/`unreference` MethodBinds with hash
`2240911060`. A true `unreference` result triggers `object_destroy` exactly
once.

Variant transport covers all 39 public Java categories: `NIL`, `BOOLEAN`,
`INTEGER`, `FLOAT`, `STRING`, `VECTOR2`, `VECTOR2I`, `RECT2`, `RECT2I`,
`VECTOR3`, `VECTOR3I`, `TRANSFORM2D`, `VECTOR4`, `VECTOR4I`, `PLANE`,
`QUATERNION`, `AABB`, `BASIS`, `TRANSFORM3D`, `PROJECTION`, `COLOR`,
`STRING_NAME`, `NODE_PATH`, `RID`, `OBJECT`, `CALLABLE`, `SIGNAL`,
`DICTIONARY`, `ARRAY`, `PACKED_BYTE_ARRAY`, `PACKED_INT32_ARRAY`,
`PACKED_INT64_ARRAY`, `PACKED_FLOAT32_ARRAY`, `PACKED_FLOAT64_ARRAY`,
`PACKED_STRING_ARRAY`, `PACKED_VECTOR2_ARRAY`, `PACKED_VECTOR3_ARRAY`,
`PACKED_COLOR_ARRAY`, and `PACKED_VECTOR4_ARRAY`. Java-local
`FoundryCallable` values use `callable_custom_create2`; a decoded native
Callable retains a native-backed representation and can be encoded again.
Native Signal values likewise decode to a native-backed representation and may
be re-encoded. The public ABI has no constructor for an arbitrary Java-local
Signal, so encoding one fails deterministically with type and phase. Supporting
that case requires a later deliberately registered proxy Object/signal; it is
not silently coerced. `FoundryCallable` and `FoundrySignal` therefore gain
explicit local/native backends while preserving their existing public behavior.

Task 4 starts below JNI. A pure-C++ fake interface must first prove immutable
all-or-nothing resolution, generated aligned storage, every Variant category,
generic dispatch route, typed handle rejection, object/reference ownership,
Callable/Signal policy, cleanup, and teardown. Only after that host RED/GREEN
surface is complete may JNI and `FoundryNativeEngine` be wired.

## Approaches

### Selected: generated application provider over a stable AAR base

The Gradle plugin generates one application-owned `ContentProvider` subclass.
The subclass directly references `FoundryGeneratedBootstrap.bootstrap()` and
passes that typed value to a stable AAR-owned provider hook. The plugin-generated
application manifest uses per-variant placeholders for the generated provider
class and authority; the base AAR manifest remains provider-free. There is no
class name lookup, registry scan, manifest metadata discovery, or reflection.

Android creates the provider before `Application.onCreate()` and every
Activity. The provider primes the application class loader, typed bootstrap,
and native library. It does not create an engine context or register a class.

### Rejected: AAR provider loads the generated class by name

An AAR-only provider could use `Class.forName` to find the generated bootstrap.
That would be short, but it reintroduces reflection and shrinker-sensitive
discovery. It is prohibited.

### Rejected: initialize only after Foundry loads the extension

The Foundry host could call Java after `foundry_java_library_init`, but the
entry already rejects an unprimed JNI bridge. This is too late and couples the
language binding to host implementation details.

## Android Startup Contract

The plugin generates these variant-specific artifacts only when the variant has
at least one validated Foundry module:

- `games.cafecito.foundry.generated.FoundryGeneratedStartupProvider`
- the deterministic registry Java source and assets already produced by the
  registry task;
- manifest placeholders:
  - `foundryJavaStartupProvider` =
    `games.cafecito.foundry.generated.FoundryGeneratedStartupProvider`;
  - `foundryJavaStartupAuthority` =
    `<variant applicationId>.foundry-java-startup`.

The generated provider extends
`games.cafecito.foundry.java.FoundryJavaStartupProvider`. Its only bootstrap
hook is a direct call to `FoundryGeneratedBootstrap.bootstrap()`.

The generated application manifest declares the provider with:

- `android:exported="false"`;
- no custom `android:process`;
- a fixed deterministic `android:initOrder`;
- the authority placeholder above.

Zero-module and non-opted variants emit no startup provider or manifest entry.
The registry task therefore emits a small variant manifest directory alongside
generated Java/assets, and the plugin wires it through the public AGP Variant
API. The base AAR manifest stays provider-free. Custom application IDs produce
distinct authorities. A duplicate authority or incompatible final provider
contract fails the build with the variant and authority in the diagnostic.

`FoundryJavaStartupProvider.onCreate()` is process-idempotent. Reentrant calls
with the same bootstrap return the existing primed state. A second different
bootstrap, second active initialization, or restart-like stale state fails
deterministically. A genuine process restart begins from an empty static state.

## Lifecycle Phases

Startup is split into two non-interchangeable phases:

1. **Provider/pre-entry phase.** Capture the application class loader, validate
   the complete typed bootstrap, and load `libfoundry_java.so`. Do not create a
   native context, construct `FoundryNativeEngine`, or register descriptors.
2. **Extension/engine phase.** `foundry_java_library_init` resolves and stores
   the complete required public FoundryExtension interface table and library
   pointer. Only the native CORE initialization callback may then create the
   context and Java production engine.

The provider installs a process `FoundryRegistryCoordinator` as the JNI
callback target. Native CORE calls the coordinator with a nonzero context
handle after the interface table is complete. The coordinator constructs
`FoundryNativeEngine`, constructs and registers one `FoundryBindingContext`
with `FoundryRuntimeCallbacks`, registers generated engine wrapper factories,
and only then registers CORE descriptors.

Pre-entry failures use a stable provider/pre-entry failure phase. Entry,
interface resolution, context creation, graph validation, class registration,
callback, rollback, and teardown failures use distinct stable phases and include
API/generator/runtime/bridge provenance plus module/class identity where known.

## Deterministic Registration Graph

Before any native registration, the coordinator validates the whole bootstrap:

- module and registry identities remain unique;
- Java and Foundry class identities are unique across every module;
- every `after` dependency is an exact qualified Java class identity present in
  the same bootstrap;
- a dependency never points to a later initialization level;
- a deterministic topological sort succeeds.

The topological sort uses exact qualified dependency identities. Ready nodes are
ordered by initialization level, qualified Java name, Foundry name, module, and
registry. Missing dependencies and cycles fail before the first class is
registered. Module-local processor order is never treated as global order.

At each native initialization level, the coordinator registers exactly the
sorted classes assigned to that level. A repeated initialization callback is
idempotent only after the same level completed successfully; out-of-order or
concurrent level transitions fail closed.

## Production Engine and Native Boundary

`FoundryNativeEngine` is the Android production implementation of the public
`FoundryEngine` interface. The public runtime interface gains explicit class
registration methods so host-neutral coordinator tests can use a fake engine
without Android:

- `registerExtensionClass(contextHandle, descriptor)`
- `unregisterExtensionClass(contextHandle, foundryName)`

The existing call, Variant, object, instantiate, singleton, retain/release, and
callback-exception methods keep their signatures. The exact API baseline is
updated deliberately. `NoOpEngine` and test engines implement the new methods.

The native bridge expands its resolved interface table for:

- memory and native-structure sizing;
- Variant construction, copy, destruction, conversion, and calls;
- String/StringName construction and destruction;
- object validity, identity, class lookup, construction, method calls, and
  singleton lookup;
- reference ownership operations used by the generated API;
- class v5, method, property, signal, and integer-constant registration plus
  class unregistration. Script virtuals use v5 creation
  `get_virtual_call_data`/`call_virtual_with_data` callbacks; no separate
  virtual-registration interface is resolved.

Java sees only opaque, nonzero, context-bound handles. It never sees a process
address. Native handle tables record generation, kind, ownership, and liveness.
Cross-context, stale, wrong-kind, and post-teardown handles fail before an
interface call. Variant conversion supports every Java `Variant` category
already exposed by the runtime; unknown or structurally incompatible values
fail with the Variant type and phase instead of coercing.

Generated providers/classes are never loaded reflectively. JNI reads values
only from already validated typed descriptor objects passed directly by Java.
JNI exceptions and Java callback exceptions are contained, reported through
Foundry's error interface, and converted to deterministic Java failures/default
returns.

## Registration Invocation and Java Instances

Native registration stores one class record per descriptor while its
initialization level is live. The record owns the direct generated
`FoundryExtensionAccess` global reference and the validated class/member
metadata required by FoundryExtension registration callbacks.

Class creation constructs the native parent object through the public interface,
creates the Java object through `FoundryExtensionAccess.construct`, and installs
the opaque instance binding. Method/property/virtual callbacks look up the
stored direct access object and convert arguments/results through the production
Variant transport. No callback performs name-based Java method discovery.

## Failure, Rollback, and Teardown

Registration is transactional by initialization callback:

1. Record each class only after native registration succeeds.
2. If any class/member registration fails, stop immediately.
3. Unregister only the classes successfully registered by that callback, in
   exact reverse order.
4. Transition the context to terminal exactly once.
5. Keep the native interface table and Java callback/global references live
   until Java rollback and context invalidation finish.
6. Then clear handles, class records, global references, library pointer, and
   interface table in that order.

Normal deinitialization unregisters the completed level in reverse topological
order. CORE teardown first blocks new callbacks, drains active callback leases,
rolls back remaining registered levels in reverse, invalidates/closes the Java
context once, releases JNI references and handle tables, and finally clears the
FoundryExtension interface table.

State transitions are guarded by terminal/idempotent gates. Reentrant provider
calls, repeated callbacks, concurrent invalidation, partial registration
failure, and shutdown races cannot register twice, unregister an unregistered
class, invoke user code after terminal state, or clear the interface table
before rollback completes.

## Packaging and Compatibility

The AAR API/classes baseline adds the stable startup provider, coordinator, and
production engine classes. The runtime public API baseline adds the two explicit
`FoundryEngine` registration methods, the stable `FoundryNativeDispatch`
record/`Kind` enum, and the narrow bridge factories required by native-backed
Callable/Signal values; the existing `FoundryEngine.call` signature remains
unchanged. Consumer rules keep the stable JNI entry class, provider base,
generated provider/bootstrap, typed providers, callback interface, and direct
generated access points narrowly.

Repository/AAR/publication contracts require:

- no `libfoundry_android.so`;
- exactly four `libfoundry_java.so` ABIs;
- one fixed FoundryExtension configuration;
- opt-in startup manifest/provider output only for module-bearing application
  variants;
- a provider-free packaged base manifest;
- strict core-library desugaring 2.1.5 for minSdk-23 application consumers and
  Android-library lint/test builds;
- no reflection/discovery APIs or broad keep rules;
- no Android host-runtime classes.

## Tests

### JVM/runtime

- whole-bootstrap missing dependency, duplicate identity, cycle, and
  cross-level dependency rejection before native mutation;
- deterministic cross-module topological order;
- exact-once per-level registration and reverse deinitialization;
- partial-failure rollback of only completed registrations;
- concurrent initialize/deinitialize/invalidate and reentrant callback races;
- context registration before CORE and terminal context close exactly once.

### Gradle/TestKit and Android packaging

- direct generated provider source and typed bootstrap reference;
- zero-module/non-opted variants emit no provider/manifest entry;
- default/custom application ID authorities;
- manifest merger output, provider flags, no custom process, deterministic
  `initOrder`, and authority collision diagnostics;
- debug/minified release, shrinker retention, configuration-cache reuse, and
  byte-for-byte reproducibility.
- actual release-AAR/runtime-JAR minSdk-23 DEX rewriting through `j$/util` and
  direct provider/bootstrap/registry retention.

### Native

- complete required interface resolution and fail-closed missing-interface
  diagnostics;
- primitive, value, object, and opaque handle Variant round trips;
- object call/construct/singleton/retain/release/report transport;
- class/member registration invocation and exact reverse unregistration;
- stale/cross-context handle rejection;
- partial registration rollback while the interface table remains usable;
- reentrant and concurrent shutdown under host tests plus ASan/UBSan.

### Instrumentation

- provider initializes before `Application`/Activity without a direct test-only
  initializer call;
- provider phase only primes the bridge;
- native CORE constructs the production engine/context and registers providers;
- callback dispatch, invalidation, reverse teardown, and simulated process
  restart complete without leaks;
- startup failure diagnostics identify provider/pre-entry provenance.

Full `clean check`, configuration-cache, AAR/publication/repository contracts,
four-ABI/native sanitizer gates, independent review, and Cursor
`RESULT: clean` are mandatory before publication.
