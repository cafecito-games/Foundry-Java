# Foundry-Java Production Bootstrap and Transport Design

**Status:** Approved

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

## Approaches

### Selected: generated application provider over a stable AAR base

The Gradle plugin generates one application-owned `ContentProvider` subclass.
The subclass directly references `FoundryGeneratedBootstrap.bootstrap()` and
passes that typed value to a stable AAR-owned provider hook. The AAR manifest
uses per-variant placeholders for the generated provider class and authority.
There is no class name lookup, registry scan, manifest metadata discovery, or
reflection.

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

The AAR manifest declares the placeholder provider with:

- `android:exported="false"`;
- no custom `android:process`;
- a fixed deterministic `android:initOrder`;
- the authority placeholder above.

Zero-module and non-opted variants emit no startup provider or manifest entry.
The registry task therefore emits a small variant manifest directory alongside
generated Java/assets, and the plugin wires it through the public AGP Variant
API. Custom application IDs produce distinct authorities. A duplicate
authority or incompatible pre-existing placeholder fails the build with the
variant and authority in the diagnostic.

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

The provider installs a process `FoundryJavaCoordinator` as the JNI callback
target. Native CORE calls the coordinator with a nonzero context handle after
the interface table is complete. The coordinator constructs
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
- class, method, property, signal, and virtual registration plus class
  unregistration.

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
production engine classes. The runtime public API baseline changes only for the
two explicit `FoundryEngine` registration methods. Consumer rules keep the
stable JNI entry class, provider base, generated provider/bootstrap, typed
providers, callback interface, and direct generated access points narrowly.

Repository/AAR/publication contracts require:

- no `libfoundry_android.so`;
- exactly four `libfoundry_java.so` ABIs;
- one fixed FoundryExtension configuration;
- opt-in startup manifest/provider output only for module-bearing application
  variants;
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
