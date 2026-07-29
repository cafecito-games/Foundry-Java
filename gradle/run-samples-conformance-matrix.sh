#!/usr/bin/env bash
# Publishes Foundry-Java to the local bootstrap repository, then builds and runs the Java and
# Kotlin conformance samples as ordinary consumer projects: first on the JVM, then as
# instrumentation on an attached API 36 device.
set -euo pipefail

serial="${1:-emulator-5554}"
sample_version="0.1.0"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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
artifact_root="${runner_temp}/foundry-java-conformance-matrix"
mkdir -p "$artifact_root"

capture_diagnostics() {
  local exit_status=$?
  "$adb" -s "$serial" logcat -d > "${artifact_root}/logcat-final.txt" 2>&1 || true
  return "$exit_status"
}
trap capture_diagnostics EXIT

api_level="$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$api_level" != "36" ]]; then
  printf 'The conformance matrix requires API 36, found API %s.\n' "$api_level" >&2
  exit 1
fi

gradle=("${repo_root}/gradlew" --no-daemon)
samples=("${gradle[@]}" --project-dir "${repo_root}/samples" "-PfoundryVersion=${sample_version}")

# A consumer resolves published artifacts, never project outputs. The bootstrap repository is
# rebuilt from scratch so the samples can only see this exact publication set.
rm -rf "${repo_root}/build/repository"
"${gradle[@]}" "-PfoundryVersion=${sample_version}" \
  :foundry-java-annotations:publishAllPublicationsToBootstrapRepository \
  :foundry-java-api-model:publishAllPublicationsToBootstrapRepository \
  :foundry-java-runtime:publishAllPublicationsToBootstrapRepository \
  :foundry-java-processor:publishAllPublicationsToBootstrapRepository \
  :foundry-java-kotlin:publishAllPublicationsToBootstrapRepository \
  :foundry-java-android:publishAllPublicationsToBootstrapRepository \
  :foundry-java-gradle-plugin:publishAllPublicationsToBootstrapRepository \
  2>&1 | tee "${artifact_root}/publish.log"

"${samples[@]}" :conformance-java:test :conformance-kotlin:test \
  2>&1 | tee "${artifact_root}/jvm-matrix.log"

for module in conformance-java-app conformance-kotlin-app; do
  apk="${repo_root}/samples/${module}/build/outputs/apk/androidTest/debug/${module}-debug-androidTest.apk"
  "${samples[@]}" ":${module}:assembleDebugAndroidTest" \
    2>&1 | tee "${artifact_root}/${module}-assemble.log"
  if [[ ! -f "$apk" ]]; then
    printf 'Consumer instrumentation APK does not exist: %s\n' "$apk" >&2
    exit 1
  fi
  entries="${artifact_root}/${module}-apk-entries.txt"
  unzip -Z1 "$apk" > "$entries"
  if grep -Fq 'libfoundry_android.so' "$entries"; then
    printf 'Consumer sample %s must not package libfoundry_android.so.\n' "$module" >&2
    exit 1
  fi
done

"$adb" -s "$serial" logcat -c
set +e
ANDROID_SERIAL="$serial" "${samples[@]}" \
  :conformance-java-app:connectedDebugAndroidTest \
  :conformance-kotlin-app:connectedDebugAndroidTest \
  2>&1 | tee "${artifact_root}/instrumentation.log"
instrumentation_status=${PIPESTATUS[0]}
set -e
"$adb" -s "$serial" logcat -d > "${artifact_root}/logcat.txt"

for module in conformance-java-app conformance-kotlin-app; do
  results="${repo_root}/samples/${module}/build/outputs/androidTest-results/connected"
  if [[ ! -d "$results" ]]; then
    printf 'Instrumentation results are missing for %s.\n' "$module" >&2
    exit 1
  fi
  mkdir -p "${artifact_root}/${module}"
  cp -R "$results" "${artifact_root}/${module}/androidTest-results"
done

if [[ "$instrumentation_status" -ne 0 ]]; then
  printf 'Consumer instrumentation exited %s.\n' "$instrumentation_status" >&2
  exit 1
fi

python3 - "$artifact_root" <<'PYTHON'
import json
import pathlib
import sys
import xml.etree.ElementTree as ElementTree

artifact_root = pathlib.Path(sys.argv[1])
modules = ["conformance-java-app", "conformance-kotlin-app"]
summary = {"schema_version": 1, "api_level": 36, "result": "pass", "modules": []}
for module in modules:
    total = failures = errors = skipped = 0
    suites = sorted((artifact_root / module).rglob("*.xml"))
    if not suites:
        raise SystemExit(f"No instrumentation result XML was captured for {module}.")
    for suite in suites:
        root = ElementTree.parse(suite).getroot()
        total += int(root.get("tests", "0"))
        failures += int(root.get("failures", "0"))
        errors += int(root.get("errors", "0"))
        skipped += int(root.get("skipped", "0"))
    if total == 0 or failures or errors or skipped:
        summary["result"] = "fail"
    summary["modules"].append(
        {
            "module": module,
            "tests": total,
            "failures": failures,
            "errors": errors,
            "skipped": skipped,
        }
    )
(artifact_root / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True))
if summary["result"] != "pass":
    raise SystemExit(f"Conformance matrix summary is not a pass: {summary}")
PYTHON
