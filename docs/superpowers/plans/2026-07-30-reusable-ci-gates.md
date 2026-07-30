# Reusable CI Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the duplicated CI and release host/device gate definitions with one mode-aware reusable GitHub Actions workflow called by both entry-point workflows.

**Architecture:** A new `.github/workflows/gates.yml` owns the host and API 36 device jobs and exposes `release` and `dry_run` boolean inputs through `workflow_call`. `ci.yml` becomes a non-release caller, while `release.yml` keeps only release orchestration and depends on a release-mode call before staging.

**Tech Stack:** GitHub Actions reusable workflows, Java 17/JUnit 5 repository contract tests, Gradle 8.11.1, actionlint.

---

## File map

- Create `.github/workflows/gates.yml`: sole owner of host and device gate jobs.
- Modify `.github/workflows/ci.yml`: retain triggers/permissions and call the shared gates.
- Modify `.github/workflows/release.yml`: replace local gate jobs with a release-mode call and make staging depend on it.
- Create `src/test/java/games/cafecito/foundry/build/ReusableGateWorkflowContractTest.java`: topology and no-duplication regression contract.
- Modify `src/test/java/games/cafecito/foundry/build/RepositoryContractTest.java`: read gate behavior from its new owner.
- Modify `src/test/java/games/cafecito/foundry/build/NativeBridgeContractTest.java`: read native/device workflow assertions from the shared gates.
- Modify `src/test/java/games/cafecito/foundry/build/EngineLoadedConformanceGateContractTest.java`: read the engine gate from the shared gates.
- Modify `src/test/java/games/cafecito/foundry/build/ReleasePipelineContractTest.java`: separate release orchestration assertions from shared gate assertions.
- Modify `docs/releasing.md`: document the called gate workflow and new dependency chain.

### Task 1: Add the failing reusable-workflow ownership contract

**Files:**

- Create: `src/test/java/games/cafecito/foundry/build/ReusableGateWorkflowContractTest.java`

- [ ] **Step 1: Write the failing topology and deduplication test**

Create the test with the complete contract below:

```java
package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReusableGateWorkflowContractTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final String SHARED = ".github/workflows/gates.yml";
    private static final String CALL = "uses: ./.github/workflows/gates.yml";

    @Test
    void ciAndReleaseCallOneSharedHostAndDeviceGateWorkflow() throws IOException {
        String shared = read(SHARED);
        String ci = read(".github/workflows/ci.yml");
        String release = read(".github/workflows/release.yml");

        assertTrue(shared.contains("on:\n  workflow_call:"));
        assertTrue(shared.contains("      release:\n"));
        assertTrue(shared.contains("        type: boolean"));
        assertTrue(shared.contains("      dry_run:\n"));
        assertTrue(shared.contains("  host-gate:\n"));
        assertTrue(shared.contains("  device-gate:\n"));

        assertEquals(1, occurrences(ci, CALL));
        assertEquals(1, occurrences(release, CALL));
        assertFalse(ci.contains("release: true"));
        assertTrue(
                release.contains(
                        "with:\n"
                                + "      release: true\n"
                                + "      dry_run: ${{ inputs.dry_run }}"));
        assertTrue(release.contains("needs: [gates]"));

        assertFalse(ci.contains("host-gate:"));
        assertFalse(ci.contains("device-gate:"));
        assertFalse(ci.contains("android-actions/setup-android@"));
        int stage = release.indexOf("  stage:\n");
        assertTrue(stage > 0);
        String releaseBeforeStage = release.substring(0, stage);
        assertFalse(releaseBeforeStage.contains("host-gate:"));
        assertFalse(releaseBeforeStage.contains("device-gate:"));
        assertFalse(releaseBeforeStage.contains("android-actions/setup-android@"));

        assertEquals(
                1, occurrences(shared, "bash gradle/verify-configuration-cache-reuse.sh"));
        assertEquals(
                1, occurrences(shared, "bash gradle/run-samples-conformance-matrix.sh"));
        assertEquals(
                1, occurrences(shared, "bash gradle/run-engine-loaded-conformance-gate.sh"));
        assertEquals(1, occurrences(shared, "- name: Create and launch the API 36 emulator"));
    }

    private static int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
    }
}
```

- [ ] **Step 2: Run the focused test and verify the RED state**

Run:

```bash
./gradlew :test \
  --tests games.cafecito.foundry.build.ReusableGateWorkflowContractTest
```

Expected: `FAILURE` because `.github/workflows/gates.yml` does not exist. Confirm the failure is the
missing shared workflow, not compilation or test-discovery failure.

### Task 2: Introduce the reusable gates and convert both callers

**Files:**

- Create: `.github/workflows/gates.yml`
- Modify: `.github/workflows/ci.yml:1-190`
- Modify: `.github/workflows/release.yml:1-203`
- Test: `src/test/java/games/cafecito/foundry/build/ReusableGateWorkflowContractTest.java`

- [ ] **Step 1: Create the reusable workflow interface and job shells**

Start `.github/workflows/gates.yml` with:

```yaml
name: Shared gates

on:
  workflow_call:
    inputs:
      release:
        description: Run the release-strength host gate and release preconditions.
        required: false
        type: boolean
        default: false
      dry_run:
        description: Whether a manually dispatched release is a staging dry run.
        required: false
        type: boolean
        default: false

permissions:
  contents: read

jobs:
  host-gate:
    name: ${{ inputs.release && 'Host gate' || 'check' }}
    runs-on: ubuntu-latest
    timeout-minutes: ${{ inputs.release && 60 || 30 }}
    steps:
      - name: Refuse a manual dispatch that is not a dry run
        if: inputs.release && github.event_name == 'workflow_dispatch' && !inputs.dry_run
        shell: bash
        run: |
          printf 'A manual dispatch only proves the pipeline against a staging target.\n' >&2
          printf 'A real release is a tag push. Re-run the dispatch with dry_run enabled.\n' >&2
          exit 1
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          fetch-depth: ${{ inputs.release && 0 || 1 }}
      - uses: actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9 # v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407 # v3
        with:
          packages: >-
            tools platform-tools platforms;android-36 build-tools;35.0.0
            ndk;29.0.14206865 cmake;3.22.1
      - uses: gradle/actions/wrapper-validation@0b6dd653ba04f4f93bf581ec31e66cbd7dcb644d # v4
```

Add the `device-gate` shell:

```yaml
  device-gate:
    name: ${{ inputs.release && 'Device gate on API 36' || 'JNI lifecycle on API 36' }}
    runs-on: ubuntu-latest
    timeout-minutes: 150
    env:
      FOUNDRY_ENGINE_CACHE: ${{ github.workspace }}/.foundry-engine-cache
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      - uses: actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9 # v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407 # v3
        with:
          packages: >-
            tools platform-tools emulator platforms;android-36 build-tools;35.0.0
            ndk;29.0.14206865 cmake;3.22.1
            system-images;android-36;default;x86_64
```

- [ ] **Step 2: Populate the host gate without changing either mode's behavior**

Place the current CI-only setup and checks behind `if: ${{ !inputs.release }}`:

```yaml
      - uses: gradle/actions/setup-gradle@0b6dd653ba04f4f93bf581ec31e66cbd7dcb644d # v4
        if: ${{ !inputs.release }}
        with:
          validate-wrappers: false
      - name: Prove configuration cache reuse from clean outputs
        shell: bash
        run: bash gradle/verify-configuration-cache-reuse.sh
      - name: Prove the configuration cache gate still fails what it exists to catch
        if: ${{ !inputs.release }}
        shell: bash
        run: bash gradle/verify-configuration-cache-reuse-selftest.sh
```

Add two explicitly conditional build steps. The non-release step copies
`.github/workflows/ci.yml:49-64` exactly and keeps the
`foundry-java-check.log`/`foundry-java-native-verifier.log` names. The release step copies
`.github/workflows/release.yml:57-73` exactly and keeps the additional runtime API, realization,
native ABI layout tasks and release log names:

```yaml
      - name: Build, test, and inspect the native bridge
        if: ${{ !inputs.release }}
        shell: bash
        run: |
          set -euo pipefail
          ./gradlew --no-daemon \
            :foundry-java-android:assembleDebug \
            :foundry-java-android:assembleDebugAndroidTest \
            :foundry-java-android:bundleReleaseAar \
            :foundry-java-android:nativeHostTest \
            :foundry-java-android:nativeSanitizerTest 2>&1 |
            tee "${RUNNER_TEMP}/foundry-java-check.log"
          bash gradle/verify-native-bridge.sh \
            foundry-java-android/build/outputs/aar/foundry-java-android-release.aar 2>&1 |
            tee "${RUNNER_TEMP}/foundry-java-native-verifier.log"
      - name: Build, test, and inspect the native bridge
        if: inputs.release
        shell: bash
        run: |
          set -euo pipefail
          ./gradlew --no-daemon \
            :foundry-java-runtime:verifyRuntimeApi \
            :foundry-java-runtime:verifyGeneratedRealization \
            :foundry-java-android:assembleDebug \
            :foundry-java-android:assembleDebugAndroidTest \
            :foundry-java-android:bundleReleaseAar \
            :foundry-java-android:nativeAbiLayoutTest \
            :foundry-java-android:nativeHostTest \
            :foundry-java-android:nativeSanitizerTest 2>&1 |
            tee "${RUNNER_TEMP}/foundry-java-release-check.log"
          bash gradle/verify-native-bridge.sh \
            foundry-java-android/build/outputs/aar/foundry-java-android-release.aar 2>&1 |
            tee "${RUNNER_TEMP}/foundry-java-release-native-verifier.log"
```

Copy the CI lock regeneration/drift steps from `.github/workflows/ci.yml:65-81` with
`if: ${{ !inputs.release }}`. Copy the release push and manual-dispatch lock/precondition steps from
`.github/workflows/release.yml:74-98`, changing their conditions to:

```yaml
if: inputs.release && github.event_name == 'push'
```

and:

```yaml
if: inputs.release && github.event_name != 'push'
```

Add separate evidence upload steps copied exactly from each caller. Use
`if: always() && !inputs.release` for CI evidence and `if: always() && inputs.release` for release
evidence so artifact names and path inventories remain unchanged.

- [ ] **Step 3: Populate the device gate once**

Add the CI-only read-only Gradle cache step:

```yaml
      - uses: gradle/actions/setup-gradle@0b6dd653ba04f4f93bf581ec31e66cbd7dcb644d # v4
        if: ${{ !inputs.release }}
        with:
          validate-wrappers: false
          cache-read-only: true
```

Copy the emulator create/launch, boot wait, startup acceptance, sample matrix, engine cache, and
engine-loaded gate steps from `.github/workflows/ci.yml:110-168`. Preserve the two existing AVD
names with one local value:

```yaml
      - name: Create and launch the API 36 emulator
        shell: bash
        run: |
          set -euo pipefail
          test -c /dev/kvm
          sudo chmod 666 /dev/kvm
          avd_name="${{ inputs.release && 'foundry-java-release' || 'foundry-java-acceptance' }}"
          export ANDROID_AVD_HOME="${RUNNER_TEMP}/android-avd"
          echo "ANDROID_AVD_HOME=${ANDROID_AVD_HOME}" >> "${GITHUB_ENV}"
          mkdir -p "${ANDROID_AVD_HOME}"
          echo no | "${ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager" create avd --force \
            --name "${avd_name}" \
            --package "system-images;android-36;default;x86_64"
          nohup "${ANDROID_HOME}/emulator/emulator" \
            -avd "${avd_name}" \
            -port 5554 \
            -no-window \
            -no-audio \
            -no-boot-anim \
            -no-snapshot \
            -wipe-data \
            -gpu swiftshader_indirect \
            > "${RUNNER_TEMP}/foundry-java-emulator.log" 2>&1 &
```

Add the current CI and release evidence uploads as separate conditional steps using the same
`always()` conditions as the host artifacts. Keep their current names and path inventories exactly.

- [ ] **Step 4: Replace the CI workflow with a shared-workflow caller**

Reduce `.github/workflows/ci.yml` to:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  check:
    uses: ./.github/workflows/gates.yml
```

- [ ] **Step 5: Replace the release gate jobs with a release-mode caller**

In `.github/workflows/release.yml`, replace lines 25-200 with:

```yaml
jobs:
  gates:
    uses: ./.github/workflows/gates.yml
    with:
      release: true
      dry_run: ${{ inputs.dry_run }}

  stage:
    name: Stage, sign, and verify
    needs: [gates]
```

Keep the existing `stage` steps and the entire `publish` job byte-for-byte after that new header.
Update the introductory comment to say the workflow calls the complete shared gate set and stages
nothing until that call succeeds.

- [ ] **Step 6: Run syntax validation and the new contract**

Run:

```bash
actionlint
./gradlew :test \
  --tests games.cafecito.foundry.build.ReusableGateWorkflowContractTest
```

Expected: actionlint exits 0 and the focused test passes.

- [ ] **Step 7: Commit the topology change**

```bash
git add \
  .github/workflows/gates.yml \
  .github/workflows/ci.yml \
  .github/workflows/release.yml \
  src/test/java/games/cafecito/foundry/build/ReusableGateWorkflowContractTest.java
git commit -m "ci: share host and device gates"
```

### Task 3: Retarget existing contracts to the workflow that owns each behavior

**Files:**

- Modify: `src/test/java/games/cafecito/foundry/build/RepositoryContractTest.java`
- Modify: `src/test/java/games/cafecito/foundry/build/NativeBridgeContractTest.java`
- Modify: `src/test/java/games/cafecito/foundry/build/EngineLoadedConformanceGateContractTest.java`
- Modify: `src/test/java/games/cafecito/foundry/build/ReleasePipelineContractTest.java`

- [ ] **Step 1: Point CI gate assertions at `gates.yml`**

In `RepositoryContractTest`, replace each workflow read at lines 181, 227, 259, 271, 314, 358,
408, 645, 694, and 789 with:

```java
String workflow = read(".github/workflows/gates.yml");
```

Keep every behavioral assertion. Adjust only assertions made obsolete by conditional mode
co-location:

```java
assertEquals(4, occurrences(workflow, UPLOAD_ARTIFACT_COMMIT));
assertEquals(2, occurrences(workflow, "if: ${{ always() && !inputs.release }}"));
assertEquals(2, occurrences(workflow, "if: always() && inputs.release"));
String ciHostEvidence =
        namedWorkflowStep(workflow, "Upload build and native verification evidence");
String releaseHostEvidence = namedWorkflowStep(workflow, "Upload host gate evidence");
String ciDeviceEvidence =
        namedWorkflowStep(workflow, "Upload API 36 production startup evidence");
String releaseDeviceEvidence = namedWorkflowStep(workflow, "Upload device gate evidence");
for (String ciEvidence : List.of(ciHostEvidence, ciDeviceEvidence)) {
    assertTrue(ciEvidence.contains("if: ${{ always() && !inputs.release }}"));
    assertTrue(ciEvidence.contains("uses: " + UPLOAD_ARTIFACT_COMMIT));
}
for (String releaseEvidence : List.of(releaseHostEvidence, releaseDeviceEvidence)) {
    assertTrue(releaseEvidence.contains("if: always() && inputs.release"));
    assertTrue(releaseEvidence.contains("uses: " + UPLOAD_ARTIFACT_COMMIT));
}
assertTrue(ciHostEvidence.contains("name: foundry-java-check-evidence"));
assertTrue(ciHostEvidence.contains("${{ runner.temp }}/foundry-java-check.log"));
assertTrue(
        releaseHostEvidence.contains("name: foundry-java-release-host-gate-evidence"));
assertTrue(
        releaseHostEvidence.contains("${{ runner.temp }}/foundry-java-release-check.log"));
assertTrue(
        ciDeviceEvidence.contains("name: foundry-java-api36-production-startup-evidence"));
assertTrue(ciDeviceEvidence.contains("${{ runner.temp }}/foundry-java-engine-gate/**"));
assertTrue(
        releaseDeviceEvidence.contains("name: foundry-java-release-device-gate-evidence"));
assertTrue(
        releaseDeviceEvidence.contains("${{ runner.temp }}/foundry-java-engine-gate/**"));
```

When extracting the CI build step in `ciPublishesImmutableCheckAndProductionStartupEvidence`, find
the first `Build, test, and inspect` step, which is the non-release step, and retain the exact
command/log assertions.

Change `NativeBridgeContractTest.androidBuildAndVerifierRequireTheExactFourAbiBridge` and
`EngineLoadedConformanceGateContractTest.continuousIntegrationRunsTheGateOnTheApi36EmulatorAndKeepsItsEvidence`
to read
`.github/workflows/gates.yml`. Do not remove any native, KVM, emulator, cache, evidence, or ordering
assertion.

- [ ] **Step 2: Separate release orchestration from shared gate ownership**

Add to `ReleasePipelineContractTest`:

```java
private static final String GATES_WORKFLOW = ".github/workflows/gates.yml";
```

Rewrite `publicationIsTagDrivenAndOrderedAfterTheCompleteGateSet` to:

```java
String workflow = read(WORKFLOW);
String gates = read(GATES_WORKFLOW);

assertTrue(workflow.contains("  push:\n    tags:\n      - 'v*'"));
assertFalse(workflow.contains("pull_request"), "a release must never run for a pull request");
assertTrue(workflow.contains("concurrency:"));
for (String gateStep : GATE_STEPS) {
    assertTrue(gates.contains(gateStep), gateStep + " must run before publication");
}
assertTrue(gates.contains("./gradlew --no-daemon --write-locks resolveAndLockAll"));

int gateCall = workflow.indexOf("  gates:\n");
int stage = workflow.indexOf("  stage:\n");
int publish = workflow.indexOf("  publish:\n");
assertTrue(gateCall > 0 && stage > gateCall && publish > stage);
assertTrue(workflow.contains("uses: ./.github/workflows/gates.yml"));
assertTrue(workflow.contains("release: true"));
assertTrue(workflow.contains("needs: [gates]"));
assertTrue(gates.contains("timeout-minutes: 150"));
assertTrue(gates.contains("FOUNDRY_ENGINE_CACHE"));
assertFalse(gates.contains("upload-staged-release"));
assertFalse(gates.contains("stage-release.sh"));
```

In `theStagingDryRunExercisesSigningValidationAndUploadWithoutMavenCentral`, read both files:

```java
String workflow = read(WORKFLOW);
String gates = read(GATES_WORKFLOW);
```

Keep the staging dry-run call assertion against `workflow`; move the manual-dispatch refusal
condition and message assertions to `gates`. All secret, signing, staging, upload, and recovery
assertions continue to read `release.yml`.

- [ ] **Step 3: Run all root contract tests**

Run:

```bash
./gradlew :test
```

Expected: all root repository, native bridge, engine gate, release pipeline, release script, and
reusable-workflow contract tests pass.

- [ ] **Step 4: Format and commit contract ownership changes**

Run:

```bash
./gradlew spotlessApply
./gradlew :test
```

Expected: formatting succeeds and root tests remain green.

Commit:

```bash
git add src/test/java/games/cafecito/foundry/build
git commit -m "test: enforce reusable gate ownership"
```

### Task 4: Update release documentation and perform full verification

**Files:**

- Modify: `docs/releasing.md:8-28`

- [ ] **Step 1: Document the called gate workflow**

Replace the opening pipeline description with:

```markdown
## What the pipeline does

[`.github/workflows/release.yml`](../.github/workflows/release.yml) calls the shared
[`.github/workflows/gates.yml`](../.github/workflows/gates.yml) workflow before its stage and
publish jobs. Nothing is signed until both called gate jobs pass, and nothing is uploaded until the
staged release is verified.

1. **`gates / Host gate`** — configuration-cache reuse from clean outputs, which runs the full
   Gradle `check` twice, plus `:foundry-java-runtime:verifyRuntimeApi`, the parity oracle
   `:foundry-java-runtime:verifyGeneratedRealization`, the native host and sanitizer tests, the
   native ABI layout test, the AAR native-bridge inspection, regenerated dependency locks, and
   [`gradle/verify-release-preconditions.sh`](../gradle/verify-release-preconditions.sh).
2. **`gates / Device gate on API 36`** — production startup twice in fresh processes on an API 36
   emulator, the Java and Kotlin conformance matrix as consumer samples, and the engine-loaded API
   36 conformance gate. It carries a 150-minute budget because the engine gate downloads a roughly
   1.1 GB export template and builds five exports.
3. **`stage`** — [`gradle/verify-release-reproducibility.sh`](../gradle/verify-release-reproducibility.sh)
   stages the tag twice through [`gradle/stage-release.sh`](../gradle/stage-release.sh) and compares
   the results byte for byte, then
   [`gradle/verify-staged-release.sh`](../gradle/verify-staged-release.sh) verifies every signature,
   every checksum, every POM, and every Gradle module metadata document in the staged repository.
4. **`publish`** — [`gradle/upload-staged-release.sh`](../gradle/upload-staged-release.sh) checks
   every coordinate against Maven Central and uploads the staged release to the Central Portal.
```

- [ ] **Step 2: Run static workflow and diff validation**

Run:

```bash
actionlint
git diff --check
git status --short
```

Expected: actionlint and diff checks exit 0; status lists only the intended documentation change
after the earlier commits.

- [ ] **Step 3: Run the required clean repository gate**

Run:

```bash
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`, with all Java, Kotlin, Android, native host/sanitizer, lint,
publication, API, realization, and repository contract tasks green.

- [ ] **Step 4: Confirm dependency locks did not change**

Run:

```bash
git status --short -- \
  gradle.lockfile \
  ':(glob)**/gradle.lockfile' \
  settings-gradle.lockfile
```

Expected: no output.

- [ ] **Step 5: Commit documentation**

```bash
git add docs/releasing.md
git commit -m "docs: describe shared release gates"
```

- [ ] **Step 6: Review final branch scope**

Run:

```bash
git status --short --branch
git diff --stat origin/main...HEAD
git log --oneline origin/main..HEAD
```

Expected: a clean `issue-68` branch containing the design, shared workflow/callers, contract
updates, and release documentation only.
