# Foundry-Java Gradle Plugin and Android Bootstrap Plan

> **For Codex:** Use `superpowers:executing-plans` and
> `superpowers:test-driven-development`. Keep Foundry-Android read-only. Do not
> edit the #26-owned lifecycle types or tests.

**Goal:** Implement issue #6's Java-first, reflection-free Android registration
pipeline: descriptor format 2 with full provenance, runtime-owned generated
providers, strict deterministic dependency aggregation, one generated registry
index/bootstrap source, and one fixed bridge/configuration payload.

**Architecture:** The annotation processor emits one literal format-2
descriptor and one generated provider per extension module. Public,
Android-free runtime interfaces and immutable descriptor records define the
handshake. The Gradle plugin resolves descriptor-bearing dependency artifacts
through a dedicated artifact view, validates every descriptor and payload,
sorts them by stable identity, and generates both
`assets/foundry_java/registry-index-v2.txt` and Java source containing direct
provider references. The Android AAR owns the fixed
`FoundryJava.foundryextension`, narrow consumer rules, bridge ABI payload, and
initializer. Runtime registration uses direct typed calls only—no reflection,
classpath scanning, manifest discovery, or legacy format-1 compatibility.

**Toolchain:** Java 17, Gradle 8.11.1, AGP 8.10, JUnit 5, Gradle TestKit,
Android library packaging, CMake/JNI.

---

## Task 1: Freeze descriptor-v2 and provider contracts

**Files:**

- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryExtensionAccess.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryMemberDescriptor.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryClassDescriptor.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryModuleDescriptor.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryModuleProvider.java`
- Create: `foundry-java-runtime/src/main/java/games/cafecito/foundry/runtime/FoundryRegistryBootstrap.java`
- Create: `foundry-java-runtime/src/test/java/games/cafecito/foundry/runtime/GeneratedProviderContractTest.java`
- Modify: `foundry-java-runtime/api/foundry-java-runtime.api`

1. Write failing tests for immutable descriptor collections, exact provenance,
   stable provider order, duplicate-module/registry rejection, and direct
   registration without reflective APIs.
2. Run the focused runtime test and capture RED.
3. Implement the smallest public, host-neutral typed contract. Keep registry
   state immutable and validate before any provider registration side effect.
4. Add only WS9-owned sorted entries to the public API baseline and run
   `:foundry-java-runtime:check`.

## Task 2: Upgrade processor output to format 2

**Files:**

- Modify: `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/ModuleEmitter.java`
- Modify: `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/FoundryModuleGenerationTest.java`
- Modify: `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/ProcessorArtifactContractTest.java`
- Modify: `foundry-java-processor/src/test/resources/golden/DemoModuleRegistry.golden`
- Modify: `foundry-java-processor/src/test/resources/golden/demo-module.descriptor`
- Modify: `foundry-java-processor/src/test/resources/golden/foundry-java-demo-module.pro`

1. Change goldens/tests first to require exactly these header fields in stable
   order: `format=2`, `module`, `registry`, `api_sha256`,
   `generator_version`, `runtime_contract_version`, and
   `bridge_contract_version`.
2. Require the generated registry to implement `FoundryModuleProvider` and
   return runtime-owned descriptor values. Preserve direct trampoline calls.
3. Run focused processor tests and capture RED.
4. Implement literal provenance extraction from public runtime compile-time
   constants, deterministic output, and a narrow provider keep rule.
5. Run processor tests twice with reordered sources and compare output bytes.

## Task 3: Implement strict descriptor and payload validation

**Files:**

- Create: `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/FoundryDescriptor.java`
- Create: `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/DescriptorValidator.java`
- Create: `foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/DescriptorValidatorTest.java`

1. Write table-driven failing tests for valid descriptors and rejection of
   format 1, unknown/duplicate/missing keys, malformed values, duplicate module
   or registry identities, mixed API/generator/runtime/bridge contracts,
   duplicate bridge/config payloads, and missing requested ABIs.
2. Prove errors include the artifact and conflicting field/value.
3. Implement a strict line parser and whole-graph validator with stable sorted
   diagnostics and no best-effort fallback.
4. Run the focused validator suite.

## Task 4: Generate the deterministic index and direct bootstrap

**Files:**

- Create: `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/RegistryIndexTask.java`
- Create: `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/BootstrapSourceTask.java`
- Modify: `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/FoundryJavaPlugin.java`
- Create: `foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java`

1. Write TestKit RED fixtures for zero, one, many, transitive, and dependency-
   reordered modules. Verify zero modules yields no opt-in marker; nonzero
   modules yield exactly one byte-identical sorted index and bootstrap source.
2. Add failure fixtures for every Task 3 incompatibility and duplicated
   bridge/config/assets.
3. Resolve descriptors from the Android runtime classpath through Gradle
   artifact APIs. Generate direct provider references; do not load classes or
   inspect manifests.
4. Wire generated assets and Java into Android application variants lazily.
   Ensure task inputs/outputs and providers are configuration-cache safe.
5. Run TestKit twice and prove second configuration-cache reuse plus
   byte-for-byte reproducibility.

## Task 5: Package and exercise the fixed Android bootstrap

**Files:**

- Create: `foundry-java-android/src/main/resources/FoundryJava.foundryextension`
- Create: `foundry-java-android/src/main/consumer-rules.pro`
- Modify: `foundry-java-android/src/main/java/games/cafecito/foundry/java/FoundryJavaInitializer.java`
- Create: `foundry-java-android/src/test/java/games/cafecito/foundry/java/FoundryJavaInitializerTest.java`
- Modify: `foundry-java-android/src/androidTest/java/games/cafecito/foundry/java/FoundryJavaInstrumentation.java`
- Modify: `build.gradle.kts`

1. Write RED archive/initializer tests requiring one fixed configuration,
   exactly four `libfoundry_java.so` payloads and no
   `libfoundry_android.so`, narrow provider/bootstrap/callback keep rules, and
   a typed initializer handshake.
2. Add deterministic machine-readable diagnostics covering API hash, generator,
   runtime and bridge versions, module list, initialization level, and failure
   phase.
3. Implement the fixed payload and initializer without Android host classes,
   reflection, or a second discovery mechanism.
4. Exercise debug and release AARs, selected ABI inspection, and the device-side
   bridge acceptance path where the environment supports it.

## Task 6: Downstream, minification, and documentation gates

**Files:**

- Extend: `foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java`
- Create: `docs/android-integration.md`
- Modify: `docs/java-authoring.md`
- Modify: `docs/architecture.md`

1. Add TestKit/downstream fixtures for supported Gradle/AGP, Java-only
   authoring, debug, minified release/R8, default/custom application IDs,
   requested ABI subsets, dependency locks, and clean Maven consumption.
2. Document plugin application, processor/module options, fixed paths,
   descriptor format 2, troubleshooting diagnostics, and forbidden legacy
   discovery.
3. Run focused plugin/processor/runtime/Android tests, two clean downstream
   builds, configuration-cache verifier, lock verification, and
   `./gradlew --no-daemon clean check`.

## Task 7: Review, rebase, publish, and close

1. Commit focused increments and keep the tracked worktree clean between review
   rounds.
2. After #26 merges, fetch and rebase onto current `origin/main`. Preserve both
   WS9 and #26 API-baseline entries, then rerun focused and full gates.
3. Obtain independent exact-head review against resolved `origin/main`; address
   all validated findings using the receiving-review workflow.
4. Run Cursor review rounds until the latest valid verdict is exactly
   `RESULT: clean`.
5. Push `issue-6`, open a PR targeting `main`, wait for checks and review
   convergence, then enable squash auto-merge.
6. Confirm the PR merged, issue #6 is Closed/Completed, and its Experiment item
   is Done. Update the epic ledger after every milestone.
7. Remove the clean issue worktree and local/remote issue branches. Reconfirm
   Foundry-Android remains unchanged.
