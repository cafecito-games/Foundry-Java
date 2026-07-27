# Kotlin-Friendly Helpers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify the optional Kotlin helper artifact for binding, Variants, delegates, signals, cancellable awaiting, collections, calls, and registry bootstrap without changing the Java/native protocol.

**Architecture:** Focused Kotlin facade files delegate only to public Foundry-Java APIs. Coroutine awaiting is owner-bound and uses a single atomic terminal decision with late-publication self-close for the Java signal connection and invalidation token; registration interop is added only after Workstream 9 merges. Module-owned tests, compilation fixtures, ABI baseline, documentation, locks, publication metadata, and configuration-cache gates freeze the public contract.

**Tech Stack:** Java 17, Kotlin 2.0.21, kotlinx-coroutines 1.9.0, Gradle 8.11.1, JUnit 5.11.3, Kotlin Gradle plugin, Maven Publish

---

## File map

- Modify `gradle/libs.versions.toml` for the pinned coroutine version and two
  aliases.
- Modify `foundry-java-kotlin/build.gradle.kts` for implementation/test
  dependencies, consumer compilation source sets, ABI verification, and module
  check wiring.
- Modify `foundry-java-kotlin/gradle.lockfile` only through Gradle lock
  generation.
- Replace
  `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/FoundryKotlin.kt`
  with the focused sources below; no compatibility is required for the empty
  marker object because it has never carried behavior.
- Create `Binding.kt`, `Variants.kt`, `Delegates.kt`, `Signals.kt`,
  `SignalResults.kt`, `SignalAwait.kt`, `Collections.kt`, `Calls.kt`, and,
  after Workstream 9 merges, `Registration.kt` in
  `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/`.
- Create focused Kotlin tests in
  `foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/`.
- Create real consumer sources below
  `foundry-java-kotlin/src/test/fixtures/java-only`,
  `foundry-java-kotlin/src/test/fixtures/kotlin-over-java`, and
  `foundry-java-kotlin/src/test/fixtures/mixed`.
- Create `foundry-java-kotlin/verify-kotlin-api.sh` and
  `foundry-java-kotlin/api/foundry-java-kotlin.api` for deterministic public ABI
  comparison.
- Create `docs/kotlin-helpers.md` for the supported authoring contract.
- Create no Android source, JNI source, generator source, or secondary registry
  protocol.

### Task 1: Pin coroutine dependencies and establish the first RED

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `foundry-java-kotlin/build.gradle.kts`
- Modify with Gradle: `foundry-java-kotlin/gradle.lockfile`
- Create: `foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/CoroutineDependencyContractTest.kt`

- [ ] **Step 1: Write the failing dependency-scope test**

Create a JUnit test that reads the generated POM and Gradle module metadata from
the module publication and asserts runtime-only coroutine scope:

```kotlin
package games.cafecito.foundry.kotlin

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CoroutineDependencyContractTest {
    @Test
    fun `coroutines are runtime metadata and absent from compile API`() {
        val repository = Path(System.getProperty("foundry.test.repository"))
        val pom = Files.readString(repository.resolve("foundry-java-kotlin.pom"))
        val module = Files.readString(repository.resolve("foundry-java-kotlin.module"))

        assertTrue(pom.contains("<artifactId>kotlinx-coroutines-core-jvm</artifactId>"))
        assertTrue(pom.contains("<scope>runtime</scope>"))
        assertFalse(pom.substringBefore("<scope>runtime</scope>").contains("kotlinx-coroutines-core"))
        assertTrue(module.contains("\"name\": \"runtimeElements\""))
        assertTrue(module.contains("kotlinx-coroutines-core-jvm"))
    }
}
```

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew --no-daemon :foundry-java-kotlin:test \
  --tests '*CoroutineDependencyContractTest'
```

Expected: FAIL because the coroutine aliases/dependencies and publication test
inputs do not exist.

- [ ] **Step 3: Add the pinned catalog and module dependencies**

Add:

```toml
[versions]
coroutines = "1.9.0"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
```

Use runtime implementation scope in the module:

```kotlin
dependencies {
    api(project(":foundry-java-runtime"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
```

The test setup must point at the module-local publication produced by
`publishMavenJavaPublicationToBootstrapRepository` and make the test depend on
that publication task without introducing a root-build edit.

- [ ] **Step 4: Generate only the Kotlin module lock**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew --no-daemon :foundry-java-kotlin:dependencies --write-locks
```

Expected: `foundry-java-kotlin/gradle.lockfile` records coroutines 1.9.0 and its
Kotlin/atomic dependencies; no other lockfile changes.

- [ ] **Step 5: Run the focused dependency and publication gate**

Run:

```bash
./gradlew --no-daemon \
  :foundry-java-kotlin:publishMavenJavaPublicationToBootstrapRepository \
  :foundry-java-kotlin:test --tests '*CoroutineDependencyContractTest'
```

Expected: PASS, with coroutines in runtime metadata and absent from the Java
runtime publication and Kotlin compile API.

- [ ] **Step 6: Commit the dependency boundary**

```bash
git add gradle/libs.versions.toml foundry-java-kotlin/build.gradle.kts \
  foundry-java-kotlin/gradle.lockfile \
  foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/CoroutineDependencyContractTest.kt
git commit -m "Pin Kotlin coroutine dependencies"
```

### Task 2: Add reified binding and Variant helpers

**Files:**
- Create: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Binding.kt`
- Create: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Variants.kt`
- Delete: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/FoundryKotlin.kt`
- Create: `foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/BindingAndVariantTest.kt`

- [ ] **Step 1: Write binding and Variant RED tests**

Cover class-literal forwarding, explicit factories, scalar round trips,
unsupported Int, nullable codecs, and exact object subclass rejection:

```kotlin
@Test
fun `reified binding uses the requested wrapper class and explicit factory`() {
    val context = contextWithType("Node")
    val wrapper = context.bind<TestObject>(7, ObjectOwnership.BORROWED, ::TestObject)
    assertEquals(TestObject::class.java, wrapper.javaClass)
}

@Test
fun `canonical codecs round trip without narrowing`() {
    assertEquals(9L, Variant.of(9L).decode<Long>())
    assertEquals("coffee", "coffee".toVariant().decode<String>())
    assertFailsWith<IllegalArgumentException> { variantCodec<Int>() }
}

@Test
fun `object subclass codec rejects a different wrapper subclass`() {
    val actual = contextWithType("").bind<OtherObject>(8, ObjectOwnership.BORROWED, ::OtherObject)
    val failure =
        assertFailsWith<IllegalArgumentException> {
            variantCodec<TestObject>().decode(Variant.of(actual))
        }
    assertTrue(failure.message.orEmpty().contains(TestObject::class.java.name))
    assertTrue(failure.message.orEmpty().contains(OtherObject::class.java.name))
}
```

The test-local engine must implement every `FoundryEngine` method and expose a
controllable `objectType` and `call` result. Test wrappers call the protected
Java constructor.

- [ ] **Step 2: Run to verify RED**

Run:

```bash
./gradlew --no-daemon :foundry-java-kotlin:test \
  --tests '*BindingAndVariantTest'
```

Expected: Kotlin compilation fails because the reified functions do not exist.

- [ ] **Step 3: Implement `Binding.kt`**

Use facade owner `FoundryBindings`:

```kotlin
@file:JvmName("FoundryBindings")

package games.cafecito.foundry.kotlin

inline fun <reified T : FoundryObject> FoundryBindingContext.bind(
    objectHandle: Long,
    ownership: ObjectOwnership,
    noinline factory: (FoundryBindingContext, ObjectLease) -> T,
): T = bind(objectHandle, ownership, T::class.java, FoundryBindingContext.ObjectFactory(factory))

inline fun <reified T : FoundryObject> FoundryBindingContext.registerObjectType(
    foundryType: String,
    noinline factory: (FoundryBindingContext, ObjectLease) -> T,
) = registerObjectType(foundryType, T::class.java, FoundryBindingContext.ObjectFactory(factory))
```

- [ ] **Step 4: Implement the explicit canonical codec table**

Use facade owner `FoundryVariants`. Map each canonical Java type to the matching
`VariantCodec` constant. For object subclasses, create a codec whose `encode`
delegates to `OBJECT` and whose `decode` checks and casts with the reified Java
class:

```kotlin
private fun <T : FoundryObject> objectCodec(type: Class<T>): VariantCodec<T> =
    object : VariantCodec<T> {
        override fun encode(value: T): Variant = VariantCodec.OBJECT.encode(value)

        override fun decode(value: Variant): T {
            val decoded = VariantCodec.OBJECT.decode(value)
            require(type.isInstance(decoded)) {
                "Expected Foundry object ${type.name}, received ${decoded.javaClass.name}."
            }
            return type.cast(decoded)
        }
    }
```

Implement `variantCodec<T>()`, `T.toVariant()`, `Variant.decode<T>()`, and the
explicit nullable wrapper. Do not support Kotlin Int/Float or generic
FoundryArray/FoundryDictionary inference.

Delete the empty `FoundryKotlin` marker when these real public facades replace
it; the later ABI test must prove the marker class is absent.

- [ ] **Step 5: Run focused tests and formatting**

Run:

```bash
./gradlew --no-daemon :foundry-java-kotlin:test \
  --tests '*BindingAndVariantTest' :spotlessCheck
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Binding.kt \
  foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Variants.kt \
  foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/FoundryKotlin.kt \
  foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/BindingAndVariantTest.kt
git commit -m "Add reified Kotlin binding helpers"
```

### Task 3: Add explicit delegates and collection conversions

**Files:**
- Create: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Delegates.kt`
- Create: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Collections.kt`
- Create: `foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/DelegatesAndCollectionsTest.kt`

- [ ] **Step 1: Write delegate and conversion RED tests**

The test must prove lambda-only delegation, ordering, copy behavior, validation
propagation, and every packed family:

```kotlin
@Test
fun `property delegates invoke only explicit lambdas`() {
    var backing = 3L
    val holder =
        object {
            var value by foundryProperty({ backing }, { backing = it })
            val doubled by foundryReadOnlyProperty { backing * 2 }
        }
    holder.value = 7L
    assertEquals(7L, holder.value)
    assertEquals(14L, holder.doubled)
}

@Test
fun `collections preserve insertion order and copy inputs`() {
    val array = listOf(1L, 2L).toFoundryArray(VariantCodec.INTEGER)
    val dictionary =
        linkedMapOf("a" to 1L, "b" to 2L)
            .toFoundryDictionary(VariantCodec.STRING, VariantCodec.INTEGER)
    assertEquals(listOf(1L, 2L), array.toKotlinList())
    assertEquals(linkedMapOf("a" to 1L, "b" to 2L), dictionary.toKotlinMap())
}
```

Add byte/int/long/float/double/string/Vector2/Vector3/Vector4/Color packed-array
round trips and mutation-after-conversion checks.

- [ ] **Step 2: Run to verify RED**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test \
  --tests '*DelegatesAndCollectionsTest'
```

Expected: compile failure for missing delegates and conversions.

- [ ] **Step 3: Implement lambda-backed delegates**

Use facade owner `FoundryDelegates` and private `ReadWriteProperty` /
`ReadOnlyProperty` implementations. Ignore the `thisRef` and `property`
metadata; call only the supplied functions.

- [ ] **Step 4: Implement generic and packed conversions**

Use facade owner `FoundryCollections`. Construct Java collections with explicit
codecs and add each value in order. Use public packed constructors and
`toArray()`. Apply unique JVM names such as `toPackedVector2Array`,
`toPackedVector3Array`, and `toPackedColorArray` where erased `List` receivers
would collide.

- [ ] **Step 5: Run focused and format gates**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test \
  --tests '*DelegatesAndCollectionsTest' :spotlessCheck
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Delegates.kt \
  foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Collections.kt \
  foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/DelegatesAndCollectionsTest.kt
git commit -m "Add Kotlin delegate and collection helpers"
```

### Task 4: Add raw and typed listener adapters and result records

**Files:**
- Create: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Signals.kt`
- Create: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/SignalResults.kt`
- Create: `foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/SignalsTest.kt`

- [ ] **Step 1: Write arity zero-through-five listener RED tests**

Instantiate one raw signal and typed views with public codecs. Assert raw
`List<Variant>`, ordinary Kotlin lambdas at every arity, caller thread, returned
connection state/disconnect, and the frozen result data classes:

```kotlin
@Test
fun `typed listeners use ordinary lambdas through arity five`() {
    val signal = FoundrySignal()
    val values = mutableListOf<String>()
    FoundryTypedSignal.Of2(signal, VariantCodec.STRING, VariantCodec.INTEGER)
        .listen { first, second -> values += "$first:$second" }
    signal.emit(Variant.of("cup"), Variant.of(2L))
    assertEquals(listOf("cup:2"), values)
}

@Test
fun `signal result records keep named and component properties`() {
    val (first, second) = SignalArgs2("cup", 2L)
    assertEquals("cup", first)
    assertEquals(2L, second)
}
```

- [ ] **Step 2: Run to verify RED**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test --tests '*SignalsTest'
```

Expected: compile failure for missing `listen` and `SignalArgsN`.

- [ ] **Step 3: Implement explicit listener adapters**

Use facade owner `FoundrySignals`. Raw `listen` must call
`FoundryCallable.variadic` and return `Variant.nil()` after the Kotlin lambda.
Typed overloads must instantiate the exact Java nested listener interface:

```kotlin
fun <A, B> FoundryTypedSignal.Of2<A, B>.listen(
    listener: (A, B) -> Unit,
): FoundrySignal.Connection =
    connect(FoundryTypedSignal.Of2.Listener { first, second -> listener(first, second) })
```

Repeat explicit concrete adapters for arities zero through five.

- [ ] **Step 4: Implement the four public data classes**

Create `SignalArgs2` through `SignalArgs5` with exact `first`, `second`,
`third`, `fourth`, and `fifth` property names. Do not use Pair, Triple, or
lists for typed results.

- [ ] **Step 5: Run focused and format gates**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test \
  --tests '*SignalsTest' :spotlessCheck
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Signals.kt \
  foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/SignalResults.kt \
  foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/SignalsTest.kt
git commit -m "Add typed Kotlin signal listeners"
```

### Task 5: Implement cancellable owner-bound signal awaiting

**Files:**
- Create: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/SignalAwait.kt`
- Create: `foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/SignalAwaitTest.kt`

- [ ] **Step 1: Write basic await RED tests**

Use `runTest` for suspension control, but use explicit engine/context actions
for lifecycle:

```kotlin
@Test
fun `raw await disconnects after one delivery`() = runTest {
    val fixture = liveOwner()
    val signal = FoundrySignal()
    val deferred = async(start = CoroutineStart.UNDISPATCHED) { signal.await(fixture.owner) }
    signal.emit(Variant.of("ready"))
    assertEquals(listOf(Variant.of("ready")), deferred.await())
}

@Test
fun `cancellation disconnects the signal`() = runTest {
    val fixture = liveOwner()
    val signal = FoundrySignal()
    val deferred = async(start = CoroutineStart.UNDISPATCHED) { signal.await(fixture.owner) }
    deferred.cancelAndJoin()
    signal.emit(Variant.of("late"))
    assertTrue(deferred.isCancelled)
}

@Test
fun `context invalidation fails with the runtime disposed exception`() = runTest {
    val fixture = liveOwner()
    val signal = FoundrySignal()
    val deferred = async(start = CoroutineStart.UNDISPATCHED) { signal.await(fixture.owner) }
    fixture.context.close()
    assertFailsWith<FoundryObjectDisposedException> { deferred.await() }
}
```

Add typed success assertions for Unit, A, and `SignalArgs2` through
`SignalArgs5`.

- [ ] **Step 2: Run to verify RED**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test --tests '*SignalAwaitTest'
```

Expected: compile failure for missing await extensions.

- [ ] **Step 3: Implement the private terminal coordinator**

Use one `AtomicBoolean` and two `AtomicReference<AutoCloseable?>` values. Its
publication method stores a registration and closes it immediately if terminal
was already won:

```kotlin
private class AwaitRegistrations {
    private val terminal = AtomicBoolean()
    private val connection = AtomicReference<AutoCloseable?>()
    private val invalidation = AtomicReference<AutoCloseable?>()

    fun publishConnection(value: AutoCloseable) = publish(connection, value)
    fun publishInvalidation(value: AutoCloseable) = publish(invalidation, value)

    fun tryTerminate(action: () -> Unit) {
        if (terminal.compareAndSet(false, true)) {
            closePublished()
            action()
        }
    }

    private fun publish(slot: AtomicReference<AutoCloseable?>, value: AutoCloseable) {
        check(slot.compareAndSet(null, value))
        if (terminal.get() && slot.compareAndSet(value, null)) value.close()
    }

    private fun closePublished() {
        connection.getAndSet(null)?.close()
        invalidation.getAndSet(null)?.close()
    }
}
```

Production code must refine this minimal shape only if a RED proves a
linearization defect; it may not add polling or timing.

- [ ] **Step 4: Implement raw and typed await adapters**

Use facade owner `FoundrySignalAwait`. Connect first, install
`invokeOnCancellation`, then call `owner.onInvalidated`. Every callback competes
through `tryTerminate`. The invalidation winner obtains the runtime-created
exception by calling `owner.objectHandle()` after invalidation and catching the
`FoundryObjectDisposedException`. Resume with `tryResume` /
`completeResume` or `tryResumeWithException`.

Typed overloads reuse one private suspending registration function and adapt
arity lambdas to Unit, A, and `SignalArgsN`; they do not duplicate lifecycle
coordination.

- [ ] **Step 5: Run focused tests**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test --tests '*SignalAwaitTest'
```

Expected: all basic await, cancellation, invalidation, and arity tests PASS.

- [ ] **Step 6: Commit**

```bash
git add foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/SignalAwait.kt \
  foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/SignalAwaitTest.kt
git commit -m "Add cancellable Kotlin signal awaiting"
```

### Task 6: Prove deterministic await publication and terminal races

**Files:**
- Modify: `foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/SignalAwaitTest.kt`
- Modify only if a RED requires it: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/SignalAwait.kt`

- [ ] **Step 1: Add deterministic race tests**

Use `CountDownLatch`, `CyclicBarrier`, fixed executors, and atomic counters. Add
tests for:

- signal versus cancel;
- signal versus object invalidation;
- cancel versus context invalidation;
- signal callback before invalidation-token publication;
- already-dead synchronous invalidation before token publication;
- repeated emit/cancel/invalidate;
- completion and callbacks on caller threads.

Each race test must assert one terminal result, no double resume, no remaining
active token, and no listener response to later emits. Do not use
`Thread.sleep`, virtual-time delay as a race oracle, or polling loops.

- [ ] **Step 2: Run each new test to verify RED where the coordinator is incomplete**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test \
  --tests '*SignalAwaitTest.*publication*' \
  --tests '*SignalAwaitTest.*versus*'
```

Expected: at least one test exposes any missing late-publication cleanup; if
the implementation already satisfies a case, temporarily prove test
sensitivity by removing its terminal compare-and-set, observe failure, then
restore before continuing.

- [ ] **Step 3: Make only the minimal race correction**

Keep connection-first then invalidation registration, one terminal gate, atomic
slots, caller-thread semantics, and idempotent close. A late publisher must
remove and close its own token after observing terminal. No new lock may be held
while a Java close or continuation resume executes.

- [ ] **Step 4: Repeat the race tests**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test \
  --tests '*SignalAwaitTest' --rerun-tasks
```

Expected: PASS repeatedly with no sleeps.

- [ ] **Step 5: Commit**

```bash
git add foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/SignalAwait.kt \
  foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/SignalAwaitTest.kt
git commit -m "Prove Kotlin signal await races"
```

### Task 7: Add the small call DSL

**Files:**
- Create: `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Calls.kt`
- Create: `foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/CallsTest.kt`

- [ ] **Step 1: Write call-order and decoding RED tests**

Use a recording `FoundryEngine`:

```kotlin
val result =
    context.call(7, "coffee/2", VariantCodec.STRING) {
        value(2L, VariantCodec.INTEGER)
        variant(Variant.of("milk"))
    }
assertEquals("served", result)
assertEquals(listOf(Variant.of(2L), Variant.of("milk")), engine.arguments)
```

Also prove Java call exceptions and codec conversion errors pass through.

- [ ] **Step 2: Run to verify RED**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test --tests '*CallsTest'
```

Expected: compile failure for missing builder and overloads.

- [ ] **Step 3: Implement the immutable-snapshot builder**

Use facade owner `FoundryCalls`. `FoundryCallArguments` owns a private mutable
list, exposes only `variant` and codec-explicit `value`, and returns
`List.copyOf` internally. Both call extensions delegate once to the public Java
context; the typed overload decodes that returned Variant.

- [ ] **Step 4: Run focused and format gates**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test \
  --tests '*CallsTest' :spotlessCheck
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Calls.kt \
  foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/CallsTest.kt
git commit -m "Add the Kotlin call DSL"
```

### Task 8: Add compilation fixtures and the Kotlin ABI baseline

**Files:**
- Modify: `foundry-java-kotlin/build.gradle.kts`
- Create: `foundry-java-kotlin/src/test/fixtures/java-only/JavaOnlyConsumer.java`
- Create: `foundry-java-kotlin/src/test/fixtures/kotlin-over-java/KotlinConsumer.kt`
- Create: `foundry-java-kotlin/src/test/fixtures/mixed/MixedObject.java`
- Create: `foundry-java-kotlin/src/test/fixtures/mixed/MixedConsumer.kt`
- Create: `foundry-java-kotlin/verify-kotlin-api.sh`
- Create: `foundry-java-kotlin/api/foundry-java-kotlin.api`

- [ ] **Step 1: Add consumers that currently fail to compile**

The Java-only fixture imports only runtime/types and binds a Java wrapper. The
Kotlin fixture uses every helper family. The mixed fixture declares a Java
wrapper subclass and uses reified binding, signals, calls, and delegates from
Kotlin. No Java-only source imports the Kotlin package.

- [ ] **Step 2: Add module-local compilation tasks and verify RED**

Create isolated configurations/source sets whose classpaths are exact:

- Java-only: `foundry-java-runtime` only;
- Kotlin-over-Java: Kotlin module output plus runtime, stdlib, and coroutine
  runtime;
- mixed: Java wrapper plus Kotlin module output and the same Kotlin runtime.

Wire their compile tasks into module `check`, then run:

```bash
./gradlew --no-daemon :foundry-java-kotlin:check
```

Expected: compile fixture failure until all frozen helper signatures exist and
the Java-only classpath is isolated from Kotlin.

- [ ] **Step 3: Add deterministic ABI generation and comparison**

`verify-kotlin-api.sh` must:

1. accept the built Kotlin JAR and checked-in baseline paths;
2. list `games/cafecito/foundry/kotlin/*.class` in sorted order;
3. invoke Java 17 `javap -public -s` for each public facade/data/builder class;
4. normalize only the temporary JAR path;
5. compare exact output with `diff -u`.

Register a module-local `Exec` verification task depending on `jar`, declare
the script/baseline/JAR as Gradle inputs, and wire it into `check`.

- [ ] **Step 4: Generate and inspect the initial baseline**

Run:

```bash
./gradlew --no-daemon :foundry-java-kotlin:jar
foundry-java-kotlin/verify-kotlin-api.sh \
  foundry-java-kotlin/build/libs/foundry-java-kotlin-0.1.0-SNAPSHOT.jar \
  foundry-java-kotlin/api/foundry-java-kotlin.api --write
```

Inspect that facade owners, all public methods, descriptors, `@JvmName`
choices, `SignalArgsN` properties/components/copy methods, and builder types are
present; the marker object is absent.

- [ ] **Step 5: Run fixture, ABI, and configuration-cache gates twice**

```bash
./gradlew --no-daemon :foundry-java-kotlin:check
./gradlew --no-daemon :foundry-java-kotlin:check
```

Expected: first PASS stores configuration cache; second PASS reports cache
reuse. Java-only compilation has no Kotlin/coroutine classpath.

- [ ] **Step 6: Commit**

```bash
git add foundry-java-kotlin/build.gradle.kts foundry-java-kotlin/src/test/fixtures \
  foundry-java-kotlin/verify-kotlin-api.sh foundry-java-kotlin/api
git commit -m "Verify Kotlin consumers and public ABI"
```

### Task 9: Document and compile the supported authoring surface

**Files:**
- Create: `docs/kotlin-helpers.md`
- Modify: `foundry-java-kotlin/src/test/fixtures/kotlin-over-java/KotlinConsumer.kt`
- Modify: `foundry-java-kotlin/src/test/fixtures/mixed/MixedConsumer.kt`

- [ ] **Step 1: Write the documentation**

Document optional coordinates; Java-first absence; Kotlin 2.0.21/coroutines
1.9.0; explicit factories; canonical/non-narrowing/nullable/generic codecs;
delegates; listener arities; owner-bound awaits and `SignalArgsN`; cancellation,
invalidation, caller-thread semantics; collections and packed arrays; call DSL;
and the no-reflection/no-JNI/no-Android/no-secondary-registry boundaries.

- [ ] **Step 2: Mirror every documentation example in a fixture**

Use the same imports and statements in the Kotlin-over-Java or mixed consumer
source so `check` compiles every advertised API.

- [ ] **Step 3: Run documentation-linked fixture and spelling/format gates**

```bash
./gradlew --no-daemon :foundry-java-kotlin:check :spotlessCheck
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add docs/kotlin-helpers.md \
  foundry-java-kotlin/src/test/fixtures/kotlin-over-java/KotlinConsumer.kt \
  foundry-java-kotlin/src/test/fixtures/mixed/MixedConsumer.kt
git commit -m "Document Kotlin helper authoring"
```

### Task 10: Rebase on merged Workstream 9 and add thin registry interop

**Files:**
- Create:
  `foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Registration.kt`
- Create:
  `foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/RegistrationTest.kt`
- Modify:
  `foundry-java-kotlin/src/test/fixtures/kotlin-over-java/KotlinConsumer.kt`
- Modify:
  `foundry-java-kotlin/src/test/fixtures/mixed/MixedConsumer.kt`
- Modify after ownership transfer only: `build.gradle.kts`
- Modify after ownership transfer only:
  `src/test/java/games/cafecito/foundry/build/RepositoryContractTest.java`

- [ ] **Step 1: Wait for Foundry-Java#6 to merge and rebase once**

Verify GitHub issue/PR completion, fetch, and run:

```bash
git rebase origin/main
```

Expected: issue-7 contains the exact merged provider/registry/bootstrap API and
no duplicated WS9 changes.

- [ ] **Step 2: Write registry DSL RED tests**

Use exact merged `FoundryModuleProvider` instances and descriptors:

```kotlin
val bootstrap =
    foundryRegistry {
        provider(betaProvider)
        provider(alphaProvider)
    }
assertEquals(listOf("alpha", "beta"), bootstrap.moduleNames())
```

Prove the DSL delegates duplicate/provenance validation to
`FoundryRegistryBootstrap` and never scans classes or descriptors.

- [ ] **Step 3: Run to verify RED**

```bash
./gradlew --no-daemon :foundry-java-kotlin:test --tests '*RegistrationTest'
```

Expected: compile failure for missing `foundryRegistry`.

- [ ] **Step 4: Implement the thin builder**

Use facade owner `FoundryRegistration`. A public `FoundryRegistryBuilder`
collects explicit `FoundryModuleProvider` values in insertion order; the
top-level function constructs exactly one public `FoundryRegistryBootstrap`.
No descriptor parsing, ServiceLoader, reflection, classpath scan, manifest
read, or alternate registry interface is permitted.

- [ ] **Step 5: Reconcile shared root integration once**

After WS9 ownership transfers, update only the exact root dependency contract,
publication expectations, and module checks needed for coroutines runtime scope,
ABI verification, and consumer compilation. Do not change Android payload,
provider generation, bootstrap generation, plugin behavior, or WS9 locks except
where fresh merged root contract tests prove a required shared integration.

- [ ] **Step 6: Run focused merged-base gates**

```bash
./gradlew --no-daemon :foundry-java-kotlin:check \
  :test --tests '*RepositoryContractTest' \
  :verifyRepositoryModel :verifyBootstrapPublications
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add \
  foundry-java-kotlin/src/main/kotlin/games/cafecito/foundry/kotlin/Registration.kt \
  foundry-java-kotlin/src/test/kotlin/games/cafecito/foundry/kotlin/RegistrationTest.kt \
  foundry-java-kotlin/src/test/fixtures/kotlin-over-java/KotlinConsumer.kt \
  foundry-java-kotlin/src/test/fixtures/mixed/MixedConsumer.kt \
  foundry-java-kotlin/api/foundry-java-kotlin.api \
  docs/kotlin-helpers.md build.gradle.kts \
  src/test/java/games/cafecito/foundry/build/RepositoryContractTest.java
git commit -m "Integrate Kotlin registry helpers"
```

Review the staged paths before committing and exclude every unrelated WS9 or
local artifact.

### Task 11: Run complete gates and prepare exact-head review

**Files:**
- Modify only for verified defects: files already owned by Tasks 1-10
- Do not modify: `Foundry-Android`

- [ ] **Step 1: Run fresh module tests with rerun**

```bash
./gradlew --no-daemon :foundry-java-kotlin:check --rerun-tasks
```

Expected: all unit, coroutine-race, fixture, ABI, and documentation gates PASS.

- [ ] **Step 2: Run full clean repository verification under Java 17**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew --no-daemon clean check
```

Expected: Java/runtime/API/Javadoc, Kotlin, publications, Android
lint/AAR/four-ABI, native host, and sanitizer gates PASS.

- [ ] **Step 3: Run publication and dependency-scope verification**

```bash
./gradlew --no-daemon verifyBootstrapPublications verifyRepositoryModel
```

Inspect Kotlin POM/module metadata: coroutines is runtime-only; Java artifacts
contain no Kotlin/coroutine dependency; published Kotlin JAR matches ABI.

- [ ] **Step 4: Prove configuration-cache reuse**

```bash
./gradlew --no-daemon check
./gradlew --no-daemon check
```

Expected: both PASS and the second reports reused configuration cache.

- [ ] **Step 5: Verify exact repository boundaries**

```bash
git diff --check
git status --short
git -C /Users/christian/CafecitoGames/Foundry-Android status --short
git -C /Users/christian/CafecitoGames/Foundry-Android rev-parse HEAD
```

Expected: only intentional Foundry-Java issue files before commit;
Foundry-Android clean at
`b8c46c807d467fcd1667b7d4cb04d07a09a08860`.

- [ ] **Step 6: Commit any verified final corrections**

Stage exact paths, inspect `git diff --cached`, and make a focused imperative
commit. Do not create an empty cleanup commit.

### Task 12: Independent review, Cursor clean verdict, PR, and cleanup

**Files:**
- Update outside Git only: epic ledger, GitHub issue/PR/project state
- Remove after merge: issue-7 worktree and local/remote issue branch

- [ ] **Step 1: Obtain independent exact-head review**

Dispatch a fresh reviewer against exact `origin/main...HEAD` with the issue,
approved spec, plan, ownership boundary, and verification evidence. Address
findings using `superpowers:receiving-code-review`, rerun affected and full
gates, and repeat until Ready is yes with no findings.

- [ ] **Step 2: Obtain Cursor `RESULT: clean`**

Use `cursor-review` against the exact same reviewed head/base. Any code change
invalidates the verdict; address findings, verify, commit, and rerun until the
latest valid output is exactly:

```text
RESULT: clean
FINDINGS:
- none
```

- [ ] **Step 3: Open the PR against `main`**

Push the reviewed issue branch and open a non-draft PR with `Closes #7`, design
summary, race model, dependency-scope proof, fixture/ABI/docs evidence, full
gates, independent review, and Cursor verdict. Do not enable auto-merge yet.

- [ ] **Step 4: Wait for checks and review convergence**

Confirm all required GitHub checks pass, mergeability is clean, no requested
changes or unresolved threads remain, and the remote head still equals the
reviewed commit. Only then enable squash auto-merge.

- [ ] **Step 5: Confirm merge and project completion**

Wait for the PR to merge. Confirm issue #7 is closed as completed and its
Experiment item is Done. Report any newly discovered required work as another
native child of Foundry epic #1241 before closing the workstream.

- [ ] **Step 6: Clean exact local and remote state**

Remove the issue-7 worktree, delete the local issue-7 branch, prune the deleted
remote branch, fast-forward the primary Foundry-Java checkout to `origin/main`,
and verify no issue branch/worktree remains. Re-verify Foundry-Android is
unchanged and update `.epic-1241-status.md` with merge and cleanup evidence.
