#!/usr/bin/env bash
set -euo pipefail

aar_path="${1:?usage: verify-native-bridge.sh path/to/foundry-java-android.aar}"
if [[ ! -f "$aar_path" ]]; then
  printf 'AAR does not exist: %s\n' "$aar_path" >&2
  exit 1
fi

android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
android_ndk_root="${ANDROID_NDK_HOME:-}"
if [[ -z "$android_ndk_root" && -n "$android_sdk_root" ]]; then
  android_ndk_root="$android_sdk_root/ndk/29.0.14206865"
fi
if [[ -z "$android_ndk_root" || ! -d "$android_ndk_root" ]]; then
  printf 'Android NDK 29.0.14206865 is required; set ANDROID_NDK_HOME or ANDROID_SDK_ROOT.\n' >&2
  exit 1
fi

llvm_readelf="$(
  find "$android_ndk_root/toolchains/llvm/prebuilt" -path '*/bin/llvm-readelf' -print -quit
)"
if [[ -z "$llvm_readelf" || ! -x "$llvm_readelf" ]]; then
  printf 'llvm-readelf was not found under %s\n' "$android_ndk_root" >&2
  exit 1
fi

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT
unzip -q "$aar_path" -d "$temporary_directory/aar"

expected_libraries="$temporary_directory/expected-libraries.txt"
actual_libraries="$temporary_directory/actual-libraries.txt"
expected_symbols="$temporary_directory/expected-symbols.txt"
actual_symbols="$temporary_directory/actual-symbols.txt"

printf '%s\n' \
  'jni/arm64-v8a/libfoundry_java.so' \
  'jni/armeabi-v7a/libfoundry_java.so' \
  'jni/x86/libfoundry_java.so' \
  'jni/x86_64/libfoundry_java.so' \
  >"$expected_libraries"
find "$temporary_directory/aar/jni" -type f -name '*.so' -print |
  sed "s#^$temporary_directory/aar/##" |
  LC_ALL=C sort >"$actual_libraries"
if ! diff -u "$expected_libraries" "$actual_libraries"; then
  printf 'AAR native payload must contain exactly one libfoundry_java.so for each supported ABI.\n' >&2
  exit 1
fi

if find "$temporary_directory/aar" -type f -name 'libfoundry_android.so' -print -quit |
  grep -q .; then
  printf 'AAR must not package libfoundry_android.so.\n' >&2
  exit 1
fi

printf '%s\n' \
  'JNI_OnLoad' \
  'JNI_OnUnload' \
  'Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeBootstrapV1' \
  'Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeCreateContextV1' \
  'Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackOnThreadV1' \
  'Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeInvokeCallbackV1' \
  'Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownBridgeV1' \
  'Java_games_cafecito_foundry_java_FoundryJavaInitializer_nativeShutdownContextV1' \
  'foundry_java_library_init' \
  | LC_ALL=C sort >"$expected_symbols"

while IFS= read -r library_path; do
  library="$temporary_directory/aar/$library_path"
  "$llvm_readelf" --dyn-syms --wide "$library" |
    awk '$4 == "FUNC" && $5 == "GLOBAL" && $7 != "UND" { print $8 }' |
    sed 's/@@.*$//' |
    LC_ALL=C sort -u >"$actual_symbols"
  if ! diff -u "$expected_symbols" "$actual_symbols"; then
    printf '%s exports an unexpected dynamic symbol surface.\n' "$library_path" >&2
    exit 1
  fi

  if "$llvm_readelf" --dyn-syms --wide "$library" |
    awk '$7 == "UND" { print $8 }' |
    grep -Eq '^Java_'; then
    printf '%s imports a forbidden host JNI symbol.\n' "$library_path" >&2
    exit 1
  fi

  if "$llvm_readelf" --dynamic "$library" |
    grep -E 'Shared library: \[(libfoundry_android|libjvm)\.so\]'; then
    printf '%s links a forbidden Android host or JVM library.\n' "$library_path" >&2
    exit 1
  fi

  printf 'Verified %s\n' "$library_path"
done <"$actual_libraries"

printf 'Verified four ABI bridge payloads and the exact stable export surface.\n'
