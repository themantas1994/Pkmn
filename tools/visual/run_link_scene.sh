#!/usr/bin/env bash
# Link play on one machine: two desktop instances under Xvfb play each other over localhost, in four scenarios.
#   battle  a scripted battle to the end; both show the matching result screen (same state hash, opposite outcomes)
#   forfeit the guest forfeits; the host wins, both show the result
#   killed  the guest process is killed (kill -9) mid-battle; the host's result is "link lost" and it returns to the overworld
#   trade   both trade their first party Pokémon (the guest offers a Kadabra, so the host's evolves to Alakazam on arrival);
#           each save then holds the other's (species and OT), the party sizes are unchanged and no trade journal is left
#   resume  an interrupted trade: the guest drops its frames from its PREPARED on, so only the guest swaps (journals: host
#           PREPARED, guest COMMITTED). Both games are closed and continued from their saves (load notices, the LINK menu's
#           UNFINISHED TRADE page), then link again: the host finishes the trade and both saves hold it, with no journal left
# In the battle scenarios the survivor's save files must be byte-identical before and after the link (nothing is written).
# Output: build/link-scene/<scenario>/{host,guest}/*.png and *.log. Exit status is non-zero when any check fails.
#   tools/visual/run_link_scene.sh [battle|forfeit|killed|trade|resume ...]     (default: all five)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/link-scene"
BASE_PORT="${LINK_PORT:-47840}"
SCENARIOS=("$@"); [ ${#SCENARIOS[@]} -eq 0 ] && SCENARIOS=(battle forfeit killed trade resume)
rm -rf "$OUT" && mkdir -p "$OUT"
"$ROOT/gradlew" -q -p "$ROOT" :desktop:installDist || { echo "build failed"; exit 1; }
BIN="$ROOT/desktop/build/install/desktop/bin/desktop"
failed=0
fail() { echo "  FAIL: $*"; failed=1; }

# Starts one instance in its own process group; prints the group id. $1 name, $2 scenario dir, $3 script, $4 player name
# (empty: no quickstart, the game starts at the title and continues its save), $5 log file (default $2/$1.log)
start_instance() {
  local name="$1" dir="$2" script="$3" player="$4" log="${5:-$2/$1.log}"
  mkdir -p "$dir/$name/shots" "$dir/$name/saves"
  ( cd "$ROOT/assets" && exec setsid timeout 240 xvfb-run -a -s "-screen 0 1024x768x24" \
      "$BIN" ${player:+--quickstart="$player"} --mute --fixedstep --scale=1 --saves="$dir/$name/saves" \
      --script="$script" --shots="$dir/$name/shots" > "$log" 2>&1 ) &
  echo $!
}
save_hash() { ( cd "$1" && find . -type f ! -name options.json | sort | xargs -r sha256sum | sha256sum | cut -c1-16 ); }
field() { grep -o "$2=[^ ]*" "$1" | tail -1 | cut -d= -f2; }
last_log() { grep "AUTOMATION frame" "$1" | tail -1; }
# First party Pokémon of a save as "species otName", the party size, and whether a trade journal is open. $1 = saves dir
save_json() { echo "$1/save.json"; }
party0() { jq -r '.payload.party[0] | "\(.speciesId) \(.otName)"' "$(save_json "$1")" 2>/dev/null; }
party_len() { jq -r '.payload.party | length' "$(save_json "$1")" 2>/dev/null; }
journal() { jq -r '.payload.pendingTrade // "none" | if type == "string" then . else "open" end' "$(save_json "$1")" 2>/dev/null; }
journal_state() { jq -r '.payload.pendingTrade.state // "none"' "$(save_json "$1")" 2>/dev/null; }
# Waits for a process group to end (up to $2 seconds), else kills it and fails. $1 pid, $2 seconds, $3 name
wait_end() { for _ in $(seq "$2"); do kill -0 "$1" 2>/dev/null || return 0; sleep 1; done; fail "$3 still running"; kill -9 -- "-$1" 2>/dev/null; }
# Starts a host and then a guest on the host's port, for one part of a scenario; sets hpid and gpid. $1 dir, $2 part suffix,
# $3 host player name, $4 guest player name (empty: continue the save)
start_pair() {
  local dir="$1" part="$2"
  sed "s/@PORT@/$port/" "$ROOT/desktop/automation/link/host_$sc$part.txt" > "$dir/host$part.txt"
  sed "s/@PORT@/$port/; s/@ADDR@/127.0.0.1/" "$ROOT/desktop/automation/link/guest_$sc$part.txt" > "$dir/guest$part.txt"
  hpid=$(start_instance host "$dir" "$dir/host$part.txt" "$3" "$dir/host$part.log")
  for _ in $(seq 150); do grep -q "^LINK host code=" "$dir/host$part.log" 2>/dev/null && break; sleep 1; done
  grep -q "^LINK host code=" "$dir/host$part.log" || { fail "host$part never started listening"; return 1; }
  gpid=$(start_instance guest "$dir" "$dir/guest$part.txt" "$4" "$dir/guest$part.log")
}

# The interrupted-trade scenario: two runs of both games, the second continuing the saves of the first.
run_resume() {
  local dir="$1"
  start_pair "$dir" 1 HOST GUEST || return
  wait_end "$hpid" 200 host1; wait_end "$gpid" 60 guest1
  local hmon1 gmon1 hlen glen
  hmon1=$(party0 "$dir/host/saves"); gmon1=$(party0 "$dir/guest/saves"); hlen=$(party_len "$dir/host/saves"); glen=$(party_len "$dir/guest/saves")
  [ "$(journal_state "$dir/host/saves")" = PREPARED ] && [ "$hmon1" = "pikachu HOST" ] && echo "  part 1: host kept its Pikachu, journal PREPARED" \
    || fail "part 1: host should hold [pikachu HOST] with a PREPARED journal: [$hmon1] $(journal_state "$dir/host/saves")"
  [ "$(journal_state "$dir/guest/saves")" = COMMITTED ] && [ "$gmon1" = "pikachu HOST" ] && echo "  part 1: guest swapped (one-sided), journal COMMITTED" \
    || fail "part 1: guest should hold [pikachu HOST] with a COMMITTED journal: [$gmon1] $(journal_state "$dir/guest/saves")"
  start_pair "$dir" 2 "" "" || return
  wait_end "$hpid" 220 host2; wait_end "$gpid" 60 guest2
  for who in host guest; do
    last=$(last_log "$dir/${who}2.log")
    echo "$last" | grep -q "top=OverworldScreen" && echo "  $who is back on the overworld" || fail "$who did not end on the overworld: [$last]"
    grep -q "Exception\|timed out" "$dir/${who}1.log" "$dir/${who}2.log" && fail "$who: an exception or a timed-out wait: $(grep -h "Exception\|timed out" "$dir/${who}1.log" "$dir/${who}2.log" | head -3)"
  done
  local hmon2 gmon2
  hmon2=$(party0 "$dir/host/saves"); gmon2=$(party0 "$dir/guest/saves")
  [ "$hmon2" = "alakazam GUEST" ] && echo "  part 2: the host finished the trade (the Kadabra evolved): $hmon2" || fail "part 2: host slot 0 is [$hmon2], expected [alakazam GUEST]"
  [ "$gmon2" = "pikachu HOST" ] && echo "  part 2: the guest still holds the host's Pikachu" || fail "part 2: guest slot 0 is [$gmon2]"
  [ "$(party_len "$dir/host/saves")" = "$hlen" ] && [ "$(party_len "$dir/guest/saves")" = "$glen" ] && echo "  party sizes unchanged ($hlen, $glen)" || fail "party sizes changed"
  [ "$(journal "$dir/host/saves")" = none ] && [ "$(journal "$dir/guest/saves")" = none ] && echo "  no trade journal left in either save" || fail "a trade journal is left: host $(journal "$dir/host/saves"), guest $(journal "$dir/guest/saves")"
  for f in trade_interrupted resume_notice link_unfinished unfinished_prepared trade_resumed link_after; do [ -f "$dir/host/shots/$f.png" ] || fail "missing host screenshot $f"; done
  for f in trade_one_sided resume_notice unfinished_committed trade_resumed link_after; do [ -f "$dir/guest/shots/$f.png" ] || fail "missing guest screenshot $f"; done
}

for sc in "${SCENARIOS[@]}"; do
  echo "scenario $sc"
  dir="$OUT/$sc"; mkdir -p "$dir"
  port=$((BASE_PORT + RANDOM % 200))
  if [ "$sc" = resume ]; then run_resume "$dir"; pkill -9 -f "pkmn.desktop.*--saves=$dir" 2>/dev/null; continue; fi
  sed "s/@PORT@/$port/" "$ROOT/desktop/automation/link/host_$sc.txt" > "$dir/host.txt"
  sed "s/@PORT@/$port/; s/@ADDR@/127.0.0.1/" "$ROOT/desktop/automation/link/guest_$sc.txt" > "$dir/guest.txt"
  hpid=$(start_instance host "$dir" "$dir/host.txt" HOST)
  for _ in $(seq 120); do grep -q "^LINK host code=" "$dir/host.log" 2>/dev/null && break; sleep 1; done
  grep -q "^LINK host code=" "$dir/host.log" || { fail "host never started listening"; kill -9 -- "-$hpid" 2>/dev/null; continue; }
  # The host saved before it listened: record that save, then let the guest in.
  hsave_before=$(save_hash "$dir/host/saves")
  hmon_before=$(party0 "$dir/host/saves"); hlen_before=$(party_len "$dir/host/saves")
  gpid=$(start_instance guest "$dir" "$dir/guest.txt" GUEST)
  gsave_before=""
  if [ "$sc" != killed ]; then
    for _ in $(seq 120); do [ -f "$dir/guest/shots/link_guest_lobby.png" ] && break; sleep 1; done
    gsave_before=$(save_hash "$dir/guest/saves")   # the guest saved before it joined
    gmon_before=$(party0 "$dir/guest/saves"); glen_before=$(party_len "$dir/guest/saves")
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
    trade)
      gl2=$(last_log "$dir/guest.log")
      echo "$gl2" | grep -q "top=OverworldScreen" && echo "  guest is back on the overworld" || fail "guest did not end on the overworld: [$gl2]"
      grep -q "Exception\|AUTOMATION waittop .* timed out" "$dir/host.log" "$dir/guest.log" && fail "an exception or a timed-out wait in the logs: $(grep -h "Exception\|timed out" "$dir/host.log" "$dir/guest.log" | head -3)"
      ;;
  esac
  last=$(last_log "$dir/host.log")
  echo "$last" | grep -q "top=OverworldScreen" && echo "  host is back on the overworld" || fail "host did not end on the overworld: [$last]"
  if [ "$sc" = trade ]; then
    hmon_after=$(party0 "$dir/host/saves"); gmon_after=$(party0 "$dir/guest/saves")
    [ -n "$hmon_before" ] && [ -n "$gmon_before" ] && [ "$hmon_before" != "$gmon_before" ] || fail "no usable save before the trade: host [$hmon_before] guest [$gmon_before]"
    # The guest offered a Kadabra with the guest as OT; it evolved to Alakazam on arrival (a trade evolution), and that was saved.
    [ "$gmon_before" = "kadabra GUEST" ] || fail "the guest's offer should be 'kadabra GUEST', its save says [$gmon_before]"
    [ "$hmon_after" = "alakazam GUEST" ] && echo "  host slot 0 now holds the guest's Kadabra, evolved: $hmon_after" || fail "host slot 0 is [$hmon_after], expected [alakazam GUEST]"
    [ "$gmon_after" = "$hmon_before" ] && echo "  guest slot 0 now holds the host's Pokemon ($gmon_after)" || fail "guest slot 0 is [$gmon_after], expected [$hmon_before]"
    [ "$(party_len "$dir/host/saves")" = "$hlen_before" ] && [ "$(party_len "$dir/guest/saves")" = "$glen_before" ] && echo "  party sizes unchanged ($hlen_before, $glen_before)" || fail "party sizes changed"
    [ "$(journal "$dir/host/saves")" = none ] && [ "$(journal "$dir/guest/saves")" = none ] && echo "  no trade journal left in either save" || fail "a trade journal is left: host $(journal "$dir/host/saves"), guest $(journal "$dir/guest/saves")"
    for who in host guest; do for f in link_${who}_lobby trade_pick trade_offers trade_saving trade_scene trade_result link_after $([ $who = host ] && echo trade_evolution); do [ -f "$dir/$who/shots/$f.png" ] || fail "missing $who screenshot $f"; done; done
  else
    [ "$hsave_before" = "$hsave_after" ] && echo "  host save unchanged ($hsave_after)" || fail "host save changed: $hsave_before -> $hsave_after"
    for f in link_host_lobby link_host_battle link_result link_after; do [ -f "$dir/host/shots/$f.png" ] || fail "missing host screenshot $f"; done
    [ "$sc" = killed ] || for f in link_guest_lobby link_guest_battle link_result link_after; do [ -f "$dir/guest/shots/$f.png" ] || fail "missing guest screenshot $f"; done
  fi
  pkill -9 -f "pkmn.desktop.*--saves=$dir" 2>/dev/null
done
[ $failed -eq 0 ] && echo "LINK SCENES PASS" || echo "LINK SCENES FAILED"
exit $failed
