#!/usr/bin/env bash
# Run the CI gates from CLAUDE.md with the full log in a file; print only the failing step's key lines.
# Usage: scripts/gates.sh [content|test|android|all]   (default: content)
#   content = compileMaps checkProgression validateContent buildAssetManifest + assets/ clean check
#   test    = :engine:test :core:test :tools:test
#   android = :android:assembleDebug
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

gradle() { ./gradlew --console=plain -q "$@"; }

step_content() {
  run_step content gradle :tools:compileMaps :tools:checkProgression :tools:validateContent :tools:buildAssetManifest || return $?
  run_step assets-clean scripts/check-generated-assets.sh
}
step_test() { run_step test gradle :engine:test :core:test :tools:test; }
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
  test) step_test ;;
  android) step_android ;;
  all) step_content && step_test && step_android ;;
  *) echo "usage: $0 [content|test|android|all]" >&2; exit 2 ;;
esac
rc=$?
echo "gates $mode exit $rc"
exit $rc
