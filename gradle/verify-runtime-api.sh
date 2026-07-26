#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: verify-runtime-api.sh <classes-directory> <baseline-or-dash>" >&2
  exit 2
fi

classes_directory=$1
baseline=$2
actual=$(mktemp "${TMPDIR:-/tmp}/foundry-java-runtime-api.XXXXXX")
class_names=$(mktemp "${TMPDIR:-/tmp}/foundry-java-runtime-api-classes.XXXXXX")
inventory=$(mktemp "${TMPDIR:-/tmp}/foundry-java-runtime-api-inventory.XXXXXX")
generated_inventory=$(mktemp "${TMPDIR:-/tmp}/foundry-java-generated-api.XXXXXX")
trap 'rm -f "$actual" "$class_names" "$inventory" "$generated_inventory"' EXIT

if [[ ! -d "$classes_directory" ]]; then
  echo "Runtime classes directory does not exist: $classes_directory" >&2
  exit 2
fi

while IFS= read -r class_file; do
  relative=${class_file#"$classes_directory/"}
  class_name=${relative%.class}
  class_name=${class_name//\//.}
  printf '%s\n' "$class_name"
done < <(
  find "$classes_directory/games/cafecito/foundry" \
    -type f -name '*.class' -print |
    LC_ALL=C sort
) >"$class_names"

xargs -n 200 javap -public -classpath "$classes_directory" <"$class_names" |
  awk '
    /^Compiled from / {
      class_name = ""
      next
    }
    /^public / {
      for (field = 1; field <= NF; field++) {
        if ($field == "class" || $field == "interface" || $field == "enum") {
          class_name = $(field + 1)
          sub(/<.*/, "", class_name)
          break
        }
      }
    }
    class_name != "" && (/^public / || /^  public /) {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      print class_name "|" line
    }
  ' |
  LC_ALL=C sort >"$inventory"

grep '^games\.cafecito\.foundry\.generated\.' "$inventory" >"$generated_inventory"
grep -v '^games\.cafecito\.foundry\.generated\.' "$inventory" >"$actual"
generated_sha256=$(shasum -a 256 "$generated_inventory" | awk '{print $1}')
generated_lines=$(wc -l <"$generated_inventory" | tr -d '[:space:]')
{
  printf 'games.cafecito.foundry.generated|public-api-lines %s\n' "$generated_lines"
  printf 'games.cafecito.foundry.generated|public-api-sha256 %s\n' "$generated_sha256"
} >>"$actual"
LC_ALL=C sort -o "$actual" "$actual"

if [[ "$baseline" == "-" ]]; then
  cat "$actual"
  exit 0
fi

if ! cmp -s "$baseline" "$actual"; then
  echo "Public runtime binary API differs from $baseline." >&2
  diff -u "$baseline" "$actual" >&2 || true
  exit 1
fi
