# Architecture

Foundry-Java is split by boundary rather than feature. `foundry-java-api-model` and
`foundry-java-annotations` define platform-neutral Java contracts. `foundry-java-runtime` invokes
only `FoundryExtension`; it contains no Android host classes. Android APIs, the JNI bridge, and
lifecycle adaptation are isolated in `foundry-java-android`.

The generator and annotation processor produce Java-facing code. The Kotlin module consumes the Java
runtime and is optional for users. The Gradle plugin owns build-time dependency aggregation but does
not discover application classes. No module packages, links, loads, or redistributes Foundry's
Android host library, `libfoundry_android.so`.

## Compile-time registration

Each Java extension module runs the JSR-269 processor with a stable `foundry.module` identity. The
processor validates declarations and emits:

- typed trampolines for extension constructors and members;
- one `FoundryModuleProvider` registry;
- one immutable format-2 descriptor under
  `META-INF/foundry-java/modules/<module>.descriptor`; and
- narrow registry/trampoline shrinker rules.

Descriptor provenance includes the generated API SHA-256 plus generator, runtime, and bridge
contract versions. The registry carries the same values in typed runtime descriptors. This binds
source validation, generated code, build aggregation, Java runtime, and native bridge to one
auditable contract.

The application applies `games.cafecito.foundry.java`. Android application variants' runtime
dependency graphs are the primary source of descriptor-bearing artifacts and the fixed binding
payload. The transitive `foundryJavaModules` configuration accepts supplemental descriptors outside
those variant graphs. The plugin reads only exact descriptor paths, rejects malformed or
incompatible graphs, sorts modules by stable identity, and emits:

```text
assets/foundry_java/registry-index-v2.txt
games.cafecito.foundry.generated.FoundryGeneratedBootstrap
```

The bootstrap source directly references each generated registry's `PROVIDER`. It constructs one
immutable `FoundryRegistryBootstrap`; it does not resolve provider names at runtime. Reordering
dependencies cannot change output bytes, and zero modules produce no opt-in marker.
AGP owns variant outputs below `build/generated/assets/generate<Variant>FoundryJavaRegistry/` and
`build/generated/java/generate<Variant>FoundryJavaRegistry/`. A non-Android supplemental invocation
retains the unscoped `generateFoundryJavaRegistry` task and `build/generated/foundryJava/` paths.

## FoundryExtension bridge

`foundry-java-android` packages `libfoundry_java.so` for `armeabi-v7a`, `arm64-v8a`, `x86`, and
`x86_64`. The bridge has one public FoundryExtension entry, `foundry_java_library_init`, and calls
the engine exclusively through `api/current/foundry_extension_interface.h`. It does not import
private engine headers or Android-host JNI symbols.

The JNI surface is versioned independently from the Java runtime contract. Bootstrap succeeds only
when the generated API SHA-256 and the generator, runtime, and bridge contract versions all match
the values compiled into the bridge. A mismatch leaves the bridge inactive instead of exposing a
partially initialized interface.

The same AAR owns the fixed `FoundryJava.foundryextension`, its narrow consumer rules, and
`FoundryJavaInitializer`. The initializer accepts a typed `FoundryRegistryBootstrap` and
`FoundryBridgeCallbacks`, loads the already packaged bridge, and reports deterministic JSON
diagnostics for initialization and callback boundaries. The application/export build includes
exactly one AAR and configuration, then selects the bridge payload for
`foundryJava.requestedAbis`. That `SetProperty<String>` defaults to all four supported Android ABIs.

## Discovery and shrinker boundary

The format-2 descriptor and direct provider bootstrap are the only registration path. Foundry-Java
does not support manifest-v1 metadata, `FoundryPlugin` discovery, project-class or classpath
scanning, Android manifest scanning, format-1 fallback, `Class.forName`, or reflective member
enumeration.

Minified releases retain only fixed JNI/callback bootstrap methods and processor-generated
registry/trampoline entry points. Broad package keep rules would hide missing generated contracts
and unnecessarily preserve implementation code, so they are prohibited.

For consumer setup, fixed paths, ABI behavior, structured logs, and diagnostics, see
[Android integration](android-integration.md).
