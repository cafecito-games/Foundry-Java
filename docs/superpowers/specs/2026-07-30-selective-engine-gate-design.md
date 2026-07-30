# Selective Engine-Loaded PR Gate Design

## Context

The API 36 device job currently takes about 20 to 22 minutes. Emulator setup, production startup,
and the Java/Kotlin consumer matrix account for roughly eight minutes. The engine-loaded
conformance gate accounts for another 10 to 13 minutes because it builds the artifacts under test,
runs a negative registration proof, exports five applications through the pinned Foundry engine,
inspects their payloads, and executes the four positive variants on the emulator.

That engine proof is uniquely valuable when a change can affect binding registration, JNI,
packaging, generated or runtime ABI, minification, the Gradle plugin, the acceptance project, or the
pinned engine contract. It provides little additional confidence for a pull request that changes
only documentation or test sources. The full proof already runs after every merge through the
`main` push trigger and before every release through the release workflow.

This design is a follow-up refinement to the reusable gates introduced for issue #68. It changes
only when the engine-loaded step runs for pull requests; ownership remains in the shared gate
workflow.

## Goals

- Keep the API 36 production-startup and Java/Kotlin sample checks on every pull request.
- Skip only the engine-loaded step for pull requests whose changed files are all known not to affect
  production or integration behavior.
- Run the engine-loaded step for every push to `main` and every release invocation.
- Default to running the engine gate when classification is unavailable or encounters an unfamiliar
  path.
- Bind the files response to the pull-request head and base recorded by the workflow event.
- Make the classification policy explicit and covered by repository contract tests.

## Non-goals

- Remove the engine-loaded gate from continuous integration or release verification.
- Skip the whole API 36 device job.
- Add a nightly workflow; successful merges already exercise the full gate on `main`.
- Use labels or manual reviewer judgment to decide whether the gate runs.
- Change what the engine-loaded gate proves or reduce its five export scenarios.
- Change the required `check / check` branch-protection context.

## Policy

Non-pull-request callers always run the engine-loaded gate. This includes pushes to `main`, tag
releases, and release dry runs.

For a pull request, the engine-loaded gate is skipped only when every changed path belongs to the
following conservative safe-to-skip set:

- documentation and Markdown files;
- repository branding assets;
- Java, Kotlin, and native test sources under a `src/test` or `src/testFixtures` tree;
- repository-only contract tests under the root `src/test` tree;
- files under `gradle/testFixtures`;
- GitHub issue and pull-request templates.

All other paths run the gate. In particular, production module sources, samples, acceptance
projects, generated API inputs, Gradle build logic, dependency declarations and locks, engine-pin
files, shell verification scripts, and workflow definitions are relevant. A pull request containing
both safe-to-skip and relevant paths runs the gate. A renamed file is classified by both its old and
new path, so moving a production file into a safe-to-skip tree still runs the gate.

The classification pipeline fails closed: an API error, an empty or malformed changed-file
response, duplicate current filenames, pull-request snapshot drift, or any path outside the
explicit safe-to-skip set produces `run=true`. A newly introduced directory therefore cannot
silently escape the gate.

## Workflow design

The shared device job gains one classification step after checkout. For pull requests, it receives
the event head SHA, base SHA, and changed-file count. It queries pull-request metadata before and
after file pagination and proceeds only when both API snapshots match the event head and base and
match each other. This prevents a same-count file-list replacement from being classified against a
different revision. Path collection is delegated to the testable
`gradle/extract-engine-gate-paths.sh`, which validates one paginated response, the page and item
shapes, the documented status allowlist, every required current filename, and the required previous
filename for a rename. It rejects duplicate current filenames before accepting the exact expected
changed-file count. Malformed metadata, unknown statuses, incomplete responses, snapshot drift, and
collection errors fail closed. For other events the workflow immediately selects the full gate.
The workflow retains read-only permissions.

The extractor emits both the current and previous paths for renamed files.
`gradle/classify-engine-gate-paths.sh` then applies the safe path policy. The workflow accepts only
the classifier's fixed decisions and exposes the selected decision as output; unexpected output
fails closed. That output is consumed only by the engine-loaded conformance step. Emulator
creation, observable boot, production startup, the Java/Kotlin conformance matrix, cache
restoration, and evidence collection remain unconditional. The engine cache may still be restored
on a skipped pull request because the cache action is fast and keeping the surrounding job shape
stable avoids coupling cache behavior to the classifier.

When skipped, the job log records that every changed path was in the safe-to-skip set. When run, it
records whether the reason was a non-pull-request event, at least one relevant path, or fail-closed
classification. It must not print credentials or request broader permissions.

## Testing

`EngineGateApiResponseExtractorTest` covers valid multi-page responses and all seven documented
statuses, current and previous rename paths, page and item schema failures, invalid or missing
filenames and statuses, unknown statuses, duplicate current filenames, an exact positive expected
count, duplicate rejection before count-mismatch classification, distinct count-mismatch handling,
and silent failures that do not leak paths.

`EngineGateChangeClassifierTest` covers:

- documentation-only and test-only path lists select `run=false`;
- production source, acceptance, sample, build, lock, workflow, and engine-pin paths select
  `run=true`;
- a mixed safe/relevant list selects `run=true`;
- an unknown path selects `run=true`;
- an empty or malformed input selects `run=true`.

Workflow contract tests prove that:

- only pull requests are eligible for the skip;
- non-pull-request callers always select the engine gate;
- the decision controls only the engine-loaded step;
- the faster API 36 checks and evidence upload remain independent of the decision;
- changed-file collection is paginated and failure selects the gate;
- event head/base metadata matches API metadata before and after pagination;
- before/after snapshot drift, including same-count replacement, selects the gate;
- only fixed decision reasons reach logs and workflow outputs.

Run the focused classifier and workflow contracts first, followed by `actionlint` and
`./gradlew clean check`.

## Expected result

Relevant pull requests retain the current 20-to-22-minute device coverage. Documentation-only and
test-only pull requests should finish the device job in roughly eight minutes. Every merged commit
and every release still receives the complete engine-loaded proof, so a classifier mistake cannot
allow an untested release.
