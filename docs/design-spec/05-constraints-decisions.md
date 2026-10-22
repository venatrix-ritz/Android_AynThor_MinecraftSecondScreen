# 05 - Constraints and design decisions

Each decision below is stated as the code and its own comments state it. The "rejected" column is taken from those comments where they name an alternative; otherwise it is marked as inferred.

## Platform and device constraints

| Constraint | Consequence in code | Source |
|---|---|---|
| A `Presentation`'s window sits outside the Activity's view hierarchy | Compose needs lifecycle, view-model and saved-state owners set by hand; Compose `Dialog` is avoided because it creates a second window | `KT/SecondScreenPresentation.kt:21-25`; `KT/ui/SettingsScreen.kt:86-90` |
| A secondary display may not show an IME the app can use | The app draws its own keyboard by default | `KT/ui/ChatKeyboard.kt:26-35`; `KT/settings/ChatSettings.kt:22-31` |
| An IME in landscape can enter full-screen "extract" mode | The system-keyboard field is a platform `EditText` with `IME_FLAG_NO_FULLSCREEN` and `IME_FLAG_NO_EXTRACT_UI`; Compose text fields cannot set those | `KT/ui/ChatScreen.kt:360-379`, `:412-416` |
| A presentation display does not report IME insets | `imePadding()` is a no-op on the second screen; the keyboard floats over the panel | `KT/settings/ChatSettings.kt:22-31`, `KT/ui/ChatScreen.kt:131-134` |
| Compose's test clock and the real device clock differ for long presses | Creative picker decides hold from event timestamps, not a timer | `KT/ui/CreativeScreen.kt:564-583` |
| Pointer-input timeouts must use the pointer scope's clock | Inventory hold uses `AwaitPointerEventScope.withTimeoutOrNull`, not coroutines | `KT/ui/InventoryScreen.kt:457-462` |
| The primary screen runs the game; the second screen must not steal taps | Nothing is drawn on the primary screen when a second display exists | `KT/MainActivity.kt:20-30` |
| The second screen must not sleep while played | `FLAG_KEEP_SCREEN_ON` | `KT/SecondScreenPresentation.kt:37-41` |
| Textures for the HUD are the game's own, possibly from a resource pack | Textures come over the socket, not from disk | `CLAUDE.md`; `KT/net/ResourcePackIconProvider.kt:169-171` |
| Android scoped storage blocks cross-app access to `Android/data` | The SAF folder approach was written for this; it is now unused | `CLAUDE.md` (GameDirectoryAccess note); `KT/net/GameDirectoryAccess.kt` |
| Bitmap font covers codepoints 0x00-0xFF | Other characters render as `?` | `KT/ui/MinecraftFont.kt:69-70`; `KT/ui/ChatScreen.kt` (doc) |
| Vanilla GUI sprites have a fixed grid | Slot pitch 18, item 16, one-pixel bevel; whole texture pixels scaled by one unit | `KT/ui/InventoryScreen.kt:386-405` |
| Loopback only | The app connects to 127.0.0.1 only | `KT/net/HudRepository.kt:27` |
| One mod process per device | Port 48291 is bound by one listener at a time | Protocol doc; `CLAUDE.md` |
| Linux runs under a Gamescope session on the bottom display | Unit file binds to the Gamescope session and conflicts with the stock bottom-screen service | `linux/systemd/thor-mc-hud.service` |

## Design decisions

### Architecture

| Decision | Rationale (from code) | Rejected alternative (named in code or docs) |
|---|---|---|
| Standalone app, not a launcher fork | `Presentation` is available to any app, so no launcher integration is needed; "show only when the mod runs" follows from the socket | Launcher-side integration (`CLAUDE.md`, "Why the app is standalone") |
| Loopback TCP, newline-delimited JSON | Two sandboxes on one device; simplest cross-process channel; no framing beyond newlines | Shared memory (`thor-hud-handoff.md`, section 1) |
| The mod sends state; the app predicts nothing | "Nothing is applied optimistically"; what is drawn is what the game reports | Local prediction of slot moves (`KT/ui/InventoryScreen.kt:107-109`) |
| Pure logic in separate files | Silent failures are only catchable by JVM tests | Logic in composables |
| One `HudRepository` for the process, owned by the activity | The Presentation can be recreated; the connection must not be | `KT/MainActivity.kt:78` |
| Caches and bindings survive disconnect; player state does not | The mod re-sends bindings and assets, and replays chat backlog; a stale container would turn taps into clicks on the wrong handler | `KT/net/HudRepository.kt:509-515`, `700-721` |

### Protocol

| Decision | Rationale | Rejected |
|---|---|---|
| `hello` is the only proof of a real mod | A socket that opens is not success; a port squatter accepts and drops | Treating "socket open" as connected (`CLAUDE.md`, hello section) |
| `noplayer` message rather than silence | Silence cannot be told apart from a stalled game; the app would freeze on the last snapshot | Going quiet (`KT/net/HudRepository.kt:895-909`) |
| `syncId` echoed with every container command | Stops a click after a close from landing on the wrong handler | No staleness check (`KT/net/HudRepository.kt:1095-1110`) |
| Full `HUD:` set every time, not deltas | A dropped delta would leave the two screens disagreeing about who draws an element | Delta messages (`KT/SecondScreenApp.kt:215-219`) |
| `disabled` sent only when true, `mayPlace`, `x`/`y` per slot | Sent on every container message; "enabled" and "no position" as defaults keep the message small | Explicit flags on all 46 slots (`CLAUDE.md`, container notes) |
| Progress sent in drawn pixels, not raw ticks | The container message is broadcast on change; raw progress would resend every tick | Raw progress (`CLAUDE.md`, progress notes) |
| Bubble phase and enchant glint animated on the client | Animated values would force a broadcast ten times a second | Mod-side animation (`CLAUDE.md`) |
| Chat backlog replayed by the mod, cleared by the client on disconnect | The mod's backlog is authoritative and would duplicate the client's copy | Client-side persistence of chat |
| Icons requested by item id, not bulk-sent | Creative catalogue needs only ids; icons are cached once requested | A bulk image channel (`KT/net/HudRepository.kt:551-552`, `KT/ui/CreativeScreen.kt:79-84`) |
| Button labels are binding ids | A slot follows the action when the player rebinds it | Hardcoded codes shared with a table in the mod, which drifted (`KT/ui/InputGridScreen.kt:40-46`) |
| `BEACON:` carries both effects | The packet carries both; sending per tap would clear the secondary | Per-tap sends (`CLAUDE.md`, beacon notes) |
| `BEACON:` uses `-` for no effect | An empty field is indistinguishable from a clear | Empty field (`KT/net/ContainerCommands.kt:15-21`) |
| `RENAME:` keeps commas and flattens newlines | Items may contain commas; newlines would split the command | Escaping (`KT/net/ContainerCommands.kt:22-25`) |

### Algorithms and reliability

| Decision | Rationale | Rejected |
|---|---|---|
| Reconnect: five fast retries, then doubling to 30 s | Game restart is quick and should be noticed quickly; a long outage should not be polled every 1.5 s all night | Flat 1.5 s retry (`KT/net/ReconnectPolicy.kt:8-19`) |
| Icon requests timestamped, not "requested once" | An unanswered request must expire and retry | A plain set (`KT/net/HudRepository.kt:591-601`) |
| Icon cache bounded at 200 with LRU | Session can touch hundreds of items; LRU keeps what is on screen | Age-based eviction (`KT/net/HudRepository.kt:565-575`) |
| Absorption as extra slots in one run, not a separate bar | Matches vanilla's `renderHealthBar`; the old clamp at 10 made absorption unreachable | Clamped run (described in `KT/ui/HeartLayout.kt:80-89`) |
| Bubble index mirrored | Vanilla empties the row from the left | Naive left-to-right order (`KT/ui/HudScreen.kt:608-620`) |
| Enchant glint masked by item alpha and additive | Avoids a highlighted square and a washed-out icon | Translucent gradient over the whole slot (`KT/ui/ItemGlint.kt:33-43`) |
| Map drawn without whole-pixel snapping | Whole-pixel steps wasted 20 to 30 percent of the panel | Snapping, which the code records as reversed (`KT/ui/MapScreen.kt:65-76`) |
| Grid stays 3x3 with empty slots kept | Thumb targets; muscle memory beats filling gaps | Compacting the grid (`KT/ui/InputGridScreen.kt:46-50`) |

### User interface

| Decision | Rationale | Rejected |
|---|---|---|
| Status stack on every tab except Items | Vital signs stay visible while the panel does something else | Full-screen tabs |
| Items tab hides the status stack | Six rows of slots do not fit under the HUD | Scrolling inventory (`KT/SecondScreenTab` doc, `KT/SecondScreenApp.kt:82-88`) |
| Visibility toggles, not drag-to-place for HUD elements | Keeps the shared status grid; free placement needs a layout engine | Drag placement (`KT/ui/SettingsScreen.kt:75-80`) |
| Mode buttons (Stack, Half, Single, Move), not long-press | Hidden gestures can silently perform the wrong move | Long-press and multi-touch (`KT/ui/InventoryScreen.kt`, `ui/InventoryMoves` doc) |
| Hold-then-drag spread at 300 ms | Visible build-up before release; no wrong move | Half-click gesture (`KT/ui/InventoryScreen.kt:88-95`) |
| Container bands across the top, villager as a side rail | Cell size is bound by width; a rail of narrow trade rows fits | Column beside the grid, kept on the branch `feat/container-panels-side-by-side` (`CLAUDE.md`) |
| Anvil keyboard shown only while typing | A permanent keyboard cost four rows | Pinned keyboard (`KT/ui/ContainerExtrasPanel.kt:553-570`) |
| ASCII-only labels in containers | Font coverage | Symbols such as `->` and `·` (`KT/ui/ContainerExtrasPanel.kt:355-357`, `CLAUDE.md`) |
| Creative search by id | Ids are all the catalogue carries; translated names would double the message | Names (`KT/ui/CreativeScreen.kt:71-77`) |
| Tab takeover restores the previous tab | Tab return after a chest; manual choice wins | Staying on Items (`KT/SecondScreenApp.kt:148-197`) |
| Nine backgrounds, not every block | Must tile seamlessly and stay quiet | All blocks (`KT/settings/HudBackground.kt:6-20`) |
| Draft hoisted above tabs | Glancing at the map must not lose a draft | Draft in the chat panel (`KT/ui/ChatScreen.kt:67-73`) |

### Packaging

| Decision | Rationale | Source |
|---|---|---|
| No game textures in the repo | Mojang's EULA; `tools/*.jar` and `app/src/main/assets/minecraft*/` are gitignored | `.gitignore` |
| CC0 for the app | As documented | `README.md:80-82`, `LICENSE` |

## Constraints on change

- Adding a status element: add an enum key (the wire name) and gate it in `HudScreen` (`KT/settings/HudSettings.kt:26-29`).
- Adding a tab: one enum entry and one `when` branch (`KT/SecondScreenApp.kt:76-107`, `280-350`).
- Adding a container kind: the enum entry and a handler-type set; the key is stored and must not change (`KT/settings/ContainerKind.kt:17-20`).
- Renaming any persisted key silently resets the setting for every installed user.
