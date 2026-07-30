#!/usr/bin/env bash

set -euo pipefail

# verify-build-cache-portability.sh asserts a property that fails silently, so a version of it that no
# longer bites would be indistinguishable from a version that passes. This drives the identical gate
# over two fixture builds, each broken in one of the exact two ways the gate exists to catch, and fails
# unless the gate rejects both for its own stated reason.
#
# absolute-path-argument has complete output declarations and stores an entry; the entry is simply
# unreadable from any other path. undeclared-output has portable arguments and stores nothing at all.
# Both defects leave a correct, green build behind, and each was present in this repository's verifier
# tasks before they were made cacheable.
#
# The expected reason is the gate's own diagnostic rather than a Gradle message. A fixture that failed
# because its build broke would exit non-zero without producing that line, and this self-test has to
# report that as a failure rather than as proof.

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gate="$repository_root/gradle/verify-build-cache-portability.sh"
fixtures="$repository_root/gradle/testFixtures/build-cache-portability"

workspace="$(mktemp -d "${TMPDIR:-/tmp}/foundry-java-cache-portability-selftest.XXXXXX")"
trap 'rm -rf "$workspace"' EXIT

# The fixtures are copied out of the checkout because the gate builds them, and a gate must not dirty
# the working tree it guards.
expect_rejection() {
  local fixture="$1"
  local defect="$2"
  local expected_reason="$3"
  local log="$workspace/$fixture.log"
  local status=0

  cp -R "$fixtures/$fixture" "$workspace/$fixture"
  bash "$gate" "$workspace/$fixture" "foundry-java-cache-portability-selftest-$fixture" \
    ":probe" >"$log" 2>&1 || status=$?

  if [[ "$status" -eq 0 ]]; then
    printf 'The portability gate accepted a task that %s. The gate no longer bites.\n' "$defect" >&2
    cat "$log" >&2
    return 1
  fi
  if ! grep -Eq "$expected_reason" "$log"; then
    printf 'The portability gate rejected the task that %s, but not for the expected reason.\n' \
      "$defect" >&2
    printf 'Expected to match: %s\n' "$expected_reason" >&2
    cat "$log" >&2
    return 1
  fi
  printf 'ok: rejected the task that %s (exit %d)\n' "$defect" "$status"
}

expect_rejection absolute-path-argument \
  'puts its own absolute output path into a cached task argument' \
  'Task :probe was not replayed from the build cache by a differently-pathed checkout'

expect_rejection undeclared-output \
  'declares no outputs, so Gradle stores nothing for it' \
  'Task :probe was not replayed from the build cache by a differently-pathed checkout'

printf 'The build cache portability gate still fails both defects it exists to catch.\n'
