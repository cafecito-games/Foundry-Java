# Foundry-Java Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish Foundry-Java as an Android-only, Java-first Gradle multi-project with safe publication, reproducible builds, and clear module boundaries.

**Architecture:** A root Kotlin DSL build applies shared Java 17, publishing, locking, and reproducibility conventions to nine deliberately thin modules. The public API model and annotations stay platform-neutral; runtime stays host-neutral; Android host packaging is isolated; Kotlin is a Java-facing optional layer.

**Tech Stack:** Gradle 8.11.1 wrapper, Kotlin DSL, Java 17 toolchains, JUnit 5, Maven Publish, Spotless, Android Gradle Plugin.

---

### Task 1: Lock the repository contract in tests

**Files:**
- Create: `src/test/java/games/cafecito/foundry/build/RepositoryContractTest.java`
- Create: `src/test/resources/junit-platform.properties`

- [ ] **Step 1: Write the failing structural contract test**

```java
assertTrue(Files.exists(root.resolve("settings.gradle.kts")));
assertEquals(9, includedModules.size());
assertTrue(settings.contains("foundry-java-android"));
assertTrue(rootBuild.contains("games.cafecito.foundry.java"));
```

- [ ] **Step 2: Run it to verify RED**

Run: `./gradlew contractTest`
Expected: failure because the wrapper and multi-project build do not exist.

- [ ] **Step 3: Add the minimal root build wiring for the test**

```kotlin
tasks.register<JavaExec>("contractTest") { classpath = sourceSets["test"].runtimeClasspath }
```

- [ ] **Step 4: Run the test to verify GREEN**

Run: `./gradlew contractTest`
Expected: successful contract suite after Tasks 2–4.

- [ ] **Step 5: Commit**

```bash
git add src/test
git commit -m "test: define repository bootstrap contract"
```

### Task 2: Add root policy, reproducible Gradle conventions, and wrapper

**Files:**
- Create: `AGENTS.md`, `.editorconfig`, `LICENSE`, `NOTICE`, `CONTRIBUTING.md`, `gradle.properties`
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`

- [ ] **Step 1: Make the root build define Java 17 and all nine included modules**

```kotlin
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
tasks.withType<AbstractArchiveTask>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
dependencyLocking { lockAllConfigurations() }
```

- [ ] **Step 2: Apply group/version, Maven Publish and formatting conventions**

```kotlin
group = "games.cafecito.foundry"
plugins.withId("maven-publish") { publishing { publications.withType<MavenPublication>().configureEach { pom { licenses { license { name.set("Apache License, Version 2.0") } } } } } }
```

- [ ] **Step 3: Generate and pin Gradle wrapper**

Run: `curl -fsSLO https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`
Expected: wrapper files reference the fixed Gradle 8.11.1 distribution.

- [ ] **Step 4: Run formatting and structural checks**

Run: `./gradlew spotlessCheck contractTest`
Expected: success.

- [ ] **Step 5: Commit**

```bash
git add AGENTS.md .editorconfig LICENSE NOTICE CONTRIBUTING.md gradle* settings.gradle.kts build.gradle.kts
git commit -m "build: establish Gradle repository conventions"
```

### Task 3: Create bounded Java-first modules

**Files:**
- Create: `foundry-java-api-model/**`, `foundry-java-annotations/**`, `foundry-java-runtime/**`
- Create: `foundry-java-generator/**`, `foundry-java-processor/**`, `foundry-java-test/**`

- [ ] **Step 1: Add module tests that assert core type and annotation availability**

```java
assertEquals("FoundryExtension", FoundryExtension.class.getSimpleName());
assertTrue(FoundryExtension.class.isAnnotationPresent(PublicFoundryAbi.class));
```

- [ ] **Step 2: Implement only Java public scaffolds, with no Android imports**

```java
public interface FoundryExtension { }
@Retention(RetentionPolicy.CLASS) public @interface PublicFoundryAbi { }
```

- [ ] **Step 3: Add generator/processor/test dependencies that point only at Java modules**

```kotlin
dependencies { implementation(project(":foundry-java-api-model")); implementation(project(":foundry-java-annotations")) }
```

- [ ] **Step 4: Run all Java module tests**

Run: `./gradlew :foundry-java-api-model:test :foundry-java-annotations:test :foundry-java-runtime:test`
Expected: success.

- [ ] **Step 5: Commit**

```bash
git add foundry-java-api-model foundry-java-annotations foundry-java-runtime foundry-java-generator foundry-java-processor foundry-java-test
git commit -m "feat: add platform-neutral Java module skeletons"
```

### Task 4: Isolate Android, Kotlin, and Gradle plugin surfaces

**Files:**
- Create: `foundry-java-android/**`, `foundry-java-kotlin/**`, `foundry-java-gradle-plugin/**`
- Create: `src/test/java/games/cafecito/foundry/build/ModuleBoundaryTest.java`

- [ ] **Step 1: Add a failing boundary test**

```java
assertFalse(source("foundry-java-runtime").contains("android."));
assertFalse(source("foundry-java-api-model").contains("android."));
assertTrue(build("foundry-java-kotlin").contains("foundry-java-runtime"));
assertTrue(build("foundry-java-android").contains("com.android.library"));
```

- [ ] **Step 2: Add Android-only packaging, a Kotlin bridge depending on Java runtime, and the required plugin id**

```kotlin
plugins { id("com.android.library") }
// Kotlin module: implementation(project(":foundry-java-runtime"))
gradlePlugin { plugins { create("foundryJava") { id = "games.cafecito.foundry.java" } } }
```

- [ ] **Step 3: Run boundary and plugin checks**

Run: `./gradlew contractTest :foundry-java-gradle-plugin:check`
Expected: success.

- [ ] **Step 4: Commit**

```bash
git add foundry-java-android foundry-java-kotlin foundry-java-gradle-plugin src/test
git commit -m "feat: isolate Android and optional Kotlin surfaces"
```

### Task 5: Document and automate the skeleton

**Files:**
- Modify: `README.md`
- Create: `docs/architecture.md`, `docs/compatibility.md`, `docs/authoring.md`, `docs/kotlin.md`, `docs/memory.md`, `docs/android.md`, `docs/releasing.md`
- Create: `.github/workflows/ci.yml`, `.github/dependabot.yml`

- [ ] **Step 1: Document Android-only policy, Java ABI, public `FoundryExtension` restriction, and banned native library**

```markdown
Never package, link, load, or redistribute `libfoundry_android.so`; integrations use only the public `FoundryExtension` ABI.
```

- [ ] **Step 2: Add CI that validates Java 17, wrapper, checks, and dependency locks**

```yaml
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: '17' }
- run: ./gradlew --no-daemon clean check dependencyLocking
```

- [ ] **Step 3: Run complete verification**

Run: `./gradlew clean check contractTest`
Expected: all projects and contract checks succeed.

- [ ] **Step 4: Commit**

```bash
git add README.md docs .github
git commit -m "docs: document Android-only Foundry-Java bootstrap"
```

### Task 6: Final self-review and handoff

- [ ] **Step 1: Inspect the final diff and repository status**

Run: `git diff origin/main...HEAD --check && git status --short`
Expected: no whitespace errors and clean status.

- [ ] **Step 2: Run fresh full verification**

Run: `./gradlew clean check contractTest`
Expected: exit 0.

- [ ] **Step 3: Report base, HEAD, commit history, test evidence, and any environment-limited checks**
