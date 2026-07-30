# Selective Engine-Loaded PR Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Skip the expensive engine-loaded conformance step only for pull requests whose changes are entirely documentation, branding, templates, or test sources, while continuing to run the full gate on relevant PRs, `main`, and releases.

**Architecture:** Add a testable Bash/JQ extractor that validates the paginated GitHub API response
and emits the complete current-and-previous path list, followed by a pure Bash/JQ classifier that
emits a fixed `run`/`reason` decision. The shared device workflow fails closed on every collection,
extraction, or classification error and applies the validated decision only to the engine-loaded
step.

**Tech Stack:** GitHub Actions reusable workflows, Bash, GitHub CLI, JQ, Java 17, JUnit 5, Gradle, actionlint.

---

## File structure

- Create `gradle/classify-engine-gate-paths.sh`: pure deterministic changed-path classifier; it has no GitHub dependency and never prints an untrusted path.
- Create `src/test/java/games/cafecito/foundry/build/EngineGateChangeClassifierTest.java`: executes the classifier against the safe, relevant, mixed, unknown, empty, and malformed cases.
- Create `gradle/extract-engine-gate-paths.sh`: validates the complete paginated API response and
  emits current and previous paths without leaking malformed metadata.
- Create `src/test/java/games/cafecito/foundry/build/EngineGateApiResponseExtractorTest.java`:
  exercises API schema, count, status, path, and silent-failure behavior independently of GitHub.
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
./gradlew :test --tests games.cafecito.foundry.build.EngineGateChangeClassifierTest
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
./gradlew :test --tests games.cafecito.foundry.build.EngineGateChangeClassifierTest
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

Task 2 established the workflow decision and step scoping. Its snippets show the final reviewed
state; the extractor's later addition and behavior tests are recorded separately in the
review-hardening subsection after this task.

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
    assertTrue(classifier.contains("EXPECTED_CHANGED_FILES"));
    assertTrue(classifier.contains("bash gradle/extract-engine-gate-paths.sh"));
    assertTrue(classifier.contains("extract_status"));
    assertTrue(classifier.contains("classification-incomplete"));
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
./gradlew :test \
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

- [ ] **Step 4: Add the fail-closed classification step (final reviewed form)**

The initial wiring established the scope step. After the review-hardening work recorded below, its
final form immediately after the device job's checkout step in `.github/workflows/gates.yml` is:

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
            local run="$1"
            local reason="$2"
            printf 'run=%s\nreason=%s\n' "$run" "$reason" >> "$GITHUB_OUTPUT"
            printf 'Engine-loaded gate decision: run=%s reason=%s\n' "$run" "$reason"
          }

          if [[ "${GITHUB_EVENT_NAME}" != "pull_request" ]]; then
            select_gate true non-pull-request
            exit 0
          fi

          if ! pages="$(
            gh api --paginate --slurp \
              "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}/files?per_page=100" 2>/dev/null
          )"; then
            select_gate true classification-failed
            exit 0
          fi
          set +e
          paths="$(
            printf '%s' "$pages" |
              bash gradle/extract-engine-gate-paths.sh "$EXPECTED_CHANGED_FILES" 2>/dev/null
          )"
          extract_status="$?"
          set -e
          case "$extract_status" in
            0)
              ;;
            2)
              select_gate true classification-incomplete
              exit 0
              ;;
            *)
              select_gate true classification-failed
              exit 0
              ;;
          esac
          if ! decision="$(
            printf '%s' "$paths" |
              bash gradle/classify-engine-gate-paths.sh 2>/dev/null
          )"; then
            select_gate true classification-failed
            exit 0
          fi

          case "$decision" in
            $'run=false\nreason=safe-only')
              select_gate false safe-only
              ;;
            $'run=true\nreason=relevant')
              select_gate true relevant
              ;;
            $'run=true\nreason=fail-closed')
              select_gate true fail-closed
              ;;
            *)
              select_gate true classification-failed
              ;;
          esac
```

This step never writes a changed filename to `GITHUB_OUTPUT` or the log. Both names of a renamed
file are classified. Extractor status `2` maps to `classification-incomplete`; every other
nonzero extractor status maps to `classification-failed`. API failure, malformed metadata,
pagination truncation, an empty list, classifier execution failure, and an unexpected classifier
decision all select the gate. Only a fixed decision is copied to the workflow output.

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
./gradlew :test \
  --tests games.cafecito.foundry.build.EngineGateApiResponseExtractorTest \
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

### Review hardening after Task 2: Validate API metadata before classification

This review-hardening work records the extractor introduced after the initial workflow wiring; it
does not change Task 1's path-policy contract.

**Files:**
- Create: `gradle/extract-engine-gate-paths.sh`
- Create: `src/test/java/games/cafecito/foundry/build/EngineGateApiResponseExtractorTest.java`
- Modify: `.github/workflows/gates.yml`
- Modify: `src/test/java/games/cafecito/foundry/build/ReusableGateWorkflowContractTest.java`

- [ ] **Step 1: Specify extractor responsibilities with behavior tests**

`EngineGateApiResponseExtractorTest` requires:

- exactly one JSON response whose outer value is a nonempty array of nonempty page arrays and
  whose page items are objects;
- one positive integer expected count and an exact flattened item count;
- the exact GitHub status allowlist: `added`, `removed`, `modified`, `renamed`, `copied`, `changed`,
  and `unchanged`;
- a nonempty string current filename for every item, a nonempty string for any present previous
  filename, and a required previous filename for every renamed item;
- current and previous paths emitted in API order;
- exit `2` for count mismatch, exit `1` for malformed metadata or invalid invocation, and no stdout
  or stderr on either failure path.

Run the extractor test before creating the script to establish RED:

```bash
./gradlew :test \
  --tests games.cafecito.foundry.build.EngineGateApiResponseExtractorTest \
  --rerun-tasks
```

Expected: FAIL because `gradle/extract-engine-gate-paths.sh` does not exist.

- [ ] **Step 2: Route only validated paths and fixed decisions**

The workflow pipes the single `gh api --paginate --slurp` response through
`gradle/extract-engine-gate-paths.sh`. Extractor exit `2` selects
`run=true reason=classification-incomplete`; exit `1` or any other nonzero status selects
`run=true reason=classification-failed`. Status `0` is the only path to
`gradle/classify-engine-gate-paths.sh`.

The workflow accepts only `run=false/reason=safe-only`, `run=true/reason=relevant`, or
`run=true/reason=fail-closed` from the classifier and maps any other output to
`run=true/reason=classification-failed`. It never exposes raw API metadata or unchecked classifier
output.

- [ ] **Step 3: Verify extractor, classifier, and workflow behavior**

Run the focused six-class suite from the repository root:

```bash
./gradlew :test \
  --tests games.cafecito.foundry.build.EngineGateApiResponseExtractorTest \
  --tests games.cafecito.foundry.build.EngineGateChangeClassifierTest \
  --tests games.cafecito.foundry.build.ReusableGateWorkflowContractTest \
  --tests games.cafecito.foundry.build.EngineLoadedConformanceGateContractTest \
  --tests games.cafecito.foundry.build.ReleasePipelineContractTest \
  --tests games.cafecito.foundry.build.RepositoryContractTest \
  --rerun-tasks
bash -n gradle/extract-engine-gate-paths.sh
bash -n gradle/classify-engine-gate-paths.sh
actionlint
```

Expected: all six test classes pass, both shell scripts parse, and `actionlint` exits zero.

### Task 3: Document the selective cadence

**Files:**
- Modify: `docs/engine-pin.md`
- Modify: `docs/releasing.md`
- Modify: `src/test/java/games/cafecito/foundry/build/EngineLoadedConformanceGateContractTest.java`

- [ ] **Step 1: Add a failing documentation contract**

Extend `theEnginePinIsDocumentedWithABumpAndLocalReproductionProcedure` in
`EngineLoadedConformanceGateContractTest`:

```java
String normalizedDocumentation = documentation.replaceAll("\\s+", " ");
String releasing = read("docs/releasing.md");

assertTrue(documentation.contains("## When the gate runs"));
assertTrue(documentation.contains("safe-to-skip"));
assertTrue(documentation.contains("Every push to `main`"));
assertTrue(documentation.contains("every release"));
assertTrue(documentation.contains("release dry-run"));
assertTrue(documentation.contains("unknown path"));
assertTrue(documentation.contains("gradle/extract-engine-gate-paths.sh"));
assertTrue(documentation.contains("gradle/classify-engine-gate-paths.sh"));
assertTrue(normalizedDocumentation.contains("current and previous paths"));
assertTrue(
        normalizedDocumentation.contains("malformed metadata")
                && normalizedDocumentation.contains("incomplete API response")
                && normalizedDocumentation.contains("unknown GitHub file status")
                && normalizedDocumentation.contains("fail closed"));
assertTrue(
        normalizedDocumentation.contains("Only the engine-loaded step is skipped")
                && normalizedDocumentation.contains("API 36 emulator")
                && normalizedDocumentation.contains("production startup")
                && normalizedDocumentation.contains("Java/Kotlin consumer matrix"));
assertTrue(releasing.contains("always selects the engine-loaded gate"));
```

- [ ] **Step 2: Run the documentation contract to establish RED**

Run:

```bash
./gradlew :test \
  --tests games.cafecito.foundry.build.EngineLoadedConformanceGateContractTest \
  --rerun-tasks
```

Expected: FAIL because the selective cadence is not documented.

- [ ] **Step 3: Document PR classification and unconditional callers**

Add this section to `docs/engine-pin.md` before `## Bumping the pin`:

```markdown
## When the gate runs

Every push to `main` runs the engine-loaded gate, as does every release and release dry-run. A pull
request always runs the API 36 emulator, production startup, and Java/Kotlin consumer matrix. Only
the engine-loaded step is skipped, and only when all changed files belong to the explicit
safe-to-skip set: documentation or Markdown, branding assets, issue or pull-request templates, and
sources under `src/test`, `src/testFixtures`, or `gradle/testFixtures`.

A mixed change or unknown path runs the gate. Collection or classification errors, an incomplete
API response, malformed metadata, and an unknown GitHub file status fail closed by running it.
Renamed files are classified by both their current and previous paths.

`gradle/extract-engine-gate-paths.sh` validates the paginated GitHub API response schema, the exact
changed-file count, and all seven documented statuses. `gradle/classify-engine-gate-paths.sh` then
applies the safe path policy.
```

Add this sentence after the release device-gate description in `docs/releasing.md`:

```markdown
The release caller always selects the engine-loaded gate; the pull-request-only
optimization cannot skip release verification.
```

- [ ] **Step 4: Run focused tests and documentation checks to establish GREEN**

Run:

```bash
./gradlew :test \
  --tests games.cafecito.foundry.build.EngineLoadedConformanceGateContractTest \
  --rerun-tasks
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
