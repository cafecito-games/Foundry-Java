#!/usr/bin/env bash
set -euo pipefail

serial="${1:-emulator-5554}"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" ]]; then
  printf 'ANDROID_SDK_ROOT or ANDROID_HOME must identify the Android SDK.\n' >&2
  exit 1
fi
adb="${sdk_root}/platform-tools/adb"
if [[ ! -x "$adb" ]]; then
  printf 'Android SDK adb does not exist: %s\n' "$adb" >&2
  exit 1
fi
android_ndk_root="${ANDROID_NDK_HOME:-${sdk_root}/ndk/29.0.14206865}"
llvm_readelf="$(
  find "$android_ndk_root/toolchains/llvm/prebuilt" -path '*/bin/llvm-readelf' -print -quit
)"
if [[ -z "$llvm_readelf" || ! -x "$llvm_readelf" ]]; then
  printf 'llvm-readelf was not found under %s\n' "$android_ndk_root" >&2
  exit 1
fi
runner_temp="${RUNNER_TEMP:?RUNNER_TEMP must be set}"
artifact_root="${runner_temp}/foundry-java-production-startup"
target_package="games.cafecito.foundry.android.test"
instrumentation_component="games.cafecito.foundry.android.test/games.cafecito.foundry.java.FoundryJavaInstrumentation"
device_evidence="/data/user/0/games.cafecito.foundry.android.test/files/foundry-java-production-startup-evidence.json"
test_apk="foundry-java-android/build/outputs/apk/androidTest/debug/foundry-java-android-debug-androidTest.apk"

mkdir -p "$artifact_root"

capture_diagnostics() {
  local exit_status=$?
  {
    printf 'serial=%s\n' "$serial"
    "$adb" -s "$serial" devices -l || true
    "$adb" -s "$serial" shell getprop || true
    "$adb" -s "$serial" shell dumpsys activity processes || true
  } > "${artifact_root}/emulator-diagnostics.txt" 2>&1
  "$adb" -s "$serial" logcat -d > "${artifact_root}/logcat-final.txt" 2>&1 || true
  if [[ -f "${runner_temp}/foundry-java-emulator.log" ]]; then
    cp "${runner_temp}/foundry-java-emulator.log" "${artifact_root}/emulator.log"
  fi
  return "$exit_status"
}
trap capture_diagnostics EXIT

api_level="$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$api_level" != "36" ]]; then
  printf 'Production startup acceptance requires API 36, found API %s.\n' "$api_level" >&2
  exit 1
fi

./gradlew --no-daemon :foundry-java-android:assembleDebugAndroidTest
if [[ ! -f "$test_apk" ]]; then
  printf 'Instrumentation APK does not exist: %s\n' "$test_apk" >&2
  exit 1
fi

test_apk_entries="${artifact_root}/instrumentation-apk-entries.txt"
unzip -Z1 "$test_apk" >"$test_apk_entries"
if awk -F/ \
  '$1 == "lib" && NF == 3 && $3 == "libfoundry_android.so" { found = 1 }
   END { exit !found }' \
  "$test_apk_entries"; then
  printf 'Instrumentation APK must not package libfoundry_android.so.\n' >&2
  exit 1
fi

native_check_root="${artifact_root}/native-check"
mkdir -p "$native_check_root"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  test_host_entry="lib/${abi}/libfoundry_java_test_host.so"
  test_host_library="${native_check_root}/${abi}-libfoundry_java_test_host.so"
  symbols_file="${native_check_root}/${abi}-symbols.txt"
  dynamic_file="${native_check_root}/${abi}-dynamic.txt"
  if ! unzip -p "$test_apk" "$test_host_entry" >"$test_host_library"; then
    printf 'Instrumentation APK is missing %s.\n' "$test_host_entry" >&2
    exit 1
  fi
  "$llvm_readelf" --dyn-syms --wide "$test_host_library" >"$symbols_file"
  "$llvm_readelf" --dynamic "$test_host_library" >"$dynamic_file"
  if ! awk \
    '$4 == "FUNC" && $5 == "GLOBAL" && $7 != "UND" && $8 == "JNI_OnLoad" { found = 1 }
     END { exit !found }' \
    "$symbols_file"; then
    printf '%s must define its own global JNI_OnLoad.\n' "$test_host_entry" >&2
    exit 1
  fi
  if ! grep -Fq 'Shared library: [libfoundry_java.so]' "$dynamic_file"; then
    printf '%s must retain its production bridge dependency.\n' "$test_host_entry" >&2
    exit 1
  fi
  rm "$test_host_library"
done
ANDROID_SERIAL="$serial" "$adb" install -r -t "$test_apk"

force_stop_and_wait() {
  "$adb" -s "$serial" shell am force-stop "$target_package"
  for _ in $(seq 1 60); do
    if [[ -z "$("$adb" -s "$serial" shell pidof "$target_package" 2>/dev/null | tr -d '\r')" ]]; then
      return 0
    fi
    sleep 1
  done
  printf 'Package %s still has a live process after force-stop.\n' "$target_package" >&2
  return 1
}

validate_evidence() {
  local evidence_file=$1
  local run_index=$2
  local observed_pid=$3
  jq -e \
    --argjson expected_run "$run_index" \
    --argjson observed_pid "$observed_pid" \
    '
      [
        "provider_on_create",
        "application_on_create",
        "activity_on_create",
        "foundry_extension_entry",
        "core_initialize",
        "scene_initialize",
        "callback_dispatch",
        "scene_deinitialize",
        "core_deinitialize",
        "context_invalidate"
      ] as $required_events
      | [
        "foundry_extension_entry",
        "core_initialize",
        "scene_initialize",
        "callback_dispatch",
        "scene_deinitialize",
        "core_deinitialize",
        "context_invalidate"
      ] as $native_events
      | .events == $required_events
      and .schema_version == 1
      and .run_index == $expected_run
      and (.pid | type == "number" and . > 0)
      and .pid == $observed_pid
      and .pid_before_lifecycle == .pid
      and .pid_after_lifecycle == .pid
      and .target_package == "games.cafecito.foundry.android.test"
      and .authority == "games.cafecito.foundry.android.test.foundry-java-startup"
      and .fresh_process == true
      and .provider_before_application == true
      and .provider_before_activity == true
      and .context_count_during_priming == 0
      and .registered_class_count_during_priming == 0
      and .core_context_nonzero == true
      and .provider_registration_count == 1
      and .application_on_create_count == 1
      and .activity_on_create_count == 1
      and .callback_dispatch_count == 1
      and .callback_result == 42
      and .callback_result_observed_in_java == 42
      and .callback_thread_attached == true
      and .exception_contained == true
      and .exception_dispatch_count == 1
      and .exception_default_is_nil == true
      and .stale_instance_callback_rejected == true
      and .invalidation_count == 1
      and .descriptor_evaluation_count == 1
      and .initialize_attempts == [
        "CORE",
        "CORE",
        "SERVERS",
        "SERVERS",
        "SCENE",
        "SCENE"
      ]
      and .registration_order == ["FoundryJavaTestCore", "FoundryJavaTestScene"]
      and .registration_counts == {
        "FoundryJavaTestCore": 1,
        "FoundryJavaTestScene": 1
      }
      and .deinitialize_attempts == [
        "SCENE",
        "SCENE",
        "SERVERS",
        "SERVERS",
        "CORE",
        "CORE"
      ]
      and .teardown_order == ["FoundryJavaTestScene", "FoundryJavaTestCore"]
      and .unregistration_counts == {
        "FoundryJavaTestCore": 1,
        "FoundryJavaTestScene": 1
      }
      and .live_instances_after_teardown == 0
      and .live_handles_after_teardown == 0
      and .entry_active_after_teardown == false
      and .result == "pass"
      and .failure == null
      and (
        .native_lifecycle as $lifecycle
        | $lifecycle.schema_version == 1
        and $lifecycle.run_index == $expected_run
        and $lifecycle.entry_accepted == true
        and $lifecycle.context_handle == 1
        and $lifecycle.initialize_attempts == [
          "CORE",
          "CORE",
          "SERVERS",
          "SERVERS",
          "SCENE",
          "SCENE"
        ]
        and $lifecycle.registration_order == [
          "FoundryJavaTestCore",
          "FoundryJavaTestScene"
        ]
        and $lifecycle.registration_counts == {
          "FoundryJavaTestCore": 1,
          "FoundryJavaTestScene": 1
        }
        and $lifecycle.callback_result == 42
        and $lifecycle.callback_thread_attached == true
        and $lifecycle.exception_contained == true
        and $lifecycle.exception_default_is_nil == true
        and $lifecycle.stale_instance_callback_rejected == true
        and $lifecycle.deinitialize_attempts == [
          "SCENE",
          "SCENE",
          "SERVERS",
          "SERVERS",
          "CORE",
          "CORE"
        ]
        and $lifecycle.unregistration_order == [
          "FoundryJavaTestScene",
          "FoundryJavaTestCore"
        ]
        and $lifecycle.unregistration_counts == {
          "FoundryJavaTestCore": 1,
          "FoundryJavaTestScene": 1
        }
        and $lifecycle.live_instances_after_teardown == 0
        and $lifecycle.live_handles_after_teardown == 0
        and $lifecycle.entry_active_after_teardown == false
        and $lifecycle.events == $native_events
      )
    ' "$evidence_file" > /dev/null
}

for run_index in 1 2; do
  run_directory="${artifact_root}/run-${run_index}"
  evidence_file="${RUNNER_TEMP}/foundry-java-production-startup/run-${run_index}/evidence.json"
  mkdir -p "$run_directory"
  force_stop_and_wait
  "$adb" -s "$serial" logcat -c
  instrumentation_file="${run_directory}/instrumentation.txt"
  "$adb" -s "$serial" shell am instrument -w -r \
    -e foundry_run_index "$run_index" \
    "$instrumentation_component" >"$instrumentation_file" 2>&1 &
  instrumentation_process=$!
  observed_pid=""
  for _ in $(seq 1 100); do
    observed_pid="$(
      "$adb" -s "$serial" shell pidof "$target_package" 2>/dev/null |
        tr -d '\r' |
        xargs || true
    )"
    if [[ "$observed_pid" =~ ^[1-9][0-9]*$ ]]; then
      break
    fi
    sleep 0.05
  done
  set +e
  wait "$instrumentation_process"
  instrumentation_status=$?
  set -e
  cat "$instrumentation_file"
  if [[ "$instrumentation_status" -ne 0 ]]; then
    printf 'Run %s instrumentation command exited %s.\n' \
      "$run_index" "$instrumentation_status" >&2
    exit 1
  fi
  if [[ ! "$observed_pid" =~ ^[1-9][0-9]*$ ]]; then
    printf 'Run %s did not leave one observable positive package PID: %s\n' \
      "$run_index" "$observed_pid" >&2
    exit 1
  fi
  "$adb" -s "$serial" exec-out run-as "$target_package" cat "$device_evidence" \
    > "$evidence_file"
  "$adb" -s "$serial" logcat -d > "${run_directory}/logcat.txt"
  validate_evidence "$evidence_file" "$run_index" "$observed_pid"
done

run_1_pid="$(jq -r '.pid' "${artifact_root}/run-1/evidence.json")"
run_2_pid="$(jq -r '.pid' "${artifact_root}/run-2/evidence.json")"
if [[ "$run_1_pid" == "$run_2_pid" ]]; then
  printf 'Production startup runs must use distinct fresh-process PIDs: %s.\n' "$run_1_pid" >&2
  exit 1
fi

jq -n \
  --arg serial "$serial" \
  --arg target_package "$target_package" \
  --argjson run_1_pid "$run_1_pid" \
  --argjson run_2_pid "$run_2_pid" \
  '{
    schema_version: 1,
    result: "pass",
    api_level: 36,
    serial: $serial,
    target_package: $target_package,
    run_count: 2,
    force_stop_observed: true,
    distinct_pids: true,
    pids: [$run_1_pid, $run_2_pid],
    runs: [
      {
        run_index: 1,
        pid: $run_1_pid,
        evidence: "run-1/evidence.json",
        instrumentation: "run-1/instrumentation.txt",
        logcat: "run-1/logcat.txt"
      },
      {
        run_index: 2,
        pid: $run_2_pid,
        evidence: "run-2/evidence.json",
        instrumentation: "run-2/instrumentation.txt",
        logcat: "run-2/logcat.txt"
      }
    ]
  }' > "${artifact_root}/summary.json"
