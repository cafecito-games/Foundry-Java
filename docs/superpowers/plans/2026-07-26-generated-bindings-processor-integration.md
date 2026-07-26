# Generated Bindings and Processor Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define and verify one reflection-free contract between generated engine bindings and the
extension annotation processor, including original virtual names, lifecycle-safe construction,
generic signal substitution, and warning-free deterministic aggregation.

**Architecture:** Generated engine types carry CLASS-retained provenance and generated virtual
methods carry their original Foundry identity. The processor resolves that metadata through
JSR-269, generates context/lease-aware factories and direct-call trampolines, and resolves inherited
signal methods as members of the concrete interface type. Module output remains aggregating and
fail-closed, but its registry source is reserved during an active round and finalized only after all
rounds so exact JDK 17 `javac -Werror` sees no final-round source-creation warning.

**Tech Stack:** Java 17, JSR-269, `javax.lang.model`, `JavaCompiler`, Gradle 8.11.1, JUnit 5,
Javadoc, Android Gradle Plugin.

---

### Task 1: Freeze generated provenance and virtual identity

**Files:**
- Create:
  `foundry-java-annotations/src/main/java/games/cafecito/foundry/annotations/FoundryVirtual.java`
- Modify:
  `foundry-java-annotations/src/test/java/games/cafecito/foundry/annotations/FoundryAnnotationApiTest.java`
- Modify:
  `foundry-java-generator/src/test/java/games/cafecito/foundry/generator/FoundrySourceGeneratorTest.java`
- Modify:
  `foundry-java-generator/src/main/java/games/cafecito/foundry/generator/FoundrySourceGenerator.java`

- [ ] **Step 1: Write the failing annotation and generator assertions**

Require `FoundryVirtual` to be CLASS-retained, METHOD-targeted, and to expose a required `String
value()`. Require generated engine roots to contain
`@games.cafecito.foundry.annotations.GeneratedByFoundry` and the generated Java callback
`onProcess` to contain
`@games.cafecito.foundry.annotations.FoundryVirtual("_process")`.

- [ ] **Step 2: Run the RED probes**

Run:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-annotations:test \
  :foundry-java-generator:test --rerun-tasks
```

Expected: annotation test compilation fails because `FoundryVirtual` is absent, then generator
assertions fail because generated sources lack both markers.

- [ ] **Step 3: Implement the smallest durable metadata contract**

Add:

```java
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface FoundryVirtual {
    String value();
}
```

Emit the provenance marker immediately before every generated public root declaration. Emit the
virtual identity marker immediately before every generated virtual callback, using the original
Foundry API name rather than the sanitized Java name.

- [ ] **Step 4: Run the GREEN probes**

Run the command from Step 2. Expected: annotation and generator tests pass, and generated fixture
compilation remains successful.

### Task 2: Consume real generated metadata and lifecycle construction

**Files:**
- Modify:
  `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/FoundryTrampolineGenerationTest.java`
- Create:
  `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/GeneratedBindingIntegrationTest.java`
- Modify:
  `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/ExtensionValidator.java`
- Modify:
  `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/ExtensionModel.java`
- Modify:
  `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/SourceEmitter.java`
- Modify:
  `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/ModuleEmitter.java`
- Modify: `foundry-java-processor/build.gradle.kts`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Write RED processor fixtures**

Compile an extension over an actual generated `Node` source. Its constructor is:

```java
public SpinningNode(
        FoundryBindingContext context,
        ObjectLease lease) {
    super(context, lease);
}
```

Its Java override is `onProcess(double)` with an empty `@FoundryOverride`, while the expected
descriptor identity is `_process`. Also require generated object/value types in methods,
properties, and signal parameters to validate, and require the trampoline/registry construction
entry point to accept `(FoundryBindingContext, ObjectLease)`.

- [ ] **Step 2: Run the RED processor tests**

Run:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-processor:test \
  --tests '*GeneratedBindingIntegrationTest' \
  --tests '*FoundryTrampolineGenerationTest' --rerun-tasks
```

Expected: compilation fails on the absent lifecycle constructor support, generated callback
metadata resolution, and generated callback type support.

- [ ] **Step 3: Implement metadata-driven validation and factories**

Resolve a base virtual by Java signature, require `@FoundryVirtual`, and use its `value` as the
descriptor/exported Foundry identity. If `@FoundryOverride.name` is non-empty, require it to equal
that identity. Accept a declared type when it or an enclosing generated root carries
`@GeneratedByFoundry`. Require one public `(FoundryBindingContext, ObjectLease)` extension
constructor and emit:

```java
public static Extension construct(
        FoundryBindingContext context,
        ObjectLease lease) {
    return new Extension(context, lease);
}
```

Propagate the same arguments through `ModuleDescriptor.ExtensionAccess.construct`.

- [ ] **Step 4: Run the GREEN processor tests**

Run the command from Step 2. Expected: both focused suites pass and the descriptor records
`_process|onProcess|void(double)`.

### Task 3: Resolve inherited generic signal SAMs

**Files:**
- Modify:
  `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/FoundryExtensionProcessorValidationTest.java`
- Modify:
  `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/ExtensionValidator.java`

- [ ] **Step 1: Write the failing generic SAM test**

Compile:

```java
interface Parent<T> { void emitted(T value); }
@FoundrySignal interface Changed extends Parent<String> {}
```

Require successful processing and descriptor signature `void(java.lang.String)`.

- [ ] **Step 2: Run and verify RED**

Run:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-processor:test \
  --tests '*FoundryExtensionProcessorValidationTest.*generic*' --rerun-tasks
```

Expected: the current validator rejects or records the unsubstituted type variable `T`.

- [ ] **Step 3: Resolve methods through `Types.asMemberOf`**

Represent each effective SAM member as its `ExecutableElement` plus the `ExecutableType` returned
by:

```java
types.asMemberOf((DeclaredType) signal.asType(), method)
```

Use the resolved return and parameter mirrors for validation, signature deduplication, and the
signal model.

- [ ] **Step 4: Run and verify GREEN**

Run the command from Step 2 and the complete processor test task. Expected: the generic signature
is concrete and all earlier inherited/non-SAM tests remain green.

### Task 4: Make aggregation warning-free and fail-closed

**Files:**
- Modify:
  `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/ProcessorCompilation.java`
- Modify:
  `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/FoundryProcessorBuildModeTest.java`
- Modify:
  `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/FoundryExtensionProcessor.java`
- Modify:
  `foundry-java-processor/src/main/java/games/cafecito/foundry/processor/ModuleEmitter.java`

- [ ] **Step 1: Add exact `-Werror` and delayed-round RED probes**

Allow `ProcessorCompilation` to pass extra javac options. Run the valid module fixture with
`-Werror`, require no WARNING or MANDATORY_WARNING diagnostics, require the registry class to
compile, and retain the tests that add extensions/external roots in later rounds. Preserve the
injected Filer-failure assertion that no registry, descriptor, or keep rules survive.

- [ ] **Step 2: Run and verify RED**

Run:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-processor:test \
  --tests '*FoundryProcessorBuildModeTest.*warning*' --rerun-tasks
```

Expected: javac reports that the registry source was created in the last round and `-Werror`
fails compilation.

- [ ] **Step 3: Reserve the registry source during an active round**

When the first valid extension is discovered, call `Filer.createSourceFile` once and retain the
returned `JavaFileObject` without writing module contents. At `processingOver`, validate the final
aggregate, open/write/close the reserved object, then emit descriptor and keep-rule resources only
after registry output succeeds. On any reservation or write failure, increment the module error
count and emit no remaining module artifacts.

- [ ] **Step 4: Run and verify GREEN**

Run all processor tests uncached. Expected: exact `-Werror`, delayed extension rounds,
continuous-external-root rounds, deterministic clean/incremental output, and injected failures all
pass without warnings.

### Task 5: Prove full generated-source integration and documentation

**Files:**
- Modify:
  `foundry-java-processor/src/test/java/games/cafecito/foundry/processor/GeneratedBindingIntegrationTest.java`
- Modify: `docs/java-authoring.md`
- Modify:
  `foundry-java-annotations/src/test/java/games/cafecito/foundry/annotations/FoundryAnnotationApiTest.java`

- [ ] **Step 1: Compile a generated binding plus extension end to end**

Generate the accepted API with `FoundrySourceGenerator`, feed the generated `Object`/`Node` binding
sources and an extension source through the processor under JDK 17 `-Werror`, and assert compiled
trampoline/registry classes plus `_process` descriptor identity.

- [ ] **Step 2: Document the exact authoring contract**

Document the required public context/lease constructor, automatic recovery of original Foundry
virtual names from generated metadata, supported generated callback types, generic inherited SAM
substitution, and no-reflection compile-time registration.

- [ ] **Step 3: Run focused Javadoc and contract gates**

Run:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-annotations:javadoc \
  :foundry-java-generator:javadoc :foundry-java-processor:javadoc \
  :foundry-java-annotations:test :foundry-java-generator:test \
  :foundry-java-processor:test --rerun-tasks
```

Expected: all tasks pass without Javadoc warnings or processor diagnostics.

### Task 6: Full verification, commit, and review convergence

**Files:**
- Modify: `/Users/christian/CafecitoGames/Foundry/.epic-1241-status.md`

- [ ] **Step 1: Run clean and incremental repository gates**

Run `clean check`, two consecutive `check` invocations and confirm configuration-cache reuse,
multi-module processor tests, Javadocs, and explicit Android
`:foundry-java-android:assembleDebugAndroidTest :foundry-java-android:bundleReleaseAar`.

- [ ] **Step 2: Run safety audits**

Require unchanged dependency locks, `git diff --check`, no production reflection/scanning/manifest
v1 strings, no `libfoundry_android.so` in the release AAR, and exact clean Foundry-Android donor
commit `b8c46c807d467fcd1667b7d4cb04d07a09a08860`.

- [ ] **Step 3: Commit the focused issue #23 correction**

Commit all issue-23 code, tests, docs, and this plan locally. Do not push or open a PR.

- [ ] **Step 4: Obtain exact-head independent and Cursor reviews**

Review `6265fc3959dfb7c8cfbc57f13b23086b096e3ea5...HEAD`, fix validated findings under RED/GREEN,
rerun full gates, and repeat Cursor review until the latest valid output is exactly
`RESULT: clean`.

- [ ] **Step 5: Update epic state after each transition**

Record the worktree/base, dependency, RED/GREEN milestones, commits, every review result/fix, and
final no-push handoff in `/Users/christian/CafecitoGames/Foundry/.epic-1241-status.md`.
