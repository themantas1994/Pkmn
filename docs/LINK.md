# Link play (battles between two devices)

Two players on the same Wi-Fi or hotspot battle each other, Android or desktop, with no server and no account.
The network is used only inside Link play and only on the local network. Protocol and design: the link play pull
requests (phases 0 to 2: #60 to #62, then the one that added the network).

## Playing
1. START menu, **LINK** (shown once the `has_pokedex` flag is set).
2. One player picks **HOST A GAME** and reads out the 7-character code. The other picks **JOIN WITH A CODE** and types it on the
   name keyboard. **JOIN BY ADDRESS** takes a dotted IPv4 address (`192.168.0.7`, optionally `:port`) as a fallback.
   The code is the host's local IPv4 address in base 32 (no 0, 1, I or O); the port is always 47840.
3. In the lobby the host chooses the rules: levels as they are or flat 50, and whether the gimmicks **both** players have
   unlocked are allowed. The guest presses READY; the host presses START.
4. Link battles are singles, Set style, with no bag, no running, no experience and no obedience check. **FORFEIT** replaces RUN.
5. Both devices play **healed copies** of their parties. **Nothing is written to either save**, whatever happens, including a void battle.

A lost link shows "Waiting for NAME..." after 10 s of silence and voids the battle after 60 s (or at once when the connection
closes). On Android, going to the background sends a pause notice to the other device.

## Where the code is
- `engine/.../link`: messages, handshake, `LockstepBattle`, lobby messages, address codes, `LinkLobbyRules` (no sockets).
- `core/.../game/link`: `TcpTransport` (`java.net`, a reader and a writer thread, frames handed to the game thread with
  `Gdx.app.postRunnable`), `LobbySession`, `LinkLobbyScreen`, `LinkResultScreen`.
- `BattleScreen` and `BattleFlow` take a local side (the guest is side 1) and a `LockstepBattle`.
- `Platform.localAddresses()`; the Android manifest asks for `INTERNET` (needed for local sockets too).

## Testing on one machine
- Desktop automation (`--script`): `link host [PORT] [flat]`, `link join CODE|ADDRESS[:PORT]`, `linkplay fight | forfeit TURN`.
- `tools/visual/run_link_scene.sh [battle|forfeit|killed]` starts two instances under Xvfb on localhost, plays the scenario and
  checks that both show the same state hash and complementary results, that a forfeit and a killed second instance end cleanly,
  and that the survivor's save files are byte-identical before and after. Output in `build/link-scene/`.
- Desktop to Android emulator: the emulator reaches the host PC at `10.0.2.2` (JOIN BY ADDRESS `10.0.2.2`); the other direction
  needs `adb forward tcp:47840 tcp:47840` and the PC joins `127.0.0.1`.
