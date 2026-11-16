# LAN multiplayer plan: trades, then battles

Status: plan only. Nothing here is implemented. Written 2026-11-09 against the online default branch
`origin/ccr-c3b68caf-pdjkp5` at `696c438` (the remote has no `main`; `origin/HEAD` points to that branch). The
local checkout (`pkm/PKM-21`, `30c9346`) is 143 commits behind, so every file:line below refers to `696c438`.
Every time or size figure in this plan is an estimate unless it cites a command.

---

## 1. Goal, non-goals, acceptance criteria

**Goal.** Two players on the same LAN can (1) trade Pokémon and (2) battle each other, between any mix of
desktop (LWJGL3) and Android builds. Multiplayer is opt-in: the offline game opens no socket, takes no lock and
needs no permission prompt until the player picks a LINK menu entry.

**Non-goals (this plan).** Internet play, relay or matchmaking servers, NAT traversal, more than 2 players, doubles
over the link (the engine's doubles mode is still "open", docs/MECHANICS.md:49), spectators, chat, cheat-proofing
against a modified client (see risk R7), ranked play, and any trade of items or money.

### Acceptance criteria: trades (feature 1)

| ID | Criterion (testable) |
|---|---|
| T-AC1 | With no LINK menu use, a full test run and a play session open no socket: `LinkGuardTest` asserts the `:link` transport classes are never loaded on the offline path, and Android lint shows no socket use outside `pkmn.link.transport`. |
| T-AC2 | Host and guest find each other by LAN discovery within 5 s (estimate) on the same subnet, or by manual IP + 4-digit join code when discovery is blocked. |
| T-AC3 | Handshake rejects a peer with a different protocol version, data hash or determinism-probe hash, with a readable message; nothing is changed in either save. |
| T-AC4 | Both players see the other's offer (species, level, nickname, OT, held item, shiny) and both must confirm; either can cancel until both confirm. |
| T-AC5 | After a completed trade each save has the other's Pokémon in the slot of the one given away, with the peer's OT name/id kept, `outsider = true`, `metLevel` = level at receipt, a fresh local `uid`, and a Pokédex entry; trade evolutions (Linking Cord path, `GameContext.evolutionByItem`, GameContext.kt:577) run. |
| T-AC6 | Killing either process or the link at any point leaves each save with exactly one of {its original Pokémon, the received Pokémon} for that trade, never zero; a journal resolves the in-doubt window on reconnect (section 5). |
| T-AC7 | A malformed or illegal incoming Pokémon (unknown species/move/item, battle-only form, level outside 1-100, IV > 31, EV > 252 or total > 510, > 4 moves) is rejected before any save change. |
| T-AC8 | Re-sending the same trade (same `tradeId`) or a Pokémon fingerprint already owned is rejected. |
| T-AC9 | Works desktop↔desktop, desktop↔Android and Android↔Android (manual LAN steps, section 7). |

### Acceptance criteria: battles (feature 2)

| ID | Criterion (testable) |
|---|---|
| B-AC1 | Singles, both sides human (`Controller.PLAYER`), no AI on either client; levels normalised to a host-chosen cap (default 50); no EXP, money, items from the bag, catching, running or friendship change written back to either save. |
| B-AC2 | Both clients build the battle from the same seed, team payloads and rules; after every turn their state checksums match (`BattleChecksum`, section 4.7). |
| B-AC3 | Two in-process peers over loopback play 200 seeded random battles (estimate of a useful count) to the end with identical outcome, turn count and per-turn checksums (integration test). |
| B-AC4 | A deliberately injected divergence (one RNG draw on one peer) is detected at the end of that turn, the battle stops with "Link error: battles out of sync", and neither save changes. |
| B-AC5 | A peer that disconnects can rejoin within 60 s (default, estimate) and the battle resumes from the replayed choice log with matching checksum; otherwise the battle ends as "Disconnected" with no save change. |
| B-AC6 | A choice deadline (default off on LAN; optional 90 s per turn) ends the battle with a forfeit for the side that ran out. |
| B-AC7 | The guest sees its own Pokémon at the bottom and the host's at the top, with correct "your/the opposing" text. |
| B-AC8 | The same seeded battle produces the same checksum sequence on desktop HotSpot and Android ART (determinism probe, P3-T4). |

---

## 2. Research summary

Key takeaways, with sources:

1. **Lockstep + per-turn hashes.** Each peer runs the same simulation from the same seed and only exchanges
   inputs; after each tick or turn it hashes gameplay state (including the RNG state) and compares. A
   non-cryptographic hash is enough; hash inputs too, because many "desyncs" are delivery errors; per-subsystem
   hashes localise the first divergence; record seed + input log for replay. Usual causes: floats across
   platforms, unordered iteration, uninitialised values.
   [Bugnet: lockstep determinism checker](https://bugnet.io/blog/how-to-build-a-lockstep-determinism-checker),
   [Bugnet: debug desync in lockstep](https://bugnet.io/blog/how-to-debug-desync-in-deterministic-lockstep-games),
   [Game Developer: minimizing the pain of lockstep](https://gamedeveloper.com/programming/minimizing-the-pain-of-lockstep-multiplayer).
   These are practitioner blogs, not standards.
2. **Showdown.** The simulator is a stream: `>start {"seed":[a,b,c,d], ...}`, then `>p1 move 1` / `>p2 switch 3`;
   same seed and same inputs give the same battle; requests go only to their player (`sideupdate`), and hidden
   information is split per player (`|split|`). Showdown is server-authoritative, so clients never hold the
   opponent's hidden data; a lockstep peer does (risk R7). Showdown's PRNG takes a 64-bit seed and can be reset
   to its starting seed.
   [SIMULATOR.md](https://github.com/smogon/pokemon-showdown/blob/master/sim/SIMULATOR.md),
   [SIM-PROTOCOL.md mirror](https://cdn.jsdelivr.net/npm/loomianstatistics@1.0.25/sim/SIM-PROTOCOL.md),
   [prng.ts mirror](https://cdn.jsdelivr.net/npm/loomianstatistics@1.0.25/sim/prng.ts).
   Reconnect: players reload into the same room; an inactivity timer decides if they don't return; tournaments use
   a separate cumulative disconnect clock (300 s)
   ([PokéBase answer](https://pokemondb.net/pokebase/135456/when-the-server-disconnects-does-it-count-as-a-loss),
   [Smogon timer ruling](https://wbf.www.smogon.com/forums/threads/spl-ix-administrative-decisions.3622424/)). Anecdotal.
3. **Official games.** Gen 5 does local-wireless trades and battles in the Union Room, with a C-Gear IR shortcut
   ([Bulbapedia: Union Room](https://bulbapedia.bulbagarden.net/wiki/Union_Room),
   [Bulbapedia: C-Gear](https://bulbapedia.bulbagarden.net/wiki/C-Gear),
   [Bulbapedia: Trade](https://bulbapedia.bulbagarden.net/wiki/Trading)). Sword/Shield Link Trade pairs players
   by an 8-digit Link Code entered on both consoles, both confirm, local wireless needs no account
   ([Nintendo support](https://en-americas-support.nintendo.com/app/answers/detail/a_id/48235/p/897/c/874)).
   I found no primary source for the session/timeout internals of either; that part is **unverified**.
4. **Trade safety.** Gen I cloning came from interrupting a trade: one cartridge had sent its Pokémon before
   receiving, so turning one Game Boy off left the Pokémon in both games; later games send simultaneously and
   auto-cancel on errors ([Bulbapedia: Cloning glitches](https://bulbapedia.bulbagarden.net/wiki/Cloning_glitches)).
   Lesson: exchange both payloads first, persist a prepared state, commit only after both sides are prepared, and
   accept that a crash in the final window can at worst duplicate, never lose.
5. **LAN discovery on Android.** The Wi-Fi stack filters multicast (and on some devices broadcast) unless the app
   holds a reference-counted `WifiManager.MulticastLock` (`CHANGE_WIFI_MULTICAST_STATE`); release it when done
   (battery). `NsdManager` (mDNS) runs outside the app process.
   [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager.html),
   [Chainfire: UDP broadcast and multicast](https://chainfire.eu/articles/742/_dev_Android_Datagrams_UDP_Broadcast_and_Multicast_.).
   **Local network permission:** opt-in on Android 16 (target 36, via `NEARBY_WIFI_DEVICES`), enforced for apps
   targeting SDK 37 (`ACCESS_LOCAL_NETWORK`); raw sockets to local addresses fail with a socket error when
   restricted; NsdManager is not affected during the opt-in; testable with
   `adb shell am compat enable RESTRICT_LOCAL_NETWORK <pkg>`
   ([Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)).
   This repo targets SDK 35 (android/build.gradle.kts:34), so it is not affected today; it will be at target 37.
   **Cleartext:** the network-security-config cleartext rule governs the platform HTTP stack; raw sockets are not
   blocked by it ([Android Developers blog, 2018](https://android-developers.googleblog.com/2018/04/protecting-users-with-tls-by-default-in.html)).
   The exact scope wording is **unverified** against the current reference page. Sockets need `INTERNET`.
6. **Libraries.** KryoNet: last upstream release 2013; the crykn fork has unclear activity
   ([crykn/kryonet](https://github.com/crykn/kryonet), [upstream](https://github.com/EsotericSoftware/kryonet)).
   Ktor raw sockets (`io.ktor:ktor-network`) work on JVM but need coroutines and you frame messages yourself
   ([Ktor sockets](https://ktor.io/docs/server-sockets.html)). Netty supports Android officially since 4.1 with
   occasional Android fixes in 4.2 (e.g. 4.2.7 VarHandle verification fix); 4.1 EOL is 2027-07-01
   ([Netty 4.1 notes](https://netty.io/wiki/new-and-noteworthy-in-4.1.html),
   [4.2.7](https://netty.io/news/2025/10/15/4-2-7.html),
   [4.1 EOL](https://netty.io/news/2026/09/09/4-1-EOL-announcement.html)).
   Plain `java.net` + kotlinx.serialization (already a dependency) needs nothing new.
7. **Floats.** `StrictMath` is bit-identical on all platforms; `Math` (incl. `pow`) may differ between JVMs and
   runtimes (an OpenJ9/HotSpot `Math.pow` difference was ruled spec-compliant)
   ([JDK-8308775](https://bugs.openjdk.org/browse/JDK-8308775),
   [OpenJ9 #10918](https://github.com/eclipse-openj9/openj9/issues/10918)). ART behaviour is only anecdotal.

---

## 3. Current-code findings (origin `696c438`) and the blockers they create

### 3.1 What already helps

| Fact | Evidence |
|---|---|
| Engine is pure Kotlin with no LibGDX; core is the LibGDX game; launchers are 1 file each | docs/BUILD.md modules table; docs/REPO_MAP.md |
| Battle API is choice/request based: `request(side, slot)`, `choose(side, choice, slot)`, `advance(ai)` | engine/.../battle/Battle.kt:336, :390, :470 |
| `advance` only asks the AI for sides that are **not** `PLAYER`; two `PLAYER` sides wait for two `choose` calls | Battle.kt:476, :484, :497 |
| `Choice` is a small sealed class (`UseMove(moveIndex, gimmick, target)`, `Switch`, `UseItem`, `Run`, `Pass`) | engine/.../battle/BattleModel.kt:339-354 |
| Battle RNG is a seedable SplitMix64 whose entire state is one `Long` (`Rng.state`) | engine/.../util/Rng.kt:9-19 |
| Speed ties and action-order ties draw from the battle RNG in a fixed order | Battle.kt:257-262, :519-520, :549-554 |
| AI damage preview draws no RNG (no crit roll, fixed roll 92) | Battle.kt:1579, :1617; BattleAI.kt:62-69 |
| Seeded singles battles are already byte-identical run to run on one JVM (golden log, SHA-256 per transcript) | engine/src/test/.../battle/SinglesGoldenLogTest.kt (class doc) |
| Effect state maps are `LinkedHashMap` (ordered) | BattleModel.kt:88, :90, :137, :266 |
| `Pokemon` is `@Serializable` (kotlinx.serialization 1.9.0 is an `api` dependency of engine) | engine/.../mon/Pokemon.kt:56-97; engine/build.gradle.kts:10; gradle.properties |
| Saves are atomic (temp + fsync + verify + backup), checksummed, versioned v3 with a migration chain | engine/.../save/SaveManager.kt:85-110, :215, :218 |
| Screens can block autosave during a transaction | core/.../screens/Screen.kt:14; core/.../PkmnGame.kt:356-372 |
| An in-game (NPC) trade path exists: `tradeCandidates`, `performTrade` → `acquire` + Linking-Cord evolution | engine/.../campaign/GameContext.kt:208-231; core/.../overworld/ScriptHost.kt:144-157; engine/.../world/ScriptVM.kt:49-50, :231-235 |

### 3.2 Hidden nondeterminism and other blockers

| # | Finding | Evidence | Blocker / required change |
|---|---|---|---|
| F1 | `Battle` default seed is `System.nanoTime()` | Battle.kt:34 | Link battles must pass the negotiated seed explicitly; add a test that the link factory never uses the default. |
| F2 | `PokemonFactory.uidCounter` starts at `System.nanoTime()`; `uid` is only a per-save counter | Pokemon.kt:92, :150-152 | `uid` is not globally unique: two saves can hold the same uid. Trades need their own identity (`tradeId` + content fingerprint); received Pokémon get a fresh local uid (`acquire` already does this, GameContext.kt:145). |
| F3 | `facedFoes` is keyed by `uid` | BattleModel.kt:176; Battle.kt:905-907, :2155 | Colliding uids across the two teams would mix participants. Only matters for EXP, which link battles disable; still, link teams should get battle-local uids (1-6, 101-106). |
| F4 | `Math.pow` in the battle path: Protect/Detect chain chance; also EXP and catch formulas | MoveScripts.kt:96; Pokemon.kt:218; CatchAndItems.kt:135, :152 | `Math.pow` may differ between HotSpot and ART (section 2.7). Protect uses `pow(3.0, n)` (likely exact, unverified) → replace with `StrictMath` or integer maths (P3). EXP/catch are disabled in link battles. |
| F5 | `Rng.nextDouble()` comparisons (Protect, AI noise) | MoveScripts.kt:97; BattleAI.kt:43, :148 | Doubles from `nextLong` are exact; fine if the compared value is computed identically (see F4). |
| F6 | `HashMap`/`HashSet` in battle state: `choices` (Int keys), `pendingSwitches`, `replacementChoices`, `usedGimmicks` (enum), `faintProcessed` (identity hash of `BattleMon`), `EffectState.data` | Battle.kt:77, :79, :658, :660, :2121; BattleModel.kt:66, :91 | Int/enum/String hash orders are deterministic across JVMs, and `faintProcessed` is only used for membership (Battle.kt:2125-2150). Not proven for every iteration site → P3 audit must list each iteration over a hash collection and either prove order-independence or switch to `LinkedHashMap`. |
| F7 | Battle side 0 is "the player": `checkWin` reports WIN/LOSE from side 0; Shift style, planned replacements, item settling and `facedFoes` use `player` | Battle.kt:2209-2216, :671-672, :689-692, :905, :2273-2286 | Both clients must use the **same** side order (host = side 0), because RNG draw order follows `sides` (Battle.kt:519). The guest must interpret WIN/LOSE inverted. Use `setMode = true` so Shift is never offered. |
| F8 | Names and texts assume one human: `nm()` prefixes "Enemy" only for AI/WILD; "your team"/"the opposing team" from `isPlayer` | Battle.kt:128-131; Conditions.kt:875; Abilities.kt:731; Gimmicks.kt:32 | With two `PLAYER` sides texts are wrong for one viewer, and log text is part of the golden transcript. Fix in the presentation (per-viewer text) or with a `viewSide` argument; do not change engine text for single-player battles (golden log). |
| F9 | Battle UI is hard-wired to `battle.player` / `battle.opponent` and to event `side` 0 = bottom | core/.../battle/BattleFlow.kt:49-60, :71-108 (7 refs); BattleScreen.kt (14 refs, e.g. :266-291) | Needs a perspective (`viewSide`) in `BattleFlow`/`BattleScreen`, or a thin view adapter, so the guest sees itself at the bottom. Also `BattleFlow.settle` calls `battle.advance(ai)` for opponent turns; the link flow must instead wait for the remote choice. |
| F10 | Player side wraps the live party (`playerSide()` passes `state.party`); `BattleMon` writes HP, status, PP, items and friendship through to `Pokemon` | GameContext.kt:428-434; BattleModel.kt:117 comment; Battle.kt:2133 | Link battles must use deep copies (serialize/deserialize) of both teams so nothing reaches the save. |
| F11 | `isNight` and `environment` come from the local clock/map | GameContext.kt:468, :496; Battle.kt:36-37 | Both must be fixed by the host in `BattleStart` (default `plain`, `false`), never from the guest's clock. |
| F12 | Default `BattleRules` enable EXP, items, catching, running, `expShareAll = true` | BattleModel.kt:19-48 | Link ruleset: `expEnabled=false, canUseItems=false, canCatch=false, canRun=false, isTrainerBattle=true, setMode=true, obedienceCap=null, playerOtId=0`, gimmicks host-chosen. |
| F13 | No test builds a battle with two `PLAYER` sides | engine tests use `PLAYER` vs `AI`/`WILD` (TestSupport.kt:44-45; BattleFuzzTest.kt:42-43) | Unknown engine paths may assume an AI opponent (e.g. self-switch `selfSwitchKey`, Battle.kt:763-770). P3 adds a PvP fuzz test. |
| F14 | `performTrade` only accepts party slots matching a wanted species and is NPC-specific (rewrites OT if empty or equal to ours) | GameContext.kt:217-231 | A new `performLinkTrade` (any party or box slot, keep peer OT, set `outsider`, `metLevel`) is needed; keep the NPC path unchanged. |
| F15 | Trainer ids are 16-bit, `seed and 0xFFFF` | GameContext.kt:613 | 1/65536 chance two players share an id, which would make a traded Pokémon look home-grown (no traded EXP bonus). Accept; show OT name. |
| F16 | Save state has no field for a pending link trade or trade history; schema v3 | GameState.kt:62-110; SaveManager.kt:215 | The trade journal needs a v4 schema + migration (Opus, per CLAUDE.md routing). |
| F17 | `SaveValidator.repair` silently repairs bad data (unknown moves removed, level clamped) | SaveManager.kt:297-330 | Not suitable for untrusted input; a strict reject-only `LinkMonValidator` is needed. |
| F18 | Android manifest declares only `VIBRATE`; docs say the game never uses the network | android/AndroidManifest.xml; docs/BUILD.md:3; docs/MECHANICS.md:149 ("no link or facility battles") | Adding `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` changes the privacy posture and the store listing (decision D1). Docs must be updated (P8). |
| F19 | Data identity: asset manifest has no content hashes | assets/manifest/assets.json (no `sha256` field) | Handshake computes its own hash over `assets/data/*.json` at load. |
| F20 | `GameState.rngState` and new-game seed use the wall clock | GameState.kt:106; FrontEnd.kt:149 | Not on the link path; noted so nobody reuses them for link seeds. |
| F21 | `startedAt`, `savedAt`, `.corrupt-*` names use `currentTimeMillis` | GameState.kt:107; SaveManager.kt:93, :105, :131 | Not part of battle state; must be excluded from any checksum. |

**Could not verify:** behaviour of every `Effect` handler under two `PLAYER` sides; whether ART's `Math.pow(3.0, n)`
equals HotSpot's for small integer `n`; whether any code iterates `EffectState.data` (a `HashMap<String, Any?>`)
in an order-sensitive way; Gen 5/Gen 8 session internals (section 2.3).

---

## 4. Architecture

### 4.1 Modules and packages

```
engine  (unchanged rules; no network)    + pkmn.engine.link   LinkMonValidator, BattleChecksum, LinkBattleFactory,
                                                               MonFingerprint (pure functions, unit-tested)
link    (NEW pure-JVM module, :engine)   pkmn.link.protocol   messages, codec, framing, versions
                                         pkmn.link.session    TradeSession, BattleSession (transport-agnostic state machines)
                                         pkmn.link.transport  TcpChannel, TcpHost, LanDiscovery (java.net only)
core    (:engine, :link)                 pkmn.game.link       LinkMenuScreen, LinkLobbyScreen, LinkTradeScreen,
                                                               LinkBattleScreen glue; the only place core touches :link
desktop / android                        Platform.lanAccess(): opens/closes the multicast lock (Android), reports
                                         Wi-Fi state; no other network code
```

Rules: `engine` stays free of sockets; `core` never opens a socket itself, it calls `pkmn.link` APIs only from
`pkmn.game.link` screens reached from the LINK menu. Offline path: no `:link` class is loaded until the menu is
used (T-AC1). `:link` is plain Kotlin/JVM 17, so its tests run in `./gradlew :link:test` without Android.

### 4.2 Transport choice (library-first)

- `library-first: ADOPT-STDLIB - TCP transport and UDP discovery - java.net Socket/ServerSocket/DatagramSocket run unchanged on JVM 17 and Android API 24+, add 0 bytes to the APK (CI enforces :tools:apkBudget) and 2-peer LAN traffic is tiny.`
- `library-first: REUSE-DECLARED - message encoding - kotlinx-serialization-json 1.9.0 is already an api dependency of :engine and Pokemon is @Serializable.`
- `library-first: REJECT - KryoNet - upstream last released 2013, fork activity unclear, Kryo serialization is fragile across versions and Android.`
- `library-first: REJECT - Netty - heavy for two peers, APK budget impact, Android support is secondary.`
- `library-first: DEFER - Ktor ktor-network - viable, but pulls in coroutines and Ktor for no gain over blocking sockets on two threads; reconsider if an internet server is added.`
- `library-first: DEFER - Android NsdManager (mDNS) - platform API, survives the Android 16/17 local-network rules better than raw multicast; add in P5 if UDP broadcast proves unreliable on test devices.`

The implementer re-runs `libcheck.py evaluate` for each line in P0 (CLAUDE.md global rule) and records the result
in the PR.

TCP gives ordered, reliable delivery, so no custom ack layer is needed. Framing: 4-byte big-endian length + UTF-8
JSON, max frame 64 KiB (rejects anything larger). Threads: one reader and one writer thread per connection; inbound
messages are queued and drained on the game thread (`Gdx.app.postRunnable` in core). Keepalive: `Ping` every 2 s,
peer considered lost after 10 s of silence (estimates, tunable). TCP port: 47_600 (any fixed unprivileged port;
open decision only if it clashes).

### 4.3 LAN discovery and manual fallback

1. Host opens `ServerSocket` (port 47600), then broadcasts `PKMN-LINK v1 <hostName> <port> <joinCodeHash>` on UDP
   47601 to `255.255.255.255` and to each interface's directed broadcast every 1 s while the lobby is open.
2. Guest listens on UDP 47601 (Android: inside `Platform.lanAccess()`, which holds a reference-counted
   `MulticastLock` and releases it when the lobby closes) and lists hosts.
3. Host shows a 4-digit join code; the guest enters it and it is checked in the handshake (`Hello.joinCode`), so a
   stranger on the same Wi-Fi cannot join by accident. It is not security (decision D3).
4. Fallback: "Enter IP" field on the guest; the host screen shows its LAN IPv4 addresses.
5. Discovery and lock stop as soon as a session starts. Android target 37 later: request `ACCESS_LOCAL_NETWORK` at
   LINK menu time (P8 note).

### 4.4 Message protocol (v1)

Every frame: `{"v":1,"t":"<type>","seq":n,"body":{...}}`. `seq` increases per sender; a receiver rejects gaps or
repeats (TCP should never cause them; this catches bugs). Unknown `t` → `Error(UNSUPPORTED)` and close.

| Type | Direction | Fields |
|---|---|---|
| `Hello` | guest→host, host→guest | `protocol` (int), `app` (versionName), `dataHash` (SHA-256 of `assets/data/*.json`), `probeHash` (section 4.7), `name`, `trainerId`, `joinCode` (guest only), `sessionId` (host reply) |
| `Ready` / `Bye(reason)` | both | — / `reason` enum (USER, VERSION, DATA, PROBE, TIMEOUT, DESYNC, ERROR) |
| `Ping` / `Pong` | both | `t` (sender counter; no wall clock) |
| `TradeOffer` | both | `tradeId` (host-generated 128-bit random), `mon` (`Pokemon` JSON), `fingerprint` |
| `TradeWithdraw` | both | `tradeId` |
| `TradeConfirm` | both | `tradeId`, `myFingerprint`, `theirFingerprint` |
| `TradePrepared` | both | `tradeId` (sent after the journal is saved) |
| `TradeCommitted` | both | `tradeId` |
| `TradeResolve` | both (on reconnect) | `tradeId`, `state` (NONE, PREPARED, COMMITTED) |
| `BattleProposal` | host→guest | `rules` (link ruleset fields), `levelCap`, `gimmicks` |
| `BattleTeam` | both | `team` (≤ 6 `Pokemon`, level-normalised copies), `teamHash` |
| `BattleStart` | host→guest | `seed` (Long, host `SecureRandom`), `environment`, `isNight`, `hostTeamHash`, `guestTeamHash` |
| `Choice` | both | `turn`, `kind` (MOVE/SWITCH/PASS), `moveIndex`, `gimmick`, `target`, `teamIndex`, `phase` (TURN/REPLACE/SELF_SWITCH) |
| `TurnHash` | both | `turn`, `stateHash`, `eventsHash`, `rngState` |
| `Resume` | rejoining peer | `sessionId`, `lastTurn`, `lastHash` |
| `ChoiceLog` | other peer | full ordered choice list since start (for replay) |
| `Error` | both | `code`, `detail` |

Versioning: `protocol` is an int constant in `pkmn.link.protocol.Protocol.VERSION`; any incompatible change bumps
it; peers must match exactly (only 2 builds, both ours). New optional fields use kotlinx defaults
(`ignoreUnknownKeys = true` within a version).

### 4.5 Session state machines

**Connection** (shared): `Idle → Listening|Connecting → Handshaking → Lobby → (Trade|Battle) → Lobby → Closed`.
Any `Bye`, framing error, version/data/probe mismatch, or 10 s silence → `Closed` (in battle: `Suspended` first).

**Trade** (per `tradeId`):
```
Lobby → Selecting (each picks; TradeOffer / TradeWithdraw freely)
      → Reviewing (both offers present and valid by LinkMonValidator)
      → Confirmed-local (sent TradeConfirm) → BothConfirmed (received matching TradeConfirm)
      → Prepared-local (journal saved, TradePrepared sent)
      → Committed (peer TradePrepared received → swap + save → TradeCommitted sent)
      → Done (peer TradeCommitted received or connection closed after Committed)
Cancel allowed until Prepared-local. Any change of either offer resets both confirms.
Disconnect: before Prepared-local → abort, nothing changed.
            Prepared-local, peer Prepared unknown → InDoubt (journal kept; section 5).
```

**Battle**:
```
Lobby → Proposed → TeamsExchanged (both BattleTeam, validated) → Started (BattleStart; both build Battle)
  loop: AwaitingChoices (local choice sent, remote awaited; each phase TURN/REPLACE/SELF_SWITCH)
        → Resolving (apply both choices in canonical order: side 0 then side 1, then battle.advance())
        → HashExchange (TurnHash; mismatch → Desynced)
  → Ended (outcome) | Desynced | Forfeit | Suspended → (Resume ok → AwaitingChoices) | (60 s → Disconnected)
```
Choices are applied only after both are known, always in side order 0,1, so `choose()` calls (and any RNG they
might draw) happen in the same order on both peers. The local choice is checked against `request()` and
`canSelectMove` before it is sent (Battle.kt:336-367). Both choices are then applied with `choose` (Battle.kt:390).
A non-null error on the remote choice is a protocol violation (→ Error). That `choose` never draws RNG is
assumed from a read of Battle.kt:390-460; P3-T1 confirms it.

### 4.6 Battle construction (`LinkBattleFactory`, engine)

Inputs: seed, host team, guest team, rules, environment, isNight. Steps: deep-copy both teams via JSON; set
battle-local uids; normalise levels to the cap (recompute stats; full HP, no status, full PP, keep IVs/EVs/nature/
held item/ability/moves); `Side(0, host, PLAYER)`, `Side(1, guest, PLAYER)`; `Battle(dex, sides, linkRules, seed,
environment, isNight)`. Pure function: same inputs → same battle; unit-tested by checksum at turn 0.

### 4.7 Desync detection

- `BattleChecksum.of(battle)`: SHA-256 over a canonical text built in a fixed order: `turn`, `outcome`,
  `rng.state`, field (weather, terrain, pseudo-weather ids and durations), each side (conditions, slot conditions,
  used gimmicks sorted), each `BattleMon` by team index (species, hp, status, sleep turns, fainted, active slot,
  item, ability, boosts, PP per move, volatile ids sorted with duration/counter). Excludes wall-clock fields (F21)
  and object identities.
- `eventsHash`: SHA-256 of the turn's `BattleEvent`s rendered without text (text is viewer-specific, F8).
- Exchanged every turn (`TurnHash`); the receiver compares after resolving its own turn. Mismatch → both show
  "Link error", save the seed + choice log + both hash lists to `saveDir/link-desync-<sessionId>.json` for replay.
  Cheap first check: `rngState` (a single Long) catches most divergence, the full hash catches the rest.
- **Determinism probe in the handshake:** each peer plays a fixed embedded seeded PvP battle (choices from a seeded
  RNG, ~30 turns, estimate < 50 ms) and sends the final checksum as `probeHash`. A different engine build or a
  platform float difference is rejected before any battle starts.

---

## 5. Trade data-safety rules

1. **Validate before anything changes** (`LinkMonValidator`, strict, reject-only): species exists in this build's
   dex and is not battle-only/totem; level 1-100; ≤ 4 known moves, no duplicates, PP ≤ max; IVs 0-31, EVs ≤ 252,
   total ≤ 510; ability slot exists for the species; held item known or null; nature/mint valid; nickname passes
   the existing nickname rules; `exp` consistent with level; JSON size ≤ 16 KiB. No repair.
2. **Both confirm**, on a screen showing both Pokémon; either offer changing resets both confirmations; the
   `TradeConfirm` carries both fingerprints so each side confirms exactly what it saw.
3. **Fingerprint and duplicate checks:** `MonFingerprint` = SHA-256 of the canonical JSON minus `uid`, `fainted`.
   Reject if the incoming fingerprint matches one already owned, or the `tradeId` is in the save's trade log.
   Cannot give away the last healthy party member or a Pokémon in the middle of a pending nickname/evolution.
4. **Save before commit (journal):** on BothConfirmed, write `pendingLinkTrade = {tradeId, peerName, peerTrainerId,
   outgoingUid, outgoingFingerprint, incoming (full JSON), incomingFingerprint, state = PREPARED}` into the save
   and call `SaveManager.save` (atomic, SaveManager.kt:85-110). Only after `SaveResult.Saved` send `TradePrepared`.
5. **Atomic swap:** on peer `TradePrepared`: in memory remove the outgoing Pokémon, `acquire` the incoming one into
   the same slot (fresh uid, keep peer OT, `outsider = true`, `metLevel = level`), append `tradeId` to `tradeLog`,
   clear `pendingLinkTrade`, save once. Evolution (Linking Cord/trade items) runs after the save as a separate,
   already-existing step.
6. **Rollback:** any failure before step 4 completes leaves the save untouched. A failed save at step 4 or 5
   reverts the in-memory change and aborts (`SaveResult.Refused` included).
7. **In-doubt window:** a journal in state PREPARED on load means the link died between the two prepares. On the
   next link with the same `peerTrainerId`+name, peers exchange `TradeResolve`: if either side committed, the other
   commits; if neither did, both roll back. If no reconnection happens, the player can choose "keep my Pokémon"
   after a warning; the worst case is a duplicate, never a loss (decision D2).
8. **Autosave:** link screens set `blocksAutosave = true` from BothConfirmed until Done (Screen.kt:14;
   PkmnGame.kt:281-289), so an Android pause cannot save a half-applied state outside the journal.
9. **Party/box consistency:** after commit, run the existing `repairIdentityAndDex` invariants in a test
   (SaveManager.kt:356) to prove uids stay unique and the Pokédex is updated.

---

## 6. Phased task breakdown

Conventions: verification commands come from CLAUDE.md. `G-content` = `./gradlew :tools:compileMaps
:tools:checkProgression :tools:validateContent :tools:buildAssetManifest && git diff --exit-code -- assets/`;
`G-test` = `./gradlew :engine:test :core:test :tools:test` (plus `:link:test` once the module exists; add it to
`scripts/gates.sh` and CI in P8-T2); `G-android` = `./gradlew :android:assembleDebug`. Run with `set -o pipefail`
and full logs (`scripts/gates.sh` does this). Model/effort: lightest that fits; Opus only where stated with reason.
Effort estimates are estimates.

### P0 spike: transport and handshake, desktop only

| ID | Title | Objective | Files | Deps | Model / effort | Agent | Acceptance | Verify |
|---|---|---|---|---|---|---|---|---|
| P0-T1 | Locate integration points | Confirm the file:line list in section 3 still holds on the current origin branch; list any drift | read-only | — | haiku / low | explorer | Report with file:line, no edits | `git fetch origin && git log origin/HEAD -1` |
| P0-T2 | Spike `:link` module + TCP echo handshake | Create `:link`, length-prefixed JSON frames, `Hello`/`Ready`/`Ping` over loopback; desktop debug flag `--link-host` / `--link-join=<ip>` | `settings.gradle.kts`, `link/build.gradle.kts`, `link/src/main/kotlin/pkmn/link/{protocol,transport}/*`, `link/src/test/...` | P0-T1 | sonnet / medium | implementer | Two desktop processes complete a handshake on one machine; framing rejects > 64 KiB | `./gradlew :link:test`; `G-test` |
| P0-T3 | Spike review | Check thread model, framing, no network on offline path | diff | P0-T2 | sonnet / high | reviewer | Findings list | reviewer reads diff |

### P1 protocol and session core, with tests

| ID | Title | Objective | Files | Deps | Model / effort | Agent | Acceptance | Verify |
|---|---|---|---|---|---|---|---|---|
| P1-T1 | Protocol messages + codec | All v1 messages (4.4), `seq` checks, version gate | `link/.../protocol/*` | P0 | sonnet / medium | implementer | Round-trip test for every message type; unknown type and bad seq rejected | `./gradlew :link:test --tests '*Protocol*'` |
| P1-T2 | In-memory channel + connection FSM | `MessageChannel` interface, `LoopbackChannel`, connection state machine incl. timeouts with an injectable tick source (no wall clock in tests) | `link/.../session/Connection*.kt` | P1-T1 | sonnet / medium | implementer | FSM table test covers every transition and timeout | `./gradlew :link:test` |
| P1-T3 | Data hash | SHA-256 over `assets/data/*.json` in sorted order, desktop and Android asset readers | `engine/.../link/DataHash.kt`, core loader hook | P1-T1 | sonnet / low | implementer | Same hash for the same assets; changes when one byte changes | `G-test` |
| P1-T4 | Review P1 | — | diff | P1-T1..3 | sonnet / high | reviewer | — | — |

### P2 trade end to end (desktop, then Android)

| ID | Title | Objective | Files | Deps | Model / effort | Agent | Acceptance | Verify |
|---|---|---|---|---|---|---|---|---|
| P2-T1 | Save v4: trade journal + trade log | Add `pendingLinkTrade`, `tradeLog` to `GameState`; v3→v4 migration; golden v3 fixture still loads | `engine/.../save/GameState.kt`, `SaveManager.kt`, `engine/src/test/.../save/*`, test resources | P1 | **opus / high** (save-format migration: CLAUDE.md routes migrations to Opus; data loss risk) | implementer | `SaveFixturesTest` passes for v1-v3 and a new v4 fixture; unknown-field and downgrade (`Unsupported`) behaviour unchanged | `./gradlew :engine:test --tests '*Save*'`; `G-test` |
| P2-T2 | `LinkMonValidator` + `MonFingerprint` + `performLinkTrade` | Strict validation (5.1), fingerprint, journal-driven swap (5.4-5.6) | `engine/.../link/*`, `engine/.../campaign/GameContext.kt` | P2-T1 | sonnet / medium | implementer | Table tests for each rejection rule; swap keeps peer OT, sets `outsider`/`metLevel`, fresh uid, Pokédex entry; NPC `performTrade` tests unchanged | `./gradlew :engine:test`; `G-test` |
| P2-T3 | `TradeSession` | Trade FSM (4.5) on `MessageChannel`, callbacks for "save journal" / "commit" | `link/.../session/TradeSession.kt` + tests | P2-T2 | sonnet / medium | implementer | Loopback test: full trade, cancel at every state, offer change resets confirms, kill at every state → T-AC6 invariant | `./gradlew :link:test --tests '*Trade*'` |
| P2-T4 | Desktop trade screens (minimal) | LINK menu → host/join → trade screen using existing `PartyScreen`/PC selection; `blocksAutosave` | `core/.../link/*`, `core/.../screens/FrontEnd.kt` (or PC/Pokémon Center entry, decision D4) | P2-T3 | sonnet / medium | implementer | Two desktop processes trade; `G-test` green; offline flow unchanged | `G-test`; `./gradlew :desktop:run` ×2 manual |
| P2-T5 | Android LAN access | `Platform.lanAccess()` (multicast lock), manifest permissions, background threads only | `core/.../Platform.kt`, `android/.../AndroidLauncher.kt`, `android/AndroidManifest.xml`, desktop launcher | P2-T4, D1 | sonnet / medium | implementer | Desktop↔emulator trade over the host bridge (manual IP); lint clean | `G-android`; `./gradlew :android:lintDebug` |
| P2-T6 | Security review of trade path | Untrusted input, size limits, no secrets, permission scope | diff | P2-T5 | sonnet / high | reviewer (Nora brief) | Findings list | — |

### P3 battle determinism audit and checksums

| ID | Title | Objective | Files | Deps | Model / effort | Agent | Acceptance | Verify |
|---|---|---|---|---|---|---|---|---|
| P3-T1 | Determinism audit | List every hash-collection iteration, float transcendental call, identity hash, wall-clock read and global mutable state in `engine/.../battle` and `mon`; classify safe/unsafe with proof | read-only report → section in this doc | P0 | **opus / high** (battle-engine determinism with subtle interactions: CLAUDE.md routes these to Opus) | explorer-style read-only (general-purpose/opus) | Each site has a verdict and file:line | — |
| P3-T2 | Fix unsafe sites | `StrictMath` (or integer maths) for `pow` in battle paths; `LinkedHashMap` where order matters; no behaviour change for singles | `engine/.../battle/*`, `mon/Pokemon.kt` | P3-T1 | **opus / high** (same reason; must keep golden log byte-identical) | implementer | `SinglesGoldenLogTest` unchanged without regeneration; full engine suite green | `./gradlew :engine:test`; `G-test` |
| P3-T3 | `BattleChecksum`, `LinkBattleFactory`, PvP fuzz | Section 4.6/4.7; new `PvpDeterminismTest`: two independent `Battle` instances fed the same seeded choices for N seeds → identical checksums every turn; also covers self-switch and replacement with two PLAYER sides (F13) | `engine/.../link/*`, `engine/src/test/.../battle/PvpDeterminismTest.kt` | P3-T2 | sonnet / high | implementer | 200 seeds (estimate) pass; no `advance` stalls with two PLAYER sides | `./gradlew :engine:test --tests '*Pvp*'` |
| P3-T4 | ART determinism probe | Debug-only Android hook (automation command or instrumented test) that runs the probe battles and logs checksums; compare with desktop | `android/src/androidTest/...` or debug flag; `engine/.../link/Probe.kt` | P3-T3 | sonnet / medium | implementer + qa-tester | Same checksums on JVM 17, JVM 21 and the emulator (x86_64) | `./gradlew :android:connectedDebugAndroidTest` (new) or logcat diff |
| P3-T5 | Review | — | diff | P3-T2..4 | sonnet / high | reviewer | — | — |

### P4 battle lockstep feature

| ID | Title | Objective | Files | Deps | Model / effort | Agent | Acceptance | Verify |
|---|---|---|---|---|---|---|---|---|
| P4-T1 | `BattleSession` | Battle FSM (4.5): proposal, team exchange + validation, seed from host `SecureRandom`, choice exchange per phase, canonical apply order, `TurnHash` compare | `link/.../session/BattleSession.kt` + tests | P3-T3, P1 | sonnet / high | implementer | Loopback integration: full battles with identical outcome/checksums (B-AC3) | `./gradlew :link:test --tests '*Battle*'` |
| P4-T2 | Perspective in battle UI | `viewSide` in `BattleFlow`/`BattleScreen` (F9), per-viewer names/texts (F8) without changing engine text for single-player | `core/.../battle/BattleFlow.kt`, `BattleScreen.kt` | P4-T1 | sonnet / medium | implementer | core `BattleFlowTest` unchanged + new guest-perspective tests; visual scenes unchanged | `G-test`; `tools/visual/run_scenes.sh` |
| P4-T3 | Remote-driven flow | Link variant of `BattleFlow` that waits for the remote choice instead of `advance(ai)`; outcome mapped per viewer; no write-back to save | `core/.../link/LinkBattleFlow.kt` | P4-T2 | sonnet / medium | implementer | Two desktop processes battle to the end; saves byte-identical before/after (B-AC1) | `G-test`; manual ×2 desktop |

### P5 desync, disconnect and timeout handling

| ID | Title | Objective | Files | Deps | Model / effort | Agent | Acceptance | Verify |
|---|---|---|---|---|---|---|---|---|
| P5-T1 | Desync handling | Stop on mismatch, write desync report (4.7), replay tool test | `link/.../session/*`, `engine/src/test/.../link/*` | P4 | sonnet / medium | implementer | Desync-injection test (B-AC4) | `./gradlew :link:test --tests '*Desync*'` |
| P5-T2 | Reconnect and timeouts | `Suspended` state, `Resume` + `ChoiceLog` replay, 60 s window, optional turn deadline, trade `TradeResolve` | `link/.../session/*` | P5-T1 | sonnet / high | implementer | Kill/reconnect tests at every phase; trade in-doubt resolution tests | `./gradlew :link:test` |
| P5-T3 | Discovery hardening | Directed broadcast per interface; optional `NsdManager` path if UDP fails on test devices (DEFER line) | `link/.../transport/LanDiscovery.kt`, `android/...` | P2-T5 | sonnet / medium | implementer | Host found on desktop LAN and emulator setups documented in 7.4 | `G-android` |

### P6 UI and screens

| ID | Title | Objective | Files | Deps | Model / effort | Agent | Acceptance | Verify |
|---|---|---|---|---|---|---|---|---|
| P6-T1 | Link lobby and trade UI polish | GSC-style link screens per docs/UI_SPEC.md; join-code entry; touch controls; error texts | `core/.../link/*` | P4, P5 | sonnet / medium | implementer (Juno-style brief) | Screens follow UI_SPEC; all strings fit 160 px boxes | `G-test`; visual scenes added |
| P6-T2 | Visual scenes | Add `link_*` scenes (lobby, trade review, battle guest view) to visual regression | `tools/visual/*`, `docs/screenshots/*` | P6-T1 | sonnet / low | implementer | Scenes reproducible | `tools/visual/run_scenes.sh` |

### P7 QA on desktop and Android emulator

| ID | Title | Objective | Files | Deps | Model / effort | Agent | Acceptance | Verify |
|---|---|---|---|---|---|---|---|---|
| P7-T1 | Desktop↔desktop and desktop↔emulator runs | Execute section 7.4 steps, screenshots via Android QA plugin | reports only | P6 | sonnet / medium | qa-tester | T-AC1..9, B-AC1..8 checked with evidence | `G-content`; `G-test`; `G-android`; emulator screenshots |
| P7-T2 | Two-device LAN run | Real phone + desktop on Wi-Fi (Paulo's hardware) | reports only | P7-T1 | sonnet / low | qa-tester | Steps 7.4 pass or issues filed | manual |

### P8 docs, STATUS and CI

| ID | Title | Objective | Files | Deps | Model / effort | Agent | Acceptance | Verify |
|---|---|---|---|---|---|---|---|---|
| P8-T1 | Docs | `docs/LINK.md` (protocol, ports, troubleshooting); update docs/BUILD.md:3 and docs/MECHANICS.md:149 claims; this plan → "done" notes | docs | P7 | haiku / medium | implementer (Pip brief) | No stale "never uses the network" claim | `G-content` |
| P8-T2 | CI + gates | Add `:link:test` to CI and `scripts/gates.sh`; STATUS numbers only from `:tools:statusCounts`/`checkStatus` | `.github/workflows/ci.yml`, `scripts/gates.sh`, `STATUS.md` | P8-T1 | sonnet / low | implementer | CI green; `:tools:checkStatus` passes | `./gradlew :tools:statusCounts :tools:checkStatus` |
| P8-T3 | Final review | Whole-feature diff review | diff | P8-T2 | sonnet / high | reviewer | — | — |

### Risk register

| ID | Risk | Likelihood / impact (estimate) | Mitigation |
|---|---|---|---|
| R1 | Cross-platform float difference (ART vs HotSpot) desyncs battles | Medium / High | P3 StrictMath/integer fixes; handshake determinism probe; per-turn checksums |
| R2 | Hidden order dependence (hash iteration, identity) | Low-Medium / High | P3 audit with file:line verdicts; PvP fuzz across JVM 17/21 and ART |
| R3 | Engine paths untested with two PLAYER sides stall or crash | Medium / Medium | PvP fuzz (P3-T3) before any UI work |
| R4 | Trade crash in the commit window duplicates a Pokémon | Low / Low-Medium | Journal + `TradeResolve`; never-lose policy (D2) |
| R5 | Save v4 migration corrupts saves | Low / High | Opus task, golden fixtures, existing atomic save + backup |
| R6 | UDP broadcast blocked (AP isolation, guest Wi-Fi, Android filtering) | High / Low | Manual IP fallback; multicast lock; NsdManager deferred option |
| R7 | Lockstep exposes the opponent's full team to a modified client | Certain / Low on LAN | Documented; teams revealed at team preview anyway; out of scope |
| R8 | New permissions change store listing / privacy expectations | Certain / Medium | Decision D1; permissions documented; network only after LINK menu |
| R9 | Android 17 target makes local sockets need `ACCESS_LOCAL_NETWORK` | Certain at target 37 / Medium | Note in docs; runtime request at LINK menu when target is raised |
| R10 | UI perspective work breaks single-player visuals | Medium / Medium | `viewSide` defaults to 0; visual regression scenes |
| R11 | Different builds connect (data or engine drift) | Medium / High | `protocol` + `dataHash` + `probeHash` in handshake |
| R12 | APK budget (`:tools:apkBudget`, ci.yml:48) exceeded by a dependency | Low with stdlib transport | No new dependency |

---

## 7. Test plan

### 7.1 Unit
- Protocol: encode/decode round trip for every message; frame length limits; seq gap/repeat rejection; version gate.
- Connection, trade and battle FSMs: table-driven transitions with an injected tick source (no sleeps).
- `LinkMonValidator`: one failing case per rule plus valid edge cases (level 1/100, 510 EVs, forms).
- `MonFingerprint`: stable across serialize/deserialize; ignores `uid`.
- `BattleChecksum`: identical for identical battles; changes when any hashed field changes; ignores wall-clock fields.
- `LinkBattleFactory`: same inputs → same turn-0 checksum; teams are copies (mutating the battle leaves the input
  `Pokemon` untouched).
- Save v4: migration from v1/v2/v3 fixtures; journal round trip.

### 7.2 Integration (two in-process peers over loopback, `:link:test`)
- Full trade over real `TcpChannel` on `127.0.0.1`: both saves (temp dirs) end with the swapped Pokémon; the
  invariant "each trade leaves exactly one copy per save" holds when the connection is cut after each message.
- Full battle: 200 seeded battles (estimate), choices from a seeded random-legal-choice driver on each side, over
  TCP loopback; assert identical outcome, turn count and checksum list on both peers. Note: in-process peers share
  static registries (`Effects`, `MoveScripts`), so this does not prove cross-process determinism; P3-T4 and a
  two-process smoke test (`:desktop` debug flags) cover that.

### 7.3 Desync injection
- Test hook draws one extra `rng.nextLong()` on the guest at turn 3: both peers must stop at turn 3 with DESYNC,
  write the report, and leave both saves unchanged. Variant: alter one move's PP on one peer → caught by the full
  hash with `rngState` still equal.

### 7.4 Manual LAN test steps (two machines)
1. Same Wi-Fi/subnet, both builds from the same commit (check the LINK screen shows the same build/data hash).
2. Machine A: title or Pokémon Center → LINK → HOST; note join code and IP.
3. Machine B: LINK → JOIN; host appears within ~5 s; enter join code. Repeat with discovery blocked (enter IP).
4. Trade: pick one Pokémon each, review, both confirm; verify Pokémon, OT, Pokédex, evolution if applicable;
   restart both games and verify the saves.
5. Interrupt: start a trade, kill B's app after "Confirm" on both; restart; verify each save has exactly one copy;
   reconnect and verify the journal resolves.
6. Battle: host proposes level 50; play to the end; verify no EXP/money/item change in either save.
7. Pull the network during a battle; reconnect within 60 s; battle resumes; repeat waiting > 60 s → "Disconnected".
8. Repeat 2-7 as desktop↔Android and Android↔Android; on Android also background the app mid-trade (autosave must
   not fire) and check logcat for socket errors.

---

## 8. Open decisions for Paulo

| ID | Decision | Default proposed |
|---|---|---|
| D1 | Add `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` to every Android build (including the store build), or only to a link-enabled flavor? | Every build; sockets only after LINK menu |
| D2 | Trade in-doubt policy without reconnection: allow "keep my Pokémon" (possible duplicate) or block trades with that peer until resolved? | Allow after a warning (never lose) |
| D3 | Join code: 4-digit convenience code (no encryption) is enough for LAN? | Yes; no TLS on LAN |
| D4 | Where the LINK entry lives: title menu, Pokémon Center counter (GSC-style), or both? Story gating (e.g. after first badge)? | Pokémon Center counter, no gating |
| D5 | Link battle rules: level cap 50 by default? Gimmicks allowed? Duplicate species/items clause? | Cap 50, all gimmicks the save has unlocked, no clauses |
| D6 | Version gate: exact build match (data + probe hash) or allow different app versions with the same hashes? | Hash match only |

---

## Appendix: git commands used to establish repo state

```
git -C /home/paulo/Pkmn fetch origin
git -C /home/paulo/Pkmn log origin/main -1          # fails: origin has no main branch
git -C /home/paulo/Pkmn remote -v
git -C /home/paulo/Pkmn branch -r
git -C /home/paulo/Pkmn ls-remote --symref origin HEAD   # default branch ccr-c3b68caf-pdjkp5
git -C /home/paulo/Pkmn for-each-ref --sort=-committerdate refs/remotes
git -C /home/paulo/Pkmn log -1 origin/ccr-c3b68caf-pdjkp5    # 696c438, 2026-10-28
git -C /home/paulo/Pkmn merge-base --is-ancestor HEAD origin/ccr-c3b68caf-pdjkp5
git -C /home/paulo/Pkmn rev-list --count HEAD..origin/HEAD   # 143
git -C /home/paulo/Pkmn archive origin/HEAD | tar -x -C /tmp/pkmn-snap   # read-only snapshot
```
