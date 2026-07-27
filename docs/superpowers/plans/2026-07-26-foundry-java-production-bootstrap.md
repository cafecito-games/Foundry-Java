# Foundry-Java Production Bootstrap and Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prime Foundry-Java before Android application startup, then create the
live Java binding context and register generated descriptors through the public
FoundryExtension interface at native initialization time.

**Architecture:** An opt-in, plugin-generated application `ContentProvider`
directly supplies the typed registry bootstrap to an AAR-owned startup base.
The provider primes the classloader/native library only. After
`foundry_java_library_init` resolves the complete interface table, native CORE
creates a context and calls a host-neutral coordinator backed by the Android
`FoundryNativeEngine`; the coordinator topologically registers descriptors and
rolls them back in reverse before native tables are cleared.

**Tech Stack:** Java 17, Android `ContentProvider`, AGP 8.10 public Variant API,
Gradle TestKit, JNI, C++17, FoundryExtension C ABI, JUnit 5, Android
instrumentation, CMake/CTest, ASan/UBSan.

---

## File Map

- `foundry-java-runtime/.../FoundryEngine.java`: add explicit generated-class
  register/unregister operations.
- `foundry-java-runtime/.../FoundryRegistrationPlan.java`: validate and
  topologically order the whole typed bootstrap.
- `foundry-java-runtime/.../FoundryRegistryCoordinator.java`: own live contexts,
  level transitions, transactional registration, rollback, and callback
  delegation.
- `foundry-java-runtime/.../FoundryRuntimeCallbacks.java`: expose deterministic
  registration/terminal readback needed by the coordinator without Android.
- `foundry-java-runtime/src/test/.../FoundryRegistryCoordinatorTest.java`: graph,
  ordering, failure, reentrancy, and race tests.
- `foundry-java-gradle-plugin/.../RegistryIndexTask.java`: generate direct
  startup provider and opt-in manifest.
- `foundry-java-gradle-plugin/.../FoundryAndroidApplicationIntegration.java`:
  wire generated manifest and exact per-variant placeholders.
- `foundry-java-gradle-plugin/src/test/.../FoundryJavaPluginTest.java`: TestKit
  source, manifest, authority, collision, shrinker, and cache tests.
- `foundry-java-android/.../FoundryJavaStartupProvider.java`: stable Android
  provider base and direct typed hook.
- `foundry-java-android/.../FoundryJavaInitializer.java`: split priming from
  engine callbacks and install the process coordinator.
- `foundry-java-android/.../FoundryNativeEngine.java`: production
  `FoundryEngine` JNI facade.
- `foundry-java-android/src/main/AndroidManifest.xml`: provider-free base AAR
  manifest contract; application placeholders live only in the plugin-generated
  variant manifest.
- `foundry-java-android/src/main/consumer-rules.pro`: narrow startup/native
  keep contract.
- `foundry-java-android/src/main/cpp/foundry_java_runtime.h`: native transport,
  handle, and registration contracts.
- `foundry-java-android/src/main/cpp/foundry_java_entry.cpp`: complete public
  interface resolution and ordered entry/teardown lifecycle.
- `foundry-java-android/src/main/cpp/foundry_java_jni.cpp`: Java/native engine,
  Variant/object transport, and direct descriptor registration JNI.
- `foundry-java-android/src/main/cpp/foundry_java_handles.cpp`: generation-bound
  handle/class records and race-safe rollback.
- `foundry-java-android/src/test/cpp/foundry_java_runtime_test.cpp`: fake
  interface, transport, registration, rollback, and race coverage.
- `foundry-java-android/src/test/java/...`: provider/initializer/native-engine
  contract tests.
- `foundry-java-android/src/androidTest/...`: provider-before-Application and
  production context lifecycle.
- `foundry-java-runtime/api/foundry-java-runtime.api`: exact public runtime ABI.
- root repository/AAR contract tests and `docs/android-integration.md`,
  `docs/architecture.md`, `docs/memory-and-threading.md`: packaged/API/docs
  contracts.

### Task 1: Freeze whole-bootstrap planning and coordinator semantics

**Files:**
- Create:
  `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/FoundryRegistryCoordinatorTest.java`
- Create:
  `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryRegistrationPlan.java`
- Create:
  `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryRegistryCoordinator.java`
- Modify:
  `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryEngine.java`
- Modify:
  `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/NoOpEngine.java`
- Modify: `foundry-java-runtime/api/foundry-java-runtime.api`

- [ ] **Step 1: Write graph/order and lifecycle tests**

Use direct typed providers to assert:

```java
FoundryRegistrationPlan plan = FoundryRegistrationPlan.create(bootstrap);
assertEquals(List.of("example.Base", "example.Leaf"),
        plan.orderedClasses().stream().map(c -> c.javaName()).toList());
```

Add separate tests for duplicate Java/Foundry names, exact-qualified missing
dependency, cycle, later-level dependency, context-before-CORE, exact-once
level registration, reverse unregister, partial failure rollback, and terminal
close once under concurrent invalidate/deinitialize.

- [ ] **Step 2: Run RED**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-runtime:test \
  --tests '*FoundryRegistryCoordinatorTest'
```

Expected: Java compilation fails because `FoundryRegistrationPlan`,
`FoundryRegistryCoordinator`, and the two `FoundryEngine` registration methods
do not exist.

- [ ] **Step 3: Implement the immutable plan**

Validate all descriptor identities before mutation, map exact qualified
dependencies, and run Kahn topological sort with this comparator:

```java
Comparator.comparing((ClassEntry e) -> e.level().code())
        .thenComparing(e -> e.descriptor().javaName())
        .thenComparing(e -> e.descriptor().foundryName())
        .thenComparing(ClassEntry::module)
        .thenComparing(ClassEntry::registry);
```

Reject duplicates, missing edges, cycles, and edges to a later level with
stable identity-rich messages.

- [ ] **Step 4: Implement the coordinator**

Add to `FoundryEngine`:

```java
void registerExtensionClass(long contextHandle, FoundryClassDescriptor descriptor);
void unregisterExtensionClass(long contextHandle, String foundryName);
```

The coordinator receives a bootstrap and
`LongFunction<? extends FoundryEngine>`, owns `FoundryRuntimeCallbacks`, creates
the context only at CORE, registers each sorted level transactionally, rolls
back only completed classes in reverse, and transitions terminal once. Invoke
engine/user operations outside the state lock while an atomic transition token
prevents competing transitions.

- [ ] **Step 5: Run GREEN and API verification**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-runtime:test \
  --tests '*FoundryRegistryCoordinatorTest' \
  --tests '*FoundryRuntimeTest' \
  :foundry-java-runtime:verifyRuntimeApi
```

Expected: all selected tests and exact API verification pass.

- [ ] **Step 6: Commit**

```bash
git add foundry-java-runtime
git commit -m "Coordinate generated registry lifecycles"
```

### Task 2: Generate the direct startup provider and opt-in manifest

**Files:**
- Modify:
  `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/RegistryIndexTask.java`
- Modify:
  `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/FoundryAndroidApplicationIntegration.java`
- Modify:
  `foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java`

- [ ] **Step 1: Write TestKit RED cases**

Require module-bearing variants to generate:

```java
public final class FoundryGeneratedStartupProvider
        extends games.cafecito.foundry.java.FoundryJavaStartupProvider {
    @Override
    protected games.cafecito.foundry.runtime.FoundryRegistryBootstrap bootstrap() {
        return FoundryGeneratedBootstrap.bootstrap();
    }
}
```

Assert the generated manifest uses provider/authority placeholders,
`exported=false`, fixed `initOrder`, and no `process`. Assert zero-module
variants emit neither provider nor manifest. Build default/custom IDs and an
authority collision fixture, a minified release, and configuration cache twice.

- [ ] **Step 2: Run RED**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-gradle-plugin:test \
  --tests '*FoundryJavaPluginTest*startup*'
```

Expected: assertions fail because only `FoundryGeneratedBootstrap` and registry
assets are generated.

- [ ] **Step 3: Generate opt-in Java and manifest**

Make `RegistryIndexTask` write startup Java/manifest only after validated
modules are nonempty. Expose an annotated `RegularFileProperty` for the
manifest output. Generate deterministic UTF-8 bytes without paths/timestamps.

- [ ] **Step 4: Wire public Variant APIs**

For module-bearing variants:

```java
variant.getManifestPlaceholders().put(
        "foundryJavaStartupProvider",
        "games.cafecito.foundry.generated.FoundryGeneratedStartupProvider");
variant.getManifestPlaceholders().put(
        "foundryJavaStartupAuthority",
        variant.getApplicationId().map(id -> id + ".foundry-java-startup"));
```

Wire the generated manifest with
`variant.getSources().getManifests().addGeneratedManifestFile(...)` using the
supported AGP generated-manifest API. Detect incompatible existing placeholder
values before publication.

- [ ] **Step 5: Run GREEN**

Run the full plugin tests, plugin validation, the two configuration-cache
builds, and custom/minified fixtures. Expected: direct source and merged
manifest assertions pass and the second cache run reports reuse.

- [ ] **Step 6: Commit**

```bash
git add foundry-java-gradle-plugin
git commit -m "Generate the pre-Activity startup provider"
```

### Task 3: Split provider priming from engine initialization

**2026-07-26 API-23 amendment:** The Java-17 runtime intentionally depends on
desugarable `java.util.function`, stream, and recent collection APIs. Do not
replace one functional interface while leaving the rest of that graph
unsupported. Preserve the public `LongFunction<? extends FoundryEngine>`
coordinator constructor. The application plugin must enable core-library
desugaring and add a strict `com.android.tools:desugar_jdk_libs:2.1.5`
dependency; the Android library must use the same dependency for lint/tests.
The base AAR manifest must remain provider-free.

**Files:**
- Create:
  `foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryJavaStartupProvider.java`
- Create:
  `foundry-java-android/src/test/java/games/cafecito/foundry/java/FoundryJavaStartupProviderTest.java`
- Modify:
  `foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryJavaInitializer.java`
- Modify: `foundry-java-android/src/main/AndroidManifest.xml`
- Modify: `foundry-java-android/src/main/consumer-rules.pro`
- Modify:
  `foundry-java-android/src/test/java/games/cafecito/foundry/java/FoundryJavaInitializerTest.java`
- Modify: `foundry-java-android/build.gradle.kts`
- Modify: `foundry-java-gradle-plugin/build.gradle.kts`
- Modify:
  `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/FoundryAndroidApplicationIntegration.java`
- Modify:
  `foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify:
  `src/test/java/games/cafecito/foundry/build/RepositoryContractTest.java`

- [ ] **Step 1: Write provider/phase RED tests**

Test a provider hook with an injected bridge seam so `onCreate()` proves:

- bootstrap/classloader/native load are primed;
- no context or engine is constructed;
- same-bootstrap reentry is idempotent;
- different bootstrap/restart-like stale state fails with
  `failure_phase=provider_pre_entry`;
- the manifest has the exact placeholders/flags.

Add repository and real-consumer RED cases requiring:

- exact provider-free packaged base manifest;
- strict desugar 2.1.5 resolution even when the consumer requests 2.0.3;
- a minSdk-23 minified application consuming the actual release AAR and
  runtime JAR;
- packaged `j$/util` DEX rewriting and exact stable/generated
  provider/bootstrap/registry retention;
- configuration-cache reuse.

- [ ] **Step 2: Run RED**

Run the two focused Android JVM test classes. Expected: compilation fails on
the absent stable provider and priming API.

- [ ] **Step 3: Implement priming state**

Replace direct production initialization with:

```java
static void prime(ClassLoader loader, FoundryRegistryBootstrap bootstrap)
```

This validates and stores the immutable bootstrap, installs the coordinator as
callbacks, and loads JNI. Keep the existing explicit `initialize` overloads as
instrumentation/test compatibility entry points but make production provider
use only `prime`.

- [ ] **Step 4: Implement the stable provider**

`FoundryJavaStartupProvider` extends `ContentProvider`, obtains the application
classloader, calls the abstract typed `bootstrap()` hook, primes once, and
implements unused CRUD methods as deterministic unsupported/no-op operations.
The manifest contains the exact placeholders and flags from the spec.

Enable core-library desugaring through the public Android application DSL and
add one strict 2.1.5 dependency from the Foundry plugin. Configure the Android
library with the same version-catalog pin. Revert any interim
`FoundryEngineFactory` API change to the approved `LongFunction` constructor.
Extend AAR verification to reject an application/provider in the packaged base
manifest.

- [ ] **Step 5: Run GREEN, lint, and AAR tests**

Run focused unit tests, `lintDebug`, `bundleReleaseAar`, and
`verifyAndroidAar`, then the actual-AAR minSdk-23 minified TestKit fixture
twice. Expected: provider/manifest/AAR assertions pass, dependency insight
selects strict 2.1.5, DEX contains desugared `j$/util` references, direct
startup symbols survive R8, configuration cache is reused, and lint reports no
minSdk finding.

- [ ] **Step 6: Commit**

```bash
git add foundry-java-android
git commit -m "Prime Foundry Java before application startup"
```

### Task 4: Implement the production NativeEngine and opaque transport

**Files:**
- Create:
  `foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryNativeEngine.java`
- Create:
  `foundry-java-android/src/test/java/games/cafecito/foundry/java/FoundryNativeEngineTest.java`
- Create:
  `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryNativeDispatch.java`
- Create:
  `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/FoundryNativeDispatchTest.java`
- Create: `foundry-java-android/src/main/cpp/foundry_java_interface.h`
- Create: `foundry-java-android/src/main/cpp/foundry_java_interface.cpp`
- Create: `foundry-java-android/src/main/cpp/foundry_java_transport.h`
- Create: `foundry-java-android/src/main/cpp/foundry_java_transport.cpp`
- Modify:
  `foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryJavaInitializer.java`
- Modify:
  `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryCallable.java`
- Modify:
  `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundrySignal.java`
- Modify:
  `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryNativeHandle.java`
- Modify:
  `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/CallAndSignalTest.java`
- Modify:
  `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/CollectionSemanticsTest.java`
- Modify:
  `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/FoundryNativeHandleTest.java`
- Modify:
  `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/VariantTest.java`
- Modify: `foundry-java-runtime/api/foundry-java-runtime.api`
- Modify:
  `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/FoundrySourceGenerator.java`
- Modify:
  `foundry-java-generator/src/test/java/games/cafecito/foundry/generator/FoundrySourceGeneratorTest.java`
- Modify: `build.gradle.kts`
- Modify: `foundry-java-android/build.gradle.kts`
- Modify: `foundry-java-android/src/main/consumer-rules.pro`
- Modify: `foundry-java-android/src/main/cpp/CMakeLists.txt`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_contract.h.in`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_exports.map`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_runtime.h`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_entry.cpp`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_jni.cpp`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_handles.cpp`
- Modify: `foundry-java-android/src/test/cpp/foundry_java_runtime_test.cpp`
- Modify: `gradle/verify-native-bridge.sh`
- Modify:
  `src/test/java/games/cafecito/foundry/build/NativeBridgeContractTest.java`
- Modify: `src/test/java/games/cafecito/foundry/build/RepositoryContractTest.java`

- [ ] **Step 1: Freeze generated dispatch and ABI-layout RED tests**

Require the generator to emit deterministic
`games.cafecito.foundry.generated.GeneratedNativeDispatch` metadata keyed by
the existing structural call identity. Freeze and test the literal stable
runtime `FoundryNativeDispatch` record and `Kind` enum from the design; the
generated table instantiates that record and never crosses JNI as its own
consumer-generated type. For every callable/property identity, assert kind,
owner, native name, compatibility hash or constructor index, getter/setter
structural identities/native names/hashes, exact argument/return native type
tokens, arity (`argumentNativeTypes.size()`, minimum fixed arity for varargs),
vararg, and static flags. Include `BUILTIN_CONSTANT` rows for every
non-integer `FoundryConstant.get()` identity. Require both `float_32` and
`float_64` built-in size tables and exact
32-/64-bit `String`, `StringName`, `Object`, and 24-byte `Variant` layouts. Add
API-baseline and repository-inventory expectations for the stable/generated
classes and new native source/header files.

- [ ] **Step 2: Write pure-C++ transport RED tests before JNI**

Extend the fake public interface to require all-or-nothing exact-name
resolution and immutable `BridgeServices`. Add table-driven host tests for
max-aligned constructed storage, copy/destruction on every path, all 39 Variant
categories, every generated dispatch route, native-structure type tokens,
unsigned instance-ID object handles, RefCounted ownership, Java-local/native
Callable behavior, native/local Signal policy, stable call errors, and
stale/cross-context/wrong-kind/post-teardown rejection. Add transport leases so
teardown blocks new admissions, drains active calls before clearing services,
and concurrent release/teardown destroys each record exactly once. RED must be
in the host-native target; do not add JNI implementation to make the first
tests pass.

- [ ] **Step 3: Run the generator and native RED gates**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon \
  :foundry-java-generator:test \
  --tests games.cafecito.foundry.generator.FoundrySourceGeneratorTest

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon \
  :foundry-java-runtime:test \
  --tests games.cafecito.foundry.runtime.FoundryNativeDispatchTest \
  --tests games.cafecito.foundry.runtime.CallAndSignalTest \
  --tests games.cafecito.foundry.runtime.CollectionSemanticsTest \
  --tests games.cafecito.foundry.runtime.FoundryNativeHandleTest \
  --tests games.cafecito.foundry.runtime.VariantTest \
  :foundry-java-runtime:verifyRuntimeApi

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-android:nativeHostTest
```

Expected: missing `GeneratedNativeDispatch`, missing generated layout data, and
missing interface/transport implementations. Record the exact first missing
interface name and representative unsupported Variant/handle failures.

- [ ] **Step 4: Generate dispatch metadata and checked layouts**

Teach `FoundrySourceGenerator` to preserve the structural identity while
emitting an immutable table of stable runtime `FoundryNativeDispatch` records.
`FoundryNativeEngine.call` must look up this record before JNI while keeping the public
`call(long, long, String, List<Variant>)` signature unchanged. Unknown
identity/kind/arity/type-token failures never enter JNI.

Generate both precision layouts from `api/current/extension_api.json`. On
Android single precision assert:

```text
32-bit: String=4 StringName=4 Object=4 Variant=24
64-bit: String=8 StringName=8 Object=8 Variant=24
```

Use max-aligned storage with explicit constructed bits. Destroy constructed
`String`/`StringName` through the destructor returned by
`variant_get_ptr_destructor`; there is no `string_name_destroy`.

- [ ] **Step 5: Resolve and publish immutable BridgeServices**

Move resolution to `foundry_java_interface.{h,cpp}`. Resolve exactly the
amended design table from `api/current/foundry_extension_interface.h`, using
the header's legacy
`FoundryExtensionInterfaceGetVariantGetInternalPtrFunc` typedef. Use current
`mem_alloc2`/`mem_realloc2`/`mem_free2`,
`string_new_with_utf8_chars_and_len2`, `classdb_construct_object2`, and class
registration v5. Do not resolve invented convenience entry points. If any
pointer or required method bind is absent, report its exact name and return
false without publishing `entry_active`, the library, or a partial table.

- [ ] **Step 6: Add typed generation-bound handle stores**

Represent bridge handles as monotonically allocated IDs mapped to records:

```cpp
struct HandleRecord {
    ContextHandle context;
    std::uint64_t generation;
    HandleKind kind;
    NativeTypeToken expected_type;
    NativeValue value;
    bool owned;
    bool live;
};
```

Validate context/generation/kind/type/liveness on every access. Native structure
storage sizing constructs a `StringName` from the expected generated type
token's exact native type name, passes that constructed value to
`get_native_struct_size`, and destroys the `StringName` on every path. Object
handles store `uint64_t` instance IDs and reacquire pointers with
`object_get_instance_from_id`; they never expose addresses.

Ref ownership uses no fabricated `Ref<T>` storage. Validate the `RefCounted`
class tag, invoke exact `reference`/`unreference` MethodBinds at hash
`2240911060`, and call `object_destroy` exactly once when `unreference` returns
true. Clear owned Variant/native storage and references before erasing records.

- [ ] **Step 7: Implement the pure-C++ transport and generic dispatch**

Implement `foundry_java_transport.{h,cpp}` against immutable
`BridgeServices`. Cover all 39 runtime `VariantType` categories. ClassDB
methods use MethodBinds (null receiver for static methods); pointer/native
signatures use `object_method_bind_ptrcall`, while Variant signatures use
`object_method_bind_call`. Built-in methods/constructors use their pointer
lookups, operators use `variant_evaluate`, utilities use
`variant_get_ptr_utility_function`, and properties use named get/set. Use
`variant_get_constant_value` for generated built-in constants. Use
`variant_call` for generic Callable invocation, keyed get/set plus iteration for
Dictionary transport, indexed get/set plus iteration for Array and packed-array
transport, and `variant_construct` for generic typed construction. These exact
families are mandatory interface entries; no unlisted
`variant_call_named|builtin|operator|utility` aliases are permitted.

Refactor `FoundryCallable` and `FoundrySignal` to explicit local/native
backends. Encode Java-local Callables with `callable_custom_create2`; preserve
native-backed Callables and Signals across decode/re-encode. Reject an arbitrary
Java-local Signal deterministically because the public ABI has no custom Signal
constructor. Do not coerce it.

Implement object calls, validity/type lookup, singleton lookup, construction,
retain/release, and callback-error reporting through the same context state.
After `classdb_construct_object2`, invoke `Object.notification`
`POSTINITIALIZE` using compatibility hash `4023243586`.

For native structures, convert the generated expected type token to its exact
native type name, construct a `StringName`, pass that constructed value to
`get_native_struct_size`, and destroy the `StringName` through the resolved
pointer destructor on every path.

- [ ] **Step 8: Wire FoundryNativeEngine and JNI**

Add the one non-reflective production `FoundryNativeEngine` implementing every
`FoundryEngine` method. Pass validated generated dispatch records and typed
values to private JNI entry points; never discover generated classes or members
by name in JNI. Convert Java exceptions/native call errors to stable
`FoundryEngine.CallResult` or deterministic typed failures only after native
cleanup. Update initializer construction without creating the engine before
native CORE. Add every versioned `FoundryNativeEngine` JNI symbol to the export
map, freeze those names in `verify-native-bridge.sh` and
`NativeBridgeContractTest`, and retain only the exact native/callback entry
points in consumer rules. Freeze the exact design descriptors for
`nativeCallV1`, decode/encode, object validity/type, instantiate,
retain/release, singleton, callback reporting, and the Task 5
register/unregister seam. JNI accepts the stable runtime
`FoundryNativeDispatch`, never `Object` or the consumer-generated table class.

- [ ] **Step 9: Run GREEN plus sanitizers**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon \
  :foundry-java-generator:test \
  --tests games.cafecito.foundry.generator.FoundrySourceGeneratorTest

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon \
  :foundry-java-runtime:test \
  --tests games.cafecito.foundry.runtime.FoundryNativeDispatchTest \
  --tests games.cafecito.foundry.runtime.CallAndSignalTest \
  --tests games.cafecito.foundry.runtime.CollectionSemanticsTest \
  --tests games.cafecito.foundry.runtime.FoundryNativeHandleTest \
  --tests games.cafecito.foundry.runtime.VariantTest \
  :foundry-java-runtime:verifyRuntimeApi

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon \
  :foundry-java-android:nativeHostTest \
  :foundry-java-android:nativeSanitizerTest

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon \
  :foundry-java-android:testDebugUnitTest \
  --tests games.cafecito.foundry.java.FoundryNativeEngineTest \
  :foundry-java-android:lintDebug \
  :foundry-java-android:bundleReleaseAar \
  :verifyAndroidAar

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon \
  :test \
  --tests games.cafecito.foundry.build.RepositoryContractTest \
  --tests games.cafecito.foundry.build.NativeBridgeContractTest \
  verifyRepositoryContract

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon \
  clean check verifyRepositoryContract verifyAndroidAar
```

Expected: exact resolution and fake-interface counters/order match; every
Variant category and dispatch family passes; all ownership/handle/cleanup cases
are green; and CTest reports zero failures under normal, ASan, and UBSan builds.
Android unit/lint/AAR, repository contracts, API baselines, and the full Gradle
check must all pass.

- [ ] **Step 10: Commit**

```bash
git add build.gradle.kts docs foundry-java-android foundry-java-generator \
  foundry-java-runtime gradle src/test
git commit -m "Implement the production Foundry engine transport"
```

### Task 5: Invoke generated class/member registration transactionally

**Files:**
- Modify:
  `foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryNativeEngine.java`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_runtime.h`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_entry.cpp`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_jni.cpp`
- Modify: `foundry-java-android/src/main/cpp/foundry_java_handles.cpp`
- Modify: `foundry-java-android/src/test/cpp/foundry_java_runtime_test.cpp`
- Modify:
  `foundry-java-android/src/test/java/games/cafecito/foundry/java/FoundryNativeEngineTest.java`

- [ ] **Step 1: Write RED registration tests**

Pass typed descriptors directly to `registerExtensionClass`. Fake interface
functions must observe class then sorted method/property/signal/constant
registrations, exact access token ownership, v5 creation info with
`get_virtual_call_data`, `call_virtual_with_data`, and `to_string_func`, and
reverse class unregistration. Assert
`classdb_register_extension_class_virtual_method` is neither resolved nor
called. Inject failure after the second class and assert only the two completed
classes are unregistered while `print_error` and unregister pointers remain
live.

- [ ] **Step 2: Run RED**

Run the focused Java/native tests. Expected: registration methods are currently
unimplemented and fake registration counters remain zero.

- [ ] **Step 3: Implement class records and callbacks**

Create native class records from validated typed descriptor fields. Keep a JNI
global reference to `FoundryExtensionAccess`; do not resolve provider or
generated class names. Register class v5 creation info, members, properties,
signals, and integer constants through the stored interface table. Populate
`get_virtual_call_data`, `call_virtual_with_data`, and `to_string_func` on the
v5 creation record; do not look up or call
`classdb_register_extension_class_virtual_method`.

- [ ] **Step 4: Implement direct instance callbacks**

Construct parent objects with the public interface, call direct access
`construct`, store instance global references, and route method/property/
virtual callbacks through the direct access object plus Variant transport.
Virtual lookup and invocation use only the v5 call-data callbacks; string
conversion uses `to_string_func`. Contain Java exceptions and return documented
Foundry call errors/defaults.

- [ ] **Step 5: Implement reverse rollback**

Unregister in exact reverse plan order before releasing access/instance global
refs. Keep library/interface state published until coordinator rollback and
context invalidation return.

- [ ] **Step 6: Run GREEN, native race tests, and commit**

Run focused Java/native tests, sanitizers, and runtime coordinator tests.
Commit:

```bash
git add foundry-java-android foundry-java-runtime
git commit -m "Register generated classes through FoundryExtension"
```

### Task 6: Prove production startup and packaged contracts

**Files:**
- Modify:
  `foundry-java-android/src/androidTest/java/games/cafecito/foundry/java/FoundryJavaInstrumentation.java`
- Modify: `src/test/java/games/cafecito/foundry/build/RepositoryContractTest.java`
- Modify: `src/test/java/games/cafecito/foundry/build/NativeBridgeContractTest.java`
- Modify: `build.gradle.kts`
- Modify: `docs/android-integration.md`
- Modify: `docs/architecture.md`
- Modify: `docs/memory-and-threading.md`

- [ ] **Step 1: Write instrumentation/contract RED**

Remove the production-path direct `initialize` call from instrumentation.
Require provider priming evidence before application/activity, then drive
native entry/CORE to assert production engine/context creation, one provider
registration, callback dispatch, invalidation, reverse teardown, and simulated
restart. Extend archive/API tests for new exact classes, manifest, native
symbols, narrow rules, and no host library/reflection.

- [ ] **Step 2: Run RED**

Run focused repository/AAR tests and assemble instrumentation. Expected:
missing production startup evidence and old exact class/native inventories.

- [ ] **Step 3: Complete instrumentation seams and docs**

Use only package-private/native test seams; no production reflection or direct
initializer shortcut. Document provider versus extension phases, authority
format, topological order, failure phases, ownership, and teardown.

- [ ] **Step 4: Run GREEN**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon \
  test verifyRepositoryContract verifyAndroidAar \
  :foundry-java-android:assembleDebugAndroidTest \
  :foundry-java-android:lintDebug \
  :foundry-java-android:nativeHostTest \
  :foundry-java-android:nativeSanitizerTest
```

Expected: all selected tasks succeed and exact inventories exclude
`libfoundry_android.so`.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src foundry-java-android docs
git commit -m "Verify production Android binding startup"
```

### Task 7: Full gates, review convergence, PR, and merge

**Files:**
- All committed files from Tasks 1-6.
- Status ledger:
  `/Users/christian/CafecitoGames/Foundry/.epic-1241-status.md`

- [ ] **Step 1: Run full verification**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon clean check
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon check
git diff --check origin/main...HEAD
git status --short
```

Require configuration-cache storage/reuse, all module/API/AAR/publication
contracts, four ABI builds, CTest, and ASan/UBSan. Verify Foundry-Android exact
HEAD/status remain unchanged and no artifact contains
`libfoundry_android.so`.

- [ ] **Step 2: Independent exact-head review**

Provide the reviewer the approved spec, this plan, issue #1251, exact
`origin/main` SHA, and exact branch HEAD. Fix every validated critical/important
finding with focused RED/GREEN tests and repeat until `Ready: Yes`.

- [ ] **Step 3: Cursor exact-head convergence**

Run `cursor-review` in read-only plan mode against exact `origin/main`.
Technically validate every finding with the receiving-review and debugging
workflows. Commit verified fixes and rerun until the latest valid output is
exactly `RESULT: clean`.

- [ ] **Step 4: Publish the Java-half PR**

Push `issue-1251-java` and open a non-draft PR against Foundry-Java `main`.
Reference native Foundry #1251 without `Closes` because the separate Foundry
integration/device PR remains. Include tests, reviewed SHAs, design, and
Foundry-Android guard evidence.

- [ ] **Step 5: Converge GitHub and merge**

Wait for every check and review thread to converge on the reviewed head. Enable
squash auto-merge only then. Confirm the PR is actually merged.

- [ ] **Step 6: Cleanup and handoff**

Remove the Foundry-Java worktree/local/remote issue branch after merge. Leave
native #1251 open/In Progress. Update the ledger with merged SHA, reviews, CI,
cleanup, and the exact dependency handoff for the Foundry half.
