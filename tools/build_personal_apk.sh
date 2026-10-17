#!/usr/bin/env bash
# Thin wrapper around the Gradle pipeline (all logic lives in Gradle/tools, see docs/ASSET_IMPORT.md):
#   import sprites into the raw cache -> style + validate + coverage -> debug APK with the sprites bundled.
# The APK contains official sprites (c) Nintendo/Creatures/GAME FREAK: personal use only, never share or publish it.
#
# Usage: tools/build_personal_apk.sh [--only=1-151] [--style=gbc|none] [--from=<dir|zip>] [--offline] [extra gradle args]
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

props=()
gradle_args=()
for a in "$@"; do
  case "$a" in
    --only=*)  props+=("-Ppkmn.sprites.only=${a#--only=}") ;;
    --style=*) props+=("-Ppkmn.sprites.style=${a#--style=}") ;;
    --from=*)  props+=("-Ppkmn.sprites.from=${a#--from=}") ;;
    --offline) props+=("-Ppkmn.sprites.offline=true") ;;
    -h|--help) sed -n '2,7p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)         gradle_args+=("$a") ;;
  esac
done

# Without an Android SDK Gradle skips the :android module entirely, so say so up front.
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]] && ! grep -qs '^sdk.dir' local.properties; then
  echo "ERROR: no Android SDK found. Set sdk.dir in $ROOT/local.properties or export ANDROID_HOME (platform 35, build-tools 35.0.0)." >&2
  exit 2
fi

exec ./gradlew :android:assemblePersonalDebugWithSprites ${props[@]+"${props[@]}"} ${gradle_args[@]+"${gradle_args[@]}"}
