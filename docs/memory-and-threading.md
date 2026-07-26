# Runtime memory and threading

Foundry-Java uses opaque, context-bound handles. Public Java code never receives a process address
or transfers ownership directly. The 14 native structures have distinct generated wrapper types,
and every other ABI pointer family has a distinct generated marker type. A handle records its
binding context and concrete type token; generated calls reject cross-context and wrong-type values
before invoking the bridge. A zero bridge handle is the explicit null pointer representation.
The FoundryExtension bridge is the only component allowed to translate handles to native values.

The separate native/Android bridge workstream owns JNI transport, native structure layouts,
reference-transfer mechanics, library loading, and Android initialization. The host-neutral runtime
described here does not implement or infer any of those details.

## Object identity and ownership

A live binding context returns one wrapper for a given object handle and generated wrapper class.
The wrapper cache is context-local: equal numeric handles from different context generations never
alias.

Generated object wrappers choose ownership from the accepted class metadata. Classes with
`is_refcounted` retain and release; other classes are borrowed. Generated bind and singleton APIs do
not ask callers to select ownership. Only classes marked `is_instantiable` expose `create()`.

Borrowed wrappers do not retain or release engine ownership. Native invalidation permanently
tombstones the handle for that context generation and every later operation throws
`FoundryObjectDisposedException`.

Reference-counted wrappers acquire exactly one retain before publication. `close()` releases only
that Java wrapper's lease and removes it from the live-wrapper cache; it does not claim that the
engine object was destroyed and does not tombstone its handle. If the engine still reports the
object as valid, a later bind may create a new wrapper and lease. Native invalidation, wrapper
release, and context shutdown are separate transitions.

Explicit close, context shutdown, native invalidation, and the `Cleaner` fallback share an
idempotent lease, so each acquired retain has at most one matching release. Use try-with-resources
or call `close()` explicitly; the cleaner is a leak-safety fallback, not a prompt lifecycle
mechanism.

## Values and collections

`Variant` equality is Foundry-type-strict. Integer and floating-point values never compare equal
across types. Floating-point equality and hashing normalize NaNs and signed zero recursively across
scalar values, vector/color/quaternion/plane composites, and packed floating-point arrays.

Foundry arrays and dictionaries are mutable reference values. Their copy constructors create an
alias; `duplicate()` creates independent shallow storage and `duplicateDeep()` also detaches nested
arrays and dictionaries. Typed collection codecs validate every insertion path. Nil is accepted
only by a codec that declares Nil support.

Packed arrays copy Java arrays on construction and on every `toArray()` call. Mutating an input or
returned Java array therefore cannot mutate the packed value.

## Calls and callbacks

Engine calls preserve the full method identity, call-error category, bad argument index, and
expected type in `FoundryCallException`.

Generated signals expose typed arity-specific adapters (`Of0` through `Of5`) backed by explicit
`VariantCodec` instances. Signal emission uses an insertion-ordered listener snapshot. Connecting
or disconnecting while a signal is being emitted affects the next emission, and same-thread
reentrant emission is supported.

Bridge callbacks run synchronously on the calling thread and permit same-thread reentrancy.
Deinitialization disables new callbacks before invalidating the context or releasing retained
objects, and waits for an in-flight callback dispatch to leave the protected Java boundary.
Every `Throwable`, including failures from argument conversion or user callbacks, is contained and
reported to the bridge; none is allowed to unwind across JNI or the C ABI.

Public runtime collections and lifecycle objects are safe for the concurrency guarantees described
above. General mutation of a `FoundryArray`, `FoundryDictionary`, packed array, or signal connection
set is not an implicit substitute for application-level synchronization.
