# Selective Engine-Loaded PR Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Skip the expensive engine-loaded conformance step only for pull requests whose changes are entirely documentation, branding, templates, or test sources, while continuing to run the full gate on relevant PRs, `main`, and releases.

**Architecture:** Add a pure Bash/JQ classifier that accepts a JSON array of changed paths and emits a fixed `run`/`reason` decision. The shared device workflow obtains the complete PR file list through the paginated GitHub API, validates its count, fails closed on every collection or classification error, and applies the decision only to the engine-loaded step.

**Tech Stack:** GitHub Actions reusable workflows, Bash, GitHub CLI, JQ, Java 17, JUnit 5, Gradle, actionlint.

---

## File structure

- Create `gradle/classify-engine-gate-paths.sh`: pure deterministic changed-path classifier; it has no GitHub dependency and never prints an untrusted path.
- Create `src/test/java/games/cafecito/foundry/build/EngineGateChangeClassifierTest.java`: executes the classifier against the safe, relevant, mixed, unknown, empty, and malformed cases.
- Modify `.github/workflows/gates.yml`: collect PR files, validate completeness, expose the decision, and condition only the engine-loaded step.
- Modify `.github/workflows/ci.yml`: grant the reusable workflow read-only access to PR file metadata.
- Modify `.github/workflows/release.yml`: grant the same read-only permission so the called workflow's declared permission never exceeds either caller.
- Modify `src/test/java/games/cafecito/foundry/build/ReusableGateWorkflowContractTest.java`: protect pagination, count validation, fail-closed handling, permissions, and step scoping.
- Modify `src/test/java/games/cafecito/foundry/build/EngineLoadedConformanceGateContractTest.java`: protect unconditional non-PR execution and the selective engine-step condition.
- Modify `docs/engine-pin.md`: document the PR classification policy and unconditional `main`/release behavior.
- Modify `docs/releasing.md`: state that release calls always select the engine-loaded gate.

### Task 1: Build the deterministic path classifier

**Files:**
- Create: `gradle/classify-engine-gate-paths.sh`
- Create: `src/test/java/games/cafecito/foundry/build/EngineGateChangeClassifierTest.java`

- [ ] **Step 1: Write the failing classifier behavior test**

Create `src/test/java/games/cafecito/foundry/build/EngineGateChangeClassifierTest.java`:

```java
package games.cafecito.foundry.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EngineGateChangeClassifierTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void documentationBrandingTemplatesAndTestSourcesAreSafeToSkip() throws Exception {
        assertEquals(
                new Decision(false, "safe-only"),
                classify(
                        """
                        [
                          "docs/engine-pin.md",
                          "README.md",
                          "assets/logo/foundry.svg",
                          ".github/ISSUE_TEMPLATE/bug.yml",
                          ".github/PULL_REQUEST_TEMPLATE.md",
                          "src/test/java/example/RepositoryTest.java",
                          "foundry-java-runtime/src/test/java/example/RuntimeTest.java",
                          "foundry-java-android/src/testFixtures/cpp/native_fixture.cpp",
                          "gradle/testFixtures/example/build.gradle.kts"
                        ]
                        """));
    }

    @Test
    void everyProductionIntegrationAndBuildCategoryRunsTheGate() throws Exception {
        for (String path :
                new String[] {
                    "foundry-java-android/src/main/java/example/Binding.java",
                    "foundry-java-runtime/src/main/java/example/Runtime.java",
                    "acceptance/project/main.fs",
                    "samples/conformance-java/build.gradle.kts",
                    "api/current/extension_api.json",
                    "build.gradle.kts",
                    "gradle.lockfile",
                    "gradle/engine-pin.json",
                    "gradle/run-engine-loaded-conformance-gate.sh",
                    ".github/workflows/gates.yml"
                }) {
            assertEquals(
                    new Decision(true, "relevant"),
                    classify("[\"" + path + "\"]"),
                    path);
        }
    }

    @Test
    void aMixedOrUnknownChangeRunsTheGate() throws Exception {
        assertEquals(
                new Decision(true, "relevant"),
                classify("[\"docs/engine-pin.md\",\"foundry-java-android/src/main/AndroidManifest.xml\"]"));
        assertEquals(
                new Decision(true, "relevant"),
                classify("[\"future-module/new-file.txt\"]"));
    }

    @Test
    void emptyOrMalformedInputFailsClosed() throws Exception {
        assertEquals(new Decision(true, "fail-closed"), classify("[]"));
        assertEquals(new Decision(true, "fail-closed"), classify("{}"));
        assertEquals(new Decision(true, "fail-closed"), classify("[\"\"]"));
        assertEquals(new Decision(true, "fail-closed"), classify("not-json"));
    }

    private static Decision classify(String input) throws Exception {
        Process process =
                new ProcessBuilder("bash", "gradle/classify-engine-gate-paths.sh")
                        .directory(ROOT.toFile())
                        .redirectErrorStream(true)
                        .start();
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "classifier timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        Map<String, String> fields =
                output.lines()
                        .filter(line -> line.contains("="))
                        .map(line -> line.split("=", 2))
                        .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
        assertEquals(2, fields.size(), output);
        return new Decision(Boolean.parseBoolean(fields.get("run")), fields.get("reason"));
    }

    private record Decision(boolean run, String reason) {}
}
```

- [ ] **Step 2: Run the test to establish RED**

Run:

```bash
./gradlew test --tests games.cafecito.foundry.build.EngineGateChangeClassifierTest
```

Expected: FAIL because `gradle/classify-engine-gate-paths.sh` does not exist.

- [ ] **Step 3: Implement the minimal pure classifier**

Create `gradle/classify-engine-gate-paths.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

input="$(cat)"
if ! decision="$(
  jq -r '
    def safe_to_skip:
      test("\\.md$") or
      startswith("docs/") or
      startswith("assets/") or
      test("(^|/)src/test(Fixtures)?/") or
      startswith("gradle/testFixtures/") or
      startswith(".github/ISSUE_TEMPLATE/") or
      test("^\\.github/PULL_REQUEST_TEMPLATE(?:\\.md|/)");

    if type != "array" then
      ["run=true", "reason=fail-closed"]
    elif length == 0 then
      ["run=true", "reason=fail-closed"]
    elif any(.[]; type != "string" or length == 0) then
      ["run=true", "reason=fail-closed"]
    elif all(.[]; safe_to_skip) then
      ["run=false", "reason=safe-only"]
    else
      ["run=true", "reason=relevant"]
    end
    | .[]
  ' <<<"$input"
)"; then
  printf 'run=true\nreason=fail-closed\n'
  exit 0
fi

printf '%s\n' "$decision"
```

The malformed-JSON branch deliberately exits successfully with `run=true`: classifier failure is a
decision to run the expensive proof, not a reason to fail CI.

- [ ] **Step 4: Run focused verification to establish GREEN**

Run:

```bash
./gradlew test --tests games.cafecito.foundry.build.EngineGateChangeClassifierTest
bash -n gradle/classify-engine-gate-paths.sh
```

Expected: both commands PASS.

- [ ] **Step 5: Commit the classifier**

```bash
git add gradle/classify-engine-gate-paths.sh \
  src/test/java/games/cafecito/foundry/build/EngineGateChangeClassifierTest.java
git commit -m "test: classify selective engine gate changes"
```

### Task 2: Wire the fail-closed decision into the shared device job

**Files:**
- Modify: `.github/workflows/gates.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `src/test/java/games/cafecito/foundry/build/ReusableGateWorkflowContractTest.java`
- Modify: `src/test/java/games/cafecito/foundry/build/EngineLoadedConformanceGateContractTest.java`

- [ ] **Step 1: Write failing workflow contract assertions**

Add this test to `ReusableGateWorkflowContractTest`:

```java
@Test
void engineGateScopeIsReadOnlyPaginatedCompleteAndFailClosed() throws IOException {
    String shared = read(SHARED);
    String ci = read(".github/workflows/ci.yml");
    String release = read(".github/workflows/release.yml");
    String deviceGate = workflowJob(shared, "device-gate");

    for (String workflow : java.util.List.of(shared, ci, release)) {
        assertTrue(
                workflow.contains("pull-requests: read"),
                "every layer must grant only read access to PR metadata");
        assertFalse(workflow.contains("pull-requests: write"));
    }

    String classifier = workflowStep(deviceGate, "Classify whether this change needs the engine-loaded gate");
    assertTrue(classifier.contains("id: engine-gate-scope"));
    assertTrue(classifier.contains("GH_TOKEN: ${{ github.token }}"));
    assertTrue(classifier.contains("github.event.pull_request.number"));
    assertTrue(classifier.contains("github.event.pull_request.changed_files"));
    assertTrue(classifier.contains("gh api --paginate --slurp"));
    assertTrue(classifier.contains("pulls/${PR_NUMBER}/files?per_page=100"));
    assertTrue(classifier.contains(".previous_filename?"));
    assertTrue(classifier.contains("observed_count"));
    assertTrue(classifier.contains("EXPECTED_CHANGED_FILES"));
    assertTrue(classifier.contains("bash gradle/classify-engine-gate-paths.sh"));
    assertTrue(classifier.contains("run=true"));
    assertTrue(classifier.contains("classification-failed"));

    String condition = "if: steps.engine-gate-scope.outputs.run == 'true'";
    assertEquals(1, occurrences(deviceGate, condition));
    assertTrue(
            workflowStep(deviceGate, "Run the engine-loaded API 36 conformance gate")
                    .contains(condition));
    for (String unconditional :
            java.util.List.of(
                    "Create and launch the API 36 emulator",
                    "Wait for observable emulator boot",
                    "Run production startup twice in fresh processes",
                    "Run the Java and Kotlin conformance matrix as consumer samples",
                    "Upload API 36 production startup evidence",
                    "Upload device gate evidence")) {
        assertFalse(
                workflowStep(deviceGate, unconditional).contains("engine-gate-scope"),
                unconditional + " must not depend on the engine decision");
    }
}
```

Add this helper beside `workflowJob`:

```java
private static String workflowStep(String job, String name) {
    String marker = "      - name: " + name + "\n";
    assertEquals(1, occurrences(job, marker), name + " must identify one workflow step");
    int start = job.indexOf(marker);
    int end = job.indexOf("\n      - ", start + marker.length());
    return job.substring(start, end < 0 ? job.length() : end);
}
```

Extend `continuousIntegrationRunsTheGateOnTheApi36EmulatorAndKeepsItsEvidence` in
`EngineLoadedConformanceGateContractTest` with:

```java
String scopeStep = "Classify whether this change needs the engine-loaded gate";
assertTrue(workflow.contains(scopeStep));
assertTrue(
        workflow.contains(
                "if: steps.engine-gate-scope.outputs.run == 'true'\n"
                        + "        shell: bash\n"
                        + "        run: bash gradle/run-engine-loaded-conformance-gate.sh"));
assertTrue(
        workflow.contains("GITHUB_EVENT_NAME") || workflow.contains("github.event_name"),
        "non-PR events must be distinguishable so main and releases always run the gate");
```

- [ ] **Step 2: Run the focused contracts to establish RED**

Run:

```bash
./gradlew test \
  --tests games.cafecito.foundry.build.ReusableGateWorkflowContractTest \
  --tests games.cafecito.foundry.build.EngineLoadedConformanceGateContractTest
```

Expected: FAIL because no scope step, PR metadata permission, pagination, count validation, or engine
condition exists.

- [ ] **Step 3: Grant read-only pull-request metadata access**

In the `permissions` block of `.github/workflows/gates.yml`, `.github/workflows/ci.yml`, and
`.github/workflows/release.yml`, retain `contents: read` and add:

```yaml
  pull-requests: read
```

No workflow receives any write permission, secret, or protected environment.

- [ ] **Step 4: Add the fail-closed classification step**

Immediately after the device job's checkout step in `.github/workflows/gates.yml`, add:

```yaml
      - name: Classify whether this change needs the engine-loaded gate
        id: engine-gate-scope
        shell: bash
        env:
          GH_TOKEN: ${{ github.token }}
          PR_NUMBER: ${{ github.event.pull_request.number }}
          EXPECTED_CHANGED_FILES: ${{ github.event.pull_request.changed_files }}
        run: |
          set -euo pipefail
          select_gate() {
            printf 'run=%s\nreason=%s\n' "$1" "$2" >> "${GITHUB_OUTPUT}"
            printf 'Engine-loaded gate decision: run=%s reason=%s\n' "$1" "$2"
          }

          if [[ "${GITHUB_EVENT_NAME}" != "pull_request" ]]; then
            select_gate true non-pull-request
            exit 0
          fi

          if ! changed_file_pages="$(
            gh api --paginate --slurp \
              "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}/files?per_page=100"
          )"; then
            select_gate true classification-failed
            exit 0
          fi

          if ! observed_count="$(jq -er '[.[][]] | length' <<<"${changed_file_pages}")"; then
            select_gate true classification-failed
            exit 0
          fi
          if [[ "${observed_count}" != "${EXPECTED_CHANGED_FILES}" ]]; then
            select_gate true classification-incomplete
            exit 0
          fi

          if ! changed_files="$(
            jq -ec \
              '[.[][] | (.filename, .previous_filename?)] | map(select(type == "string"))' \
              <<<"${changed_file_pages}"
          )"; then
            select_gate true classification-failed
            exit 0
          fi
          if ! classification="$(
            bash gradle/classify-engine-gate-paths.sh <<<"${changed_files}"
          )"; then
            select_gate true classification-failed
            exit 0
          fi
          printf '%s\n' "${classification}" >> "${GITHUB_OUTPUT}"
          printf 'Engine-loaded gate decision: %s\n' \
            "$(sed -n 's/^reason=//p' <<<"${classification}")"
```

This step never writes a changed filename to `GITHUB_OUTPUT` or the log. Both names of a renamed
file are classified. API failure, unexpected JSON, pagination truncation, an empty list, and
classifier execution failure all select the gate.

- [ ] **Step 5: Condition only the engine-loaded step**

Change the engine-loaded step in `.github/workflows/gates.yml` to:

```yaml
      - name: Run the engine-loaded API 36 conformance gate
        if: steps.engine-gate-scope.outputs.run == 'true'
        shell: bash
        run: bash gradle/run-engine-loaded-conformance-gate.sh emulator-5554
```

Do not add the condition to Android setup, emulator lifecycle, production startup, the sample
matrix, engine cache restoration, or either evidence-upload step.

- [ ] **Step 6: Run focused tests and workflow lint to establish GREEN**

Run:

```bash
./gradlew test \
  --tests games.cafecito.foundry.build.EngineGateChangeClassifierTest \
  --tests games.cafecito.foundry.build.ReusableGateWorkflowContractTest \
  --tests games.cafecito.foundry.build.EngineLoadedConformanceGateContractTest
actionlint
```

Expected: all tests PASS and actionlint exits zero.

- [ ] **Step 7: Commit workflow behavior**

```bash
git add .github/workflows/gates.yml \
  .github/workflows/ci.yml \
  .github/workflows/release.yml \
  src/test/java/games/cafecito/foundry/build/ReusableGateWorkflowContractTest.java \
  src/test/java/games/cafecito/foundry/build/EngineLoadedConformanceGateContractTest.java
git commit -m "ci: run the engine gate only for relevant pull requests"
```

### Task 3: Document the selective cadence

**Files:**
- Modify: `docs/engine-pin.md`
- Modify: `docs/releasing.md`
- Modify: `src/test/java/games/cafecito/foundry/build/EngineLoadedConformanceGateContractTest.java`

- [ ] **Step 1: Add a failing documentation contract**

Extend `theEnginePinIsDocumentedWithABumpAndLocalReproductionProcedure` in
`EngineLoadedConformanceGateContractTest`:

```java
assertTrue(documentation.contains("## When the gate runs"));
assertTrue(documentation.contains("safe-to-skip"));
assertTrue(documentation.contains("Every push to `main`"));
assertTrue(documentation.contains("every release"));
assertTrue(documentation.contains("unknown path"));
assertTrue(read("docs/releasing.md").contains("always selects the engine-loaded gate"));
```

- [ ] **Step 2: Run the documentation contract to establish RED**

Run:

```bash
./gradlew test \
  --tests games.cafecito.foundry.build.EngineLoadedConformanceGateContractTest
```

Expected: FAIL because the selective cadence is not documented.

- [ ] **Step 3: Document PR classification and unconditional callers**

Add this section to `docs/engine-pin.md` before `## Bumping the pin`:

```markdown
## When the gate runs

Every push to `main` and every release runs the engine-loaded gate. Pull requests continue to run
the API 36 emulator, production-startup acceptance, and Java/Kotlin consumer matrix, but the
engine-loaded step is skipped when every changed file is in the explicit safe-to-skip set:
documentation, Markdown, branding assets, issue or pull-request templates, and sources under
`src/test`, `src/testFixtures`, or `gradle/testFixtures`.

Every other change runs the engine gate. A mixed change runs it, and an unknown path, incomplete
GitHub response, or classification error fails closed by running it. The classifier lives in
`gradle/classify-engine-gate-paths.sh`; update its behavioral tests whenever the safe-to-skip set
changes.
```

Add this sentence after the release device-gate description in `docs/releasing.md`:

```markdown
The release caller always selects the engine-loaded gate; the pull-request-only safe-path
optimization cannot skip release verification.
```

- [ ] **Step 4: Run focused tests and documentation checks to establish GREEN**

Run:

```bash
./gradlew test \
  --tests games.cafecito.foundry.build.EngineLoadedConformanceGateContractTest
git diff --check
```

Expected: the contract passes and `git diff --check` reports no whitespace errors.

- [ ] **Step 5: Commit the documentation**

```bash
git add docs/engine-pin.md \
  docs/releasing.md \
  src/test/java/games/cafecito/foundry/build/EngineLoadedConformanceGateContractTest.java
git commit -m "docs: explain selective engine gate coverage"
```

### Task 4: Verify and publish the follow-up

**Files:**
- Verify all files changed since `origin/main`

- [ ] **Step 1: Run static workflow and patch validation**

Run:

```bash
actionlint
git diff --check origin/main...HEAD
```

Expected: both commands exit zero.

- [ ] **Step 2: Run the full repository gate**

Run:

```bash
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`. Dependency locks remain unchanged because this work adds no
dependency.

- [ ] **Step 3: Confirm lock and worktree state**

Run:

```bash
git status --porcelain --untracked-files=all -- \
  gradle.lockfile \
  ':(glob)**/gradle.lockfile' \
  settings-gradle.lockfile
git status --short --branch
```

Expected: the first command prints nothing. The second shows only the intended committed branch
state.

- [ ] **Step 4: Review the complete diff**

Run:

```bash
git diff --stat origin/main...HEAD
git log --oneline origin/main..HEAD
```

Expected: the selective-gate classifier, workflow wiring, contracts, docs, design, and plan are
present; no unrelated files appear.

- [ ] **Step 5: Push the branch and observe both PR paths**

Run:

```bash
git push origin issue-68
gh pr checks 77 --repo cafecito-games/Foundry-Java
```

Expected: PR #77 updates. Because the implementation itself changes production CI scripts and
workflow definitions, the classifier selects the engine-loaded gate on this run. A later
documentation-only PR is expected to retain the device check but show the engine-loaded step as
skipped.
