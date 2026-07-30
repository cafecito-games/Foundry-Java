#!/usr/bin/env bash
# Stages a signed release into a local repository directory. Nothing here uploads anything: the
# staged repository is the artifact under test for gradle/verify-staged-release.sh,
# gradle/verify-release-reproducibility.sh, and only then gradle/upload-staged-release.sh.
#
# The signing key and its password arrive as environment variables and are handed to Gradle as
# ORG_GRADLE_PROJECT_ environment values, so no key material ever appears on a command line, in a
# process listing, in a Gradle property file, or in this script's output.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'usage: %s <tag> <staging-directory>\n' "$0" >&2
  exit 2
fi

tag="$1"
staging="$2"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
surface_manifest_path="foundry-java-runtime/build/generated/foundryApi/foundry-java-surface-manifest.json"
accepted_provenance="${repo_root}/api/current/provenance.json"

bash "${repo_root}/gradle/verify-release-preconditions.sh" "$tag"

if [[ -z "${FOUNDRY_SIGNING_KEY:-}" ]]; then
  printf 'Release refused: FOUNDRY_SIGNING_KEY must supply an ASCII-armored signing key.\n' >&2
  exit 1
fi
if [[ -z "${FOUNDRY_SIGNING_PASSWORD+set}" ]]; then
  printf 'Release refused: FOUNDRY_SIGNING_PASSWORD must be set, even when it is empty.\n' >&2
  exit 1
fi

version="${tag#v}"
rm -rf "$staging"
mkdir -p "${staging}/repository"
staging="$(cd "$staging" && pwd)"

# A release is built from nothing, so the two stagings a reproducibility proof compares can never
# differ because one of them reused an output the other rebuilt.
rm -rf "${repo_root}/build"
find "$repo_root" -mindepth 2 -maxdepth 2 -type d -name build -exec rm -rf {} +

publication_tasks=(
  :foundry-java-android:publishAllPublicationsToStagingRepository
  :foundry-java-annotations:publishAllPublicationsToStagingRepository
  :foundry-java-api-model:publishAllPublicationsToStagingRepository
  :foundry-java-generator:publishAllPublicationsToStagingRepository
  :foundry-java-gradle-plugin:publishAllPublicationsToStagingRepository
  :foundry-java-kotlin:publishAllPublicationsToStagingRepository
  :foundry-java-processor:publishAllPublicationsToStagingRepository
  :foundry-java-runtime:publishAllPublicationsToStagingRepository
  :foundry-java-test:publishAllPublicationsToStagingRepository
)

ORG_GRADLE_PROJECT_signingKey="$FOUNDRY_SIGNING_KEY" \
  ORG_GRADLE_PROJECT_signingPassword="$FOUNDRY_SIGNING_PASSWORD" \
  "${repo_root}/gradlew" --no-daemon \
  "-PfoundryStagingRepository=${staging}/repository" \
  "${publication_tasks[@]}"

# Repository-level maven-metadata.xml records a lastUpdated timestamp, so it is bookkeeping rather
# than a release artifact and cannot be reproducible. Maven Central generates its own metadata, so the
# staged release drops it instead of shipping something that changes on every run.
find "${staging}/repository" -type f -name 'maven-metadata.xml*' -delete

if [[ ! -f "${repo_root}/${surface_manifest_path}" ]]; then
  printf 'Release refused: the binding-neutral surface manifest was not produced at %s.\n' \
    "$surface_manifest_path" >&2
  exit 1
fi
cp "${repo_root}/${surface_manifest_path}" "${staging}/foundry-java-surface-manifest.json"

# The staged release carries the public half of the key it was signed with, so verification is
# self-contained on any machine. It is derived from the supplied key rather than configured
# separately, and its fingerprint is recorded by gradle/verify-staged-release.sh so a human can
# compare it with the published Foundry-Java signing key. GnuPG keeps its agent socket inside
# GNUPGHOME, and a Unix domain socket path is short, so this home lives directly under /tmp.
signing_home="$(mktemp -d /tmp/foundry-release-XXXXXX)"
chmod 700 "$signing_home"
remove_signing_home() {
  rm -rf "$signing_home"
}
trap remove_signing_home EXIT
GNUPGHOME="$signing_home" gpg --batch --quiet --import <<< "$FOUNDRY_SIGNING_KEY"
GNUPGHOME="$signing_home" gpg --batch --quiet --armor --output \
  "${staging}/signing-public-key.asc" --export

sha256_of() {
  shasum -a 256 "$1" | cut -d' ' -f1
}

source_commit="$(git -C "$repo_root" rev-parse 'HEAD^{commit}')"
surface_manifest_sha256="$(sha256_of "${staging}/foundry-java-surface-manifest.json")"
engine_api_version="$(jq -r '.api_version' "$accepted_provenance")"
engine_api_sha256="$(jq -r '.files.extension_api_json.sha256' "$accepted_provenance")"
engine_archive_sha256="$(jq -r '.archive_sha256' "$accepted_provenance")"
engine_producer_commit="$(jq -r '.foundry_commit' "$accepted_provenance")"
generator_version="$(jq -r '.generator_version' "$accepted_provenance")"
bridge_contract_version="$(jq -r '.bridge_contract_version' "$accepted_provenance")"

# Signatures embed their own creation time and checksum files restate content that is already
# recorded here, so provenance covers exactly the content-bearing files. That keeps the record itself
# byte-identical between two stagings of the same tag.
staged_artifacts="$(
  cd "${staging}/repository" && find . -type f \
    ! -name '*.asc' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' |
    sed 's|^\./||' | LC_ALL=C sort
)"

{
  printf '{\n'
  printf '  "schema_version": 1,\n'
  printf '  "release_tag": "%s",\n' "$tag"
  printf '  "binding_version": "%s",\n' "$version"
  printf '  "source_repository": "https://github.com/cafecito-games/Foundry-Java",\n'
  printf '  "source_commit": "%s",\n' "$source_commit"
  printf '  "engine_api_version": "%s",\n' "$engine_api_version"
  printf '  "engine_api_sha256": "%s",\n' "$engine_api_sha256"
  printf '  "engine_api_archive_sha256": "%s",\n' "$engine_archive_sha256"
  printf '  "engine_producer_commit": "%s",\n' "$engine_producer_commit"
  printf '  "generator_version": "%s",\n' "$generator_version"
  printf '  "bridge_contract_version": "%s",\n' "$bridge_contract_version"
  printf '  "surface_manifest": "foundry-java-surface-manifest.json",\n'
  printf '  "surface_manifest_sha256": "%s",\n' "$surface_manifest_sha256"
  printf '  "artifacts": [\n'
  separator=''
  while IFS= read -r staged; do
    if [[ -z "$staged" ]]; then
      continue
    fi
    printf '%s    { "path": "%s", "sha256": "%s" }' \
      "$separator" "$staged" "$(sha256_of "${staging}/repository/${staged}")"
    separator=$',\n'
  done <<< "$staged_artifacts"
  printf '\n  ]\n'
  printf '}\n'
} > "${staging}/release-provenance.json"

printf 'Staged %s at %s with provenance for commit %s.\n' "$tag" "$staging" "$source_commit"
