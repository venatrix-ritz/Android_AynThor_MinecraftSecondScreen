# 06 - Testing, limitations and glossary

## Testing

Nothing in this section was run. Counts are numbers of `@Test`-annotated (or `unittest` test) methods found by reading the files. They are not pass or fail results.

### How the suites are meant to run (from docs)

| Suite | Command documented | Source |
|---|---|---|
| Android JVM unit tests | `./gradlew test` | `CLAUDE.md` Commands; `README.md:65` |
| Android instrumented tests | `./gradlew connectedAndroidTest` (needs a device or emulator) | `CLAUDE.md` Commands; `README.md:66` |
| Linux unit tests | Not documented. The file has an `if __name__ == "__main__": unittest.main()` block, so it can be run as a script. | `linux/tests/test_icon_requests.py:66-67` |

### Android JVM unit tests (`TEST/`)

| File | @Test count | Covers |
|---|---|---|
| `net/ContainerCommandsTest.kt` | 6 | BUTTON, TRADE, SLOTSTATE, BEACON dash sentinel, RENAME commas and newlines, empty rename |
| `net/ReconnectPolicyTest.kt` | 5 | Fast window, back-off, cap, overflow safety, monotone delays |
| `settings/ContainerKindTest.kt` | 10 | Handler-type mapping, chest group, cooking group, creative to Inventory, unknown to Other, key uniqueness and round trip |
| `settings/InputSlotCodecTest.kt` | 8 | Missing vs empty string, round trip, padding, truncation, default shape |
| `ui/ChatLineWrapperTest.kt` | 11 | Word breaks, hard breaks, colour runs, empty message, non-positive width |
| `ui/ContainerLayoutTest.kt` | 13 | Furnace and brewing placement, centring, gaps, decoration offset, fallback strip, slotAt |
| `ui/HeartLayoutTest.kt` | 18 | Health, absorption rows, odd absorption, reduced and raised max health, row cap, bubble counts |
| `ui/HeartSpritesTest.kt` | 6 | Heart set by status, hardcore axis, absorption axis, unknown type |
| `ui/InventoryLayoutTest.kt` | 10 | Player rows, hotbar 36-44, armor and off-hand, crafting, row width, chest above player, slotAt |
| `ui/InventoryMovesTest.kt` | 14 | Each mode's tap, pick-up, drop, single-onto-same, quick-craft packing, distribute, drop-cursor |
| `ExampleUnitTest.kt` | 1 | Template `addition_isCorrect` |

Total: 102 annotated methods, 101 excluding the template.

### Android instrumented tests (`ATEST/`)

| File | @Test count | Covers |
|---|---|---|
| `ui/InventoryScreenTest.kt` | 13 | Tap and drag sends, mode drags, hold-then-drag spread, off-grid release, held stack, furnace output drag, container title, furnace spacing, full player inventory on screen |
| `ui/CreativeScreenTest.kt` | 5 | Hold-and-drag onto hotbar, last hotbar slot, tap gives, drift during hold, release away from hotbar |
| `ExampleInstrumentedTest.kt` | 1 | Template `useAppContext` |

Total: 19 annotated methods, 18 excluding the template.

### Linux unit tests (`linux/tests/`)

`test_icon_requests.py` has 6 test methods for `IconRequests` (first request, timeout retry, failure back-off, answered clears pending, independence, reset). The module stubs `gi`, `gi.repository` and `cairo` so it does not need GTK.

### What the tests do not cover

- Socket I/O, reconnect loop timing, and the port-conflict rule (no test drives `HudRepository`'s connection loop).
- Container decoration rendering and the Creative picker's non-gesture behaviour.
- Anything in `decky-plugin/` or `tools/`.
- The mod's behaviour.

### Tool-based checks

`tools/hud_sim.py` and `tools/capture_hud.ps1` are the manual way to check screens against scripted state (see [03](03-devices-platforms.md#developer-tools)). The simulator does not model `SLOT:`, `RENAME:` or `TRADE:`; it acknowledges those without changing its state (`tools/hud_sim.py:792-842`), so an Items-tab drag shows no change in a simulated container. Its README says a green screenshot means "the app renders this state correctly", not that a feature works end to end (`tools/README.md:96-101`).

## Known limitations

Limitations stated by the repository are attributed to their source. Limitations found while reading the code are marked "code".

- **Device state of the container features is not verified.** `CLAUDE.md` states that furnace and brewing progress, the extras payload for enchanting, stonecutter, loom, villager, beacon and anvil, and loom previews have not been seen on a live container. Crafter cell toggles have a verb (`SLOTSTATE:`) but no gesture (`CLAUDE.md`, "Known unverified/fragile areas").
- **Screen close from the second display is not possible** according to `CLAUDE.md`. The Linux companion still sends `CLOSE_CONTAINER` (`linux/thor_mc_hud.py:636`); whether the mod acts on it is unverified.
- **Enchant glint is a simplified animation** (`KT/ui/ItemGlint.kt:76-77`, `GLINT_ALPHA` tuned by eye).
- **Hotbar texture alignment is approximate** until a real `hotbar.png` is bundled (`CLAUDE.md`, `HotbarRow` notes). No bundled hotbar texture exists in this tree (`app/src/main/assets/` is not tracked).
- **Item icons are the mod's renders**; this app does not render items. Loom pattern previews are names only.
- **Fonts and text**: only codepoints 0x00-0xFF render; anything else is `?` (`KT/ui/MinecraftFont.kt:69-70`).
- **Creative search** matches item ids, not translated names (`KT/ui/CreativeScreen.kt:71-77`).
- **Chat log** keeps 100 messages; anything older is dropped (`KT/net/HudRepository.kt:44`).
- **Icon cache** on the Linux companion is unbounded (`linux/thor_mc_hud.py:236`, code).
- **Linux reconnect** has no hello handling and a fixed 1.5 s retry (code).
- **Linux container key** may not match the mod's field (see drift below).
- **Off-hand box and map overlap** is prevented only by layout arithmetic and a z-index; if the map ever grows, the box could sit under it (`KT/SecondScreenApp.kt:245-252`, `KT/ui/HotbarRow.kt:79-94`, `CLAUDE.md`).
- **Simulator listens on all interfaces** (`tools/hud_sim.py:1028`, code).
- **Decky status poll** opens a TCP connection every 2 s while the panel is open (`decky-plugin/main.py`, `_is_mc_port_open`; `decky-plugin/dist/index.js`, 2000 ms interval). Effect on the mod is unverified.
- **Send ordering** is not guaranteed; see [design-spec/02](../design-spec/02-protocols-algorithms.md#send-ordering).
- **Linux `sendall` on a non-blocking socket** can raise if the send buffer is full; the outer handler then drops the connection (`linux/thor_mc_hud.py:372`, `388`, `409-410`, code). Frequency is unverified.

### Port-conflict banner on cold start

Code path (traced, not run): the connection loop counts a failed connection toward the port-conflict threshold whenever `sawHello` is false (`KT/net/HudRepository.kt:689-698`). `sawHello` is only set to true by a hello and is reset to false only after a successful `connect()` (`660`, `890`). A refused `connect()` does not reset it. So if the app starts before the game and has never received a hello, each refused attempt counts. The threshold is 3 (`53`). With the back-off in [02](02-features.md) (1.5 s per attempt), the banner would appear about 3 s after launch with no process on the port.

The code comment (`KT/net/HudRepository.kt:685-688`) describes the signature as "a connection that came and went without the mod identifying itself", which a refused connection also satisfies. Whether the banner appears in practice is **unverified**.

## Documentation and code drift

| Where | Says | Code does |
|---|---|---|
| `README.md:29` ("No permissions") | No permissions | Declares `INTERNET` (`AndroidManifest.xml:4`) |
| `README.md:15`, `22` | Mod and releases under an `exojosh` namespace | `origin` is `venatrix-ritz/Android_AynThor_MinecraftSecondScreen`. **Unverified** which namespace is canonical. |
| `CLAUDE.md` (Commands and Handoff sections) | Points to `tools/capture_protocol.py` | File not in repository |
| `CLAUDE.md` ("Known unverified" items) | Refers to a mod `CLAUDE.md` | Not in repository |
| `CLAUDE.md` ("HUD textures from the mod") | Legacy fallback under `app/src/main/assets/minecraft/` | Directory is gitignored and no assets are tracked |
| `CLAUDE.md` (GameDirectoryAccess) | "Dead weight, not yet removed" | Class still present; `persist` is called from a launcher that is never launched (`KT/MainActivity.kt:42-47`, `84`) |
| `thor-hud-handoff.md` section 3 | Lists `RadialInputPad.kt`, `TiledTextureBackground.kt`, `BundledIconProvider.kt` | None exist |
| `thor-hud-handoff.md` section 2.3 | Letter codes such as `E`, `R`, `G`, `H`, `K` | Android sends `BIND:<id>`; Linux sends its own strings |
| `thor-hud-handoff.md` section 5 | "No reconnect backoff on the Android side" and "no icon-cache eviction" | Both implemented (`KT/net/ReconnectPolicy.kt`, `KT/net/HudRepository.kt:1241-1256`) |
| `linux/thor_mc_hud.py:288` comment | "60fps refresh timer" | Interval is 33 ms (about 30 Hz) |
| `linux/thor_mc_hud.py:1-6` docstring | Describes "Fabric/Forge" mod | Only Fabric is documented elsewhere |
| `linux/README.md:8` | Panel 1080x1240 | Canvas default 1240x1080 (`linux/thor_mc_hud.py:56-57`) |
| `decky-plugin/package.json` | License MIT | Root `LICENSE` is CC0 1.0; `README.md:80-82` says CC0 |
| `tools/README.md` | Simulator "listens on 48291" | Binds every interface |
| `KT/ui/InventoryMoves.kt:48-51` doc comment | Lists `THROW`, `SWAP`, `CLONE`, `PICKUP_ALL` as action names | Only `PICKUP`, `QUICK_MOVE`, `QUICK_CRAFT` are used |

## Dead or unused code

Defined in the repository but never called from a reachable path (checked by searching the source):

- `HudRepository.requestAssets` and the `ASSETS` command (`KT/net/HudRepository.kt:793`).
- `HudRepository.sendSlotState` and `SLOTSTATE:` (`KT/net/HudRepository.kt:1139`).
- `HudRepository.isAssetUnavailable` has no caller in the source (`KT/net/HudRepository.kt:588`).
- `ResourcePackIconProvider.resolveActivePacks`, `searchActivePacksFor`, `parseActivePackNames` and `refresh`'s body: commented out or unreachable (`KT/net/ResourcePackIconProvider.kt:150-154`, `267-318`).
- `GameDirectoryAccess` launch: `pickFolderLauncher.launch(null)` is commented out (`KT/MainActivity.kt:84`).
- `KT/ui/theme/Theme.kt` (`AYNThorSecondScreenMinecraftDisplayTheme`) and `Type.kt`, `Color.kt`: Material template, no call site.
- `RES/values/colors.xml`: template colours.
- `ExampleUnitTest.kt` and `ExampleInstrumentedTest.kt`: template tests.

## Glossary

| Term | Meaning |
|---|---|
| Bottom screen | The Thor's second display, where the app is shown |
| Presentation | Android's API for showing a window on a secondary display (`android.app.Presentation`) |
| Mod | The Fabric mod in Minecraft that streams state and answers commands (out of tree) |
| Snapshot | The untyped HUD JSON line sent repeatedly by the mod |
| syncId | The container handler's id, echoed back with every container command as a staleness check |
| Handler | Minecraft's `ScreenHandler`; the server-side view of an open inventory |
| Cursor stack | The item held on the mouse during a move |
| Quick-craft | Vanilla's drag-to-distribute click sequence |
| Takeover | The app switching to the Items or Creative tab when a container opens |
| Extras | Container-specific choice data (offers, recipes, trades, beacon effects, anvil cost) |
| Binding id | A key binding's translation key, e.g. `key.inventory`; the stable identity for input buttons |
| Hello | The first line from the mod on each connection; its absence is used for port-conflict detection |
| Port conflict | Something other than the mod accepting on 48291, typically a leftover `adb reverse` |
| LSO | Legendary Survival Overhaul, a mod whose thirst overlay the Linux companion can draw |
| TACZ | A firearms mod whose gun state the Linux companion can draw |
| ParCool | A movement mod whose stamina the Linux companion can draw |
| Decky | Decky Loader, the Steam Game Mode plugin framework |
| Armada OS | The Thor's Linux distribution, per `linux/README.md` |
| Gamescope | The nested compositor the Linux companion's session runs under (`linux/systemd/thor-mc-hud.service`) |
