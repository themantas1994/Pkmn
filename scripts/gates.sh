#!/usr/bin/env bash
# Run the CI gates from CLAUDE.md with the full log in a file; print only the failing step's key lines.
# Usage: scripts/gates.sh [content|test|android|all]   (default: content)
#   content = compileMaps checkProgression validateContent buildAssetManifest + assets/ clean check
#   test    = :engine:test :core:test :tools:test
#   test <module> [--tests <pattern>]   = only :<module>:test (engine|core|tools), optional filter for fast iteration
#   android = :android:assembleDebug
# reports/ files that were clean before the run are restored afterwards (they embed host-specific sprite coverage);
# set GATES_KEEP_REPORTS=1 to keep regenerated reports.
# Exit status is the first failing step's real status. Logs: ${GATES_LOG_DIR:-build/gates}/<step>.log
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
logdir="${GATES_LOG_DIR:-build/gates}"
mkdir -p "$logdir"

run_step() { # name, command...
  local name="$1"; shift
  local log="$logdir/$name.log" start=$SECONDS
  "$@" >"$log" 2>&1
  local rc=$?
  if [ $rc -eq 0 ]; then
    echo "PASS $name ($((SECONDS - start))s)"
    return 0
  fi
  echo "FAIL $name (exit $rc, $((SECONDS - start))s) log: $log"
  grep -E "^e: |FAILED|What went wrong|^> |Exception|AssertionError|error:" "$log" | head -20
  return $rc
}

reports_before="$(git diff --name-only -- reports)"
restore_reports() {
  [ "${GATES_KEEP_REPORTS:-0}" = 1 ] && return 0
  local f
  while IFS= read -r f; do
    [ -n "$f" ] && ! grep -qxF "$f" <<<"$reports_before" && git checkout -q -- "$f"
  done < <(git diff --name-only -- reports)
  return 0
}
trap restore_reports EXIT

gradle() { ./gradlew --console=plain -q "$@"; }

step_content() {
  run_step content gradle :tools:compileMaps :tools:checkProgression :tools:validateContent :tools:buildAssetManifest || return $?
  run_step assets-clean scripts/check-generated-assets.sh
}
step_test() {
  local module="${1:-}"; shift || true
  if [ -z "$module" ]; then run_step test gradle :engine:test :core:test :tools:test; return $?; fi
  case "$module" in engine|core|tools) ;; *) echo "unknown module '$module' (engine|core|tools)" >&2; return 2 ;; esac
  local filter=()
  if [ "${1:-}" = "--tests" ] && [ -n "${2:-}" ]; then filter=(--tests "$2"); fi
  run_step "test-$module" gradle ":$module:test" "${filter[@]}"
}
step_android() {
  if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && [ -d "$HOME/Android/Sdk" ]; then export ANDROID_HOME="$HOME/Android/Sdk"; fi
  if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && ! grep -qs sdk.dir local.properties; then
    echo "SKIP android (no Android SDK: set ANDROID_HOME or local.properties)"; return 0
  fi
  run_step android gradle :android:assembleDebug
}

mode="${1:-content}"
case "$mode" in
  content) step_content ;;
  test) shift; step_test "$@" ;;
  android) step_android ;;
  all) step_content && step_test && step_android ;;
  *) echo "usage: $0 [content|test [module [--tests pattern]]|android|all]" >&2; exit 2 ;;
esac
rc=$?
echo "gates $mode exit $rc"
exit $rc
