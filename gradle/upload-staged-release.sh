#!/usr/bin/env bash
# Uploads a verified staged release to one target: `staging`, a repository directory used to prove the
# pipeline end to end, or `central`, the Maven Central Portal.
#
# Two invariants make this safe to automate. It refuses to run at all unless
# gradle/verify-staged-release.sh already recorded a successful verification of this exact version, so
# no unsigned or unverified artifact can be uploaded. And it checks every coordinate against the
# target before transferring a single byte, so an already-published coordinate fails the whole release
# loudly instead of republishing part of it.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  printf 'usage: %s <staging-directory> <version> <staging|central>\n' "$0" >&2
  exit 2
fi

staging="$(cd "$1" && pwd -P)"
version="$2"
target="$3"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
topology="${FOUNDRY_RELEASE_TOPOLOGY:-${repo_root}/gradle/release-topology.txt}"
repository="${staging}/repository"
central_release_repository="https://repo1.maven.org/maven2"
central_portal_upload="https://central.sonatype.com/api/v1/publisher/upload"

summary="${staging}/verification-summary.json"
if [[ ! -f "$summary" ]] || ! grep -q '"result": "ok"' "$summary" ||
  ! grep -q "\"version\": \"${version}\"" "$summary"; then
  printf 'Upload refused: the staged release has not been verified for version %s.\n' "$version" >&2
  printf 'Run gradle/verify-staged-release.sh %s %s first.\n' "$staging" "$version" >&2
  exit 1
fi
if [[ ! -f "$topology" ]]; then
  printf 'The declared release topology does not exist: %s\n' "$topology" >&2
  exit 1
fi

# A Central Portal deployment stays USER_MANAGED until a human publishes it, so it is not yet visible
# in the public repository and the coordinate probe below cannot see it. This staged release records
# what it uploaded, so a rerun against the same staged release refuses instead of submitting the
# bundle a second time. Recovering from a partial upload means dropping the incomplete deployment in
# the Portal first; see docs/releasing.md.
uploaded="${staging}/upload-summary.json"
if [[ -f "$uploaded" ]] && grep -q "\"target\": \"${target}\"" "$uploaded" &&
  grep -q "\"version\": \"${version}\"" "$uploaded"; then
  printf 'Upload refused: release %s is already published to the %s target according to %s.\n' \
    "$version" "$target" "$uploaded" >&2
  printf 'Refusing to republish. Drop any incomplete deployment at the target first.\n' >&2
  exit 1
fi

# The intent marker is written to disk before the irreversible upload call, precisely so that if the
# process dies between the target accepting the bundle and upload-summary.json being written, the next
# run finds evidence that an attempt was made. Central uploads are not reversible, so a surviving
# intent marker without a completed record is treated as ambiguous rather than as "safe to retry": it
# may already have been accepted. Resolving it requires a human to check the target directly and, once
# satisfied nothing was accepted, remove the marker before releasing again.
#
# A caller may durably persist this exact marker itself, strictly before invoking this script, as a
# guard against losing the runner entirely before this script gets to run at all. FOUNDRY_RELEASE_UPLOAD_ATTEMPT
# names that caller's attempt, so its own marker is recognized as the current attempt continuing,
# never as a stale, unrelated one: a match requires both a non-empty token here and the same token
# recorded in the marker, so the absence of an attempt token on either side is never treated as a match.
attempt_token="${FOUNDRY_RELEASE_UPLOAD_ATTEMPT:-}"
intent="${staging}/upload-intent.json"
if [[ -f "$intent" ]] && grep -q "\"target\": \"${target}\"" "$intent" &&
  grep -q "\"version\": \"${version}\"" "$intent"; then
  continuing_this_attempt=0
  if [[ -n "$attempt_token" ]] && grep -q "\"attempt\": \"${attempt_token}\"" "$intent"; then
    continuing_this_attempt=1
  fi
  if [[ "$continuing_this_attempt" -ne 1 ]]; then
    printf 'Upload refused: a previous attempt to upload release %s to the %s target started but never\n' \
      "$version" "$target" >&2
    printf 'recorded a completed upload (%s exists, %s does not).\n' "$intent" "$uploaded" >&2
    printf 'This is ambiguous: the bundle may already have been accepted. Verify against the %s target\n' \
      "$target" >&2
    printf 'directly (the Central Portal, for the central target) before doing anything else. Never\n' >&2
    printf 'resubmit while this is unresolved. Once confirmed nothing was accepted, remove %s and\n' \
      "$intent" >&2
    printf 'retry.\n' >&2
    exit 1
  fi
fi

case "$target" in
  staging)
    staging_target="${FOUNDRY_RELEASE_STAGING_TARGET:-}"
    if [[ -z "$staging_target" ]]; then
      printf 'Upload refused: FOUNDRY_RELEASE_STAGING_TARGET must name the staging repository root.\n' >&2
      exit 1
    fi
    mkdir -p "$staging_target"
    staging_target="$(cd "$staging_target" && pwd -P)"
    ;;
  central)
    if [[ -z "${FOUNDRY_CENTRAL_PORTAL_TOKEN:-}" ]]; then
      printf 'Upload refused: FOUNDRY_CENTRAL_PORTAL_TOKEN must supply a Central Portal token.\n' >&2
      exit 1
    fi
    ;;
  *)
    printf 'Upload refused: unknown target %s; expected staging or central.\n' "$target" >&2
    exit 1
    ;;
esac

coordinate_paths=""
while IFS= read -r line; do
  case "$line" in
    '' | '#'*) continue ;;
  esac
  group="${line%%:*}"
  remainder="${line#*:}"
  artifact="${remainder%%:*}"
  coordinate_paths="${coordinate_paths}$(printf '%s' "$group" | tr '.' '/')/${artifact}/${version}"$'\n'
done < "$topology"

already_published=""
while IFS= read -r coordinate; do
  if [[ -z "$coordinate" ]]; then
    continue
  fi
  artifact="$(basename "$(dirname "$coordinate")")"
  probe="${coordinate}/${artifact}-${version}.pom"
  case "$target" in
    staging)
      if [[ -e "${staging_target}/${probe}" ]]; then
        already_published="${already_published}${coordinate}"$'\n'
      fi
      ;;
    central)
      status="$(
        curl --silent --show-error --location --head --output /dev/null \
          --write-out '%{http_code}' "${central_release_repository}/${probe}"
      )"
      # Only 404 proves a coordinate is free. Anything else — a throttling or server response — leaves
      # the question unanswered, and an unanswered question must stop the release rather than be read
      # as permission to upload.
      case "$status" in
        200) already_published="${already_published}${coordinate}"$'\n' ;;
        404) ;;
        *)
          printf 'Upload refused: %s answered HTTP %s, so whether %s is published is unknown.\n' \
            "$central_release_repository" "$status" "$coordinate" >&2
          exit 1
          ;;
      esac
      ;;
  esac
done <<< "$coordinate_paths"

if [[ -n "$(printf '%s' "$already_published" | tr -d '[:space:]')" ]]; then
  printf 'Upload refused: a coordinate is already published at the %s target:\n' "$target" >&2
  printf '%s' "$already_published" >&2
  printf 'The release is already published; refusing to republish any coordinate.\n' >&2
  exit 1
fi

deployment_id=''

upload_bundle() {
  case "$target" in
    staging)
      # Files land under a temporary root first, so a failure mid-copy cannot leave a partially
      # published coordinate behind at the target.
      local pending
      pending="$(mktemp -d "${staging_target}/.pending-XXXXXX")"
      (cd "$repository" && tar cf - .) | (cd "$pending" && tar xf -)
      while IFS= read -r coordinate; do
        if [[ -z "$coordinate" ]]; then
          continue
        fi
        mkdir -p "${staging_target}/$(dirname "$coordinate")"
        mv "${pending}/${coordinate}" "${staging_target}/${coordinate}"
      done <<< "$coordinate_paths"
      rm -rf "$pending"
      ;;
    central)
      local bundle="${staging}/central-bundle.zip"
      rm -f "$bundle"
      (cd "$repository" && zip --quiet --recurse-paths -X "$bundle" .)
      # The token never appears on the command line: curl reads its own configuration, including the
      # Authorization header, from standard input.
      printf 'header = "Authorization: Bearer %s"\n' "$FOUNDRY_CENTRAL_PORTAL_TOKEN" |
        curl --silent --show-error --fail --config - \
          --form "bundle=@${bundle}" \
          --form "name=foundry-java-${version}" \
          --form 'publishingType=USER_MANAGED' \
          --output "${staging}/central-deployment-id.txt" \
          "$central_portal_upload"
      # The Portal answers with the deployment identifier, which is the only handle a human has for
      # publishing or dropping the deployment, so it is kept as evidence.
      deployment_id="$(tr -d '[:space:]' < "${staging}/central-deployment-id.txt")"
      ;;
  esac
}

# Persisted before upload_bundle is called, so it survives on disk even if the process dies during or
# immediately after the irreversible transfer, before the completed-upload record below can be written.
# If a caller already durably persisted this same marker before invoking this script, this simply
# rewrites it with identical content.
{
  printf '{\n'
  printf '  "schema_version": 1,\n'
  printf '  "result": "intent",\n'
  printf '  "target": "%s",\n' "$target"
  printf '  "version": "%s",\n' "$version"
  printf '  "attempt": "%s"\n' "$attempt_token"
  printf '}\n'
} > "$intent"

upload_bundle

uploaded_files="$(cd "$repository" && find . -type f | sed 's|^\./||' | LC_ALL=C sort)"
{
  printf '{\n'
  printf '  "schema_version": 1,\n'
  printf '  "result": "ok",\n'
  printf '  "target": "%s",\n' "$target"
  printf '  "version": "%s",\n' "$version"
  printf '  "deployment_id": "%s",\n' "$deployment_id"
  printf '  "uploaded_files": %s,\n' "$(printf '%s\n' "$uploaded_files" | sed '/^$/d' | wc -l | tr -d '[:space:]')"
  printf '  "coordinates": [\n'
  separator=''
  while IFS= read -r coordinate; do
    if [[ -n "$coordinate" ]]; then
      printf '%s    "%s"' "$separator" "$coordinate"
      separator=$',\n'
    fi
  done <<< "$coordinate_paths"
  printf '\n  ]\n'
  printf '}\n'
} > "${staging}/upload-summary.json"
rm -f "$intent"

printf 'Uploaded release %s to the %s target.\n' "$version" "$target"
