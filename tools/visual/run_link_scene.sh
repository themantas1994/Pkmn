#!/usr/bin/env bash
# Link play on one machine: two desktop instances under Xvfb play each other over localhost, in three scenarios.
#   battle  a scripted battle to the end; both show the matching result screen (same state hash, opposite outcomes)
#   forfeit the guest forfeits; the host wins, both show the result
#   killed  the guest process is killed (kill -9) mid-battle; the host's result is "link lost" and it returns to the overworld
# In every scenario the survivor's save files must be byte-identical before and after the link (nothing is written).
# Output: build/link-scene/<scenario>/{host,guest}/*.png and *.log. Exit status is non-zero when any check fails.
#   tools/visual/run_link_scene.sh [battle|forfeit|killed ...]     (default: all three)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/link-scene"
BASE_PORT="${LINK_PORT:-47840}"
SCENARIOS=("$@"); [ ${#SCENARIOS[@]} -eq 0 ] && SCENARIOS=(battle forfeit killed)
rm -rf "$OUT" && mkdir -p "$OUT"
"$ROOT/gradlew" -q -p "$ROOT" :desktop:installDist || { echo "build failed"; exit 1; }
BIN="$ROOT/desktop/build/install/desktop/bin/desktop"
failed=0
fail() { echo "  FAIL: $*"; failed=1; }

# Starts one instance in its own process group; prints the group id. $1 name, $2 scenario dir, $3 script, $4 player name
start_instance() {
  local name="$1" dir="$2" script="$3" player="$4"
  mkdir -p "$dir/$name/shots" "$dir/$name/saves"
  ( cd "$ROOT/assets" && exec setsid timeout 240 xvfb-run -a -s "-screen 0 1024x768x24" \
      "$BIN" --quickstart="$player" --mute --fixedstep --scale=1 --saves="$dir/$name/saves" \
      --script="$script" --shots="$dir/$name/shots" > "$dir/$name.log" 2>&1 ) &
  echo $!
}
save_hash() { ( cd "$1" && find . -type f ! -name options.json | sort | xargs -r sha256sum | sha256sum | cut -c1-16 ); }
field() { grep -o "$2=[^ ]*" "$1" | tail -1 | cut -d= -f2; }
last_log() { grep "AUTOMATION frame" "$1" | tail -1; }

for sc in "${SCENARIOS[@]}"; do
  echo "scenario $sc"
  dir="$OUT/$sc"; mkdir -p "$dir"
  port=$((BASE_PORT + RANDOM % 200))
  sed "s/@PORT@/$port/" "$ROOT/desktop/automation/link/host_$sc.txt" > "$dir/host.txt"
  sed "s/@PORT@/$port/; s/@ADDR@/127.0.0.1/" "$ROOT/desktop/automation/link/guest_$sc.txt" > "$dir/guest.txt"
  hpid=$(start_instance host "$dir" "$dir/host.txt" HOST)
  for _ in $(seq 120); do grep -q "^LINK host code=" "$dir/host.log" 2>/dev/null && break; sleep 1; done
  grep -q "^LINK host code=" "$dir/host.log" || { fail "host never started listening"; kill -9 -- "-$hpid" 2>/dev/null; continue; }
  # The host saved before it listened: record that save, then let the guest in.
  hsave_before=$(save_hash "$dir/host/saves")
  gpid=$(start_instance guest "$dir" "$dir/guest.txt" GUEST)
  gsave_before=""
  if [ "$sc" != killed ]; then
    for _ in $(seq 120); do [ -f "$dir/guest/shots/link_guest_lobby.png" ] && break; sleep 1; done
    gsave_before=$(save_hash "$dir/guest/saves")   # the guest saved before it joined
  fi
  if [ "$sc" = killed ]; then
    for _ in $(seq 180); do [ -f "$dir/host/shots/link_host_battle.png" ] && break; sleep 1; done
    [ -f "$dir/host/shots/link_host_battle.png" ] || fail "the battle never started"
    sleep 8
    kill -9 -- "-$gpid" 2>/dev/null; pkill -9 -f "pkmn.desktop.*--saves=$dir/guest/saves" 2>/dev/null
    echo "  guest killed"
  fi
  # Back on the overworld the script idles for a few seconds before it quits (quitting autosaves, as it always does):
  # sample the save files in that window.
  for _ in $(seq 240); do [ -f "$dir/host/shots/link_after.png" ] && break; sleep 1; done
  sleep 2
  hsave_after=$(save_hash "$dir/host/saves")
  [ "$sc" = killed ] || gsave_after=$(save_hash "$dir/guest/saves")
  # Wait for the host (and the guest unless killed) to finish.
  for _ in $(seq 240); do kill -0 "$hpid" 2>/dev/null || break; sleep 1; done
  kill -0 "$hpid" 2>/dev/null && { fail "host still running"; kill -9 -- "-$hpid" 2>/dev/null; }
  if [ "$sc" != killed ]; then
    for _ in $(seq 60); do kill -0 "$gpid" 2>/dev/null || break; sleep 1; done
    kill -0 "$gpid" 2>/dev/null && { fail "guest still running"; kill -9 -- "-$gpid" 2>/dev/null; }
  fi
  # ---- checks
  hl=$(grep "top=LinkResultScreen" "$dir/host.log" | tail -1); gl=$(grep "top=LinkResultScreen" "$dir/guest.log" | tail -1)
  case "$sc" in
    battle|forfeit)
      [ -n "$hl" ] && [ -n "$gl" ] || fail "a result screen was never logged (host: ${hl:+yes}, guest: ${gl:+yes})"
      hh=$(field <(echo "$hl") hash); gh=$(field <(echo "$gl") hash)
      hw=$(field <(echo "$hl") winnerSide); gw=$(field <(echo "$gl") winnerSide)
      [ "$(field <(echo "$hl") link)" = completed ] && [ "$(field <(echo "$gl") link)" = completed ] || fail "not both completed: [$hl] [$gl]"
      [ -n "$hh" ] && [ "$hh" = "$gh" ] && echo "  same state hash: $hh" || fail "hashes differ: '$hh' vs '$gh'"
      [ "$hw" = "$gw" ] && echo "  same winner side: $hw" || fail "winners differ: '$hw' vs '$gw'"
      [ "$sc" = forfeit ] && { [ "$hw" = 0 ] && echo "  the host won by forfeit" || fail "forfeit: expected the host to win, winnerSide=$hw"; }
      [ "$gsave_before" = "$gsave_after" ] && echo "  guest save unchanged ($gsave_after)" || fail "guest save changed: $gsave_before -> $gsave_after"
      ;;
    killed)
      [ "$(field <(echo "$hl") link)" = void ] && [ "$(field <(echo "$hl") reason)" = LINK_LOST ] && echo "  host result: void, link lost" || fail "host result was not 'void LINK_LOST': [$hl]"
      ;;
  esac
  last=$(last_log "$dir/host.log")
  echo "$last" | grep -q "top=OverworldScreen" && echo "  host is back on the overworld" || fail "host did not end on the overworld: [$last]"
  [ "$hsave_before" = "$hsave_after" ] && echo "  host save unchanged ($hsave_after)" || fail "host save changed: $hsave_before -> $hsave_after"
  for f in link_host_lobby link_host_battle link_result link_after; do [ -f "$dir/host/shots/$f.png" ] || fail "missing host screenshot $f"; done
  [ "$sc" = killed ] || for f in link_guest_lobby link_guest_battle link_result link_after; do [ -f "$dir/guest/shots/$f.png" ] || fail "missing guest screenshot $f"; done
  pkill -9 -f "pkmn.desktop.*--saves=$dir" 2>/dev/null
done
[ $failed -eq 0 ] && echo "LINK SCENES PASS" || echo "LINK SCENES FAILED"
exit $failed
