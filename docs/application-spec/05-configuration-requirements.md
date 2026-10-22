# 05 - Configuration and requirements

## Persisted preferences (Android)

All stored in `SharedPreferences` in private mode. No DataStore, no database.

| File (preference name) | Key | Type and default | Code |
|---|---|---|---|
| `hud_layout` | Each `HudElement.key`: `armor`, `bubbles`, `hearts`, `hunger`, `xp_bar`, `hotbar`, `offhand` | Boolean, default true | `KT/settings/HudSettings.kt:9`, `31-47` |
| `chat_settings` | `system_keyboard` | Boolean, default false | `KT/settings/ChatSettings.kt:11-12` |
| `container_settings` | Each `ContainerKind.key` (17 kinds) | Boolean, default true | `KT/settings/ContainerSettings.kt:9`, `ContainerKind.kt:23-71` |
| `background_settings` | `background` | One of nine keys; default `dirt` | `KT/settings/BackgroundSettings.kt:11-12`, `HudBackground.kt` |
| `input_layout` | `slots` | One comma-joined string of nine binding ids; empty field is an empty slot; missing string means defaults | `KT/settings/InputSettings.kt:9-13`, `InputSlotCodec.kt:23-46` |
| `thorhud_prefs` | `game_dir_uri` | String URI; written only by `GameDirectoryAccess.persist`, which is never reached | `KT/net/GameDirectoryAccess.kt:8-9` |

Keys must not be renamed: `HudElement.key` is both the stored key and the wire name (`KT/settings/HudSettings.kt:26-29`); `ContainerKind.key` is stored (`ContainerKind.kt:17-20`).

Default input grid: `key.inventory`, `key.drop`, `key.swapOffhand`, `key.use`, `key.attack`, `key.jump`, `key.sneak`, empty, empty (`KT/settings/InputSettings.kt:29-33`).

## Linux configuration

| Item | Value | Source |
|---|---|---|
| Hotkeys file | `~/.config/thor-mc-hud/hotkeys.json` | `linux/thor_mc_hud.py:59-60` |
| Hotkeys format | JSON list of six objects `{label, cmd}`; a file that is not a list of six falls back to defaults | `linux/thor_mc_hud.py:104-121` |
| Default hotkeys | INV [E] (`E`), DROP [Q] (`Q`), SWAP [F] (`F`), SORT (`SORT`), MAP [M] (`M`), PAUSE (`ESCAPE`) | `linux/thor_mc_hud.py:62-69` |
| Connection | `--host` (default `127.0.0.1`), `--port` (default 48291), `--demo` | `linux/thor_mc_hud.py:2149-2154` |
| LSO overlay search | `~/.local/share/thor-mc-hud/assets/lso_overlay.png`, then `assets/lso_overlay.png` relative to the working directory, then mod-jar globs | `linux/thor_mc_hud.py:95-102` |
| Client jar search | Glob patterns, with a 1.20.1 path listed first; the last sorted match of the first pattern that matches is used | `linux/thor_mc_hud.py:88-93`, `294-297` |

The search lists contain paths under a specific user's home directory. They are not reproduced here; `linux/thor_mc_hud.py:88-102` is the source.

## Tools configuration

| Tool | Setting | Default |
|---|---|---|
| `hud_sim.py` | Argument 1 client jar, argument 2 scenario | Scenario `absorption` (`tools/hud_sim.py:1001`) |
| `capture_hud.ps1` | `-Jar`, `-Scenarios`, `-OutDir`, `-Adb`, `-DisplayId`, `-LogicalDisplayId`, `-Tab`, `-Package` | Logical display 4, tab HUD, output `.` (`tools/capture_hud.ps1:21-38`) |
| `decky-plugin/deploy.sh` | Argument 1 device host | A hardcoded LAN address (not reproduced here) |

## Build configuration

- Single module `:app` (`settings.gradle.kts:23`), no product flavours, one `release` build type with `optimization.enable = false` (`app/build.gradle.kts:24-30`).
- Compose enabled (`app/build.gradle.kts:35-37`).
- Java source and target 11 (`app/build.gradle.kts:31-34`); the toolchain for the Gradle daemon is 21 (`gradle/gradle-daemon-jvm.properties`).
- Dependencies: Compose BOM, Material 3, Activity Compose, core KTX, lifecycle runtime, `androidx.documentfile` 1.0.1; test: JUnit 4.13.2; instrumented: Compose UI test, Espresso 3.5.1, AndroidX JUnit (`app/build.gradle.kts:40-57`, `gradle/libs.versions.toml`).
- `gradle.properties`: `-Xmx2048m`, configuration cache on.
- ProGuard/R8 rules file `app/src/main/keepRules/rules.keep` contains only comments (template).
- Gitignored: `app/src/main/assets/minecraft*/` (extracted game textures; legacy fallback), `tools/*.jar`, `local.properties`, build outputs.

Nothing above was built or executed for this spec.

## Runtime requirements

| Requirement | Level | Basis |
|---|---|---|
| Mod listening on `127.0.0.1:48291` | Required for any data | Protocol, [04](04-protocol-reference.md) |
| Second display exposed as a presentation or secondary display | Required for the normal UI | `KT/MainActivity.kt:170-177` |
| Android 7.0 or later | Required | minSdk 24 |
| Loopback socket permitted | Required | `INTERNET` permission declared |
| Font sheet, background and GUI sprites from the mod | Recommended | Fallbacks exist for the background and font; see [design-spec/04](../design-spec/04-interfaces-error-handling.md#fallbacks) |
| Client jar on disk (Linux companion) | Optional | Procedural drawing otherwise (`linux/thor_mc_hud.py:302`) |
| GTK 3, PyGObject, Cairo, X11 session (Linux) | Required for the Linux companion | `linux/thor_mc_hud.py:45-50` |
| Decky Loader with root plugins (Decky) | Required for the plugin | `decky-plugin/plugin.json:4` |

## Requirements

Derived from the code. Each requirement names the code that enforces it. "Must" means the code enforces it; "should" means a comment or doc states it and the code only partly does.

### Functional

| ID | Requirement | Where |
|---|---|---|
| FR-1 | Connect to `127.0.0.1:48291` and reconnect with the back-off in [design-spec/02](../design-spec/02-protocols-algorithms.md#reconnect-policy). | `KT/net/HudRepository.kt:652-733` |
| FR-2 | Show the HUD only from received snapshots; show waiting text otherwise. | `KT/ui/HudScreen.kt:103-112` |
| FR-3 | When the player leaves the world, clear player-shaped state and keep the connection. | `KT/net/HudRepository.kt:910-921` |
| FR-4 | Hide a status element when its setting is off, and send the off-set to the mod. | `KT/SecondScreenApp.kt:215-219` |
| FR-5 | Send every container interaction as a command with the open `syncId`. | `KT/net/HudRepository.kt:1111-1140` |
| FR-6 | Do not draw optimistically; redraw from the next `container` message. | `KT/ui/InventoryScreen.kt:107-109` |
| FR-7 | Switch to Items or Creative when a container opens if its kind allows it, and restore on close. | `KT/SecondScreenApp.kt:178-197` |
| FR-8 | Keep the Creative tab only in creative mode. | `KT/SecondScreenApp.kt:133-138` |
| FR-9 | Store every setting across restarts. | [Persisted preferences](#persisted-preferences-android) |
| FR-10 | Flatten newlines in outbound chat and rename text. | `KT/net/HudRepository.kt:1151`; `KT/net/ContainerCommands.kt:56` |
| FR-11 | Retry an unanswered icon request after 3 s; back off 60 s after an explicit "no icon". | `KT/net/HudRepository.kt:620-650` |
| FR-12 | Show the port-conflict banner when the detection rule fires. | `KT/SecondScreenApp.kt:360-372` |

### Non-functional

| ID | Requirement | Where |
|---|---|---|
| NFR-1 | Core parsing and layout logic is free of Android and Compose types so it can be unit tested. | `KT/ui/HeartLayout.kt`, `KT/ui/ChatLineWrapper.kt`, `KT/ui/InventoryLayout.kt`, `KT/ui/InventoryMoves.kt`, `KT/ui/ContainerLayout.kt`, `KT/net/ContainerCommands.kt`, `KT/net/ReconnectPolicy.kt`, `KT/settings/InputSlotCodec.kt`, `KT/settings/ContainerKind.kt` |
| NFR-2 | The app needs no game assets on disk. | `CLAUDE.md` ("HUD textures come from the mod") |
| NFR-3 | Pixel art is drawn with nearest-neighbour filtering, except the enchant glint. | `KT/ui/MinecraftGui.kt`, `KT/ui/ItemGlint.kt:110-120` |
| NFR-4 | Memory of icons is bounded to 200 entries. | `KT/net/HudRepository.kt:32`, `1241-1256` |
| NFR-5 | Chat history is bounded to 100 messages. | `KT/net/HudRepository.kt:44`, `880` |
| NFR-6 | The app connects only over loopback. | `KT/net/HudRepository.kt:27` |
| NFR-7 | Linux companion keeps working with no client jar present. | `linux/thor_mc_hud.py:302` |
| NFR-8 | Layout works at the target panel's width without horizontal scrolling of the main panel. | `KT/SecondScreenApp.kt` (`fillMaxSize`/`weight`); `KT/ui/InventoryScreen.kt:246-266` |

## Data rules

- Icons and textures are PNG (base64) from the mod. The Android decoder uses `inScaled = false` for pixel art (`KT/net/HudRepository.kt:775-780`).
- Durability fraction is `1 - damage/maxDamage`, or null when `maxDamage <= 0` (`KT/net/HudRepository.kt:64-65`).
- Item ids are namespaced strings; `minecraft:air` is empty.
- No personal data is stored or sent. The only outbound destinations are the loopback socket and, for the Decky plugin, `systemctl` on the device.
