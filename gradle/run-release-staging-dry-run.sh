#!/usr/bin/env bash
# Proves the release pipeline end to end against a staging target: an ephemeral signing key signs a
# real release, the same tag is staged twice and compared byte for byte, the staged repository is
# verified against that key, the release is uploaded to a staging repository, and a second upload of
# the same release is required to fail.
#
# This never publishes anything publicly. The only target it will accept is `staging`; the Maven
# Central path exists in gradle/upload-staged-release.sh and is exercised by the first real release,
# not here.
set -euo pipefail

if [[ $# -gt 1 ]]; then
  printf 'usage: %s [tag]\n' "$0" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
declared_version="$(sed -n 's/^foundryVersion=\([^[:space:]]*\)[[:space:]]*$/\1/p' \
  "${repo_root}/gradle.properties" | head -n 1)"
tag="${1:-v${declared_version}}"
version="${tag#v}"

requested_target="${FOUNDRY_RELEASE_TARGET:-staging}"
if [[ "$requested_target" != "staging" ]]; then
  printf 'The dry run must never publish to Maven Central; it only accepts the staging target.\n' >&2
  exit 1
fi

work="${FOUNDRY_RELEASE_DRY_RUN_DIR:-${RUNNER_TEMP:-/tmp}/foundry-java-release-dry-run}"
rm -rf "$work"
mkdir -p "$work"
work="$(cd "$work" && pwd)"

# The ephemeral key lives only inside this run's work directory. Its private half is read into an
# environment variable and never written to a log, a file the release reads, or a command line.
export GNUPGHOME="${work}/gnupg"
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
signing_identity="Foundry-Java Release Dry Run <release-dry-run@cafecito.games>"
gpg --batch --yes --passphrase '' --quick-generate-key "$signing_identity" default default never
public_key="${work}/public-key.asc"
gpg --batch --yes --armor --output "$public_key" --export "$signing_identity"

FOUNDRY_SIGNING_KEY="$(
  gpg --batch --yes --pinentry-mode loopback --passphrase '' --armor \
    --export-secret-keys "$signing_identity"
)"
export FOUNDRY_SIGNING_KEY
export FOUNDRY_SIGNING_PASSWORD=""

bash "${repo_root}/gradle/verify-release-reproducibility.sh" "$tag" "${work}/reproducibility"
staged="${work}/reproducibility/first"

bash "${repo_root}/gradle/verify-staged-release.sh" "$staged" "$version" "$public_key"

staging_target="${work}/staging-repository"
mkdir -p "$staging_target"
FOUNDRY_RELEASE_STAGING_TARGET="$staging_target" \
  bash "${repo_root}/gradle/upload-staged-release.sh" "$staged" "$version" staging

second_status=0
FOUNDRY_RELEASE_STAGING_TARGET="$staging_target" \
  bash "${repo_root}/gradle/upload-staged-release.sh" "$staged" "$version" staging \
  > "${work}/second-upload.log" 2>&1 || second_status=$?
if [[ "$second_status" -eq 0 ]]; then
  printf 'A second upload of %s unexpectedly succeeded; publication is not refusing a republish.\n' \
    "$version" >&2
  exit 1
fi
if ! grep -q 'is already published' "${work}/second-upload.log"; then
  printf 'A second upload of %s failed for the wrong reason:\n' "$version" >&2
  cat "${work}/second-upload.log" >&2
  exit 1
fi

uploaded="$(cd "$staging_target" && find . -type f | wc -l | tr -d '[:space:]')"
signatures="$(cd "$staging_target" && find . -type f -name '*.asc' | wc -l | tr -d '[:space:]')"

{
  printf '{\n'
  printf '  "schema_version": 1,\n'
  printf '  "result": "ok",\n'
  printf '  "release_tag": "%s",\n' "$tag"
  printf '  "target": "staging",\n'
  printf '  "published_to_maven_central": false,\n'
  printf '  "reproducibility_summary": "reproducibility/summary.json",\n'
  printf '  "verification_summary": "reproducibility/first/verification-summary.json",\n'
  printf '  "upload_summary": "reproducibility/first/upload-summary.json",\n'
  printf '  "staging_repository": "%s",\n' "$staging_target"
  printf '  "uploaded_files": %s,\n' "$uploaded"
  printf '  "uploaded_signatures": %s,\n' "$signatures"
  printf '  "republish_refused": true\n'
  printf '}\n'
} > "${work}/summary.json"

printf 'Release dry run for %s succeeded: %s files and %s signatures staged, republish refused.\n' \
  "$tag" "$uploaded" "$signatures"
printf 'Evidence: %s\n' "${work}/summary.json"
