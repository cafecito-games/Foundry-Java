#!/usr/bin/env bash

set -euo pipefail

# Proves that this repository's hand-written verifier tasks produce build cache entries that can be
# replayed by a checkout at a different absolute path.
#
# The property is not automatic and it fails silently. Exec and JavaExec put `args` into the cache key
# and Test puts `systemProperties` there, so a task that names its inputs or outputs by absolute path
# stores an entry that only the checkout which produced it can ever read. Nothing reports that: the
# build is correct, the cache is enabled, every run is a miss, and the only symptom is a job that is
# mysteriously slow on one machine and fast on another. The same is true of a task that declares no
# outputs at all — Gradle stores nothing and says nothing.
#
# Two copies of the tree are used rather than one, at deliberately different path lengths, and both
# start with no project output directories. That makes the check self-contained: the first run either
# reuses an entry from the ambient cache or executes and stores one, and the second run must then
# replay it. Asserting against the checkout under test instead would be unreliable, because a task
# whose outputs are already present reports UP-TO-DATE and stores nothing, so an empty cache would
# read as a pass.
#
# The default task set is deliberately partial. :foundry-java-gradle-plugin:test,
# :foundry-java-android:nativeHostTest and :foundry-java-android:nativeSanitizerTest use the identical
# mechanism but are minutes of work each when they miss, and a gate that re-executes them would double
# the cost of exactly the pull requests that touch the plugin or the C++. Their portability is
# asserted from the build files by RepositoryContractTest instead.
#
# Both runs stay --no-daemon to match the rest of the CI job. The project directory and task arguments
# exist so verify-build-cache-portability-selftest.sh can drive this exact gate over fixture builds
# that are deliberately unportable, confirming that it still fails when it should.

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
log_prefix="${2:-foundry-java-build-cache-portability}"

if ! command -v rsync >/dev/null 2>&1; then
  printf 'rsync is required to copy the checkout to a second path.\n' >&2
  exit 1
fi

tasks=(
  :foundry-java-runtime:verifyRuntimeApi
  :foundry-java-runtime:verifyGeneratedRealization
  :foundry-java-kotlin:verifyKotlinApi
  :foundry-java-android:nativeAbiLayoutTest
)
if [[ "$#" -gt 2 ]]; then
  tasks=("${@:3}")
fi

workspace="$(mktemp -d "${TMPDIR:-/tmp}/foundry-java-cache-portability.XXXXXX")"
trap 'rm -rf "$workspace"' EXIT

# The two destinations differ in path length as well as in name, so any absolute path that leaked into
# a cache key changes the key between them rather than merely relocating it.
store_directory="$workspace/a"
replay_directory="$workspace/replayed-from-a-much-longer-checkout-path"

# .gradle carries the configuration cache and other absolute-path state, .git is irrelevant here, and
# every build directory is excluded so neither copy can report UP-TO-DATE and skip the cache entirely.
#
# .git is excluded without a trailing slash because in a git worktree it is a file rather than a
# directory, and copying one whose gitdir pointer no longer resolves is a trap for no benefit.
copy_project() {
  local destination="$1"
  mkdir -p "$destination"
  rsync -a \
    --exclude '.git' \
    --exclude '.gradle/' \
    --exclude '.kotlin/' \
    --exclude '.cxx/' \
    --exclude 'build/' \
    --exclude '.worktrees/' \
    "$project_directory/" "$destination/"
}

copy_project "$store_directory"
copy_project "$replay_directory"

log_directory="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
store_log="$log_directory/$log_prefix-store.log"
replay_log="$log_directory/$log_prefix-replay.log"

run_tasks() {
  local directory="$1"
  local log="$2"
  "$repository_root/gradlew" \
    --project-dir "$directory" \
    --no-daemon \
    --build-cache \
    "${tasks[@]}" 2>&1 | tee "$log"
}

run_tasks "$store_directory" "$store_log"
run_tasks "$replay_directory" "$replay_log"

status=0
for task in "${tasks[@]}"; do
  if ! grep -Fq "> Task $task FROM-CACHE" "$replay_log"; then
    printf 'Task %s was not replayed from the build cache by a differently-pathed checkout.\n' \
      "$task" >&2
    status=1
  fi
done

if [[ "$status" -ne 0 ]]; then
  printf 'Its cache key is tied to the checkout that produced it, or it declares no outputs.\n' >&2
  printf 'Stored from: %s\nReplayed in: %s\n' "$store_directory" "$replay_directory" >&2
  exit 1
fi

printf 'All %d task(s) were replayed from the build cache at a different checkout path.\n' \
  "${#tasks[@]}"
