#!/usr/bin/env bash
set -euo pipefail

if ! decision="$(
  jq -Rrs '
    def safe_to_skip:
      test("\\.md$") or
      startswith("docs/") or
      startswith("assets/") or
      test("(^|/)src/test(Fixtures)?/") or
      startswith("gradle/testFixtures/") or
      startswith(".github/ISSUE_TEMPLATE/") or
      test("^\\.github/PULL_REQUEST_TEMPLATE(?:\\.md$|/)");

    fromjson
    | if type != "array" then
      ["run=true", "reason=fail-closed"]
    elif length == 0 then
      ["run=true", "reason=fail-closed"]
    elif any(.[]; type != "string" or length == 0) then
      ["run=true", "reason=fail-closed"]
    elif all(.[]; safe_to_skip) then
      ["run=false", "reason=safe-only"]
    else
      ["run=true", "reason=relevant"]
    end
    | .[]
  ' 2>/dev/null
)"; then
  printf 'run=true\nreason=fail-closed\n'
  exit 0
fi

printf '%s\n' "$decision"
