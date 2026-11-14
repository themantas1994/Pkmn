# Link play (battles and trades between two devices)

Two players on the same Wi-Fi or hotspot battle or trade with each other, Android or desktop, with no server and no account.
The network is used only inside Link play and only on the local network. Protocol and design: the link play pull
requests (phases 0 to 2: #60 to #62, then the one that added the network).

## Playing
1. START menu, **LINK** (shown once the `has_pokedex` flag is set).
2. One player picks **HOST A GAME** and reads out the 7-character code. The other picks **JOIN WITH A CODE** and types it on the
   name keyboard. **JOIN BY ADDRESS** takes a dotted IPv4 address (`192.168.0.7`, optionally `:port`) as a fallback.
   The code is the host's local IPv4 address in base 32 (no 0, 1, I or O); the port is always 47840.
3. In the lobby the host chooses **MODE: BATTLE or TRADE** and, for a battle, the rules: levels as they are or flat 50, and whether the gimmicks **both** players have
   unlocked are allowed. The guest presses READY; the host presses START.
4. Link battles are singles, Set style, with no bag, no running, no experience and no obedience check. **FORFEIT** replaces RUN.
5. Both devices play **healed copies** of their parties. **Nothing is written to either save**, whatever happens, including a void battle.

A lost link shows "Waiting for NAME..." after 10 s of silence and voids the battle after 60 s (or at once when the connection
closes). On Android, going to the background sends a pause notice to the other device.

## Trading
Pick **MODE: TRADE** in the lobby (the guest follows the host's choice; changing it withdraws READY). Both players then:
1. Choose one **party** Pokémon. Your party is on the left; the friend's party is on the right (read-only: species, level, nickname). The last Pokémon that can battle is refused, with the reason shown.
2. See both offers side by side: species, level, nickname, OT, held item and moves. Pick **CONFIRM** or **CANCEL**.
3. Wait through **Saving...** (no input is accepted). The trade is written to the save as a journal before anything moves, and
   the swap is saved in one atomic write, so a crash or a lost link never leaves a damaged save or half a trade on a device.
   One window remains, as in the plan: if the link drops after one device applied the swap and before the other heard about it,
   the trade is one-sided (one player has both Pokémon, the other only its own). Linking again with the same friend in the same
   session finishes it; if either game is restarted first, the waiting device cancels on load and keeps its Pokémon. The same
   happens if the waiting device fails to save the swap. While waiting for the friend to confirm, B still backs out.
4. The two Pokémon swap on screen ("trade" sound), then "NAME traded X for Y!". A Pokémon that evolves by trade (with its held
   item, e.g. Metal Coat, used up) evolves right away and the game saves again.
5. Back on the overworld. A cancel, a refused offer or a lost link ends with a sentence and nothing changed.

The received Pokémon is an outsider (level-based obedience, no nickname prompt) unless it returns to its original trainer.
Engine side: `TradeTransaction`, `GameTradeStore`, `TradeJournal`, `MonValidator`; screen: `LinkTradeScreen`.

| Lobby (trade) | Pick | Offers | Saving | Swap | Result | Evolution |
|---|---|---|---|---|---|---|
| ![](link-screens/trade_lobby.png) | ![](link-screens/trade_pick.png) | ![](link-screens/trade_offers.png) | ![](link-screens/trade_saving.png) | ![](link-screens/trade_scene.png) | ![](link-screens/trade_result.png) | ![](link-screens/trade_evolution.png) |

Also `trade_evolved.png` (the evolution's last message), and `trade_guest_lobby.png`, `trade_guest_offers.png`, `trade_guest_result.png` (the guest's side).

## Where the code is
- `engine/.../link`: messages, handshake, `LockstepBattle`, lobby messages, address codes, `LinkLobbyRules` (no sockets).
- `core/.../game/link`: `TcpTransport` (`java.net`, a reader and a writer thread, frames handed to the game thread with
  `Gdx.app.postRunnable`), `LobbySession`, `LinkLobbyScreen`, `LinkResultScreen`, `LinkTradeScreen`.
- `BattleScreen` and `BattleFlow` take a local side (the guest is side 1) and a `LockstepBattle`.
- `Platform.localAddresses()`; the Android manifest asks for `INTERNET` (needed for local sockets too).

## Testing on one machine
- Desktop automation (`--script`): `link host [PORT] [flat] [trade]`, `link join CODE|ADDRESS[:PORT]`, `linkplay fight | forfeit TURN`,
  `ot` (give the party's Pokémon the player as original trainer), `waitstate trade=PICK|CONFIRM|SAVING|SCENE` and `waitstate mode=TRADE`
  (the top link screen's state).
- `tools/visual/run_link_scene.sh [battle|forfeit|killed|trade]` starts two instances under Xvfb on localhost, plays the scenario and
  checks that both show the same state hash and complementary results, that a forfeit and a killed second instance end cleanly,
  and that the survivor's save files are byte-identical before and after. The `trade` scenario swaps both first party Pokémon (the guest offers a Kadabra, so the host's evolves to Alakazam through
  `EvolutionScreen` and the second save holds it) and checks each save afterwards: slot 0 holds the other's species (Alakazam for
  the host) and OT, party sizes are unchanged, no trade journal is left.
  Output in `build/link-scene/`.
- Desktop to Android emulator: the emulator reaches the host PC at `10.0.2.2` (JOIN BY ADDRESS `10.0.2.2`); the other direction
  needs `adb forward tcp:47840 tcp:47840` and the PC joins `127.0.0.1`.
