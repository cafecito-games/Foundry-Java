#!/usr/bin/env bash
# The engine-loaded API 36 conformance gate.
#
# Proves that a real Foundry engine loads the Foundry-Java binding built from this commit and that a
# Java-defined class is reachable from the running game, for debug and minified release, with the
# default and a custom application ID. Foundry PR #1338 documented a correctly packaged binding the
# engine never loaded, passing a gate that asserted packaging instead of behaviour, so packaging
# inspection here is only ever an input beside a real engine run.
#
# Foundry-Java owns a thin harness. The deep device assertions live upstream in
# platform/android/android_device_acceptance.py, which is fetched at the pinned engine revision and
# digest-verified; it is never forked or vendored.
set -euo pipefail

serial="${1:-emulator-5554}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
acceptance_version="0.1.0"
runtime_marker="FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_READY"
failure_marker="FOUNDRY_JAVA_ENGINE_LOADED_ACCEPTANCE_FAILED"
load_failed_token="FOUNDRY_JAVA_PLATFORM_EXTENSION_LOAD_FAILED"
default_application_id="games.cafecito.foundry.game"
custom_application_id="dev.example.foundryjava"
keystore_secret="foundry-java-acceptance"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" ]]; then
  printf 'ANDROID_SDK_ROOT or ANDROID_HOME must identify the Android SDK.\n' >&2
  exit 1
fi
adb="${sdk_root}/platform-tools/adb"
apkanalyzer="${sdk_root}/cmdline-tools/latest/bin/apkanalyzer"
for tool in "$adb" "$apkanalyzer"; do
  if [[ ! -x "$tool" ]]; then
    printf 'Required Android SDK tool is not executable: %s\n' "$tool" >&2
    exit 1
  fi
done
keytool="${JAVA_HOME:?JAVA_HOME must identify the JDK used to sign the acceptance exports}/bin/keytool"
if [[ ! -x "$keytool" ]]; then
  printf 'keytool is not executable: %s\n' "$keytool" >&2
  exit 1
fi

runner_temp="${RUNNER_TEMP:?RUNNER_TEMP must be set}"
# Evidence is kept separate from the materialized engine and the exported applications so the
# uploaded evidence bundle stays reviewable instead of carrying an editor and four applications.
artifact_root="${runner_temp}/foundry-java-engine-gate"
work_root="${runner_temp}/foundry-java-engine-work"
engine_root="${runner_temp}/foundry-java-engine-runtime"
rm -rf "$artifact_root" "$work_root"
mkdir -p "$artifact_root" "$work_root"

capture_diagnostics() {
  local exit_status=$?
  {
    printf 'serial=%s\n' "$serial"
    "$adb" -s "$serial" devices -l || true
    "$adb" -s "$serial" shell getprop || true
  } >"${artifact_root}/emulator-diagnostics.txt" 2>&1
  "$adb" -s "$serial" logcat -d >"${artifact_root}/logcat-final.txt" 2>&1 || true
  if [[ -f "${runner_temp}/foundry-java-emulator.log" ]]; then
    cp "${runner_temp}/foundry-java-emulator.log" "${artifact_root}/emulator.log"
  fi
  return "$exit_status"
}
trap capture_diagnostics EXIT

api_level="$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$api_level" != "36" ]]; then
  printf 'The engine-loaded conformance gate requires API 36, found API %s.\n' "$api_level" >&2
  exit 1
fi

bash gradle/fetch-pinned-engine.sh "$engine_root" 2>&1 |
  tee "${artifact_root}/engine-fetch.log"
engine_manifest="${engine_root}/engine-manifest.json"
cp "$engine_manifest" "${artifact_root}/engine-manifest.json"
editor="$(jq -r '.editor' "$engine_manifest")"
source_template="$(jq -r '.source_template' "$engine_manifest")"
device_acceptance="$(jq -r '.device_acceptance_tool' "$engine_manifest")"

gradle=("${repo_root}/gradlew" --no-daemon)
publication_repository="${repo_root}/build/repository"
published() {
  printf '%s/games/cafecito/foundry/%s/%s/%s-%s.%s\n' \
    "$publication_repository" "$1" "$acceptance_version" "$1" "$acceptance_version" "$2"
}

# The artifacts under test are always built here, from the commit under test. The gate never
# resolves a published or cached release of this repository.
rm -rf "$publication_repository"
"${gradle[@]}" "-PfoundryVersion=${acceptance_version}" \
  :foundry-java-annotations:publishAllPublicationsToBootstrapRepository \
  :foundry-java-api-model:publishAllPublicationsToBootstrapRepository \
  :foundry-java-runtime:publishAllPublicationsToBootstrapRepository \
  :foundry-java-processor:publishAllPublicationsToBootstrapRepository \
  :foundry-java-kotlin:publishAllPublicationsToBootstrapRepository \
  :foundry-java-android:publishAllPublicationsToBootstrapRepository \
  :foundry-java-gradle-plugin:publishAllPublicationsToBootstrapRepository \
  2>&1 | tee "${artifact_root}/publish.log"

plugin_jar="$(published foundry-java-gradle-plugin jar)"
binding_aar="$(published foundry-java-android aar)"
runtime_jar="$(published foundry-java-runtime jar)"
annotations_jar="$(published foundry-java-annotations jar)"
for artifact in "$plugin_jar" "$binding_aar" "$runtime_jar" "$annotations_jar"; do
  if [[ ! -f "$artifact" ]]; then
    printf 'Foundry-Java artifact under test does not exist: %s\n' "$artifact" >&2
    exit 1
  fi
done

# Foundry-Java is never permitted to package or redistribute the Android host library, and the
# exported application legitimately contains the engine's own copy, so absence is asserted on the
# inputs this repository produces rather than inferred from the final application.
for artifact in "$plugin_jar" "$binding_aar" "$runtime_jar" "$annotations_jar"; do
  if unzip -Z1 "$artifact" | grep -Fq 'libfoundry_android.so'; then
    printf 'Foundry-Java artifact %s must not package libfoundry_android.so.\n' "$artifact" >&2
    exit 1
  fi
done

# Minification keep rules must stay narrow. A rule may name one exact class, or it may match a
# wildcard only when the same rule also constrains the match by supertype; anything broader keeps
# most of the application and would hide a real minification failure. Disabling shrinking,
# obfuscation, or optimization outright is never acceptable.
assert_narrow_keep_rules() {
  local archive="$1"
  local entry="$2"
  local rules
  rules="${artifact_root}/$(basename "$archive").$(basename "$entry")"
  if ! unzip -p "$archive" "$entry" >"$rules" 2>/dev/null; then
    printf 'Keep rules %s are missing from %s.\n' "$entry" "$archive" >&2
    exit 1
  fi
  if grep -Eq '^-(dontobfuscate|dontshrink|dontoptimize)([[:space:]]|$)' "$rules"; then
    printf 'Keep rules %s must not disable minification wholesale.\n' "$entry" >&2
    exit 1
  fi
  if ! awk '
      /^-keep/ {
        specification = ""
        count = split($0, tokens, /[[:space:]]+/)
        for (index_of_token = 1; index_of_token < count; index_of_token++) {
          if (tokens[index_of_token] == "class" ||
              tokens[index_of_token] == "interface" ||
              tokens[index_of_token] == "enum") {
            specification = tokens[index_of_token + 1]
            break
          }
        }
        if (specification ~ /[*?]/ && $0 !~ / implements / && $0 !~ / extends /) {
          printf "%s\n", $0 > "/dev/stderr"
          broad = 1
        }
      }
      END { exit broad }
    ' "$rules"; then
    printf 'Keep rules %s contain an unconstrained wildcard class pattern.\n' "$entry" >&2
    exit 1
  fi
}

# Builds one variant of the acceptance module and leaves its path in acceptance_module_jar. The
# result is returned through a variable rather than standard output so the build log can stream and
# so the harness never runs a leg inside a command substitution subshell.
acceptance_module_jar=""
build_acceptance_module() {
  local variant="$1"
  local disabled="false"
  if [[ "$variant" == "unregistered" ]]; then
    disabled="true"
  fi
  "${gradle[@]}" --project-dir "${repo_root}/acceptance" \
    "-PfoundryVersion=${acceptance_version}" \
    "-PfoundryJavaRegistrationDisabled=${disabled}" \
    clean :extension:jar \
    2>&1 | tee "${artifact_root}/acceptance-module-${variant}.log"
  acceptance_module_jar="${repo_root}/acceptance/extension/build/libs/extension-${acceptance_version}.jar"
  if [[ ! -f "$acceptance_module_jar" ]]; then
    printf 'The acceptance module JAR does not exist: %s\n' "$acceptance_module_jar" >&2
    exit 1
  fi
}

keystore="${artifact_root}/foundry-java-engine-gate.jks"
"$keytool" -genkeypair -noprompt \
  -keystore "$keystore" \
  -storepass "$keystore_secret" \
  -keypass "$keystore_secret" \
  -alias "$keystore_secret" \
  -dname "CN=Foundry Java Engine Gate,O=Cafecito Games,C=US" \
  -keyalg RSA -keysize 2048 -validity 365 \
  >"${artifact_root}/keystore.log" 2>&1

write_acceptance_project() {
  local project="$1"
  local application_id="$2"
  local module_jar="$3"
  shift 3
  local requested_abis=("$@")
  rm -rf "$project"
  mkdir -p "$project"
  cp "${repo_root}/acceptance/project/project.foundry" "$project/project.foundry"
  cp "${repo_root}/acceptance/project/main.tscn" "$project/main.tscn"
  cp "${repo_root}/acceptance/project/main.fs" "$project/main.fs"

  local architecture_options=""
  local abi
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    local enabled="false"
    local requested
    for requested in "${requested_abis[@]}"; do
      if [[ "$requested" == "$abi" ]]; then
        enabled="true"
      fi
    done
    architecture_options+="architectures/${abi}=${enabled}"$'\n'
  done

  {
    printf '[preset.0]\n\n'
    printf 'name="Android"\n'
    printf 'platform="Android"\n'
    printf 'runnable=false\n'
    printf 'export_filter="all_resources"\n'
    printf 'include_filter=""\n'
    printf 'exclude_filter=""\n\n'
    printf '[preset.0.options]\n\n'
    printf 'gradle_build/use_gradle_build=true\n'
    printf 'gradle_build/foundry_java/enabled=true\n'
    printf 'gradle_build/foundry_java/gradle_plugin_local="%s"\n' "$plugin_jar"
    printf 'gradle_build/foundry_java/local_artifacts=PackedStringArray("%s", "%s", "%s", "%s")\n' \
      "$binding_aar" "$runtime_jar" "$annotations_jar" "$module_jar"
    printf 'gradle_build/android_source_template="%s"\n' "$source_template"
    printf 'gradle_build/export_format=0\n'
    printf 'gradle_build/target_sdk="36"\n'
    printf 'package/unique_name="%s"\n' "$application_id"
    printf 'package/signed=true\n'
    printf 'keystore/debug="%s"\n' "$keystore"
    printf 'keystore/debug_user="%s"\n' "$keystore_secret"
    printf 'keystore/debug_password="%s"\n' "$keystore_secret"
    printf 'keystore/release="%s"\n' "$keystore"
    printf 'keystore/release_user="%s"\n' "$keystore_secret"
    printf 'keystore/release_password="%s"\n' "$keystore_secret"
    printf '%s' "$architecture_options"
  } >"$project/export_presets.cfg"
}

# Exports one scenario and leaves the exported application path in exported_apk.
exported_apk=""
export_scenario() {
  local scenario="$1"
  local application_id="$2"
  local mode="$3"
  local module_jar="$4"
  shift 4
  local requested_abis=("$@")
  local scenario_root="${artifact_root}/${scenario}"
  local scenario_work="${work_root}/${scenario}"
  local project="${scenario_work}/project"
  local apk="${scenario_work}/foundry-java-${scenario}.apk"
  mkdir -p "$scenario_root" "$scenario_work"
  write_acceptance_project "$project" "$application_id" "$module_jar" "${requested_abis[@]}"
  cp "$project/export_presets.cfg" "${scenario_root}/export_presets.cfg"
  local previous_directory="$PWD"
  cd "$project"
  "$editor" --headless project export \
    --project "$project" \
    --preset Android \
    --output "$apk" \
    --mode "$mode" \
    --install-android-build-template \
    2>&1 | tee "${scenario_root}/export.log"
  cd "$previous_directory"
  if [[ ! -f "$apk" ]]; then
    printf 'Scenario %s did not produce an exported application.\n' "$scenario" >&2
    exit 1
  fi
  local inspection=("--apk" "$apk" "--evidence-dir" "${scenario_root}/payload" )
  local abi
  for abi in "${requested_abis[@]}"; do
    inspection+=("--abi" "$abi")
  done
  if [[ "$mode" == "release" ]]; then
    inspection+=(
      "--mapping" "${project}/android/build/build/outputs/mapping/standardRelease/mapping.txt"
    )
  fi
  bash gradle/verify-exported-abi-payloads.sh "${inspection[@]}" \
    2>&1 | tee "${scenario_root}/payload-inspection.log"
  "$apkanalyzer" manifest application-id "$apk" >"${scenario_root}/application-id.txt"
  if [[ "$(tr -d '\r\n' <"${scenario_root}/application-id.txt")" != "$application_id" ]]; then
    printf 'Scenario %s exported the wrong application ID.\n' "$scenario" >&2
    exit 1
  fi
  "$apkanalyzer" manifest print "$apk" >"${scenario_root}/manifest.txt"
  exported_apk="$apk"
}

run_device_acceptance() {
  local evidence_dir="$1"
  local process_timeout="$2"
  shift 2
  local acceptance=(
    python3 "$device_acceptance" verify-apks
    --evidence-dir "$evidence_dir"
    --adb "$adb"
    --apkanalyzer "$apkanalyzer"
    --serial "$serial"
    --process-timeout "$process_timeout"
    --required-runtime-marker "$runtime_marker"
  )
  local apk_argument
  for apk_argument in "$@"; do
    acceptance+=(--apk "$apk_argument")
  done
  "${adb}" -s "$serial" logcat -c
  "${acceptance[@]}"
}

# The negative proof runs first, and it is the reason this gate exists. The same binding, packaged
# identically, with only its engine class registration disabled, must not pass. A run that passes
# here means the gate is asserting packaging instead of engine-loaded behaviour.
self_test_root="${artifact_root}/self-test"
mkdir -p "$self_test_root"
build_acceptance_module unregistered
export_scenario self-test-default-debug "$default_application_id" debug \
  "$acceptance_module_jar" x86_64
self_test_apk="$exported_apk"
set +e
run_device_acceptance "${self_test_root}/device-evidence" 45 \
  "${default_application_id}=${self_test_apk}" \
  >"${self_test_root}/device.log" 2>&1
self_test_status=$?
set -e
"$adb" -s "$serial" logcat -d >"${self_test_root}/logcat.txt"
if [[ "$self_test_status" -eq 0 ]]; then
  printf 'The gate self-test unexpectedly passed against a binding whose registration is disabled.\n' >&2
  exit 1
fi
# The self-test must fail for the one reason it exists to demonstrate. Upstream reports a live
# process that never emitted the marker as a marker wait that timed out, and a process that emitted
# nothing at all as a marker the package did not log; anything else -- a forbidden runtime failure, a
# failed install, an unstable process -- means the run proved something other than a disabled
# registration and is not accepted as the negative proof.
if ! grep -Eq \
  "(did not log required runtime marker|waiting for required runtime marker .* timed out)" \
  "${self_test_root}/device.log"; then
  printf 'The gate self-test failed for a reason unrelated to the missing runtime marker.\n' >&2
  cat "${self_test_root}/device.log" >&2
  exit 1
fi
if grep -Fq "$runtime_marker" "${self_test_root}/logcat.txt"; then
  printf 'A binding whose registration is disabled produced the runtime marker.\n' >&2
  exit 1
fi
# The engine still had to load the binding and run the acceptance script for the class lookup to be
# the thing that failed. Without this the self-test would also accept a binding the engine never
# loaded, which is a weaker proof than the gate claims to make.
if ! grep -Fq "${failure_marker} class_missing" "${self_test_root}/logcat.txt"; then
  printf 'The gate self-test did not observe the acceptance script rejecting the missing class.\n' >&2
  exit 1
fi

build_acceptance_module registered
registered_module="$acceptance_module_jar"
assert_narrow_keep_rules "$registered_module" 'META-INF/proguard/foundry-java-acceptance.pro'
assert_narrow_keep_rules "$binding_aar" 'proguard.txt'

# Four combinations: debug and minified release, each with the default and a custom application ID.
# The first also requests every supported ABI so all four exported bridge payloads are statically
# inspected, while the device leg executes on the emulator's x86_64 ABI.
export_scenario default-debug "$default_application_id" debug "$registered_module" \
  arm64-v8a armeabi-v7a x86 x86_64
default_debug_apk="$exported_apk"
export_scenario custom-debug "$custom_application_id" debug "$registered_module" x86_64
custom_debug_apk="$exported_apk"
export_scenario default-release "$default_application_id" release "$registered_module" x86_64
default_release_apk="$exported_apk"
export_scenario custom-release "$custom_application_id" release "$registered_module" x86_64
custom_release_apk="$exported_apk"

set +e
run_device_acceptance "${artifact_root}/device-evidence" 120 \
  "${default_application_id}=${default_debug_apk}" \
  "${custom_application_id}=${custom_debug_apk}" \
  "${default_application_id}=${default_release_apk}" \
  "${custom_application_id}=${custom_release_apk}" \
  >"${artifact_root}/device.log" 2>&1
device_status=$?
set -e
cat "${artifact_root}/device.log"
"$adb" -s "$serial" logcat -d >"${artifact_root}/logcat.txt"
if grep -Fq "$load_failed_token" "${artifact_root}/logcat.txt"; then
  printf 'The engine reported %s; the binding was packaged but never loaded.\n' \
    "$load_failed_token" >&2
  exit 1
fi
if [[ "$device_status" -ne 0 ]]; then
  printf 'Engine-loaded device acceptance exited %s.\n' "$device_status" >&2
  exit 1
fi

jq -n \
  --arg serial "$serial" \
  --arg runtime_marker "$runtime_marker" \
  --slurpfile engine "$engine_manifest" \
  --slurpfile device "${artifact_root}/device-evidence/report.json" \
  '{
    schema_version: 1,
    result: "pass",
    api_level: 36,
    serial: $serial,
    runtime_marker: $runtime_marker,
    engine: $engine[0],
    self_test: {
      variant: "unregistered",
      expected: "fail",
      observed: "fail"
    },
    scenarios: [
      { name: "default-debug", build_type: "debug", requested_abis: ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"] },
      { name: "custom-debug", build_type: "debug", requested_abis: ["x86_64"] },
      { name: "default-release", build_type: "release", requested_abis: ["x86_64"] },
      { name: "custom-release", build_type: "release", requested_abis: ["x86_64"] }
    ],
    device: $device[0]
  }' >"${artifact_root}/summary.json"

printf 'The engine loaded the Foundry-Java binding for every acceptance combination.\n'
