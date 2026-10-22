# 01 - Architecture and modules

## Process layout

```
+-------------------------- AYN Thor -----------------------------+
|                                                                 |
|  Minecraft (mod inside)  --- 127.0.0.1:48291 --- Android app    |  primary screen runs game
|  [out of tree]                                   (KT/)         |  second screen shows HUD
|                                                                 |
|  or: Minecraft --- 127.0.0.1:48291 --- Linux companion          |
|                                        (linux/thor_mc_hud.py)   |
|                                                                 |
|  Decky loader --- decky-plugin/main.py --- systemctl            |
+-----------------------------------------------------------------+
```

The two clients are alternatives on the same socket. The Decky plugin starts and stops the Linux companion and the trackpad services; it never reads or writes the socket beyond a TCP connect probe (`decky-plugin/main.py`, `_is_mc_port_open`).

The Android app and the mod are separate processes in separate sandboxes. The socket is the only channel between them (`README.md:34-38`).

## Android app modules

All paths are under `KT/`. The package root is `com.exojosh.minecraftsecondscreen`.

| Module | Files | Responsibility |
|---|---|---|
| Entry | `MainActivity.kt` | Owns the one `HudRepository`. Finds the presentation display and shows the `Presentation`. On a single display, renders the UI on the primary screen. Its window is translucent. |
| Window | `SecondScreenPresentation.kt` | `Presentation` hosting one `ComposeView`; wires the activity's lifecycle, view-model and saved-state owners into it. |
| Shell | `SecondScreenApp.kt` | Tab enum, tab strip, container takeover, HUD-element handoff, port-conflict banner, backdrop. |
| Network | `net/HudRepository.kt` | Connection loop, parsing, all flows (`hudState`, `isConnected`, `portConflict`, `mapTile`, `bindings`, `chatMessages`, `container`, `creativeGroups`), icon and asset caches, every outbound command. |
| Network | `net/ReconnectPolicy.kt` | Pure back-off schedule. |
| Network | `net/ContainerCommands.kt` | Pure string builders for container verbs. |
| Network | `net/ResourcePackIconProvider.kt` | `HudIcon` enum (asset keys and legacy candidate names), texture lookup, font sheet, backdrops, sprite-by-id and effect-icon lookups, legacy pack parsing (inactive). |
| Network | `net/GameDirectoryAccess.kt` | Persisted SAF tree URI. Launch never happens (see [06 roadmap](06-roadmap.md)). |
| Settings | `settings/HudSettings.kt`, `ChatSettings.kt`, `ContainerSettings.kt`, `BackgroundSettings.kt`, `InputSettings.kt` | `SharedPreferences`-backed state, mirrored into Compose state maps or lists. |
| Settings (pure) | `settings/InputSlotCodec.kt`, `ContainerKind.kt`, `HudBackground.kt` | Codec and enums with no Android types. |
| UI, pure logic | `ui/HeartLayout.kt`, `ui/ChatLineWrapper.kt`, `ui/InventoryLayout.kt`, `ui/InventoryMoves.kt`, `ui/ContainerLayout.kt` | Layout and gesture-sequence maths, no Compose or Android types. Unit tested. |
| UI, screens | `ui/HudScreen.kt`, `HotbarRow.kt`, `MapScreen.kt`, `ChatScreen.kt`, `ChatKeyboard.kt`, `InventoryScreen.kt`, `CreativeScreen.kt`, `InputGridScreen.kt`, `SettingsScreen.kt`, `ContainerExtrasPanel.kt` | Composables for each tab and panel. |
| UI, drawing | `ui/ContainerDecorations.kt`, `ItemStackGraphic.kt`, `ItemGlint.kt`, `MinecraftFont.kt`, `MinecraftGui.kt` | Vanilla-style drawing: progress art, item decorations, glint, bitmap font, GUI chrome. |
| Theme | `ui/theme/*.kt` | Material defaults. Not used by any composable on the active path (see [06 roadmap](06-roadmap.md)). |

### Dependency rule

The pure modules (`HeartLayout`, `ChatLineWrapper`, `InventoryLayout`, `InventoryMoves`, `ContainerLayout`, `ContainerCommands`, `ReconnectPolicy`, `InputSlotCodec`, `ContainerKind`) use no Android or Compose types in their own code. Three of them take data types declared elsewhere: `HeartLayout` uses the `HudIcon` enum (declared in `net/ResourcePackIconProvider.kt`), and `InventoryLayout` and `ContainerLayout` use `ContainerState` (declared in `net/HudRepository.kt`). Those declaring files import Android classes, so the pure files are not free of the Android dependency at the file level, only at the type level (checked by reading the imports). The file headers say why the split exists: a wrong result there fails silently and is only catchable by a JVM test (`KT/ui/HeartLayout.kt`, `KT/ui/InventoryMoves.kt` headers).

Composables depend on the pure modules, never the reverse.

## State ownership

| State | Owner | Lifetime | Survives disconnect |
|---|---|---|---|
| Socket, writer, reconnect counters | `HudRepository` | App process | Yes (counters reset on hello) |
| `hudState`, `mapTile`, `container`, `chatMessages` | `HudRepository` | Until disconnect or noplayer | No (cleared, `KT/net/HudRepository.kt:700-707`, `910-921`) |
| `bindings`, `assetCache`, `iconCache`, `creativeGroups` | `HudRepository` | App process | Yes (deliberately, `KT/net/HudRepository.kt:509-515`, `541-552`, `578-581`) |
| `pendingIconRequests`, `failedIconRequests` | `HudRepository` | Until disconnect or noplayer | No |
| Tab selection, container memory | `SecondScreenApp` (`remember`) | Composition | Yes across disconnects; reset by `SecondScreenPresentation` recreation |
| Chat draft | `ChatDraftState`, hoisted to `SecondScreenApp` | Composition | Yes across tab switches (`KT/ui/ChatScreen.kt:67-73`) |
| Move mode, drag, painted slots | `InventoryScreen` `remember` | Composition | No |
| Settings | `HudSettings`, `ChatSettings`, `ContainerSettings`, `BackgroundSettings`, `InputSettings` | Preferences | Yes (persisted) |

`HudRepository` is one instance for the process (`KT/MainActivity.kt:78`). Compose reads its `StateFlow`s with `collectAsState`; the `iconCache` and `assetCache` are `mutableStateMapOf` so a late arrival recomposes only what reads it (`KT/net/HudRepository.kt:562`, `581`).

## Linux companion modules

One file, `linux/thor_mc_hud.py`, with these classes and functions:

| Name | Role |
|---|---|
| `IconRequests` | Pending and failed icon request timers (`linux/thor_mc_hud.py:123-158`). Pure and tested. |
| `HotbarSlot`, `HudState` | Plain data (`:160-201`). |
| `draw_sprite` | Nearest-neighbour sub-rectangle drawing (`:203-219`). |
| `ThorMcHud(Gtk.Window)` | Window, network thread, parser, input handling, all drawing (`:221-1941`). |
| `load_hotkeys`, `save_hotkeys` | JSON persistence (`:104-121`). |
| `main` | Argument parsing and GTK main loop (`:2149-2158`). |

Threads: one GLib timer at 33 ms calls `queue_draw` (`:289`); one daemon thread runs `network_loop` (`:285`). They share state through `self.lock`. Drawing reads the state under the lock (`:690-693`).

## Android threading

- Main thread: Compose composition and drawing.
- `HudRepository.scope` (the activity's `lifecycleScope`) launches `connectionLoop` on `Dispatchers.IO` (`KT/net/HudRepository.kt:612-614`).
- Each `sendCommand` launches its own coroutine on `Dispatchers.IO` (`KT/net/HudRepository.kt:1306`).
- Inbound parsing runs on the reader in `connectionLoop`, so `parseAndPublish` executes on the IO thread. `StateFlow` updates are thread-safe; `requestIcon` is called from composition and uses `ConcurrentHashMap` for its timers (`KT/net/HudRepository.kt:602-607`).

## Linux and Decky modules

- `decky-plugin/main.py`: `Plugin` with four async methods, each running blocking `systemctl` calls through `asyncio.to_thread` (`main.py`, `get_status`, `load_hud`, `unload_hud`, `restart_hud`).
- `decky-plugin/dist/index.js`: frontend; one `Content` component polling `get_status` every 2 s.
- `tools/hud_sim.py`: a single file. Asset and creative catalogues are loaded from the client jar at start-up; each connection gets its own thread, and a reader thread consumes commands while the main loop streams the snapshot (`tools/hud_sim.py:845-996`).
- `tools/capture_hud.ps1`: sequential driver for scenarios.
