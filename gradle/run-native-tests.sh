#!/usr/bin/env bash
set -euo pipefail

# The CMake build tree and the verification evidence are deliberately separate directories.
#
# The tree under native-host/cmake is not a declared output of the Gradle task that drives this
# script. CMakeCache.txt records the absolute source and build paths it was configured with, so a
# tree stored in the build cache by one checkout is meaningless to another; and a declared output of
# a cacheable task is wiped before every execution, which would turn every C++ edit into a full
# reconfigure and rebuild. Leaving the tree undeclared keeps local iteration incremental.
#
# The report under native-host/report is what the task declares, what the build cache stores, and
# what the CI job uploads as evidence. It holds no absolute paths, so a cache hit leaves the same
# observable state an execution would.

mode="${1:?usage: run-native-tests.sh host|sanitizer android-module-directory}"
android_module_directory="${2:?usage: run-native-tests.sh host|sanitizer android-module-directory}"

case "$mode" in
  host)
    output_root="$android_module_directory/build/native-host"
    sanitizer_flag="OFF"
    ;;
  sanitizer)
    output_root="$android_module_directory/build/native-host-sanitized"
    sanitizer_flag="ON"
    ;;
  *)
    printf 'Unsupported native test mode: %s\n' "$mode" >&2
    exit 2
    ;;
esac

build_directory="$output_root/cmake"
report_directory="$output_root/report"
rm -rf "$report_directory"
mkdir -p "$report_directory"

cmake \
  -S "$android_module_directory/src/main/cpp" \
  -B "$build_directory" \
  -DFOUNDRY_JAVA_BUILD_TESTS=ON \
  -DFOUNDRY_JAVA_ENABLE_SANITIZERS="$sanitizer_flag"
cmake --build "$build_directory" --parallel

# The toolchain is not a declared input of the task, only the host platform is. Recording the exact
# versions here is what makes a surprising cache hit traceable to the machine that produced it.
{
  printf 'mode %s\n' "$mode"
  printf 'sanitizers %s\n' "$sanitizer_flag"
  printf 'uname %s\n' "$(uname -sm)"
  cmake --version | head -n 1
} >"$report_directory/toolchain.txt"

ctest --test-dir "$build_directory" --show-only=human >"$report_directory/tests.txt"
ctest --test-dir "$build_directory" --output-on-failure 2>&1 |
  tee "$report_directory/ctest.log"
