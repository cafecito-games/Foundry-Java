#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: verify-runtime-api.sh <classes-directory> <baseline-or-dash> [report]" >&2
  exit 2
fi

classes_directory=$1
baseline=$2
# The Gradle task that drives this script declares the report as its only output, which is what makes
# the task build-cacheable, and it is the artifact to read when the baseline and the compiled classes
# disagree. Left unset the script behaves exactly as before.
report=${3:-}
# The task exports the configured Java toolchain's bin directory so the inventory is decided by a JDK
# the build declares and names in the task's cache key, rather than by whatever is first on PATH.
javap="${FOUNDRY_JDK_BIN:+$FOUNDRY_JDK_BIN/}javap"
actual=$(mktemp "${TMPDIR:-/tmp}/foundry-java-runtime-api.XXXXXX")
class_names=$(mktemp "${TMPDIR:-/tmp}/foundry-java-runtime-api-classes.XXXXXX")
inventory=$(mktemp "${TMPDIR:-/tmp}/foundry-java-runtime-api-inventory.XXXXXX")
generated_inventory=$(mktemp "${TMPDIR:-/tmp}/foundry-java-generated-api.XXXXXX")
root_inventory=$(mktemp -d "${TMPDIR:-/tmp}/foundry-java-generated-roots.XXXXXX")
root_digests=$(mktemp "${TMPDIR:-/tmp}/foundry-java-generated-root-digests.XXXXXX")
root_counts=$(mktemp "${TMPDIR:-/tmp}/foundry-java-generated-root-counts.XXXXXX")
trap 'rm -rf "$actual" "$class_names" "$inventory" "$generated_inventory" "$root_inventory" \
  "$root_digests" "$root_counts"' EXIT

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

xargs -n 200 "$javap" -public -classpath "$classes_directory" <"$class_names" |
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
# The generated surface is accounted for per public root rather than as one opaque pair of
# aggregate lines, so a dropped, renamed, or mistyped member identifies the root it moved in. The
# aggregate digest is retained for cheap whole-surface change detection, and the realization oracle
# supplies the per-entity accounting.
generated_sha256=$(shasum -a 256 "$generated_inventory" | awk '{print $1}')
awk -F'|' -v directory="$root_inventory" '
  {
    root = $1
    sub(/\$.*/, "", root)
    if (root != previous) {
      if (previous != "") {
        close(directory "/" previous)
      }
      previous = root
    }
    print $0 >> (directory "/" root)
  }
' "$generated_inventory"
{
  printf 'games.cafecito.foundry.generated|public-api-sha256 %s\n' "$generated_sha256"
  if [[ -n "$(ls -A "$root_inventory")" ]]; then
    shasum -a 256 "$root_inventory"/* >"$root_digests"
    wc -l "$root_inventory"/* >"$root_counts"
    awk -v inventory="$root_inventory" '
      FNR == NR {
        path = $2
        sub(inventory "/", "", path)
        digest[path] = $1
        next
      }
      $2 != "total" && $2 != "" {
        path = $2
        sub(inventory "/", "", path)
        printf "%s|public-api-lines %s\n", path, $1
        printf "%s|public-api-sha256 %s\n", path, digest[path]
      }
    ' "$root_digests" "$root_counts"
  fi
} >>"$actual"
LC_ALL=C sort -o "$actual" "$actual"

# Published before the comparison, so a failing run still leaves the inventory that disagreed.
if [[ -n "$report" ]]; then
  mkdir -p "$(dirname "$report")"
  cp "$actual" "$report"
fi

if [[ "$baseline" == "-" ]]; then
  cat "$actual"
  exit 0
fi

if ! cmp -s "$baseline" "$actual"; then
  echo "Public runtime binary API differs from $baseline." >&2
  diff -u "$baseline" "$actual" >&2 || true
  exit 1
fi
