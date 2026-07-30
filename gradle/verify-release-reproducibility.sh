#!/usr/bin/env bash
# Proves that staging the same tag twice produces byte-identical artifacts. Both stagings start from
# removed build outputs, so nothing is carried between them.
#
# Detached OpenPGP signatures, and the checksum files that restate them, are excluded from the
# comparison because a signature packet records its own signature creation time, which differs between
# two runs by construction. Everything a signature covers — every POM, Gradle module, JAR, AAR,
# sources and Javadoc archive, every artifact checksum, the signing public key, and the provenance
# record — is compared byte for byte.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'usage: %s <tag> <work-directory>\n' "$0" >&2
  exit 2
fi

tag="$1"
work="$2"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

mkdir -p "$work"
work="$(cd "$work" && pwd -P)"
first="${work}/first"
second="${work}/second"

bash "${repo_root}/gradle/stage-release.sh" "$tag" "$first"
bash "${repo_root}/gradle/stage-release.sh" "$tag" "$second"

comparable_files() {
  (
    cd "$1" && find . -type f \
      ! -name '*.asc' \
      ! -name '*.asc.md5' ! -name '*.asc.sha1' ! -name '*.asc.sha256' ! -name '*.asc.sha512' |
      sed 's|^\./||' | LC_ALL=C sort
  )
}

first_files="$(comparable_files "$first")"
second_files="$(comparable_files "$second")"
differences=0

if [[ "$first_files" != "$second_files" ]]; then
  printf 'The two stagings do not contain the same files.\n' >&2
  comm -3 <(printf '%s\n' "$first_files") <(printf '%s\n' "$second_files") >&2
  differences=$((differences + 1))
fi

while IFS= read -r relative; do
  if [[ -z "$relative" ]]; then
    continue
  fi
  if [[ ! -f "${second}/${relative}" ]]; then
    printf 'Missing from the second staging: %s\n' "$relative" >&2
    differences=$((differences + 1))
    continue
  fi
  if ! cmp -s "${first}/${relative}" "${second}/${relative}"; then
    printf 'Differs between two stagings of %s: %s\n' "$tag" "$relative" >&2
    differences=$((differences + 1))
  fi
done <<< "$first_files"

signature_count="$(cd "$first" && find . -type f -name '*.asc' | wc -l | tr -d '[:space:]')"
compared_count="$(printf '%s\n' "$first_files" | sed '/^$/d' | wc -l | tr -d '[:space:]')"

{
  printf '{\n'
  printf '  "schema_version": 1,\n'
  printf '  "release_tag": "%s",\n' "$tag"
  printf '  "result": "%s",\n' "$([[ "$differences" -eq 0 ]] && printf 'ok' || printf 'failed')"
  printf '  "compared_files": %s,\n' "$compared_count"
  printf '  "excluded_signatures": %s,\n' "$signature_count"
  printf '  "exclusion_reason": "an OpenPGP signature records its own signature creation time",\n'
  printf '  "differences": %s,\n' "$differences"
  printf '  "first_staging": "%s",\n' "$first"
  printf '  "second_staging": "%s"\n' "$second"
  printf '}\n'
} > "${work}/summary.json"

if [[ "$differences" -ne 0 ]]; then
  printf 'Release %s is not reproducible: %s difference(s).\n' "$tag" "$differences" >&2
  exit 1
fi

printf 'Release %s is reproducible across two independent stagings.\n' "$tag"
