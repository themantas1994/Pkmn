#!/usr/bin/env bash
# Captures the visual-regression scenes (desktop/automation/scenes/*.txt) with the real desktop game under Xvfb,
# using --fixedstep so frames are reproducible, then compares them with docs/screenshots/ baselines.
#   tools/visual/run_scenes.sh            capture + compare (non-zero exit on a visual change)
#   tools/visual/run_scenes.sh --update   capture + replace the baselines
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/screenshots"
rm -rf "$OUT" && mkdir -p "$OUT"
failed=0
"$ROOT/gradlew" -q -p "$ROOT" :desktop:installDist
for scene in "$ROOT"/desktop/automation/scenes/*.txt; do
  saves="$(mktemp -d)"
  echo "scene $(basename "$scene")"
  # Optional directives on the scene's first lines: "#! no-quickstart" (start at the title) and "#! args: --flag ..." (extra desktop flags).
  quick="--quickstart"; grep -q '^#! no-quickstart' "$scene" && quick=""
  extra="$(sed -n 's/^#! args: //p' "$scene")"
  (cd "$ROOT/assets" && timeout 300 xvfb-run -a -s "-screen 0 1024x768x24" \
     "$ROOT/desktop/build/install/desktop/bin/desktop" $quick $extra --mute --fixedstep --scale=1 \
     --saves="$saves" --script="$scene" --shots="$OUT" > "$OUT/$(basename "$scene" .txt).log" 2>&1) || { rc=$?; echo "  (exit $rc)"; if [ "$rc" -eq 124 ]; then failed=1; fi; }  # a hung scene fails; crashes show up as NOT-CAPTURED below
  rm -rf "$saves"
done
cd "$ROOT"
java_args="screenshots $OUT $ROOT/docs/screenshots ${1:-}"
"$ROOT/gradlew" -q :tools:run --args="$java_args" || failed=1
exit $failed
