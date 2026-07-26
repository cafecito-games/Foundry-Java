#!/usr/bin/env bash
set -euo pipefail

mode="${1:?usage: run-native-tests.sh host|sanitizer android-module-directory}"
android_module_directory="${2:?usage: run-native-tests.sh host|sanitizer android-module-directory}"

case "$mode" in
  host)
    build_directory="$android_module_directory/build/native-host"
    sanitizer_flag="OFF"
    ;;
  sanitizer)
    build_directory="$android_module_directory/build/native-host-sanitized"
    sanitizer_flag="ON"
    ;;
  *)
    printf 'Unsupported native test mode: %s\n' "$mode" >&2
    exit 2
    ;;
esac

cmake \
  -S "$android_module_directory/src/main/cpp" \
  -B "$build_directory" \
  -DFOUNDRY_JAVA_BUILD_TESTS=ON \
  -DFOUNDRY_JAVA_ENABLE_SANITIZERS="$sanitizer_flag"
cmake --build "$build_directory" --parallel
ctest --test-dir "$build_directory" --output-on-failure
