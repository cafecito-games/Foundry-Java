#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: verify-runtime-api.sh <classes-directory> <baseline-or-dash>" >&2
  exit 2
fi

classes_directory=$1
baseline=$2
actual=$(mktemp "${TMPDIR:-/tmp}/foundry-java-runtime-api.XXXXXX")
trap 'rm -f "$actual"' EXIT

if [[ ! -d "$classes_directory" ]]; then
  echo "Runtime classes directory does not exist: $classes_directory" >&2
  exit 2
fi

while IFS= read -r class_file; do
  relative=${class_file#"$classes_directory/"}
  class_name=${relative%.class}
  class_name=${class_name//\//.}
  javap_output=$(javap -public -classpath "$classes_directory" "$class_name")
  if ! grep -q '^public ' <<<"$javap_output"; then
    continue
  fi
  awk -v class_name="$class_name" '
    /^public / || /^  public / {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      print class_name "|" line
    }
  ' <<<"$javap_output"
done < <(
  find "$classes_directory/games/cafecito/foundry/runtime" \
    "$classes_directory/games/cafecito/foundry/types" \
    -type f -name '*.class' -print |
    LC_ALL=C sort
) | LC_ALL=C sort >"$actual"

if [[ "$baseline" == "-" ]]; then
  cat "$actual"
  exit 0
fi

if ! cmp -s "$baseline" "$actual"; then
  echo "Public runtime binary API differs from $baseline." >&2
  diff -u "$baseline" "$actual" >&2 || true
  exit 1
fi
