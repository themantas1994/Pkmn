# Link play: hardware checklist

Link play has been tested only on one desktop (two instances on localhost, `tools/visual/run_link_scene.sh`) and in
engine tests. This list covers what only real devices can show. It takes about an hour with two people.

**You need:** one Android phone (ARM) with a debug build, a second Android phone, the desktop build on a PC, one Wi-Fi
network all three can join, and a phone that can share a hotspot. Each player needs a save with the Pokédex (START, LINK
appears) and at least three Pokémon; for step 7, one player has a Kadabra or a Haunter.

Debug build: `./gradlew :android:assembleDebug`, then `adb install -r android/build/outputs/apk/debug/android-debug.apk`.
Desktop: `./gradlew :desktop:installDist`, then run `desktop/build/install/desktop/bin/desktop` from `assets/`.

**When a step fails, send back:** the step number, which devices (phone model and Android version, or desktop OS),
Wi-Fi or hotspot, what you saw (a photo or screenshot of the screen), and on Android the log:
`adb logcat -d > step-N.txt` taken right after the failure (on desktop, copy the terminal output).

## 1. Determinism on a real ARM phone
1. Connect the phone with USB and run
   `adb shell am start -n io.github.pkmncrystalline/.AndroidLauncher --es selftest determinism`, then
   `adb logcat -s PkmnDeterminism` and wait for the line that starts with `determinism battles=` (a minute or two).
2. **Expect:** `determinism battles=400 turns=4839 hash=7d23207b2db0ac700bf3f9900ebf55d1ad1abe7128398c3cb04c6cbe71d06567`,
   the same as `desktop --selftest=determinism`.
3. **If it differs:** send the whole `PkmnDeterminism` output and the phone model. Do not continue with battles on that
   phone: link battles between it and a desktop would end as "The games disagreed".

## 2. Battle: desktop against phone, phone against phone (Wi-Fi)
1. Desktop: LINK, HOST A GAME. Phone: LINK, FIND A HOST, pick the desktop's name. Battle to the end.
2. **Expect:** both show the same result screen ("YOU WON!" on one, "YOU LOST..." on the other) and the same 16-character
   hash under "Both games agree". Each trainer card shows one more win or loss.
3. Repeat with the phone hosting, then with two phones. Try each RULES preset once: LEVELS AS THEY ARE, FLAT 50,
   3 VS 3 (both pick three; neither should see the other's picks before the battle starts).

## 3. Forfeit
1. In a link battle, pick FORFEIT and confirm.
2. **Expect:** both devices end at once with the forfeiting player as the loser and the same hash.

## 4. Lost link during a battle
1. Mid-battle, turn Wi-Fi off on one phone for about 5 s, then on again.
2. **Expect:** both show "Link lost. Reconnecting" with a countdown, then the battle goes on at the same turn (same Pokémon,
   same HP) and ends with the same hash on both.
3. Turn Wi-Fi off for more than 20 s. **Expect:** after the countdown, "NO RESULT" ("The link was lost.") on both; the
   trainer card counts one void. Neither save changed (party HP, PP and items are as before the battle).
4. Lock the screen (or switch apps) on one phone for 5 s, then come back. **Expect:** the other device shows the friend
   paused, or the reconnect countdown; the battle goes on if the phone is back within the countdown.

## 5. Trade
1. Host picks MODE: TRADE. Each player offers one Pokémon, both CONFIRM.
2. **Expect:** "Saving...", the swap scene, "NAME traded X for Y!". After closing and reopening both games, each save holds
   the other's Pokémon (check the OT on its summary) and each trainer card shows one link trade.

## 6. Trade with evolution
1. Trade a Kadabra (or Haunter, or an Onix holding a Metal Coat).
2. **Expect:** it evolves on the receiving device right after the trade (the Metal Coat is used up), and is still evolved
   after reopening the game.

## 7. Interrupted trade, then reconnect
1. Start a trade; on one phone, turn Wi-Fi off the moment "Saving..." appears.
2. **Expect:** one device may finish the trade while the other says the trade was interrupted. Close both games.
3. Reopen both. **Expect:** a notice on load ("The link trade with NAME was interrupted..." or "...done on your side...").
   The offered Pokémon cannot be deposited or released (a message says why). LINK shows UNFINISHED TRADE.
4. Link the same two players again (MODE: TRADE). **Expect:** a notice that the earlier trade was completed (or did not
   happen on either side); afterwards each player has exactly one of the two Pokémon (never both, never neither), and
   UNFINISHED TRADE is gone.
5. Optional: repeat 1-2, then on one device use LINK, UNFINISHED TRADE, CANCEL IT (or END IT), YES. **Expect:** the warning
   names both Pokémon; afterwards the entry is gone.

## 8. Discovery on Wi-Fi and on a phone hotspot
1. On Wi-Fi: phone A hosts; phone B (and the desktop) use FIND A HOST. **Expect:** A's trainer name and mode appear within
   a few seconds. Change A's mode (A on the host screen) and expect the list to follow.
2. Phone A shares a hotspot; phone B joins it. B uses FIND A HOST. Then swap roles (the hotspot phone joins as guest).
3. **If nothing is listed:** note which direction failed (which device hosted, Wi-Fi or hotspot), then check that
   JOIN WITH A CODE with the host's code still works. Discovery failing is not a blocker as long as the code works; send
   the result so we know which networks filter it.

## 9. Link attendant
1. Walk into any Pokémon Center and talk to the attendant beside the nurse.
2. **Expect:** "Welcome to the LINK CLUB!", then the LINK menu. After leaving it you are back in the Center, able to move.
   On a save without the Pokédex she only says that Link play opens once you have one.
