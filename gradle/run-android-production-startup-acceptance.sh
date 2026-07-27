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
      . as $evidence
      | (.events | type == "array" and length > 0)
      and (
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
        ]
        | map(. as $event | $evidence.events | index($event))
        | all(.[]; . != null) and (. == sort)
      )
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
      and .core_context_nonzero == true
      and .provider_registration_count == 1
      and .application_on_create_count == 1
      and .activity_on_create_count == 1
      and .callback_dispatch_count == 1
      and .callback_result == 42
      and .callback_thread_attached == true
      and .exception_contained == true
      and .stale_instance_callback_rejected == true
      and .invalidation_count == 1
      and .registration_order == ["FoundryJavaTestCore", "FoundryJavaTestScene"]
      and .teardown_order == ["FoundryJavaTestScene", "FoundryJavaTestCore"]
      and .result == "pass"
      and .failure == null
    ' "$evidence_file" > /dev/null
}

for run_index in 1 2; do
  run_directory="${artifact_root}/run-${run_index}"
  evidence_file="${RUNNER_TEMP}/foundry-java-production-startup/run-${run_index}/evidence.json"
  mkdir -p "$run_directory"
  force_stop_and_wait
  "$adb" -s "$serial" logcat -c
  "$adb" -s "$serial" shell am instrument -w -r \
    -e foundry_run_index "$run_index" \
    "$instrumentation_component" |
    tee "${run_directory}/instrumentation.txt"
  observed_pid="$("$adb" -s "$serial" shell pidof "$target_package" | tr -d '\r' | xargs)"
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
  --arg target_package "$target_package" \
  --argjson run_1_pid "$run_1_pid" \
  --argjson run_2_pid "$run_2_pid" \
  '{
    schema_version: 1,
    result: "pass",
    target_package: $target_package,
    run_count: 2,
    distinct_pids: ($run_1_pid != $run_2_pid),
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
