#!/usr/bin/env bash

set -euo pipefail

# Proves two independent properties of this repository's build logic. Neither is a test of whether
# Gradle's own configuration cache works.
#
# Run 1 executes the real task graph from an empty output state with
# --configuration-cache-problems=fail. It catches configuration-cache *violations* in build logic: a
# task holding a Project reference at execution time, non-serializable task state, a
# configuration-time environment read. This repository is unusually exposed to those, between AGP, an
# annotation processor, CMake/NDK wiring, and hand-written JavaExec verifier tasks.
#
# Run 2 catches cache *discard from unstable fingerprints*: the entry stores, but a configuration
# input is not stable across runs — an absolute path, a timestamp, a file the build writes and then
# reads — so Gradle silently re-configures every time. Correctness is preserved and the speed is
# lost with nothing reporting it, and a single run cannot detect that.
#
# Whether Gradle would reuse the stored entry is decided entirely during configuration, before any
# task action runs, so run 2 uses --dry-run. The identical task graph is configured and the
# fingerprint check runs; nothing executes. Reuse also means the serialized task graph was read back
# successfully, so the load path is still exercised — only task actions are skipped, and run 1
# already ran those for real. Executing the whole test suite a second time added nothing to this
# proof and cost roughly eight minutes of every pull request.
#
# Both runs stay --no-daemon. Run 2's proof is specifically that a *fresh process* can reuse the
# on-disk entry, and a warm daemon could satisfy the same assertion from in-memory state instead.
#
# The optional project directory argument exists so verify-configuration-cache-reuse-selftest.sh can
# drive this exact proof over fixture builds that are deliberately broken, confirming the gate still
# fails when it should. A faster gate that no longer bites would be worse than a slow one.

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "$repository_root/settings.gradle.kts" || ! -x "$repository_root/gradlew" ]]; then
  printf 'Unable to identify the Foundry Java repository root: %s\n' "$repository_root" >&2
  exit 1
fi

project_directory="${1:-$repository_root}"
if [[ ! -f "$project_directory/settings.gradle.kts" ]]; then
  printf 'Not a Gradle build: %s\n' "$project_directory" >&2
  exit 1
fi
project_directory="$(cd "$project_directory" && pwd)"
log_prefix="${2:-foundry-java-configuration-cache}"

# The first configuration must observe the same empty output state as a clean CI checkout. Only
# project output directories are cleared; the shared build cache under ~/.gradle is deliberately
# left alone, because reusing task outputs across runs is not what either run is proving.
rm -rf "$project_directory/build" "$project_directory/.gradle/configuration-cache"
find "$project_directory" -mindepth 2 -maxdepth 2 -type d -name build -exec rm -rf {} +

log_directory="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
first_log="$log_directory/$log_prefix-first.log"
second_log="$log_directory/$log_prefix-second.log"
gradle_command=(
  "$repository_root/gradlew"
  --project-dir "$project_directory"
  --no-daemon
  clean
  check
  --configuration-cache
  --configuration-cache-problems=fail
)

"${gradle_command[@]}" 2>&1 | tee "$first_log"
"${gradle_command[@]}" --dry-run 2>&1 | tee "$second_log"

if grep -Fq 'configuration cache cannot be reused' "$second_log"; then
  printf 'The immediate second configuration of the identical task graph discarded the cache.\n' >&2
  exit 1
fi
grep -Eq 'Configuration cache entry reused|Reusing configuration cache' "$second_log"
