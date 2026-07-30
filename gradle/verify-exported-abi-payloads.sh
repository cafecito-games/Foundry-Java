#!/usr/bin/env bash
# Statically inspects one exported Foundry application for the Foundry-Java contract this
# repository owns: the exact requested bridge ABI set, the exact exported bridge symbol surface, the
# dynamic dependencies the bridge is allowed to have, exactly one binding configuration and one
# registry index with no duplicate payloads, and narrow minification keep rules.
#
# This inspection is necessary but never sufficient. Foundry PR #1338 documented a correctly
# packaged binding the engine never loaded, so the engine-loaded conformance gate treats this script
# as one input beside a real engine run, never as a substitute for it.
set -euo pipefail

apk=""
evidence_dir=""
mapping=""
abis=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk)
      apk="$2"
      shift 2
      ;;
    --evidence-dir)
      evidence_dir="$2"
      shift 2
      ;;
    --abi)
      abis+=("$2")
      shift 2
      ;;
    --mapping)
      mapping="$2"
      shift 2
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$apk" || -z "$evidence_dir" || "${#abis[@]}" -eq 0 ]]; then
  printf 'usage: verify-exported-abi-payloads.sh --apk PATH --evidence-dir DIR --abi ABI...\n' >&2
  exit 1
fi
if [[ ! -f "$apk" ]]; then
  printf 'Exported application does not exist: %s\n' "$apk" >&2
  exit 1
fi

# The four ABIs Foundry-Java supports. A requested ABI outside this set, or requested twice, is a
# harness mistake and must fail before anything is inspected.
supported_abis=(arm64-v8a armeabi-v7a x86 x86_64)
for requested_abi in "${abis[@]}"; do
  matched=false
  for supported_abi in "${supported_abis[@]}"; do
    if [[ "$requested_abi" == "$supported_abi" ]]; then
      matched=true
    fi
  done
  if [[ "$matched" != "true" ]]; then
    printf 'Requested ABI is not supported: %s\n' "$requested_abi" >&2
    exit 1
  fi
done
if [[ "$(printf '%s\n' "${abis[@]}" | LC_ALL=C sort -u | wc -l | tr -d ' ')" \
  != "${#abis[@]}" ]]; then
  printf 'Requested ABI set contains duplicates.\n' >&2
  exit 1
fi

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
symbol_contract="${script_directory}/foundry-java-bridge-symbols.txt"
if [[ ! -f "$symbol_contract" ]]; then
  printf 'The shared bridge symbol contract is missing: %s\n' "$symbol_contract" >&2
  exit 1
fi

android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
android_ndk_root="${ANDROID_NDK_HOME:-}"
if [[ -z "$android_ndk_root" && -n "$android_sdk_root" ]]; then
  android_ndk_root="${android_sdk_root}/ndk/29.0.14206865"
fi
if [[ -z "$android_ndk_root" || ! -d "$android_ndk_root" ]]; then
  printf 'Android NDK 29.0.14206865 is required; set ANDROID_NDK_HOME or ANDROID_SDK_ROOT.\n' >&2
  exit 1
fi
llvm_readelf="$(
  find "${android_ndk_root}/toolchains/llvm/prebuilt" -path '*/bin/llvm-readelf' -print -quit
)"
if [[ -z "$llvm_readelf" || ! -x "$llvm_readelf" ]]; then
  printf 'llvm-readelf was not found under %s\n' "$android_ndk_root" >&2
  exit 1
fi

mkdir -p "$evidence_dir"
evidence_dir="$(cd "$evidence_dir" && pwd)"
entries="${evidence_dir}/entries.txt"
unzip -Z1 "$apk" | LC_ALL=C sort >"$entries"

configuration_entry='assets/FoundryJava.foundryextension'
registry_entry='assets/foundry_java/registry-index-v2.txt'

# Exactly one configuration and exactly one registry index, and no duplicate payload of either name
# anywhere else in the archive. A second copy would make the loaded configuration ambiguous.
for required in "$configuration_entry" "$registry_entry"; do
  count="$(grep -Fxc -- "$required" "$entries" || true)"
  if [[ "$count" != "1" ]]; then
    printf '%s must contain exactly one %s; found %s.\n' "$apk" "$required" "$count" >&2
    exit 1
  fi
done
for basename_pattern in 'FoundryJava\.foundryextension' 'registry-index-v2\.txt'; do
  duplicates="$(grep -Ec "(^|/)${basename_pattern}$" "$entries" || true)"
  if [[ "$duplicates" != "1" ]]; then
    printf '%s carries %s duplicate configuration payloads matching %s.\n' \
      "$apk" "$duplicates" "$basename_pattern" >&2
    exit 1
  fi
done

expected_bridges="${evidence_dir}/expected-bridges.txt"
actual_bridges="${evidence_dir}/actual-bridges.txt"
printf '%s\n' "${abis[@]}" | LC_ALL=C sort -u |
  sed 's#^#lib/#; s#$#/libfoundry_java.so#' >"$expected_bridges"
grep -E '^lib/[^/]+/libfoundry_java\.so$' "$entries" | LC_ALL=C sort >"$actual_bridges" || true
if ! diff -u "$expected_bridges" "$actual_bridges"; then
  printf '%s bridge payloads differ from the requested ABI set.\n' "$apk" >&2
  exit 1
fi

expected_symbols="${evidence_dir}/expected-symbols.txt"
LC_ALL=C sort <"$symbol_contract" >"$expected_symbols"

while IFS= read -r bridge_entry; do
  abi="$(printf '%s' "$bridge_entry" | cut -d/ -f2)"
  extracted="${evidence_dir}/${abi}-libfoundry_java.so"
  unzip -p "$apk" "$bridge_entry" >"$extracted"
  "$llvm_readelf" --dyn-syms --wide "$extracted" >"${evidence_dir}/${abi}-dyn-syms.txt"
  "$llvm_readelf" --dynamic "$extracted" >"${evidence_dir}/${abi}-dynamic.txt"

  actual_symbols="${evidence_dir}/${abi}-exported-symbols.txt"
  awk '$4 == "FUNC" && $5 == "GLOBAL" && $7 != "UND" { print $8 }' \
    "${evidence_dir}/${abi}-dyn-syms.txt" |
    sed 's/@@.*$//' |
    LC_ALL=C sort -u >"$actual_symbols"
  if ! diff -u "$expected_symbols" "$actual_symbols"; then
    printf '%s exports an unexpected dynamic symbol surface.\n' "$bridge_entry" >&2
    exit 1
  fi

  if awk '$7 == "UND" { print $8 }' "${evidence_dir}/${abi}-dyn-syms.txt" | grep -Eq '^Java_'; then
    printf '%s imports a forbidden host JNI symbol.\n' "$bridge_entry" >&2
    exit 1
  fi

  # Foundry-Java is never permitted to link, load, or redistribute the Android host library, so the
  # exported bridge must not depend on libfoundry_android.so even though the engine's own host
  # library is legitimately present in the exported application.
  if grep -E 'Shared library: \[(libfoundry_android|libjvm)\.so\]' \
    "${evidence_dir}/${abi}-dynamic.txt"; then
    printf '%s links a forbidden Android host or JVM library.\n' "$bridge_entry" >&2
    exit 1
  fi

  rm -f "$extracted"
  printf 'Verified %s\n' "$bridge_entry"
done <"$actual_bridges"

if [[ -n "$mapping" ]]; then
  if [[ ! -f "$mapping" ]]; then
    printf 'Minified release mapping does not exist: %s\n' "$mapping" >&2
    exit 1
  fi
  cp "$mapping" "${evidence_dir}/mapping.txt"
  # A minified release must keep exactly the reflection-free entry points the engine resolves by
  # name, each under its original name. Anything broader would hide a real minification failure.
  for retained in \
    'games.cafecito.foundry.generated.FoundryGeneratedStartupProvider' \
    'games.cafecito.foundry.generated.FoundryGeneratedBootstrap' \
    'games.cafecito.foundry.generated.acceptance.AcceptanceRegistry' \
    'games.cafecito.foundry.acceptance.EngineProbe_FoundryTrampoline'; do
    if ! grep -Fq "${retained} -> ${retained}:" "$mapping"; then
      printf 'Minified release must keep %s under its original name.\n' "$retained" >&2
      exit 1
    fi
  done
fi

printf 'Verified the exported Foundry-Java payload contract for %s\n' "$(basename "$apk")"
