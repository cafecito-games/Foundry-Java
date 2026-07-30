# Android integration

Foundry-Java is an opt-in Android binding. An ordinary Foundry Android export does not depend on it.
An application that contains Java extensions applies the Foundry-Java Gradle plugin, includes the
Android binding AAR exactly once, and exposes descriptor-bearing extension modules through its
Android runtime dependency graph.

## Application setup

Use Java 17 and apply the Android application and Foundry-Java plugins:

```kotlin
plugins {
    id("com.android.application")
    id("games.cafecito.foundry.java") version "<foundry-java-version>"
}

dependencies {
    implementation("games.cafecito.foundry:foundry-java-android:<foundry-java-version>")

    implementation(project(":gameplay"))
}
```

The plugin uses the Android application variants' runtime dependency graphs as the primary source
of descriptors and the binding payload. Normal `implementation` and transitive runtime dependencies
therefore participate automatically.

`foundryJavaModules` is a resolvable, transitive supplemental input for descriptor artifacts that
are intentionally outside an Android variant's runtime graph:

```kotlin
dependencies {
    "foundryJavaModules"(project(":detached-extension-descriptors"))
}
```

Do not duplicate ordinary Android runtime dependencies on `foundryJavaModules`, and do not add
arbitrary application class directories.

Each extension module applies the annotation processor and gives the processor one stable module
identity:

```kotlin
dependencies {
    compileOnly("games.cafecito.foundry:foundry-java-annotations:<foundry-java-version>")
    implementation("games.cafecito.foundry:foundry-java-runtime:<foundry-java-version>")
    annotationProcessor("games.cafecito.foundry:foundry-java-processor:<foundry-java-version>")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Afoundry.module=gameplay")
}
```

The `foundry.module` value is a lowercase, hyphen-separated artifact identity such as `gameplay` or
`world-events`. Do not derive it from a build directory or application ID. Changing it changes the
generated registry and descriptor identities. See [Java extension authoring](java-authoring.md) for
the declaration contract.

By default every application variant requests `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.
Limit the binding payload deliberately through the plugin extension:

```kotlin
foundryJava {
    requestedAbis.set(setOf("arm64-v8a", "x86_64"))
}
```

`requestedAbis` is a `SetProperty<String>`. Use Android ABI names, not Foundry feature names.

## Generated application outputs

For each Android application variant, the plugin registers
`generate<Variant>FoundryJavaRegistry`, such as `generateDebugFoundryJavaRegistry` and
`generateReleaseFoundryJavaRegistry`. The corresponding variant build depends on that task. It
reads only exact descriptor entries at:

```text
META-INF/foundry-java/modules/<module>.descriptor
```

With one or more modules, the task writes:

- `foundry_java/registry-index-v2.txt` relative to
  `build/generated/assets/generate<Variant>FoundryJavaRegistry/`;
- `FoundryJava.foundryextension` at the root of that generated assets directory; and
- `games/cafecito/foundry/generated/FoundryGeneratedBootstrap.java` relative to
  `build/generated/java/generate<Variant>FoundryJavaRegistry/`.

The packaged asset paths are `assets/foundry_java/registry-index-v2.txt` and
`assets/FoundryJava.foundryextension`. The configuration is copied byte-for-byte from the single
validated binding AAR; the plugin never synthesizes or republishes it. The index records the common
API, generator, runtime, and bridge provenance followed by modules sorted by stable module and
registry identity. The generated Java source directly references each registry's `PROVIDER` field
and exposes the immutable result through `FoundryGeneratedBootstrap.bootstrap()`.

Dependency declaration order, filesystem order, and archive entry order cannot change the generated
files. An application with zero descriptors produces no registry asset, copied configuration, or
generated bootstrap class.

When the plugin is applied outside an Android application, the explicit supplemental workflow keeps
the task `generateFoundryJavaRegistry` and the non-variant paths
`build/generated/foundryJava/assets` and `build/generated/foundryJava/java`.

## Descriptor format 2

The processor emits one descriptor per module. Its first seven fields are required in this exact
order:

```text
format=2
module=gameplay
registry=games.cafecito.foundry.generated.gameplay.GameplayRegistry
api_sha256=<64 lowercase hexadecimal characters>
generator_version=<positive integer>
runtime_contract_version=<positive integer>
bridge_contract_version=<positive integer>
```

Sorted `class`, `method`, `override`, `property`, and `signal` entries follow those headers. The
registry implements `FoundryModuleProvider`, returns a matching immutable
`FoundryModuleDescriptor`, and invokes generated trampolines through typed calls.

Member signatures are transport signatures. Java enum declarations remain enum-typed, but enum
method and override returns and parameters, property types, and signal parameters are serialized as
primitive `long`; signal returns remain `void`. For example, Java
`MovementMode convert(EngineMode, MovementMode)` is recorded as `long(long,long)`, and an
enum-bearing signal is recorded as `void(long)`. Integral `@FoundryConstant` entries retain their
declared type.

User-authored callback enums require a unique explicit signed `long` on every constant through
`@FoundryEnumValue`. Generator-owned engine enums use their generated `value()` and
`fromValue(long)` methods. Ordinals, names, runtime reflection, and descriptor-side class lookup are
not conversion fallbacks. Primitive enum transport cannot represent null/NIL: null, a wrong boxed
transport type, an unknown signed value, or a null/unmapped outbound enum fails deterministically
at the generated trampoline boundary. See [Java extension authoring](java-authoring.md#enum-callbacks)
for the declaration contract.

The plugin validates the complete graph before writing outputs. All modules must agree on
`api_sha256`, `generator_version`, `runtime_contract_version`, and `bridge_contract_version`.
Module and registry identities must be unique. Format 1, unknown or reordered headers, malformed
values, and best-effort fallback are rejected.

## Fixed Android payload

`foundry-java-android` is the single binding AAR. It contains:

- `FoundryJava.foundryextension`;
- `libfoundry_java.so` for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`;
- `FoundryJavaInitializer`;
- an API dependency on the separate, Android-free Java runtime; and
- narrow consumer shrinker rules.

The fixed configuration is stored at the root of the AAR's `classes.jar`. Native payloads use the
standard AAR paths `jni/<android-abi>/libfoundry_java.so`, and the consumer rules are packaged as
`proguard.txt`.

The fixed configuration is:

```ini
[configuration]

entry_symbol = "foundry_java_library_init"
compatibility_minimum = "0.1.0"

[libraries]

android.arm32 = "libfoundry_java.so"
android.arm64 = "libfoundry_java.so"
android.x86_32 = "libfoundry_java.so"
android.x86_64 = "libfoundry_java.so"
```

The application or Foundry exporter supplies the requested Android ABI set through
`foundryJava.requestedAbis`. Packaging selects only that subset, while validation requires every
requested ABI to exist in the one bridge AAR. A missing ABI fails before assembly and names both the
ABI and artifact. Duplicate bridge AARs or duplicate fixed configurations also fail; the plugin
never chooses one by dependency order.

The binding AAR does not contain, link, load, or redistribute `libfoundry_android.so`. The Foundry
application host supplies its own engine library independently.

## Typed initialization

Production applications use the plugin-generated `FoundryGeneratedStartupProvider`. Android creates
that provider before the application object or an activity. Provider priming runs before
`Application.onCreate()` and creates no binding context. It validates the typed generated
bootstrap, captures the application class loader, installs the coordinator callback target, and
loads `libfoundry_java.so`.

`foundry_java_library_init` and the native CORE callback create the production context. The public
FoundryExtension entry first resolves the complete interface table; CORE then creates the native
context and production engine, registers the Java binding context, and begins generated
registration. Registration follows the exact deterministic topological order. A provider never
constructs an engine, creates a context, or registers a descriptor.

Direct `FoundryJavaInitializer.initialize` is a compatibility and test entry only. It validates the
same generated handoff and provenance when a controlled host must exercise the bridge without
Android provider startup, but production applications must not call it from `Application` or
activity code.

The one-argument `initialize(FoundryBridgeCallbacks)` overload is the empty-registry bridge entry
used when no generated module bootstrap is present. Registration itself always uses typed provider
and trampoline calls; initialization does not discover classes.

Teardown unregisters in exact reverse topological order. It blocks new callbacks, drains admitted
callbacks, deinitializes completed levels, invalidates the binding context, releases instance and
class references, and only then clears native tables. Bridge shutdown is process-terminal; restart
requires a fresh Android process.
`FoundryBridgeCallbacks.terminalCleanupComplete` must confirm the exact terminal context before
native teardown begins; otherwise the bridge retains the context and cleanup ownership for retry.

## Structured bootstrap logs

Every bootstrap diagnostic is one line beginning with:

```text
FOUNDRY_JAVA_BOOTSTRAP <JSON object>
```

The remainder is deterministic JSON with these fields:

```json
{
  "api_sha256": "<sha256>",
  "generator_version": "<version>",
  "runtime_contract_version": "<version>",
  "bridge_contract_version": "<version>",
  "registry_modules": ["gameplay"],
  "initialization_level": 2,
  "failure_phase": "none"
}
```

`registry_modules` is sorted. `initialization_level` is the Foundry initialization level reported
by the callback; `-1` means the event is not tied to one initialization callback.
`failure_phase="none"` is success. Other phases identify the boundary that failed, including
`native_library_load`, `native_bootstrap`, `native_bootstrap_exception`,
`initialization_callback`, `initialization_exception`, `deinitialization_exception`,
`callback_exception`, `invalidation_exception`, and `terminal_cleanup_query_exception`.

## Minified release builds

The Android AAR's consumer rules retain only the initializer's native methods, the fixed generated
bootstrap entry, `FoundryModuleProvider.descriptor()`, and `FoundryBridgeCallbacks` methods. Each
processor output contributes exact registry and trampoline rules under:

```text
META-INF/proguard/foundry-java-<module>.pro
```

Do not add a package-wide `-keep` rule. A minified release must preserve the generated entry points
while allowing unrelated application and binding implementation code to shrink normally.

## Troubleshooting

The applicable `generate<Variant>FoundryJavaRegistry` task, or the non-Android
`generateFoundryJavaRegistry` task, reports descriptor parse errors with both the artifact and exact
descriptor path. Whole-graph failures begin with `Invalid Foundry dependency graph:` and use
stable-sorted detail lines so the same invalid graph produces the same diagnostic.

- **No registry index:** Confirm that the extension artifact is on the Android variant runtime
  graph (or the supplemental `foundryJavaModules` input), contains
  `META-INF/foundry-java/modules/<module>.descriptor`, and was compiled with
  `-Afoundry.module=<module>`. Zero descriptors intentionally produce no marker.
- **Mixed provenance:** Rebuild all extension modules with one aligned Foundry-Java release. Do not
  edit a generated descriptor to hide an API, generator, runtime, or bridge mismatch.
- **Duplicate module or registry:** Give each consumer module a unique stable `foundry.module`
  value and remove duplicate artifacts from the dependency graph.
- **Duplicate bridge or configuration:** Depend on `foundry-java-android` once and remove an older
  transitive or manually copied AAR/configuration.
- **Missing ABI:** Select an ABI present in the binding release or use a release that contains the
  requested ABI. The error identifies the deficient artifact.
- **Native bootstrap failure:** Filter logs for `FOUNDRY_JAVA_BOOTSTRAP`, then compare the four
  provenance values and inspect `failure_phase`.
- **Minified-only failure:** Remove broad or handwritten discovery rules and verify that generated
  registry/trampoline rules and the AAR consumer rules reached R8.

## Prohibited legacy discovery

Foundry-Java has one registration protocol: format-2 descriptors, generated providers, the sorted
index, and direct typed bootstrap calls. The following are unsupported and must not be introduced:

- manifest-v1 keys such as `games.cafecito.foundry.plugin.v1.*`;
- `FoundryPlugin` or plugin-registry discovery;
- Android manifest scanning;
- arbitrary classpath or application-class scanning;
- `Class.forName`, reflective member enumeration, or reflection-based registration; and
- format-1 descriptors, aliases, migration readers, or compatibility fallback.

These mechanisms are not troubleshooting options. A missing or incompatible generated contract is
a build error.
