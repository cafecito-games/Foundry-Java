#!/usr/bin/env bash
set -euo pipefail

android_project="${1:?usage: verify-native-abi-layout.sh path/to/foundry-java-android report}"
report="${2:?usage: verify-native-abi-layout.sh path/to/foundry-java-android report}"
repository_root="$(cd "$android_project/.." && pwd)"
generator="$android_project/src/main/cpp/cmake/GenerateFoundryJavaAbiLayout.cmake"
template="$android_project/src/main/cpp/foundry_java_abi_layout.h.in"
api="$repository_root/api/current/extension_api.json"
provenance="$repository_root/api/current/provenance.json"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

generate() {
  local input_api="$1"
  local input_provenance="$2"
  local output="$3"
  cmake \
    -Werror=dev \
    -DFOUNDRY_JAVA_API_JSON="$input_api" \
    -DFOUNDRY_JAVA_PROVENANCE="$input_provenance" \
    -DFOUNDRY_JAVA_ABI_TEMPLATE="$template" \
    -DFOUNDRY_JAVA_ABI_OUTPUT="$output" \
    -DFOUNDRY_JAVA_BRIDGE_PRECISION=float \
    -P "$generator"
}

generate "$api" "$provenance" "$temporary_directory/first.h"
generate "$api" "$provenance" "$temporary_directory/second.h"
cmp "$temporary_directory/first.h" "$temporary_directory/second.h"
[[ "$(grep -c '^        { "' "$temporary_directory/first.h")" -eq 80 ]]
grep -Fq '{ "Nil", 0 }' "$temporary_directory/first.h"
grep -Fq '{ "Variant", 24 }' "$temporary_directory/first.h"

make_fixture() {
  local mode="$1"
  local fixture="$temporary_directory/$mode"
  mkdir -p "$fixture"
  python3 - "$api" "$provenance" "$fixture" "$mode" <<'PY'
import hashlib
import json
import pathlib
import sys

api_path, provenance_path, fixture_path, mode = sys.argv[1:]
api = json.loads(pathlib.Path(api_path).read_text(encoding="utf-8"))
provenance = json.loads(pathlib.Path(provenance_path).read_text(encoding="utf-8"))
layouts = {row["build_configuration"]: row for row in api["builtin_class_sizes"]}
if mode == "missing_configuration":
    api["builtin_class_sizes"] = [
        row for row in api["builtin_class_sizes"] if row["build_configuration"] != "float_64"
    ]
elif mode == "duplicate":
    layouts["float_64"]["sizes"][2]["name"] = layouts["float_64"]["sizes"][1]["name"]
elif mode == "order":
    rows = layouts["float_64"]["sizes"]
    rows[1], rows[2] = rows[2], rows[1]
elif mode == "invalid_size":
    layouts["float_32"]["sizes"][1]["size"] = -1
elif mode == "nil_nonzero":
    layouts["float_32"]["sizes"][0]["size"] = 1
elif mode == "nil_misplaced":
    rows = layouts["float_32"]["sizes"]
    rows[0], rows[1] = rows[1], rows[0]
elif mode == "wrong_sentinel":
    next(row for row in layouts["float_64"]["sizes"] if row["name"] == "Variant")["size"] = 23
elif mode != "sha":
    raise SystemExit(f"unknown fixture mode: {mode}")
serialized = json.dumps(api, separators=(",", ":")).encode()
fixture = pathlib.Path(fixture_path)
(fixture / "extension_api.json").write_bytes(serialized)
if mode != "sha":
    provenance["files"]["extension_api_json"]["sha256"] = hashlib.sha256(serialized).hexdigest()
(fixture / "provenance.json").write_text(
    json.dumps(provenance, separators=(",", ":")), encoding="utf-8"
)
PY
}

rejected_fixtures=()
for mode in sha missing_configuration duplicate order invalid_size nil_nonzero nil_misplaced wrong_sentinel; do
  make_fixture "$mode"
  if generate \
      "$temporary_directory/$mode/extension_api.json" \
      "$temporary_directory/$mode/provenance.json" \
      "$temporary_directory/$mode/generated.h" \
      >"$temporary_directory/$mode/stdout" 2>"$temporary_directory/$mode/stderr"; then
    printf 'Malformed native ABI fixture unexpectedly succeeded: %s\n' "$mode" >&2
    exit 1
  fi
  rejected_fixtures+=("$mode")
done

if cmake \
    -Werror=dev \
    -DFOUNDRY_JAVA_API_JSON="$api" \
    -DFOUNDRY_JAVA_PROVENANCE="$provenance" \
    -DFOUNDRY_JAVA_ABI_TEMPLATE="$template" \
    -DFOUNDRY_JAVA_ABI_OUTPUT="$temporary_directory/double.h" \
    -DFOUNDRY_JAVA_BRIDGE_PRECISION=double \
    -P "$generator" >"$temporary_directory/double.stdout" 2>"$temporary_directory/double.stderr"; then
  printf 'Non-float bridge precision unexpectedly succeeded.\n' >&2
  exit 1
fi

# The Gradle task that drives this script declares the report below as its only output, which is what
# lets the task be build-cached at all: with nothing declared it could never be up to date and never
# be replayed. Every line is derived from the pinned engine API and from which fixtures the generator
# refused, so the report is identical on any machine that reaches this point — nothing here records a
# path, a timestamp or a duration.
mkdir -p "$(dirname "$report")"
{
  printf 'accepted-layout-sha256 %s\n' \
    "$(shasum -a 256 "$temporary_directory/first.h" | awk '{print $1}')"
  printf 'builtin-class-size-rows %s\n' \
    "$(grep -c '^        { "' "$temporary_directory/first.h")"
  for mode in "${rejected_fixtures[@]}"; do
    printf 'rejected-fixture %s\n' "$mode"
  done
  printf 'rejected-bridge-precision double\n'
} >"$report"

printf 'Verified deterministic native ABI layouts and malformed fixtures.\n'
