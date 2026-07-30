#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
    echo "usage: verify-kotlin-api.sh <jar> <baseline> [--write] [--report <path>]" >&2
    exit 2
fi

jar_file=$1
baseline=$2
shift 2

# The Gradle task that drives this script declares the report as its only output, which is what makes
# the task build-cacheable, and it is the artifact to read when the baseline and the jar disagree.
mode=verify
report=
while [[ $# -gt 0 ]]; do
    case "$1" in
        --write)
            mode=--write
            shift
            ;;
        --report)
            report=${2:?--report requires a path}
            shift 2
            ;;
        *)
            echo "unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

# The task exports the configured Java toolchain's bin directory so the declaration dump is decided by
# a JDK the build declares and names in the task's cache key, rather than by whatever is first on PATH.
jdk_bin=${FOUNDRY_JDK_BIN:+$FOUNDRY_JDK_BIN/}

if [[ ! -f "$jar_file" ]]; then
    echo "Kotlin artifact does not exist: $jar_file" >&2
    exit 2
fi

temporary_directory=$(mktemp -d)
trap 'rm -rf "$temporary_directory"' EXIT
actual="$temporary_directory/foundry-java-kotlin.api"

LC_ALL=C "${jdk_bin}jar" tf "$jar_file" |
    sed -n 's#^games/cafecito/foundry/kotlin/\([^$]*\)\.class$#games.cafecito.foundry.kotlin.\1#p' |
    sort |
    {
        first_declaration=true
        while IFS= read -r class_name; do
            raw_declaration="$temporary_directory/raw-declaration"
            declaration="$temporary_directory/declaration"
            "${jdk_bin}javap" -classpath "$jar_file" -public -s "$class_name" >"$raw_declaration"
            awk '
            /^  public .* (access\$|snapshot\$foundry_java_kotlin)/ {
                skip_descriptor = 1
                next
            }
            skip_descriptor && /^    descriptor:/ {
                skip_descriptor = 0
                next
            }
            NF { print }
        ' "$raw_declaration" >"$declaration"
            if grep -Eq '^public (final |abstract )?(class|interface) ' "$declaration"; then
                if [[ "$first_declaration" == false ]]; then
                    printf '\n'
                fi
                printf '## %s\n' "$class_name"
                cat "$declaration"
                first_declaration=false
            fi
        done
    } >"$actual"

# Published before the comparison, so a failing run still leaves the dump that disagreed.
if [[ -n "$report" ]]; then
    mkdir -p "$(dirname "$report")"
    cp "$actual" "$report"
fi

case "$mode" in
    --write)
        cp "$actual" "$baseline"
        ;;
    verify)
        diff -u "$baseline" "$actual"
        ;;
esac
