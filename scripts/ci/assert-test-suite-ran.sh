#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <junit-xml> [...]" >&2
  exit 2
fi

for report in "$@"; do
  if [ ! -f "$report" ]; then
    echo "required integration test report is missing: $report" >&2
    exit 1
  fi

  suite="$(grep -m 1 '<testsuite ' "$report")"
  tests="$(printf '%s' "$suite" | sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p')"
  skipped="$(printf '%s' "$suite" | sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p')"

  if [ -z "$tests" ] || [ "$tests" -eq 0 ]; then
    echo "required integration test suite did not execute tests: $report" >&2
    exit 1
  fi
  if [ -z "$skipped" ] || [ "$skipped" -ne 0 ]; then
    echo "required integration test suite was skipped: $report" >&2
    exit 1
  fi
done
