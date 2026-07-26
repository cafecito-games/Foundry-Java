# Kotlin-Friendly Helpers Design

**Date:** 2026-07-26
**Issue:** [cafecito-games/Foundry-Java#7](https://github.com/cafecito-games/Foundry-Java/issues/7)
**Parent epic:** [cafecito-games/Foundry#1241](https://github.com/cafecito-games/Foundry/issues/1241)
**Status:** Approved

## Summary

Foundry-Java will provide an optional Kotlin artifact containing concise, typed
adapters over the public Java runtime. The helpers will improve common Kotlin
authoring paths without changing the native bridge, introducing a Kotlin
generator, depending on Android, or defining a second registration protocol.

The helper layer will remain deliberately explicit at Java boundary points:
object binding still receives a factory, generic collections still receive
codecs, signal listeners use concrete Java listener adapters, and calls still
use the runtime's public `FoundryBindingContext.call` method. Reification removes
repetitive class literals and selects only canonical codecs; it does not
construct wrappers or infer arbitrary conversion rules through reflection.

Coroutine signal awaiting will use the race-safe invalidation subscription
merged for Foundry-Java#26. A single atomic terminal decision will linearize
signal delivery, owner invalidation, and coroutine cancellation. Every terminal
path will disconnect both registrations exactly once, including registrations
published after another path has already won.

## Goals

- Make public Java binding, Variant, signal, collection, and call APIs pleasant
  to use from Kotlin.
- Preserve a Java-first runtime and keep Kotlin completely optional.
- Support raw and typed signal listeners and awaits for arities zero through
  five.
- Make coroutine cancellation and object/context invalidation deterministic
  without polling, sleeps, or dispatcher hops.
- Provide a thin Kotlin facade over the public Workstream 9 registration API
  after that dependency merges.
- Prove Java-only, Kotlin-over-Java, and mixed Java/Kotlin consumer
  interoperability.
- Freeze the public Kotlin surface in a stable ABI dump.
- Publish reproducibly with Kotlin 2.0.21 and kotlinx-coroutines 1.9.0 under the
  repository's dependency-locking and configuration-cache contracts.

## Non-goals

- JNI or native bridge additions.
- A Kotlin-specific binding generator.
- Android host or `Foundry-Android` dependencies.
- Reflection-based wrapper construction, codec discovery, or property mapping.
- A registration protocol separate from the public Java provider/registry
  protocol.
- Implicit thread switching, dispatcher ownership, or signal buffering.
- Coroutine polling for lifecycle state.
- Replacing the Java runtime's collection, call, signal, or lifecycle
  semantics.

`Foundry-Android` is a read-only source donor and will not be modified, renamed,
archived, deleted, republished, or imported.

## Module and dependency boundary

All implementation code will live in `foundry-java-kotlin`. The module will
continue to expose `foundry-java-runtime` and will add
`org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0` as an implementation
dependency. Public suspend signatures compile to Kotlin
`kotlin.coroutines.Continuation` and expose no kotlinx-coroutines type, so the
dependency belongs only on runtime metadata and must not leak onto the compile
API. Tests will use `kotlinx-coroutines-test:1.9.0`.

The version catalog will own the coroutine version and aliases. Dependency
locking will update only `foundry-java-kotlin/gradle.lockfile` during the
independent implementation phase. Shared root build, repository contract,
Android module/lock, provider/bootstrap/plugin files, and root locks remain
owned by Workstream 9 until Foundry-Java#6 merges.

No Java module will depend on `foundry-java-kotlin`. A Java-only consumer must
therefore compile and run with only the Java artifacts and no Kotlin or
coroutine classes.

## Public API organization

The package is `games.cafecito.foundry.kotlin`. Each focused source file will
use a stable `@file:JvmName` facade name so file renames do not accidentally
change JVM owners. Public overloads that erase to the same JVM signature will
use explicit `@JvmName` values. The ABI baseline will freeze facade owners,
names, signatures, generic bounds, and result data classes.

### Binding helpers

`Binding.kt` will provide reified extensions for the existing public context
methods:

```kotlin
inline fun <reified T : FoundryObject> FoundryBindingContext.bind(
    objectHandle: Long,
    ownership: ObjectOwnership,
    noinline factory: (FoundryBindingContext, ObjectLease) -> T,
): T

inline fun <reified T : FoundryObject> FoundryBindingContext.registerObjectType(
    foundryType: String,
    noinline factory: (FoundryBindingContext, ObjectLease) -> T,
)
```

Both helpers pass `T::class.java` and an explicit
`FoundryBindingContext.ObjectFactory` to the Java method. They never inspect
constructors or bypass runtime wrapper compatibility checks.

### Variant helpers

`Variants.kt` will expose:

```kotlin
inline fun <reified T : Any> variantCodec(): VariantCodec<T>
inline fun <reified T : Any> T.toVariant(): Variant
inline fun <reified T : Any> Variant.decode(): T
fun <T : Any> VariantCodec<T>.nullable(): VariantCodec<T?>
```

The reified codec table will cover the canonical public Java Variant types:
`Variant`, Boolean, Long, Double, String, math/value classes, `FoundryObject`
and subclasses, `FoundryCallable`, `FoundrySignal`, and all packed-array
classes. Foundry integer and float values remain Long and Double; helpers will
not silently narrow to Kotlin Int or Float.

`FoundryObject` subclasses will not receive an unchecked cast of
`VariantCodec.OBJECT`. The helper will wrap that codec and use
`T::class.java.isInstance` plus `T::class.java.cast` during decode. A Variant
containing a different wrapper subclass will fail with a concrete conversion
error naming the expected and actual wrapper classes.

Generic `FoundryArray` and `FoundryDictionary` values cannot safely recover
element codecs from a `KClass`, so callers will use their explicit Java codecs
or collection conversion helpers. Unsupported reified types fail immediately
with an `IllegalArgumentException` naming the Kotlin class. Nullable conversion
is explicit so Kotlin nullability erasure cannot choose the wrong policy.

### Property delegates

`Delegates.kt` will provide explicit lambda-backed delegates:

```kotlin
fun <T> foundryProperty(
    getter: () -> T,
    setter: (T) -> Unit,
): ReadWriteProperty<Any?, T>

fun <T> foundryReadOnlyProperty(
    getter: () -> T,
): ReadOnlyProperty<Any?, T>
```

These helpers only centralize generated or handwritten getter/setter calls.
They do not inspect `KProperty` names, infer method identities, cache native
state, or define lifecycle behavior beyond the invoked Java API.

### Signal listeners

`Signals.kt` will provide `listen` extensions for `FoundrySignal` and
`FoundryTypedSignal.Of0` through `Of5`. Raw listeners receive
`List<Variant>`. Typed listeners receive ordinary Kotlin lambdas with matching
arity.

Every extension will instantiate an explicit `FoundryCallable` or concrete
`FoundryTypedSignal.Listener` SAM. This avoids Kotlin overload/SAM ambiguity
and preserves the Java signal's insertion-order, snapshot, caller-thread, and
exception behavior. Each helper returns the underlying
`FoundrySignal.Connection`; it does not create another connection type.

### Typed await result model

`SignalResults.kt` will define these public Kotlin data classes:

```kotlin
data class SignalArgs2<A, B>(val first: A, val second: B)
data class SignalArgs3<A, B, C>(val first: A, val second: B, val third: C)
data class SignalArgs4<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
data class SignalArgs5<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)
```

Data-class `componentN`, equality, hash, copy, and stable property semantics are
part of the Kotlin ABI. Await return types are:

- raw `FoundrySignal`: `List<Variant>`
- `Of0`: `Unit`
- `Of1<A>`: `A`
- `Of2` through `Of5`: the matching `SignalArgsN`

Dedicated result types avoid an inconsistent Pair/Triple cutoff and preserve
types without falling back to untyped lists.

### Cancellable signal awaiting

`SignalAwait.kt` will provide `await(owner: FoundryObject)` extensions for raw
and typed signals. Await is explicitly owner-bound because a standalone
`FoundrySignal` does not expose binding-context lifecycle.

Implementation uses `suspendCancellableCoroutine` and a private registration
coordinator containing:

- one `AtomicBoolean` terminal gate;
- one atomic signal-connection reference;
- one atomic invalidation-subscription reference.

Registration order is signal connection first, owner invalidation second. That
order prevents an event emitted after the function starts from being missed,
while the already-invalid `onInvalidated` contract closes the remaining race.
Each registration is published through a helper that immediately closes it if
the terminal gate was already won.

The three competing paths are:

1. Signal callback wins the terminal compare-and-set, disconnects both
   registrations, and resumes the continuation with the raw or typed value.
2. Invalidation callback wins, disconnects both registrations, and resumes with
   `FoundryObjectDisposedException` for the owner.
3. Cancellation handler wins, disconnects both registrations, and leaves
   coroutine cancellation as the terminal outcome.

Losers perform no continuation action. Cleanup is idempotent and exactly-once
from the observer's perspective even if a callback occurs before either
registration is stored. No path polls `isAlive`, sleeps, launches another
coroutine, or changes dispatcher/thread. Listener decoding and continuation
resumption occur on the signal or invalidation caller thread, subject to the
continuation's normal coroutine machinery.

The helper will use `tryResume`/`completeResume` and
`tryResumeWithException`-style non-throwing continuation APIs so a cancellation
race cannot turn into a double-resume exception.

`FoundryObjectDisposedException` has a Java package-private constructor, so the
Kotlin layer will not manufacture or widen that exception. After the
invalidation callback commits, it will invoke the owner's public,
lifecycle-checking `objectHandle()` accessor and capture the runtime exception
that accessor deterministically raises. This preserves the Java runtime's
exception type and context/object identity without adding another public Java
surface.

### Collection and packed-array conversions

`Collections.kt` will provide codec-explicit conversions:

```kotlin
fun <T> Iterable<T>.toFoundryArray(codec: VariantCodec<T>): FoundryArray<T>
fun <T> FoundryArray<T>.toKotlinList(): List<T>
fun <K, V> Map<K, V>.toFoundryDictionary(
    keyCodec: VariantCodec<K>,
    valueCodec: VariantCodec<V>,
): FoundryDictionary<K, V>
fun <K, V> FoundryDictionary<K, V>.toKotlinMap(): Map<K, V>
```

Primitive arrays and typed lists/arrays will have direct conversions to and
from their corresponding packed-array classes. JVM-erased overload collisions,
especially object `List<T>` families, will receive explicit `@JvmName` values.
Conversions copy through public constructors and `toArray`/`toList`/`toMap`
methods and retain the Java runtime's validation behavior.

### Call DSL

`Calls.kt` will add a small argument builder and typed result adapter:

```kotlin
class FoundryCallArguments {
    fun variant(value: Variant)
    fun <T> value(value: T, codec: VariantCodec<T>)
}

fun FoundryBindingContext.call(
    objectHandle: Long,
    methodIdentity: String,
    arguments: FoundryCallArguments.() -> Unit,
): Variant

fun <T> FoundryBindingContext.call(
    objectHandle: Long,
    methodIdentity: String,
    resultCodec: VariantCodec<T>,
    arguments: FoundryCallArguments.() -> Unit,
): T
```

The builder preserves insertion order and passes an immutable snapshot to the
public Java call method. It performs no method-name inference, overload
resolution, native dispatch, or hidden registration.

### Registration interop

`Registration.kt` will be designed against the exact public Workstream 9 API
after Foundry-Java#6 merges. It may provide reified/lambda conveniences for
provider or registry types, but it will delegate directly to that public Java
protocol and will not scan classes, descriptors, or manifests.

The issue branch will rebase once onto the merged Workstream 9 head before this
file and its mixed-module fixture are finalized. Root build/repository
integration will be reconciled at that point rather than duplicated during
parallel development.

## Testing strategy

All behavior changes begin with a failing test.

### Unit and API tests

- Reified binding and registration pass the correct class and explicit factory
  through Java runtime validation.
- Canonical reified codecs round-trip representative scalar, object, value,
  signal, callable, and packed-array values; unsupported and nullable cases
  fail or succeed deliberately.
- A reified `FoundryObject` subclass codec rejects a Variant containing a
  different wrapper subclass instead of returning an unchecked base wrapper.
- Read/write and read-only delegates invoke only their supplied lambdas.
- Raw and typed listeners cover arities zero through five and return live Java
  connections.
- Signal result data classes expose stable properties and destructuring.
- Collection and packed-array conversions preserve order, values, copying, and
  Java validation errors.
- The call builder preserves argument order and typed decoding.
- Registration helpers delegate to the merged Workstream 9 protocol.

### Deterministic coroutine races

Tests will use latches, barriers, and controllable executors rather than timing
sleeps or polling:

- signal delivery disconnects both registrations before successful completion;
- cancellation disconnects both registrations;
- object invalidation and context invalidation fail with
  `FoundryObjectDisposedException`;
- an already-invalid owner fails during registration;
- signal versus cancellation, signal versus invalidation, and cancellation
  versus invalidation each produce one terminal outcome;
- signal delivery before invalidation-token publication and invalidation before
  publication of its returned token still self-close late registrations;
- typed awaits cover arities zero through five;
- repeated cancellation/invalidation/emission cannot resume twice.

### Consumer compilation fixtures

- A Java-only fixture compiles against Java artifacts without the Kotlin
  artifact or Kotlin/coroutine runtime.
- A Kotlin fixture compiles helpers over the public Java runtime.
- A mixed Java/Kotlin extension fixture compiles Java wrappers and Kotlin
  authoring code together.
- After Workstream 9 merges, the Kotlin and mixed fixtures exercise its public
  provider/registry protocol through the thin helper layer.

These fixtures are compilation contracts, not source-string assertions.

### ABI, publication, and build-system gates

- A deterministic Kotlin public ABI dump is compared with a checked-in
  baseline.
- Kotlin unit/coroutine/interoperability/documentation checks are wired into
  module `check`.
- The module publication contains the expected classes and dependency metadata.
- Publication POM and Gradle module metadata place coroutines in runtime scope,
  exclude it from compile API, and preserve complete Java-only absence.
- Dependency locks are generated by Gradle, never hand-edited.
- Repeated configuration-cache runs reuse the stored configuration.
- Before publication, the branch rebases onto merged Workstream 9 and runs the
  repository's full clean check, publication, dependency-lock, and
  configuration-cache gates under Java 17.

## Documentation

`docs/kotlin-helpers.md` will document:

- optional dependency coordinates and the Java-first boundary;
- binding and registration examples with explicit factories;
- canonical Variant mappings and explicit nullable/generic codec rules;
- property delegates;
- raw and typed signal listeners;
- owner-bound signal awaiting, cancellation, invalidation, caller-thread
  semantics, and result shapes;
- collection and packed-array conversions;
- call and registration DSL examples;
- unsupported reflection, Android, JNI, and secondary-protocol behavior.

Examples will compile as part of the documentation or fixture gates rather than
drifting as prose-only snippets.

## Error handling and compatibility

Helpers preserve Java exceptions unless the helper itself detects an
unsupported reified type. Lifecycle awaiting uses the runtime's
`FoundryObjectDisposedException`; no new lifecycle exception hierarchy is
introduced. Signal listener exceptions retain Java signal behavior.

The Kotlin artifact is additive and optional. Its ABI baseline is intentionally
strict because Kotlin top-level owners, default arguments, inline bodies, data
class members, and erased overload names are all consumer-visible. Any future
change to those surfaces requires an explicit baseline update and review.

## Delivery sequence

1. Implement independent Kotlin-owned helpers test-first on the exact
   Foundry-Java#26 merged base.
2. Run focused module, coroutine, compilation-fixture, ABI, publication, lock,
   and configuration-cache gates.
3. Wait for Foundry-Java#6 to merge, rebase once, and implement only the thin
   public registration interop plus final mixed fixture.
4. Run fresh full repository gates.
5. Obtain an independent exact-head review and Cursor review against current
   `origin/main` until the latest valid verdict is exactly `RESULT: clean`.
6. Open a PR targeting `main`; enable squash auto-merge only after checks and
   both reviews converge.
7. Confirm merge, issue completion, Experiment Done status, and local
   worktree/branch cleanup.
