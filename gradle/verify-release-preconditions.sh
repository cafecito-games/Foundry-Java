#!/usr/bin/env bash
# Refuses a release before any signing, staging, or upload work exists. The tag must name the
# declared project version in gradle.properties, that version must be a release version, the tag
# must point at HEAD, every tracked dependency lock must be committed and unmodified, and the
# working tree must be completely clean including untracked files.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  printf 'usage: %s <tag> [repo-root]\n' "$0" >&2
  exit 2
fi

tag="$1"
repo_root="${2:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
repo_root="$(cd "$repo_root" && pwd)"
lock_patterns=(gradle.lockfile ':(glob)**/gradle.lockfile' settings-gradle.lockfile)

fail() {
  printf 'Release refused: %s\n' "$1" >&2
  exit 1
}

properties="${repo_root}/gradle.properties"
if [[ ! -f "$properties" ]]; then
  fail "gradle.properties does not exist at ${properties}."
fi

declared_version="$(sed -n 's/^foundryVersion=\([^[:space:]]*\)[[:space:]]*$/\1/p' "$properties" | head -n 1)"
if [[ -z "$declared_version" ]]; then
  fail "gradle.properties does not declare foundryVersion."
fi

release_pattern='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.(0|[1-9][0-9]*))?$'
if [[ "$declared_version" == *SNAPSHOT* ]] || [[ ! "$declared_version" =~ $release_pattern ]]; then
  fail "the declared project version ${declared_version} is not a release version."
fi
if [[ "$tag" != "v${declared_version}" ]]; then
  fail "the release tag ${tag} does not match the declared project version ${declared_version}; expected v${declared_version}."
fi

if ! git -C "$repo_root" rev-parse -q --verify "refs/tags/${tag}" > /dev/null; then
  fail "the release tag ${tag} does not exist in this repository."
fi
tagged_commit="$(git -C "$repo_root" rev-parse "${tag}^{commit}")"
head_commit="$(git -C "$repo_root" rev-parse 'HEAD^{commit}')"
if [[ "$tagged_commit" != "$head_commit" ]]; then
  fail "the release tag ${tag} does not point at HEAD (${tagged_commit} versus ${head_commit})."
fi

tracked_locks="$(git -C "$repo_root" ls-files -- "${lock_patterns[@]}")"
if [[ -z "$tracked_locks" ]]; then
  fail "no dependency lock is tracked; a release requires committed dependency locks."
fi
while IFS= read -r lock; do
  if [[ ! -f "${repo_root}/${lock}" ]]; then
    fail "the dependency lock ${lock} is tracked but does not exist on disk."
  fi
done <<< "$tracked_locks"

# The strict path a release workflow uses regenerates the locks first, so a lock that merely happens
# to be committed cannot pass as a lock that still describes the declared dependencies.
if [[ "${FOUNDRY_RELEASE_VERIFY_LOCKS:-inspect}" == "regenerate" ]]; then
  (cd "$repo_root" && ./gradlew --no-daemon --write-locks resolveAndLockAll)
fi

lock_status="$(git -C "$repo_root" status --porcelain --untracked-files=all -- "${lock_patterns[@]}")"
if [[ -n "$lock_status" ]]; then
  printf '%s\n' "$lock_status" >&2
  fail "a dependency lock is dirty; regenerate with ./gradlew --write-locks resolveAndLockAll and commit the result."
fi

tree_status="$(git -C "$repo_root" status --porcelain --untracked-files=all)"
if [[ -n "$tree_status" ]]; then
  printf '%s\n' "$tree_status" >&2
  fail "the working tree is not clean."
fi

printf 'Release preconditions satisfied: tag %s, declared version %s, commit %s.\n' \
  "$tag" "$declared_version" "$head_commit"
