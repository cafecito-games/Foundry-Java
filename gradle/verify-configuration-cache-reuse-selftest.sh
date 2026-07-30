#!/usr/bin/env bash

set -euo pipefail

# verify-configuration-cache-reuse.sh became roughly eight minutes cheaper by configuring, rather
# than executing, its second invocation. A faster gate that no longer bites would be worse than the
# slow one it replaced, so this drives the identical proof over two fixture builds that are each
# broken in one of the exact ways the two runs exist to catch, and fails unless the proof rejects
# both for the right reason.
#
# configuration-cache-violation is caught by run 1, which executes with
# --configuration-cache-problems=fail. unstable-configuration-input passes run 1 and stores cleanly;
# only run 2's fingerprint check can see that the entry is discarded on every build. That second
# fixture is what establishes that a --dry-run second invocation still checks fingerprints instead of
# reusing blindly.

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
proof="$repository_root/gradle/verify-configuration-cache-reuse.sh"
fixtures="$repository_root/gradle/testFixtures/configuration-cache"

workspace="$(mktemp -d "${TMPDIR:-/tmp}/foundry-java-cc-reuse-selftest.XXXXXX")"
trap 'rm -rf "$workspace"' EXIT

# The fixtures are copied out of the checkout because unstable-configuration-input rewrites one of
# its own tracked source files as it runs, and a gate must not dirty the working tree it guards.
expect_rejection() {
  local fixture="$1"
  local defect="$2"
  local expected_reason="$3"
  local log="$workspace/$fixture.log"
  local status=0

  cp -R "$fixtures/$fixture" "$workspace/$fixture"
  bash "$proof" "$workspace/$fixture" "foundry-java-cc-reuse-selftest-$fixture" \
    >"$log" 2>&1 || status=$?

  if [[ "$status" -eq 0 ]]; then
    printf 'The reuse proof accepted a build that %s. The gate no longer bites.\n' "$defect" >&2
    cat "$log" >&2
    return 1
  fi
  if ! grep -Eq "$expected_reason" "$log"; then
    printf 'The reuse proof rejected the build that %s, but not for the expected reason.\n' \
      "$defect" >&2
    printf 'Expected to match: %s\n' "$expected_reason" >&2
    cat "$log" >&2
    return 1
  fi
  printf 'ok: rejected the build that %s (exit %d)\n' "$defect" "$status"
}

expect_rejection configuration-cache-violation \
  'holds a Project reference at execution time' \
  "invocation of 'Task\.project' at execution time is unsupported"

expect_rejection unstable-configuration-input \
  'rewrites one of its own configuration inputs while it runs' \
  'configuration cache cannot be reused because file'

printf 'The configuration cache reuse proof still fails both defects it exists to catch.\n'
