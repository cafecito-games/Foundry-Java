# Reusable CI Gates Design

## Context

Issue #68 tracks duplicated host and API 36 device gate definitions in
`.github/workflows/ci.yml` and `.github/workflows/release.yml`. Both copies are currently correct,
but setup steps, emulator lifecycle, gate invocations, and evidence collection can drift because
each workflow owns a separate definition.

The repository currently protects `main` with an exact `check` status. Calling a reusable workflow
changes the visible check names to the reusable workflow's nested jobs. The repository owner has
confirmed that branch protection may be updated, so the implementation does not need a synthetic
aggregator job solely to preserve the old status name.

## Goals

- Make one reusable workflow the sole owner of the host and API 36 device gate jobs.
- Have both pull-request/default-branch CI and tag-driven release call that workflow.
- Preserve the current CI and release behavior, including their deliberate differences.
- Keep staging, signing, and publishing exclusively in the release workflow.
- Add repository contracts that reject renewed duplication.

## Non-goals

- Change the Gradle dependency graph or dependency locks.
- Add, remove, or weaken a gate.
- Change signing, staging, publishing, or release-recovery behavior.
- Preserve the old exact `check` status context.
- Introduce a composite action or multiple reusable workflows.

## Architecture

Create `.github/workflows/gates.yml` with an `on.workflow_call` trigger, a boolean `release` input,
and a boolean `dry_run` input used only by release-mode manual dispatches. The workflow contains two
jobs:

1. `host-gate` owns Java and Android setup, wrapper validation, configuration-cache proof,
   native build and inspection, dependency-lock validation, and evidence upload.
2. `device-gate` owns Java and Android emulator setup, emulator boot, production startup,
   Java/Kotlin consumer conformance, engine-loaded conformance, and evidence upload.

The reusable workflow uses the `release` input only where the existing callers differ:

- release checkout history and release precondition handling;
- CI-only Gradle cache setup and configuration-cache self-test;
- the release host task set, which includes the explicit runtime API, realization, and native ABI
  layout verifiers;
- release versus CI dependency-lock validation;
- release versus CI artifact names and evidence paths;
- the existing emulator names.

Common setup and gate invocations are written once. Conditional steps remain adjacent in the shared
workflow so the mode-specific behavior is visible without consulting either caller.

## Caller workflows

`.github/workflows/ci.yml` retains only its push and pull-request triggers, read-only contents
permission, and one job that calls `./.github/workflows/gates.yml` with the default non-release
mode.

`.github/workflows/release.yml` retains its tag/manual triggers, concurrency policy, staging,
signing, publishing, environments, secrets, and recovery logic. It replaces `host-gate` and
`device-gate` with one reusable-workflow call passing `release: true` and the manual-dispatch
`dry_run` state needed by the existing refusal. The `stage` job depends on the reusable-workflow
call, which does not succeed until both called jobs succeed.

No release credential is passed to the reusable workflow. Its permissions remain read-only and it
cannot reach either protected release environment.

## Error handling and ordering

GitHub treats a reusable-workflow call as failed when any non-skipped called job fails. CI therefore
fails if either host or device verification fails. In release mode, `stage` depends on that call and
cannot begin until both gate jobs succeed.

The manual-dispatch refusal remains at the beginning of the release-mode host gate. Gate evidence
uploads retain `if: always()` so failures still preserve diagnostics. Staging and publishing remain
outside the reusable workflow, preventing any gate-only caller from invoking release operations.

## Contract tests

Add a focused repository contract that proves:

- `gates.yml` is triggered by `workflow_call` and declares the release and dry-run inputs;
- both `ci.yml` and `release.yml` call the same local reusable workflow;
- release passes release mode while CI does not;
- gate job definitions and representative setup/emulator commands exist only in `gates.yml`;
- the release `stage` job depends on its reusable-workflow call.

Update existing `RepositoryContractTest`, `EngineLoadedConformanceGateContractTest`, and
`ReleasePipelineContractTest` assertions to read gate-owned content from `gates.yml`, while leaving
trigger, staging, credential, and publishing assertions against their actual caller workflow.
These changes keep the tests aligned with ownership rather than weakening their behavioral
coverage.

Run the new contract test before creating `gates.yml` to establish the required failing TDD state.
After implementation, run focused contract tests, `actionlint`, and the full
`./gradlew clean check` repository gate.

## Documentation and rollout

Update `docs/releasing.md` to describe the release workflow as calling the shared host/device gate
workflow before its stage and publish jobs. On the pull request, observe the exact nested
reusable-workflow check names and update branch protection from the old exact `check` context before
or as part of merging. Do not merge until the desired nested checks protect `main`. This external
repository setting is operational and is not encoded in this repository.
