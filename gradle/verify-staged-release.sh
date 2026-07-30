#!/usr/bin/env bash
# Verifies a staged release repository completely, before any upload exists. Every file in every
# expected coordinate must carry a valid detached OpenPGP signature and four matching checksums,
# every module must ship sources and Javadoc, every POM must carry the metadata Maven Central
# requires, and no coordinate outside the declared topology may be present. On success it writes
# verification-summary.json, which gradle/upload-staged-release.sh requires before it will transfer
# anything.
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  printf 'usage: %s <staging-directory> <version> [public-key-file]\n' "$0" >&2
  exit 2
fi

staging="$(cd "$1" && pwd)"
version="$2"
public_key="${3:-${FOUNDRY_SIGNING_PUBLIC_KEY:-$1/signing-public-key.asc}}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
topology="${FOUNDRY_RELEASE_TOPOLOGY:-${repo_root}/gradle/release-topology.txt}"
repository="${staging}/repository"
checksum_algorithms="md5 sha1 sha256 sha512"
verified_files=0
failures=0
expected_files=""

if [[ ! -d "$repository" ]]; then
  printf 'The staged repository does not exist: %s\n' "$repository" >&2
  exit 1
fi
if [[ ! -f "$topology" ]]; then
  printf 'The declared release topology does not exist: %s\n' "$topology" >&2
  exit 1
fi

report() {
  printf 'Staged release rejected: %s\n' "$1" >&2
  failures=$((failures + 1))
}

# A dedicated keyring keeps verification independent of whatever the ambient GnuPG home happens to
# trust. Without an explicit public key the release cannot be verified at all, so that is fatal.
gnupg_home="$(mktemp -d)"
chmod 700 "$gnupg_home"
cleanup() {
  rm -rf "$gnupg_home"
}
trap cleanup EXIT
if [[ -z "$public_key" || ! -f "$public_key" ]]; then
  printf 'A signing public key must be supplied to verify signatures.\n' >&2
  exit 1
fi
# The public key the staged release carries is derived from the same secret that signed it, so on its
# own it proves internal consistency and nothing more. The expected primary key fingerprint is
# therefore required, is public data rather than a secret, and is what turns "these signatures verify"
# into "these signatures were made by the Foundry-Java release key".
expected_fingerprint="${FOUNDRY_RELEASE_SIGNING_FINGERPRINT:-}"
expected_fingerprint="$(printf '%s' "$expected_fingerprint" | tr -d '[:space:]' | tr 'a-f' 'A-F')"
if [[ ! "$expected_fingerprint" =~ ^[0-9A-F]{40}$ ]]; then
  printf 'FOUNDRY_RELEASE_SIGNING_FINGERPRINT must be the expected 40-character primary key fingerprint.\n' >&2
  exit 1
fi

GNUPGHOME="$gnupg_home" gpg --batch --quiet --import "$public_key"
fingerprints="$(
  GNUPGHOME="$gnupg_home" gpg --batch --with-colons --fingerprint |
    awk -F: '$1 == "pub" { primary = 1; next } $1 == "fpr" && primary { print $10; primary = 0 }'
)"
if [[ "$fingerprints" != "$expected_fingerprint" ]]; then
  printf 'The staged signing key does not match the expected release key.\n' >&2
  printf 'Expected %s, found %s.\n' "$expected_fingerprint" "${fingerprints//$'\n'/, }" >&2
  exit 1
fi

digest() {
  case "$1" in
    md5)
      if command -v md5sum > /dev/null 2>&1; then
        md5sum "$2" | cut -d' ' -f1
      else
        md5 -q "$2"
      fi
      ;;
    sha1) shasum -a 1 "$2" | cut -d' ' -f1 ;;
    sha256) shasum -a 256 "$2" | cut -d' ' -f1 ;;
    sha512) shasum -a 512 "$2" | cut -d' ' -f1 ;;
    *)
      printf 'Unsupported checksum algorithm: %s\n' "$1" >&2
      exit 1
      ;;
  esac
}

# Every declared file is recorded here together with the signature and checksum files that
# accompany it, so the final sweep can reject anything the topology did not declare.
expect_file() {
  local relative="$1"
  local algorithm
  expected_files="${expected_files}${relative}"$'\n'
  expected_files="${expected_files}${relative}.asc"$'\n'
  for algorithm in $checksum_algorithms; do
    expected_files="${expected_files}${relative}.${algorithm}"$'\n'
    expected_files="${expected_files}${relative}.asc.${algorithm}"$'\n'
  done
}

verify_file() {
  local file="$1"
  local relative="${file#"${repository}"/}"
  expect_file "$relative"
  if [[ ! -s "$file" ]]; then
    report "${relative} is missing or empty."
    return
  fi
  if [[ ! -f "${file}.asc" ]]; then
    report "${relative} is not signed; no detached signature exists."
    return
  fi
  local status
  if ! status="$(
    GNUPGHOME="$gnupg_home" gpg --batch --quiet --status-fd 1 \
      --verify "${file}.asc" "$file" 2> /dev/null
  )"; then
    report "${relative} signature is invalid."
    return
  fi
  # The last VALIDSIG field is the primary key fingerprint, so a signature made by any other key —
  # including a valid signature from an unintended key — is rejected here rather than trusted.
  local signing_key
  signing_key="$(
    printf '%s\n' "$status" |
      awk '$1 == "[GNUPG:]" && $2 == "VALIDSIG" { print toupper($NF) }'
  )"
  if [[ "$signing_key" != "$expected_fingerprint" ]]; then
    report "${relative} signature was made by ${signing_key:-an unknown key}, not the expected release key ${expected_fingerprint}."
    return
  fi
  local algorithm
  for algorithm in $checksum_algorithms; do
    if [[ ! -f "${file}.${algorithm}" ]]; then
      report "${relative} has no ${algorithm} checksum."
      continue
    fi
    local recorded
    local computed
    recorded="$(tr -d '[:space:]' < "${file}.${algorithm}")"
    computed="$(digest "$algorithm" "$file")"
    if [[ "$recorded" != "$computed" ]]; then
      report "${relative} ${algorithm} checksum does not match its content."
    fi
  done
  verified_files=$((verified_files + 1))
}

pom_element() {
  # The POM is flat enough that a scoped grep is both sufficient and immune to XML parser
  # availability on a release runner.
  grep -c "<$1>" "$2"
}

verify_pom() {
  local pom="$1"
  local group="$2"
  local artifact="$3"
  local packaging="$4"
  local relative="${pom#"${repository}"/}"
  local element
  if ! grep -q "<groupId>${group}</groupId>" "$pom"; then
    report "${relative} does not declare groupId ${group}."
  fi
  if ! grep -q "<artifactId>${artifact}</artifactId>" "$pom"; then
    report "${relative} does not declare artifactId ${artifact}."
  fi
  if ! grep -q "<version>${version}</version>" "$pom"; then
    report "${relative} does not declare version ${version}."
  fi
  if [[ "$packaging" != "jar" ]] && ! grep -q "<packaging>${packaging}</packaging>" "$pom"; then
    report "${relative} does not declare packaging ${packaging}."
  fi
  for element in name description url licenses developers scm; do
    if [[ "$(pom_element "$element" "$pom")" == "0" ]]; then
      report "${relative} does not declare the required <${element}> Maven Central metadata."
    fi
  done
}

verify_module() {
  local module="$1"
  local group="$2"
  local artifact="$3"
  local declared_variants="$4"
  local relative="${module#"${repository}"/}"
  local variant
  if ! grep -q "\"group\": \"${group}\"" "$module"; then
    report "${relative} does not declare group ${group}."
  fi
  if ! grep -q "\"module\": \"${artifact}\"" "$module"; then
    report "${relative} does not declare module ${artifact}."
  fi
  if ! grep -q "\"version\": \"${version}\"" "$module"; then
    report "${relative} does not declare version ${version}."
  fi
  if [[ "$declared_variants" == "none" ]]; then
    return
  fi
  for variant in $(printf '%s' "$declared_variants" | tr '+' ' '); do
    if ! grep -q "\"${variant}\"" "$module"; then
      report "${relative} does not publish a ${variant} variant."
    fi
  done
}

coordinates=0

while IFS= read -r line; do
  case "$line" in
    '' | '#'*) continue ;;
  esac
  group="${line%%:*}"
  remainder="${line#*:}"
  artifact="${remainder%%:*}"
  remainder="${remainder#*:}"
  packaging="${remainder%%:*}"
  module_variants="${remainder#*:}"
  coordinates=$((coordinates + 1))
  relative_directory="$(printf '%s' "$group" | tr '.' '/')/${artifact}/${version}"
  directory="${repository}/${relative_directory}"
  if [[ ! -d "$directory" ]]; then
    report "the coordinate ${group}:${artifact}:${version} is not staged."
    continue
  fi
  base="${directory}/${artifact}-${version}"
  verify_file "${base}.pom"
  verify_pom "${base}.pom" "$group" "$artifact" "$packaging"
  if [[ "$packaging" == "pom" ]]; then
    continue
  fi
  if [[ ! -f "${base}.${packaging}" ]]; then
    report "${group}:${artifact}:${version} has no ${packaging} artifact."
  else
    verify_file "${base}.${packaging}"
  fi
  for classified in -sources.jar -javadoc.jar; do
    if [[ ! -f "${base}${classified}" ]]; then
      report "${group}:${artifact}:${version} does not publish ${artifact}-${version}${classified}."
    else
      verify_file "${base}${classified}"
    fi
  done
  if [[ ! -f "${base}.module" ]]; then
    report "${group}:${artifact}:${version} has no Gradle module metadata."
  else
    verify_file "${base}.module"
    verify_module "${base}.module" "$group" "$artifact" "$module_variants"
  fi
done < "$topology"

# Anything staged outside the declared topology — a stray coordinate, a second version left behind by
# an earlier run, an extra artifact inside a declared coordinate, repository bookkeeping — must fail
# rather than travel along with the release, because the uploader transfers every staged file.
while IFS= read -r staged_file; do
  if [[ -z "$staged_file" ]]; then
    continue
  fi
  if ! printf '%s' "$expected_files" | grep -qxF "$staged_file"; then
    report "the staged repository contains the undeclared file ${staged_file}."
  fi
done <<< "$(cd "$repository" && find . -type f | sed 's|^\./||' | LC_ALL=C sort)"

if [[ "$failures" -ne 0 ]]; then
  printf 'Staged release verification failed with %s problem(s).\n' "$failures" >&2
  exit 1
fi

{
  printf '{\n'
  printf '  "schema_version": 1,\n'
  printf '  "result": "ok",\n'
  printf '  "version": "%s",\n' "$version"
  printf '  "coordinates": %s,\n' "$coordinates"
  printf '  "verified_files": %s,\n' "$verified_files"
  printf '  "signing_key_fingerprint": "%s"\n' "$expected_fingerprint"
  printf '}\n'
} > "${staging}/verification-summary.json"

printf 'Verified %s signed and checksummed files across %s coordinates at version %s.\n' \
  "$verified_files" "$coordinates" "$version"
