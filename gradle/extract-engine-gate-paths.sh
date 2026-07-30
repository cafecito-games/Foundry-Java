#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  exit 1
fi
if [[ ! "$1" =~ ^[1-9][0-9]*$ ]]; then
  exit 1
fi
expected_count="$1"

set +e
paths="$(
  jq -cse --argjson expected "$expected_count" '
    def reject: null | halt_error(1);
    def nonempty_string: type == "string" and length > 0;
    def valid_status:
      . == "added" or
      . == "removed" or
      . == "modified" or
      . == "renamed" or
      . == "copied" or
      . == "changed" or
      . == "unchanged";

    if length != 1 then
      reject
    else
      .[0] as $pages
      | if ($pages | type) != "array" or ($pages | length) == 0 then
          reject
        elif any($pages[]; type != "array" or length == 0) then
          reject
        else
          [$pages[][]] as $items
          | if ($items | length) == 0 then
              reject
            elif any($items[]; type != "object") then
              reject
            elif any(
              $items[];
              ((.filename | nonempty_string) | not) or
              ((.status | valid_status) | not) or
              (
                has("previous_filename") and
                ((.previous_filename | nonempty_string) | not)
              ) or
              (
                .status == "renamed" and
                ((has("previous_filename") and
                  (.previous_filename | nonempty_string)) | not)
              )
            ) then
              reject
            elif ($items | length) != $expected then
              null | halt_error(2)
            else
              [
                $items[]
                | .filename,
                  (
                    if has("previous_filename") then
                      .previous_filename
                    else
                      empty
                    end
                  )
              ]
            end
        end
    end
  ' 2>/dev/null
)"
status="$?"
set -e

case "$status" in
  0)
    printf '%s\n' "$paths"
    ;;
  2)
    exit 2
    ;;
  *)
    exit 1
    ;;
esac
