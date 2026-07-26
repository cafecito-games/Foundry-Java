#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "usage: verify-kotlin-api.sh <jar> <baseline> [--write]" >&2
    exit 2
fi

jar_file=$1
baseline=$2
mode=${3:-verify}

if [[ ! -f "$jar_file" ]]; then
    echo "Kotlin artifact does not exist: $jar_file" >&2
    exit 2
fi

temporary_directory=$(mktemp -d)
trap 'rm -rf "$temporary_directory"' EXIT
actual="$temporary_directory/foundry-java-kotlin.api"

LC_ALL=C jar tf "$jar_file" |
    sed -n 's#^games/cafecito/foundry/kotlin/\([^$]*\)\.class$#games.cafecito.foundry.kotlin.\1#p' |
    sort |
    while IFS= read -r class_name; do
        raw_declaration="$temporary_directory/raw-declaration"
        declaration="$temporary_directory/declaration"
        javap -classpath "$jar_file" -public -s "$class_name" >"$raw_declaration"
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
            printf '## %s\n' "$class_name"
            cat "$declaration"
            printf '\n'
        fi
    done >"$actual"

case "$mode" in
    --write)
        cp "$actual" "$baseline"
        ;;
    verify)
        diff -u "$baseline" "$actual"
        ;;
    *)
        echo "unknown mode: $mode" >&2
        exit 2
        ;;
esac
