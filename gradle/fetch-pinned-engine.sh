#!/usr/bin/env bash
# Materializes the pinned Foundry engine release for the engine-loaded conformance gate.
#
# The pinned release is described entirely by gradle/engine-pin.json. Every asset is verified
# against its pinned size and SHA-256 before it is opened, whether it was just downloaded or served
# from the cache: the export template archive is roughly 1.1 GB, so it is cached by release tag and
# digest rather than downloaded per job, and a cache hit is not evidence of integrity.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pin="${repo_root}/gradle/engine-pin.json"
cache_root="${FOUNDRY_ENGINE_CACHE:-${HOME}/.cache/foundry-engine}"
output_root="${1:?usage: fetch-pinned-engine.sh OUTPUT_DIRECTORY}"

for tool in jq curl unzip shasum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'Required tool is unavailable: %s\n' "$tool" >&2
    exit 1
  fi
done

release_tag="$(jq -r '.release_tag' "$pin")"
producer_commit="$(jq -r '.producer_commit' "$pin")"
download_prefix="$(jq -r '.download_url_prefix' "$pin")"
raw_prefix="$(jq -r '.raw_url_prefix' "$pin")"

# The pinned release must be the release the vendored API identity was generated from. Asserting it
# here means a pin bump that forgets to re-vendor the API cannot reach the export.
vendored_release="$(jq -r '.source_release' "${repo_root}/api/current/provenance.json")"
vendored_commit="$(jq -r '.foundry_commit' "${repo_root}/api/current/provenance.json")"
vendored_archive="$(jq -r '.archive_sha256' "${repo_root}/api/current/provenance.json")"
pinned_archive="$(jq -r '.assets.api.sha256' "$pin")"
if [[ "$release_tag" != "$vendored_release" ]]; then
  printf 'Pinned engine release %s differs from the vendored API release %s.\n' \
    "$release_tag" "$vendored_release" >&2
  exit 1
fi
if [[ "$producer_commit" != "$vendored_commit" ]]; then
  printf 'Pinned producer commit %s differs from the vendored producer commit %s.\n' \
    "$producer_commit" "$vendored_commit" >&2
  exit 1
fi
if [[ "$pinned_archive" != "$vendored_archive" ]]; then
  printf 'Pinned API archive digest %s differs from the vendored digest %s.\n' \
    "$pinned_archive" "$vendored_archive" >&2
  exit 1
fi

cache_directory="${cache_root}/${release_tag}"
mkdir -p "$cache_directory" "$output_root"
output_root="$(cd "$output_root" && pwd)"

verify_digest() {
  local file="$1"
  local expected_digest="$2"
  local expected_size="$3"
  local observed_size
  local observed_digest
  observed_size="$(wc -c <"$file" | tr -d ' ')"
  if [[ "$observed_size" != "$expected_size" ]]; then
    printf 'Pinned asset %s has size %s, expected %s.\n' "$file" "$observed_size" "$expected_size" >&2
    return 1
  fi
  observed_digest="$(shasum -a 256 "$file" | cut -d' ' -f1)"
  if [[ "$observed_digest" != "$expected_digest" ]]; then
    printf 'Pinned asset %s digest mismatch: observed %s, expected %s.\n' \
      "$file" "$observed_digest" "$expected_digest" >&2
    return 1
  fi
}

# Returns the cached path for one pinned asset, downloading it only when the cache does not already
# hold the exact pinned bytes. The digest is verified on both paths.
resolve_asset() {
  local asset="$1"
  local name expected_digest expected_size cached cache_hit
  name="$(jq -r ".assets.${asset}.name" "$pin")"
  expected_digest="$(jq -r ".assets.${asset}.sha256" "$pin")"
  expected_size="$(jq -r ".assets.${asset}.size" "$pin")"
  # The digest is part of the cached name, so a bumped pin can never collide with a stale asset.
  cached="${cache_directory}/${expected_digest}-${name}"
  cache_hit=false
  if [[ -f "$cached" ]]; then
    cache_hit=true
  else
    curl --fail --location --silent --show-error --retry 3 --retry-delay 5 \
      --output "${cached}.partial" "${download_prefix}/${name}"
    mv "${cached}.partial" "$cached"
  fi
  if ! verify_digest "$cached" "$expected_digest" "$expected_size"; then
    rm -f "$cached"
    printf 'Pinned asset %s failed verification (cache_hit=%s); refusing to use it.\n' \
      "$name" "$cache_hit" >&2
    return 1
  fi
  printf '%s\n' "$cached"
}

editor_archive="$(resolve_asset editor)"
templates_archive="$(resolve_asset export_templates)"
api_archive="$(resolve_asset api)"

# The deep device assertions are upstream property. The harness fetches the pinned revision of the
# upstream tool and verifies it; it never forks or vendors it.
acceptance_path="$(jq -r '.device_acceptance.path' "$pin")"
acceptance_digest="$(jq -r '.device_acceptance.sha256' "$pin")"
acceptance_tool="${cache_directory}/${acceptance_digest}-android_device_acceptance.py"
if [[ ! -f "$acceptance_tool" ]]; then
  curl --fail --location --silent --show-error --retry 3 --retry-delay 5 \
    --output "${acceptance_tool}.partial" "${raw_prefix}/${acceptance_path}"
  mv "${acceptance_tool}.partial" "$acceptance_tool"
fi
acceptance_size="$(wc -c <"$acceptance_tool" | tr -d ' ')"
if ! verify_digest "$acceptance_tool" "$acceptance_digest" "$acceptance_size"; then
  rm -f "$acceptance_tool"
  exit 1
fi

editor_root="${output_root}/editor"
rm -rf "$editor_root"
mkdir -p "$editor_root"
unzip -q "$editor_archive" -d "$editor_root"
editor_binary="$(
  find "$editor_root" -maxdepth 2 -type f -name '*.x86_64' -print | LC_ALL=C sort | head -n 1
)"
if [[ -z "$editor_binary" ]]; then
  printf 'The pinned editor archive does not contain one Linux editor binary.\n' >&2
  exit 1
fi
chmod +x "$editor_binary"

# Only the Android source template is needed from the 1.1 GB archive: the Gradle-built export
# compiles the application from it and takes the engine host libraries from the AARs it carries.
source_template_root="${output_root}/templates"
rm -rf "$source_template_root"
mkdir -p "$source_template_root"
unzip -j -q -o "$templates_archive" '*android_source.zip' -d "$source_template_root"
source_template="${source_template_root}/android_source.zip"
if [[ ! -f "$source_template" ]]; then
  printf 'The pinned export template archive does not contain android_source.zip.\n' >&2
  exit 1
fi

manifest="${output_root}/engine-manifest.json"
jq -n \
  --arg release_tag "$release_tag" \
  --arg producer_commit "$producer_commit" \
  --arg editor "$editor_binary" \
  --arg editor_sha256 "$(shasum -a 256 "$editor_binary" | cut -d' ' -f1)" \
  --arg source_template "$source_template" \
  --arg source_template_sha256 "$(shasum -a 256 "$source_template" | cut -d' ' -f1)" \
  --arg acceptance_tool "$acceptance_tool" \
  --arg acceptance_tool_sha256 "$acceptance_digest" \
  --arg templates_archive "$templates_archive" \
  --arg templates_archive_sha256 "$(jq -r '.assets.export_templates.sha256' "$pin")" \
  --arg api_archive "$api_archive" \
  --arg api_archive_sha256 "$pinned_archive" \
  '{
    schema_version: 1,
    release_tag: $release_tag,
    producer_commit: $producer_commit,
    editor: $editor,
    editor_sha256: $editor_sha256,
    source_template: $source_template,
    source_template_sha256: $source_template_sha256,
    device_acceptance_tool: $acceptance_tool,
    device_acceptance_tool_sha256: $acceptance_tool_sha256,
    export_templates_archive: $templates_archive,
    export_templates_archive_sha256: $templates_archive_sha256,
    api_archive: $api_archive,
    api_archive_sha256: $api_archive_sha256
  }' >"$manifest"

printf 'Resolved the pinned Foundry engine %s into %s\n' "$release_tag" "$output_root"
