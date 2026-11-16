#!/usr/bin/env bash
# Run the CI gates from CLAUDE.md with the full log in a file; print only the failing step's key lines.
# Usage: scripts/gates.sh [content|test|android|all]   (default: content)
#   content = compileMaps checkProgression validateContent buildAssetManifest + assets/ clean check
#   test    = :engine:test :core:test :tools:test
#   test <module> [--tests <pattern>]   = only :<module>:test (engine|core|tools), optional filter for fast iteration
#   android = :android:assembleDebug
# Regenerated reports/ are kept (CI diffs them, so commit them when they change). Only the host-specific ones (timings, local
# sprite coverage; see HOST_REPORTS in scripts/check-generated-assets.sh) are restored afterwards.
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

# Host-specific reports that were already modified before the run are left alone (someone is working on them).
export GATES_DIRTY_BEFORE="$(git diff --name-only -- reports)"
trap 'scripts/check-generated-assets.sh --restore-host-reports' EXIT

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
