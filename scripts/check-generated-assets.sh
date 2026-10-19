#!/usr/bin/env bash
# CI guard: assets/ is generated from data-src/ and world/. After regenerating, nothing under assets/ may differ from
# the commit: no modified/deleted tracked file AND no new untracked file (git diff alone misses the latter).
# Usage: scripts/check-generated-assets.sh [repo-dir]   (defaults to the current directory)
set -euo pipefail
cd "${1:-.}"
dirty="$(git status --porcelain --untracked-files=all -- assets/)"
if [ -n "$dirty" ]; then
  echo "Generated content under assets/ is not committed (regenerate with the content gates and commit):" >&2
  echo "$dirty" >&2
  exit 1
fi
echo "assets/ is clean"
