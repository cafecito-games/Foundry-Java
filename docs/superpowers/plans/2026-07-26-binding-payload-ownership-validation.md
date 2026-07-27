# Binding Payload Ownership Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permit ordinary Android host AARs to retain `libfoundry_android.so` while rejecting that library from every Foundry-Java binding/configuration/bridge claimant.

**Architecture:** Keep the complete Android runtime graph as input, scan each archive into immutable evidence, then apply the forbidden-host rule only after configuration/bridge ownership is known. Descriptor aggregation remains independent, so host archives can still contribute generated module descriptors.

**Tech Stack:** Java 17, Gradle 8.14, Gradle TestKit, AGP 8.9.1/8.10, ZIP/AAR fixtures, JUnit 5.

---

## File Map

- Modify
  `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/RegistryIndexTask.java`
  to separate archive evidence collection from binding-claimant validation.
- Modify
  `foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java`
  with local-file, staged-Maven, AGP, APK, and deterministic diagnostic
  regressions.
- Create
  `docs/superpowers/specs/2026-07-26-binding-payload-ownership-validation-design.md`
  and this plan to freeze the approved ownership boundary.

### Task 1: Freeze the Reproducer

**Files:**
- Modify:
  `foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java`

- [ ] **Step 1: Add host and claimant fixture builders**

Add an AAR builder accepting explicit entries so tests can create host-only,
configuration-only, bridge-only, and combined artifacts:

```java
private Path aar(Path output, List<RawZipEntry> entries) throws IOException {
    List<RawZipEntry> complete = new ArrayList<>();
    complete.add(new RawZipEntry(
            "AndroidManifest.xml",
            "<manifest package=\"games.cafecito.fixture\" />\n"
                    .getBytes(StandardCharsets.UTF_8)));
    complete.add(new RawZipEntry("classes.jar", classesJar(null, null, false)));
    complete.addAll(entries);
    writeRawZip(output, complete);
    return output;
}
```

Use exact entries such as
`jni/arm64-v8a/libfoundry_android.so`,
`jni/x86_64/libfoundry_java.so`, and
`FoundryJava.foundryextension`.

- [ ] **Step 2: Add the allowed local-file regression**

Create an ordinary host AAR with `libfoundry_android.so` and a separate valid
binding AAR. Add both as Android application dependencies and assert
`generateDebugFoundryJavaRegistry` succeeds, the index/config/bootstrap exist,
and a host module descriptor is aggregated when present.

- [ ] **Step 3: Add forbidden claimant matrix**

For configuration-only, bridge-only, and combined claimant artifacts, add
multiple deliberately reverse-ordered host entries. Assert failure includes the
absolute artifact path and every host entry in lexical order, and that no index
was generated.

- [ ] **Step 4: Run RED in the shared focused window**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-gradle-plugin:test \
  --tests '*FoundryJavaPluginTest.host*' \
  --tests '*FoundryJavaPluginTest.*Claimant*'
```

Expected: the allowed host test fails with the existing
`forbidden host payload` diagnostic. Claimant diagnostics may report only the
first entry instead of the required complete sorted evidence.

- [ ] **Step 5: Commit the RED tests**

```bash
git add foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java
git commit -m "Test binding payload ownership"
```

### Task 2: Scope Validation to Binding Ownership

**Files:**
- Modify:
  `foundry-java-gradle-plugin/src/main/java/games/cafecito/foundry/gradle/RegistryIndexTask.java`
- Test:
  `foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java`

- [ ] **Step 1: Collect complete archive evidence**

During `readPayloads()`, collect forbidden entry names into a `TreeSet<String>`
instead of throwing inside the archive loop. Continue collecting configuration
bytes and bridge ABIs exactly as before.

- [ ] **Step 2: Validate after claimant classification**

After nested configuration discovery, determine:

```java
boolean bindingClaimant = configuration != null || !bridgeAbis.isEmpty();
```

When `bindingClaimant && !forbiddenHostEntries.isEmpty()`, throw one stable
`GradleException` containing the artifact and
`String.join(", ", forbiddenHostEntries)`. When it is false, do not add the
archive to the payload list and do not reject its host library.

- [ ] **Step 3: Run focused GREEN**

Run the command from Task 1 Step 4. Expected: every selected test passes.

- [ ] **Step 4: Run strict payload regressions**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-gradle-plugin:test \
  --tests '*FoundryJavaPluginTest.duplicate*' \
  --tests '*FoundryJavaPluginTest.requested*' \
  --tests '*FoundryJavaPluginTest.androidRuntimeGraph*'
```

Expected: duplicate bridge/configuration, split ownership, requested ABI, and
normal binding graph tests pass without changed diagnostics.

- [ ] **Step 5: Commit the minimal fix**

```bash
git add foundry-java-gradle-plugin
git commit -m "Scope host payload validation to bindings"
```

### Task 3: Prove Real Android and Maven Topologies

**Files:**
- Modify:
  `foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java`

- [ ] **Step 1: Add APK preservation proof**

Assemble a custom-application-ID minified release from a host AAR plus binding
AAR. Select `x86_64` and assert the APK contains:

```text
lib/x86_64/libfoundry_android.so
lib/x86_64/libfoundry_java.so
assets/FoundryJava.foundryextension
assets/foundry_java/registry-index-v2.txt
```

Also assert excluded ABIs are absent and the direct generated bootstrap refers
to each sorted module provider.

- [ ] **Step 2: Add staged Maven WS11 topology**

Build a temporary repository containing the marker POM pointing to the
implementation plugin, the Android AAR depending on runtime, and runtime
depending on API-model and annotations. Resolve the plugin and application
dependencies through that repository, with the host AAR as a separate
application dependency. Do not configure `RegistryIndexTask` inputs directly.

- [ ] **Step 3: Verify AGP 8.10, minification, and cache reuse**

Run the local-file and staged-Maven builds twice with `--configuration-cache`.
Expected: first build stores and second build reuses the cache; both preserve
the host library and generated outputs.

- [ ] **Step 4: Verify AGP 8.9.1**

Run the existing isolated-plugin-classpath AGP 8.9.1 fixture with the separate
host AAR. Expected: `AGP_VERSION=8.9.1`, registry success, selected ABI output,
and configuration-cache reuse.

- [ ] **Step 5: Commit integration proof**

```bash
git add foundry-java-gradle-plugin/src/test/java/games/cafecito/foundry/gradle/FoundryJavaPluginTest.java
git commit -m "Verify host and binding Android packaging"
```

### Task 4: Full Gates and Review Convergence

**Files:**
- All committed issue files.
- Status ledger:
  `/Users/christian/CafecitoGames/Foundry/.epic-1241-status.md`

- [ ] **Step 1: Run complete plugin and repository gates**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon :foundry-java-gradle-plugin:test \
  verifyRepositoryModel verifyPublications verifyAndroidAar
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew --no-daemon clean check
git diff --check f884aba7571ebe97c1b982770a3bd87e277d288f...HEAD
git status --short
```

Expected: all Gradle commands exit zero, diff check is empty, and tracked
status is clean.

- [ ] **Step 2: Reconfirm immutable boundaries**

Verify no production coordinate/name heuristic, task-input override, or
Foundry-Android mutation exists. Inspect assembled AARs to confirm none package
`libfoundry_android.so`; only the downstream application APK contains the host
library from its independent host dependency.

- [ ] **Step 3: Obtain independent exact-head review**

Provide issue #1252, the approved design/plan, exact base
`f884aba7571ebe97c1b982770a3bd87e277d288f`, and exact committed HEAD. Fix every
validated blocking/critical/major finding with focused RED/GREEN coverage and
repeat until `Ready: Yes`.

- [ ] **Step 4: Converge Cursor review**

Run `cursor-review` in read-only plan mode against exact `origin/main`. Validate
findings through receiving-review and systematic-debugging, commit fixes, and
repeat until the latest valid result is exactly `RESULT: clean`.

### Task 5: Publish, Merge, and Cleanup

**Files:**
- Status ledger:
  `/Users/christian/CafecitoGames/Foundry/.epic-1241-status.md`

- [ ] **Step 1: Push and open the PR**

Push `issue-1252`, open a non-draft PR against Foundry-Java `main`, reference
Foundry #1252 without relying on cross-repository automatic closure, and include
the exact tests and reviewed SHAs.

- [ ] **Step 2: Converge GitHub checks**

Wait for all checks and review threads on the reviewed head. Enable squash
auto-merge only after they converge, then confirm the PR is merged.

- [ ] **Step 3: Close the native child and project item**

Close Foundry #1252 as completed and move Experiment item
`PVTI_lADODvOSms4Bbc4gzg0Ka_c` to Done. Record the merged SHA and WS11 unblock
in the epic ledger.

- [ ] **Step 4: Clean the issue workspace**

Remove the clean worktree and local/remote `issue-1252` branches. Reconfirm
Foundry-Android remains unchanged and notify WS11 to rebase/rerun its exact
local and staged-Maven integration proof.
