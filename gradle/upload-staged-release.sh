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

staging="$(cd "$1" && pwd)"
version="$2"
target="$3"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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

case "$target" in
  staging)
    staging_target="${FOUNDRY_RELEASE_STAGING_TARGET:-}"
    if [[ -z "$staging_target" ]]; then
      printf 'Upload refused: FOUNDRY_RELEASE_STAGING_TARGET must name the staging repository root.\n' >&2
      exit 1
    fi
    mkdir -p "$staging_target"
    staging_target="$(cd "$staging_target" && pwd)"
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
      if [[ "$status" == "200" ]]; then
        already_published="${already_published}${coordinate}"$'\n'
      fi
      ;;
  esac
done <<< "$coordinate_paths"

if [[ -n "$(printf '%s' "$already_published" | tr -d '[:space:]')" ]]; then
  printf 'Upload refused: a coordinate is already published at the %s target:\n' "$target" >&2
  printf '%s' "$already_published" >&2
  printf 'The release is already published; refusing to republish any coordinate.\n' >&2
  exit 1
fi

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
          "$central_portal_upload"
      ;;
  esac
}

upload_bundle

uploaded_files="$(cd "$repository" && find . -type f | sed 's|^\./||' | LC_ALL=C sort)"
{
  printf '{\n'
  printf '  "schema_version": 1,\n'
  printf '  "result": "ok",\n'
  printf '  "target": "%s",\n' "$target"
  printf '  "version": "%s",\n' "$version"
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

printf 'Uploaded release %s to the %s target.\n' "$version" "$target"
