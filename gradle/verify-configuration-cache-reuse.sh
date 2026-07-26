#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "$repo_root/settings.gradle.kts" || ! -x "$repo_root/gradlew" ]]; then
  printf 'Unable to identify the Foundry Java repository root: %s\n' "$repo_root" >&2
  exit 1
fi

cd "$repo_root"

# The first configuration must observe the same empty output state as a clean CI checkout.
rm -rf "$repo_root/build" "$repo_root/.gradle/configuration-cache"
find "$repo_root" -mindepth 2 -maxdepth 2 -type d -name build -exec rm -rf {} +

log_directory="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
first_log="$log_directory/foundry-java-configuration-cache-first.log"
second_log="$log_directory/foundry-java-configuration-cache-second.log"
gradle_command=(
  ./gradlew
  --no-daemon
  clean
  check
  --configuration-cache
  --configuration-cache-problems=fail
)

"${gradle_command[@]}" 2>&1 | tee "$first_log"
"${gradle_command[@]}" 2>&1 | tee "$second_log"

if grep -Fq 'configuration cache cannot be reused' "$second_log"; then
  printf 'The immediate second clean check discarded the configuration cache.\n' >&2
  exit 1
fi
grep -Eq 'Configuration cache entry reused|Reusing configuration cache' "$second_log"
