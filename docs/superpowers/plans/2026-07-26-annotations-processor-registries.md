# Annotations, Processor, and Registries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a dependency-light public annotation API and a JSR-269 processor that validates
Foundry extension declarations and emits deterministic, reflection-free registration artifacts.

**Architecture:** Public declaration annotations remain in the Android-free annotations artifact.
The aggregating JSR-269 processor builds immutable in-memory declaration models, validates them
against javac's type model, emits one direct-call trampoline per extension class, and emits one
stable registry, descriptor, and narrow keep-rule resource per consumer module. Consumer modules
identify themselves with the required `-Afoundry.module=<stable-name>` processor option.

**Tech Stack:** Java 17 annotations and records, JSR-269, `javax.tools.JavaCompiler`, JUnit 5,
Gradle 8.11.1, Android Gradle Plugin, Spotless, and Javadoc.

---

### Task 1: Freeze the public annotation contract

**Files:**

- Create: `foundry-java-annotations/src/main/java/games/cafecito/foundry/annotations/FoundryClass.java`
- Create: `foundry-java-annotations/src/main/java/games/cafecito/foundry/annotations/FoundryMethod.java`
- Create: `foundry-java-annotations/src/main/java/games/cafecito/foundry/annotations/FoundryProperty.java`
- Create: `foundry-java-annotations/src/main/java/games/cafecito/foundry/annotations/FoundrySignal.java`
- Create: `foundry-java-annotations/src/main/java/games/cafecito/foundry/annotations/FoundryOverride.java`
- Create: `foundry-java-annotations/src/main/java/games/cafecito/foundry/annotations/FoundryInitialization.java`
- Create: `foundry-java-annotations/src/main/java/games/cafecito/foundry/annotations/InitializationLevel.java`
- Create: `foundry-java-annotations/src/test/java/games/cafecito/foundry/annotations/FoundryAnnotationApiTest.java`

- [ ] **Step 1: Write reflection-based API tests**

Assert exact targets and retention, required `base`, optional exported names, property accessor
names, and initialization level/dependency members. Assert each annotation type is immutable Java
metadata with no Android imports or native methods.

- [ ] **Step 2: Run the annotation test and observe RED**

Run:

```bash
./gradlew :foundry-java-annotations:test --tests '*FoundryAnnotationApiTest'
```

Expected: test compilation fails because the new annotation types do not exist.

- [ ] **Step 3: Implement the minimal public annotations**

Use `RetentionPolicy.CLASS` for extension classes and initialization metadata and
`RetentionPolicy.SOURCE` for members consumed entirely by javac. Target extension/initialization
annotations at types, methods/overrides at methods, properties at fields, and signals at nested
interfaces. Model initialization levels as `CORE`, `SERVERS`, `SCENE`, and `EDITOR`.

- [ ] **Step 4: Run the annotation tests and boundary check**

Run:

```bash
./gradlew :foundry-java-annotations:test
./gradlew verifyRepositoryModel
```

Expected: both commands succeed and the annotations project has no dependencies.

### Task 2: Build a real compile-testing harness and semantic validation

**Files:**

- Create: `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/ProcessorCompilation.java`
- Create: `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/FoundryExtensionProcessorValidationTest.java`
- Modify: `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/FoundryExtensionProcessor.java`

- [ ] **Step 1: Write valid and invalid in-memory compilation fixtures**

Compile Java source strings with the system Java compiler and a real processor instance. Preserve
diagnostic source, line, column, kind, and message. Cover a valid class plus invalid non-class and
non-public/final extension declarations, mismatched bases, unsupported member types, invalid
override signatures, duplicate exported names, missing or mismatched property accessors, invalid
signal shapes, invalid initialization dependencies, and initialization cycles.

- [ ] **Step 2: Run validation tests and observe RED**

Run:

```bash
./gradlew :foundry-java-processor:test --tests '*FoundryExtensionProcessorValidationTest'
```

Expected: invalid declarations compile because the bootstrap processor does not validate them.

- [ ] **Step 3: Implement immutable declaration models and source-positioned validation**

Collect annotated extension types across rounds, read class-valued annotation members through
`AnnotationMirror`, normalize exported names, and validate with `Types`/`Elements`. Emit every error
through `Messager.printMessage(ERROR, message, element, mirror, value)` when an exact annotation
value exists, otherwise attach it to the declaring element.

- [ ] **Step 4: Run the focused processor tests**

Run:

```bash
./gradlew :foundry-java-processor:test --tests '*FoundryExtensionProcessorValidationTest'
```

Expected: all valid fixtures compile and every invalid fixture fails at its asserted source
position.

### Task 3: Generate direct trampolines

**Files:**

- Create: `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/ExtensionModel.java`
- Create: `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/ExtensionValidator.java`
- Create: `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/SourceEmitter.java`
- Create: `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/FoundryTrampolineGenerationTest.java`
- Create: `foundry-java-processor/src/test/resources/golden/SpinningCube_FoundryTrampoline.java`

- [ ] **Step 1: Write a failing golden-output test**

Compile a representative extension containing methods, a property, a signal, and a virtual
override. Assert exact generated source and compile the generated tree. Assert invocation uses
direct Java calls and contains none of `java.lang.reflect`, `Class.forName`, `getDeclaredMethod`,
`getDeclaredMethods`, or `ServiceLoader`.

- [ ] **Step 2: Run the trampoline test and observe RED**

Run:

```bash
./gradlew :foundry-java-processor:test --tests '*FoundryTrampolineGenerationTest'
```

Expected: the asserted generated source file is absent.

- [ ] **Step 3: Emit one stable trampoline per extension class**

Generate a public final class beside its extension. Provide direct constructor, method/override
dispatch, and typed property getter/setter dispatch. Dispatch is an explicit string switch over
stable exported names; primitive arguments use their boxed Java types and `void` callbacks return
`null`.

- [ ] **Step 4: Run the golden and generated-compilation tests**

Run:

```bash
./gradlew :foundry-java-processor:test --tests '*FoundryTrampolineGenerationTest'
```

Expected: exact golden comparison and generated Java compilation pass.

### Task 4: Generate deterministic module artifacts

**Files:**

- Create: `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/ModuleEmitter.java`
- Create: `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/FoundryModuleGenerationTest.java`
- Create: `foundry-java-processor/src/test/resources/golden/DemoModuleRegistry.java`
- Create: `foundry-java-processor/src/test/resources/golden/demo-module.descriptor`
- Create: `foundry-java-processor/src/test/resources/golden/foundry-java-demo-module.pro`
- Create: `foundry-java-processor/src/main/resources/META-INF/gradle/incremental.annotation.processors`

- [ ] **Step 1: Write failing registry/resource golden tests**

Compile the same declarations in normal and reversed source order and assert byte-identical output.
Assert one registry class, one immutable descriptor value, one descriptor resource, and one keep
rule resource. Assert all records and lists are immutable and stable-sorted.

- [ ] **Step 2: Run module generation tests and observe RED**

Run:

```bash
./gradlew :foundry-java-processor:test --tests '*FoundryModuleGenerationTest'
```

Expected: registry, descriptor, and keep-rule outputs are absent.

- [ ] **Step 3: Emit the registry, descriptor, and narrow keep rules**

Generate `<sanitized-module>.FoundryModuleRegistry` under
`games.cafecito.foundry.generated`, exact descriptor lines under
`META-INF/foundry-java/modules/`, and exact generated-class keeps under
`META-INF/proguard/`. Declare the processor `aggregating`; never enumerate a classpath or read
manifest metadata.

- [ ] **Step 4: Run module generation tests**

Run:

```bash
./gradlew :foundry-java-processor:test --tests '*FoundryModuleGenerationTest'
```

Expected: all golden and determinism assertions pass.

### Task 5: Prove incremental and multi-module behavior

**Files:**

- Create: `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/FoundryProcessorBuildModeTest.java`

- [ ] **Step 1: Write clean/repeat/incremental/multi-module tests**

Run the compiler into independent output trees, rerun one module after changing one extension, and
compile two modules with distinct stable names. Assert unchanged output bytes remain identical,
removed declarations disappear, changed declarations update, and each module owns exactly one
distinct registry and descriptor.

- [ ] **Step 2: Run build-mode tests and observe RED**

Run:

```bash
./gradlew :foundry-java-processor:test --tests '*FoundryProcessorBuildModeTest'
```

Expected: current aggregation output does not satisfy the asserted deterministic module contract.

- [ ] **Step 3: Fix only build-mode defects revealed by the tests**

Clear processor state per javac invocation, reject a missing/invalid `foundry.module` option, sort
by qualified extension name and exported member identity, and ensure generated files contain no
timestamps, absolute paths, hash-map iteration order, or locale-sensitive formatting.

- [ ] **Step 4: Run all processor tests twice**

Run:

```bash
./gradlew :foundry-java-processor:clean :foundry-java-processor:test
./gradlew :foundry-java-processor:clean :foundry-java-processor:test
```

Expected: both fresh runs pass with byte-identical golden output.

### Task 6: Document authoring and prove artifact boundaries

**Files:**

- Create: `docs/java-authoring.md`
- Modify: `docs/authoring.md`
- Create: `src/test/java/games/cafecito/foundry/build/ProcessorArtifactContractTest.java`

- [ ] **Step 1: Write failing production-artifact inspections**

Inspect compiled annotation and processor JARs. Require no Android/native classes or dependencies
from annotations, no reflection/scanning/manifest-discovery strings from processor classes, one
service provider, one aggregating metadata entry, and no broad package keep rule.

- [ ] **Step 2: Run the contract test and observe RED**

Run:

```bash
./gradlew test --tests '*ProcessorArtifactContractTest'
```

Expected: the new processor metadata and production outputs are not yet complete.

- [ ] **Step 3: Write the Java authoring guide**

Document every annotation, legal declaration shape, supported types, initialization ordering,
generated artifacts, required `-Afoundry.module`, direct-call behavior, and the prohibition on
reflection, scanning, manifests, Foundry-Android imports, and `libfoundry_android.so`.

- [ ] **Step 4: Run Javadoc and artifact inspections**

Run:

```bash
./gradlew :foundry-java-annotations:javadoc :foundry-java-processor:javadoc
./gradlew test --tests '*ProcessorArtifactContractTest'
```

Expected: public Javadoc and production artifact boundaries pass.

### Task 7: Complete the local verification gate

**Files:**

- Modify only files already named by Tasks 1 through 6 when verification exposes a defect.

- [ ] **Step 1: Run focused gates from clean state**

Run:

```bash
./gradlew clean \
  :foundry-java-annotations:test \
  :foundry-java-processor:test \
  :foundry-java-annotations:javadoc \
  :foundry-java-processor:javadoc
```

Expected: all annotation/processor tests and Javadoc tasks succeed.

- [ ] **Step 2: Run the repository and Android gate**

Run:

```bash
./gradlew clean check
```

Expected: repository model, publication, lint, Android AAR, all JVM tests, and formatting succeed.

- [ ] **Step 3: Prove configuration-cache reuse**

Run:

```bash
./gradlew clean check
./gradlew check
```

Expected: the second invocation reports configuration-cache reuse and succeeds.

- [ ] **Step 4: Inspect forbidden payloads and immutable donor state**

Search production source and built artifacts for reflection/scanning/manifest discovery and
`libfoundry_android.so`, inspect the branch diff, and verify
`/Users/christian/CafecitoGames/Foundry-Android` remains clean at its original commit.

- [ ] **Step 5: Commit the verified checkpoint**

Commit all intended files with an imperative subject under 72 characters. Do not push or open a
pull request. Report the exact commit, test evidence, changed public API, and any required follow-up
to the orchestrator for independent review.
