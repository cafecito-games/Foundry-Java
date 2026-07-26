# Java Runtime and Generated API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the host-neutral Java runtime and generate a callable, exhaustive public Java
API for all 57,904 accepted Foundry API entities.

**Architecture:** `foundry-java-runtime` owns immutable value semantics, typed mutable containers,
opaque context-bound object wrappers, call/callback/error handling, and the WS7 Java callback
contract. `foundry-java-generator` continues emitting exhaustive deterministic metadata and also
emits public engine wrappers that delegate through the runtime; the runtime build generates these
sources from checked accepted inputs and packages their provenance hashes.

**Tech Stack:** Java 17, Gradle 8.11.1 Kotlin DSL, JUnit 5, `Cleaner`, concurrent collections,
the existing immutable API model and compatibility manifest, `JavaCompiler`, `javadoc`, and
`javap`.

---

### Task 1: Freeze the runtime and WS7 callback interfaces

**Files:**
- Create: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/RuntimeInterfaceTest.java`
- Create: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/RuntimeContractTest.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryBridgeCallbacks.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryEngine.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryRuntime.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryBindingContext.java`
- Create: `foundry-java-runtime/api/foundry-java-runtime.api`

- [x] **Step 1: Write compile-time and reflection tests for the exact interfaces**

Freeze `FoundryBridgeCallbacks` as:

```java
public interface FoundryBridgeCallbacks {
    boolean initialize(long contextHandle, int level);
    void deinitialize(long contextHandle, int level);
    long invoke(long contextHandle, long callbackHandle, long[] argumentHandles);
    void invalidate(long contextHandle);
}
```

Freeze `FoundryEngine` around `CallResult call(long, long, String, List<Variant>)`,
`retain`, `release`, `isObjectValid`, `singleton`, and callback-exception reporting. Require
`FoundryRuntime.RUNTIME_CONTRACT_VERSION` to equal `"1"` and require its API, generator, and
bridge constants to come from generated provenance.

- [x] **Step 2: Run the focused tests and record RED**

Run:

```bash
./gradlew --no-daemon :foundry-java-runtime:test \
  --tests 'games.cafecito.foundry.runtime.RuntimeInterfaceTest' \
  --tests 'games.cafecito.foundry.runtime.RuntimeContractTest' --rerun-tasks
```

Expected: compilation fails because the frozen interfaces and generated runtime contract do not
exist.

- [x] **Step 3: Add the smallest compiling interfaces**

Add only the tested signatures, immutable result records, and constructor validation for nonzero
context handles. Do not add JNI, Android, native loading, or host lifecycle behavior.

- [x] **Step 4: Run the focused tests and record GREEN**

Run the command from Step 2. Expected: both interface tests pass.

### Task 2: Implement Variant and foundational value semantics

**Files:**
- Create: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/VariantTest.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/Variant.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/VariantType.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/VariantConversionException.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/StringName.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/NodePath.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/Rid.java`

- [x] **Step 1: Write failing Variant tests**

Require one Nil singleton, `Variant.of(null) == Variant.nil()`, exact type reporting, Foundry
type-strict equality (`int` differs from `float`), hash compatibility for equal values, normalized
NaN/signed-zero equality and hashing, lossless checked conversions, strict wrong-type errors, and
immutable `StringName`, `NodePath`, and `Rid` values.

- [x] **Step 2: Run and record RED**

```bash
./gradlew --no-daemon :foundry-java-runtime:test \
  --tests 'games.cafecito.foundry.runtime.VariantTest' --rerun-tasks
```

Expected: test compilation fails on the absent value types.

- [x] **Step 3: Implement the tested value model**

Store `(VariantType, Object)` immutably. Keep equality type-strict, normalize NaNs and signed zero
within `FLOAT`, clone array inputs, and reject lossy conversions with a message containing source
and target types.

- [x] **Step 4: Run and record GREEN**

Run the command from Step 2. Expected: all Variant tests pass.

### Task 3: Implement arrays, dictionaries, and every packed-array family

**Files:**
- Create: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/CollectionSemanticsTest.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/VariantCodec.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/FoundryArray.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/FoundryDictionary.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedArray.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedByteArray.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedInt32Array.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedInt64Array.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedFloat32Array.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedFloat64Array.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedStringArray.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedVector2Array.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedVector3Array.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedVector4Array.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/types/PackedColorArray.java`
- Create immutable `Vector2`, `Vector3`, `Vector4`, and `Color` records in the same package.

- [x] **Step 1: Write failing container tests**

Require bounds errors with index and size, mutable ordered array/dictionary behavior, typed codecs
rejecting wrong variants at insertion, Nil support only through a codec that accepts it,
shared backing identity for shallow array/dictionary wrappers, explicit independent shallow/deep
duplication, content-based equality/hash codes, insertion-order-independent dictionary equality,
and defensive copying both into and out of every packed array.

- [x] **Step 2: Run and record RED**

```bash
./gradlew --no-daemon :foundry-java-runtime:test \
  --tests 'games.cafecito.foundry.runtime.CollectionSemanticsTest' --rerun-tasks
```

Expected: test compilation fails on the absent collection types.

- [x] **Step 3: Implement the tested collections**

Back `FoundryArray` with `ArrayList<Variant>`, `FoundryDictionary` with
`LinkedHashMap<Variant, Variant>`, and packed arrays with private copied storage. Keep typed codecs
on every mutation path and return snapshots from public bulk accessors.

- [x] **Step 4: Run and record GREEN**

Run the command from Step 2. Expected: all collection tests pass.

### Task 4: Implement context-bound objects, ownership, close, and cleaner fallback

**Files:**
- Create: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/ObjectLifecycleTest.java`
- Create: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/ObjectConcurrencyTest.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryObject.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryObjectDisposedException.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/ObjectOwnership.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/ObjectLease.java`

- [x] **Step 1: Write failing lifecycle and concurrency tests**

Require identical wrapper instances for a live `(context, objectHandle, wrapperClass)`, rejection of
zero and cross-context handles, borrowed invalidation without release, one retain/one release for
reference-counted wrappers, idempotent explicit close, idempotent cleaner action, deterministic
disposed exceptions, shutdown release, and races between bind/invalidate/close/shutdown that never
double-release or return a live wrapper after shutdown.

- [x] **Step 2: Run and record RED**

```bash
./gradlew --no-daemon :foundry-java-runtime:test \
  --tests 'games.cafecito.foundry.runtime.ObjectLifecycleTest' \
  --tests 'games.cafecito.foundry.runtime.ObjectConcurrencyTest' --rerun-tasks
```

Expected: test compilation fails on the absent lifecycle model.

- [x] **Step 3: Implement stable weak identity and release state**

Use a context-local concurrent weak-wrapper table and a separate atomic `ObjectLease` registered
with `Cleaner`. Retain before publishing reference-counted wrappers, release at most once, invalidate
all wrappers before engine/context teardown, and make every public operation call `requireAlive()`.

- [x] **Step 4: Run and record GREEN**

Run the command from Step 2 repeatedly. Expected: all lifecycle/concurrency tests pass with exact
retain/release counts.

### Task 5: Implement calls, callables, signals, callbacks, and shutdown

**Files:**
- Create: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/CallAndSignalTest.java`
- Create: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/CallbackShutdownTest.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryCallError.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryCallException.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryCallable.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundrySignal.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/CallbackRegistry.java`

- [x] **Step 1: Write failing call/callback tests**

Require engine errors to preserve method identity, call error, bad argument index, and expected
type; callable arity/conversion errors; deterministic signal connection order and idempotent
disconnect; same-thread reentrancy; callback exceptions caught and reported with a zero default
handle; deinitialization disabling callbacks before invalidation; and late callback rejection during
concurrent shutdown.

- [x] **Step 2: Run and record RED**

```bash
./gradlew --no-daemon :foundry-java-runtime:test \
  --tests 'games.cafecito.foundry.runtime.CallAndSignalTest' \
  --tests 'games.cafecito.foundry.runtime.CallbackShutdownTest' --rerun-tasks
```

Expected: test compilation fails on the absent call/callback API.

- [x] **Step 3: Implement exception-contained dispatch**

Translate non-OK `FoundryEngine.CallResult` values at the Java boundary, use copy-on-write listener
snapshots for reentrancy, catch every `Throwable` at `FoundryBridgeCallbacks.invoke`, report it
through `FoundryEngine`, and return zero without letting it cross JNI/C.

- [x] **Step 4: Run and record GREEN**

Run the command from Step 2. Expected: all call, signal, callback, reentrancy, and shutdown tests
pass.

### Task 6: Generate and package the exhaustive public API

**Files:**
- Modify: `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/FoundrySourceGenerator.java`
- Modify: `foundry-java-generator/src/test/java/games/cafecito/foundry/generator/FoundrySourceGeneratorTest.java`
- Modify: `foundry-java-runtime/build.gradle.kts`
- Modify: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryRuntime.java`
- Create generated engine wrappers under the runtime build directory.

- [x] **Step 1: Write failing generator/public-artifact tests**

Require a generated public root for every accepted class, builtin, singleton, utility, global
constant/enum, size/offset block, and native structure. Require one callable member for every
method/operator/constructor/property/signal/constant/enum value identity, sanitized deterministic
Java names, inheritance for engine classes, invocation through `FoundryBindingContext`, embedded
API/manifest hashes, exact 57,904 classification coverage, and compilation with the runtime
classpath.

- [x] **Step 2: Run and record RED**

```bash
./gradlew --no-daemon :foundry-java-generator:test \
  --tests 'games.cafecito.foundry.generator.FoundrySourceGeneratorTest' --rerun-tasks
```

Expected: assertions fail because output contains only metadata descriptors.

- [x] **Step 3: Implement deterministic public-wrapper generation**

Emit public wrappers in category subpackages. Engine classes extend their generated parent (or
`FoundryObject`), builtins wrap `Variant`, utility/singleton/global roots expose static entry points,
and all entity identities remain represented in deterministic generated metadata. Use stable
sanitized names plus source hashes where Java overload/collision rules require disambiguation.

- [x] **Step 4: Generate during runtime compilation**

Register a cacheable Gradle generation task with checked API/provenance/manifest inputs and a build
directory output. Add that output to the runtime main source set, make `compileJava` and `javadoc`
depend on it, and package `GeneratedApiProvenance` so `FoundryRuntime` exposes the accepted API,
generator, manifest, and bridge versions.

- [x] **Step 5: Run and record GREEN**

Run the command from Step 2, then:

```bash
./gradlew --no-daemon :foundry-java-runtime:clean :foundry-java-runtime:compileJava \
  :foundry-java-runtime:javadoc
```

Expected: every generated source compiles and Javadoc exits zero.

### Task 7: Add binary API, determinism, classification, and full repository gates

**Files:**
- Modify: `foundry-java-runtime/build.gradle.kts`
- Modify: `build.gradle.kts`
- Create or update: `foundry-java-runtime/api/foundry-java-runtime.api`
- Create: `docs/memory-and-threading.md`

- [x] **Step 1: Add runtime API verification**

Generate a sorted `javap -public` signature inventory for the hand-written runtime contracts and
compare it byte-for-byte to `foundry-java-runtime/api/foundry-java-runtime.api`. Wire
`verifyRuntimeApi`, generated determinism, Javadoc, and complete classification checks into
`:foundry-java-runtime:check`.

- [x] **Step 2: Document the frozen lifecycle**

Document context-bound opaque handles, borrowed/reference-counted ownership, explicit close,
cleaner fallback, stable live identity, callback thread/reentrancy behavior, exception containment,
and shutdown order. State that WS7 owns JNI/native transport and Android initialization.

- [x] **Step 3: Run focused and broad verification**

```bash
./gradlew --no-daemon :foundry-java-runtime:test :foundry-java-generator:test --rerun-tasks
./gradlew --no-daemon :foundry-java-runtime:javadoc :foundry-java-runtime:verifyRuntimeApi
./gradlew --no-daemon clean check
./gradlew --no-daemon clean check
./gradlew --no-daemon --write-locks resolveAndLockAll
```

Expected: all commands exit zero, the second full run reuses configuration cache, locks are
unchanged, all 57,904 entities remain classified, generated output is deterministic, and the
generator-only/publication consumers compile.

- [x] **Step 4: Verify boundaries and commit**

Require no diff under `api/current`, `foundry-java-android`, or Foundry-Android; no
`libfoundry_android.so`, Android host classes, reflection discovery, or native implementation in the
WS6 diff; no whitespace errors; and a clean worktree after a focused commit.
