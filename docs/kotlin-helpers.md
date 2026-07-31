# Kotlin helpers

`foundry-java-kotlin` is an optional authoring layer over the public
Foundry-Java runtime. It adds Kotlin syntax conveniences; it does not add native
entry points, change registration, or replace the Java runtime.

Java modules do not depend on this artifact. A Java-only extension can use
`foundry-java-runtime` without Kotlin, kotlinx-coroutines, or any Android host
library.

## Dependency

The module targets Java 17 and Kotlin 2.4.10:

```kotlin
dependencies {
    implementation("games.cafecito.foundry:foundry-java-kotlin:0.1.0-SNAPSHOT")
}
```

The artifact exposes `foundry-java-runtime`. It uses
`kotlinx-coroutines-core:1.9.0` as a runtime implementation dependency, not as
part of its compile API.

## Bind and register object wrappers

Reified helpers remove the repeated class literal. They still require an
explicit factory and delegate wrapper validation to `FoundryBindingContext`:

```kotlin
import games.cafecito.foundry.kotlin.bind
import games.cafecito.foundry.kotlin.registerObjectType
import games.cafecito.foundry.runtime.FoundryBindingContext
import games.cafecito.foundry.runtime.FoundryObject
import games.cafecito.foundry.runtime.ObjectLease
import games.cafecito.foundry.runtime.ObjectOwnership

class CoffeeNode(
    context: FoundryBindingContext,
    lease: ObjectLease,
) : FoundryObject(context, lease)

context.registerObjectType<CoffeeNode>("CoffeeNode", ::CoffeeNode)

val coffee =
    context.bind<CoffeeNode>(
        objectHandle = handle,
        ownership = ObjectOwnership.BORROWED,
        factory = ::CoffeeNode,
    )
```

The helpers never inspect constructors or bypass the context's cached-wrapper,
ownership, or type-compatibility rules.

## Variants and codecs

Use reified helpers for canonical, non-null Variant types:

```kotlin
import games.cafecito.foundry.kotlin.decode
import games.cafecito.foundry.kotlin.nullable
import games.cafecito.foundry.kotlin.toVariant
import games.cafecito.foundry.kotlin.variantCodec
import games.cafecito.foundry.types.Variant
import games.cafecito.foundry.types.VariantCodec

val encoded: Variant = "coffee".toVariant()
val decoded: String = encoded.decode()
val strings: VariantCodec<String> = variantCodec()
val optionalStrings: VariantCodec<String?> = VariantCodec.STRING.nullable()
```

Canonical reified mappings include:

- `Boolean`, `Long`, `Double`, `String`, and `Variant`;
- Foundry math/value classes;
- `FoundryObject` and concrete wrapper subclasses;
- `FoundryCallable` and `FoundrySignal`;
- every packed-array class.

Foundry integers and floats map to `Long` and `Double`. The helpers do not
silently narrow them to Kotlin `Int` or `Float`. Unsupported types fail with
the requested class name. A concrete object-wrapper codec validates the decoded
wrapper subclass and rejects a different wrapper type.

Nullability is explicit because Kotlin nullability is erased. Generic
`FoundryArray<T>` and `FoundryDictionary<K, V>` values also require explicit
element codecs; a reified class alone cannot recover them.

## Property delegates

Delegates invoke only the supplied accessors:

```kotlin
import games.cafecito.foundry.kotlin.foundryProperty
import games.cafecito.foundry.kotlin.foundryReadOnlyProperty

var displayName by foundryProperty(
    getter = coffee::getDisplayName,
    setter = coffee::setDisplayName,
)

val objectId by foundryReadOnlyProperty(coffee::objectHandle)
```

They do not derive method identities from property names, cache native values,
or use reflection.

## Signal listeners

`listen` returns the Java `FoundrySignal.Connection`. Closing it disconnects
the listener:

```kotlin
import games.cafecito.foundry.kotlin.listen

val connection =
    rawSignal.listen { arguments ->
        println("received ${arguments.size} Variant values")
    }

connection.close()
```

Typed signals accept ordinary Kotlin lambdas at every supported arity:

```kotlin
val connection =
    typedSignal.listen { name: String, count: Long ->
        println("$name: $count")
    }
```

Raw listeners receive an immutable `List<Variant>`. Typed listeners preserve
their declared types. Listener ordering, snapshot emission, exceptions, and
threading are the Java signal's behavior: callbacks run on the thread that
emits the signal, with no helper-owned dispatcher hop.

## Await one signal

Every await is bound to a `FoundryObject` owner:

```kotlin
import games.cafecito.foundry.kotlin.SignalArgs2
import games.cafecito.foundry.kotlin.await

suspend fun nextServing(
    signal: FoundryTypedSignal.Of2<String, Long>,
    owner: CoffeeNode,
): SignalArgs2<String, Long> = signal.await(owner)
```

Return shapes are stable:

- raw `FoundrySignal`: `List<Variant>`;
- typed arity 0: `Unit`;
- typed arity 1: the value itself;
- typed arities 2 through 5: `SignalArgs2` through `SignalArgs5`, with
  `first`, `second`, `third`, `fourth`, and `fifth` properties.

The helper disconnects after signal delivery or coroutine cancellation. If the
owner is closed, invalidated, or loses its binding context first, awaiting
fails with the Java runtime's `FoundryObjectDisposedException`.

Signal, cancellation, and invalidation race through one atomic terminal
decision. Connection and invalidation registrations published after another
path wins immediately close themselves. The helper never polls lifecycle
state, sleeps, launches a watcher coroutine, or chooses a dispatcher.
Registration cleanup and the initial continuation resume happen on the signal,
invalidation, or cancellation caller thread; normal coroutine dispatch rules
still control where the suspended coroutine continues.

## Collections

Generic arrays and dictionaries require their Java codecs:

```kotlin
import games.cafecito.foundry.kotlin.toFoundryArray
import games.cafecito.foundry.kotlin.toFoundryDictionary
import games.cafecito.foundry.kotlin.toKotlinList
import games.cafecito.foundry.kotlin.toKotlinMap

val array =
    listOf(1L, 2L)
        .toFoundryArray(VariantCodec.INTEGER)

val dictionary =
    linkedMapOf("coffee" to 2L)
        .toFoundryDictionary(VariantCodec.STRING, VariantCodec.INTEGER)

val values: List<Long> = array.toKotlinList()
val entries: Map<String, Long> = dictionary.toKotlinMap()
```

Direct copy conversions are available for:

- `ByteArray` and `PackedByteArray`;
- `IntArray` and `PackedInt32Array`;
- `LongArray` and `PackedInt64Array`;
- `FloatArray` and `PackedFloat32Array`;
- `DoubleArray` and `PackedFloat64Array`;
- string arrays/iterables and `PackedStringArray`;
- `Vector2`, `Vector3`, `Vector4`, and `Color` arrays/iterables and their
  packed classes.

For example:

```kotlin
import games.cafecito.foundry.kotlin.toPackedInt32Array
import games.cafecito.foundry.kotlin.toIntArray

val packed = intArrayOf(1, 2, 3).toPackedInt32Array()
val copied: IntArray = packed.toIntArray()
```

Conversions use the Java constructors and accessors, so Java validation and
copying semantics remain authoritative.

## Call DSL

The small call builder preserves argument order and delegates exactly once to
`FoundryBindingContext.call`:

```kotlin
import games.cafecito.foundry.kotlin.call

val result: String =
    context.call(
        objectHandle = coffee.objectHandle(),
        methodIdentity = "CoffeeNode/serve",
        resultCodec = VariantCodec.STRING,
    ) {
        value(2L, VariantCodec.INTEGER)
        variant("milk".toVariant())
    }
```

Use the overload without `resultCodec` to receive the raw `Variant`. Codec and
Java call exceptions pass through unchanged. The DSL does not infer method
names or overloads.

## Registration boundary

Kotlin registration is a thin builder over the public generated
module-provider and registry-bootstrap API:

```kotlin
import games.cafecito.foundry.kotlin.foundryRegistry

val bootstrap =
    foundryRegistry {
        provider(CoffeeRegistry.PROVIDER)
        provider(PastryRegistry.PROVIDER)
    }
```

Providers remain explicit and reflection-free. The Java
`FoundryRegistryBootstrap` performs deterministic sorting, duplicate checks,
and provenance validation. The Kotlin artifact never scans classes, service
metadata, manifests, or descriptors and never defines another registry
protocol.

## Deliberate boundaries

The Kotlin helper artifact does not:

- define JNI or FoundryExtension methods;
- generate wrappers or registration providers;
- depend on Android or package `libfoundry_android.so`;
- reflect over constructors, properties, classes, or manifests;
- change Java signal, collection, call, ownership, or lifecycle semantics.

`Foundry-Android` is not part of this artifact.
