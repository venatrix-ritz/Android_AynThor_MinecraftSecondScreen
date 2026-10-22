# 01 - Purpose, users and workflows

## Purpose

Minecraft (Java Edition, run on the device through a launcher) draws its HUD on the only screen the game uses. The AYN Thor has a second, smaller display. This repository puts Minecraft's HUD on that second display and lets the player use it for touch input.

The system has two halves that share one device but run in separate processes:

1. **The mod** (Fabric, out of tree) hides the vanilla HUD elements, streams the game state as JSON lines, serves textures and item icons, and executes commands it receives. Its repository is named in `README.md:15` and `CLAUDE.md:9`. **Unverified in this tree:** every mod-side behaviour in this spec.
2. **The client** in this repository. Its only job is to show the state and send commands back. There are two clients: the Android app (`KT/`) and the Linux companion (`linux/thor_mc_hud.py`). The Decky plugin (`decky-plugin/`) only starts and stops the Linux companion.

Developer tools (`tools/`) act as a fake mod so the clients can be exercised without Minecraft. They are not part of the shipped product.

## People

| Role | Who | What they touch |
|---|---|---|
| Developer and sole maintainer | Ven (per `CLAUDE.md` and `README.md`) | All code, the device, the mod |
| Player | The person holding the Thor | Uses the second screen during play; can switch elements, containers and chat keyboard in Settings |
| Developer using the simulator | Same maintainer | `tools/hud_sim.py`, `tools/capture_hud.ps1`, `adb reverse` |

No other users, accounts, or servers are involved. The socket is loopback only and no network permission is used beyond the manifest's `INTERNET` entry (see [03](03-devices-platforms.md)).

## Workflows

Each workflow names the entry point and the code that handles it. Steps are what the code does; how the player perceives them is in [02](02-features.md).

### W1. Launch the app before or after the game

1. The Android activity starts and begins a reconnect loop to `127.0.0.1:48291` (`KT/net/HudRepository.kt:612-614`, `652-733`).
2. If the game is not running, connections are refused and retried on the schedule in [design-spec/02](../design-spec/02-protocols-algorithms.md#reconnect-policy).
3. The bottom display stays blank until the mod sends its `hello` line, after which the HUD appears. A connection that never gets a hello counts toward a port-conflict banner; see [06](06-testing-limitations-glossary.md#port-conflict-banner-on-cold-start).

### W2. Play with the HUD tab

1. The status stack (armor, breath, hearts, hunger, XP, hotbar, off-hand) is always on top (`KT/SecondScreenApp.kt:253-272`).
2. The panel below shows the live minimap (`KT/ui/MapScreen.kt`).
3. Tapping a hotbar slot sends its 1-based number as a bare line (`KT/ui/HudScreen.kt:277`).
4. Tapping the off-hand box sends `SWAP` (`KT/ui/HudScreen.kt:281`).

### W3. Open a container and move items

1. The mod sends a `container` message when a handler opens (`KT/net/HudRepository.kt:930-978`).
2. If the container kind is set to open automatically (default on, `KT/settings/ContainerSettings.kt:40`), the app switches to the Items tab (or Creative for the creative inventory) and remembers the previous tab (`KT/SecondScreenApp.kt:178-197`).
3. Taps and drags become `SLOT:` clicks (`KT/ui/InventoryMoves.kt`). Nothing is drawn optimistically; the next `container` message is what the player sees.
4. Closing the container restores the previous tab, unless the player chose a tab by hand while it was open (`KT/SecondScreenApp.kt:148`, `402-406`).

### W4. Use a container's own controls

Enchanting offers, stonecutter recipes, loom patterns, villager trades, beacon effects and anvil rename appear in a band above the grid (`KT/ui/ContainerExtrasPanel.kt:87-154`). Each sends its own command verb (`BUTTON:`, `TRADE:`, `BEACON:`, `RENAME:`); see [04](04-protocol-reference.md).

### W5. Chat

1. The Chat tab shows the backlog the mod replays on connect and live lines (`KT/net/HudRepository.kt:862-881`).
2. Typing uses the app's own keyboard (default) or the Android IME when the chat setting is on (`KT/settings/ChatSettings.kt:34`).
3. Send emits `CHAT:<message>` with newlines flattened (`KT/net/HudRepository.kt:1150-1154`).

### W6. Press game keys from the Input tab

1. Nine buttons each hold a key-binding id chosen in Settings (`KT/settings/InputSettings.kt`).
2. Pressing one sends `BIND:<id>` (`KT/net/HudRepository.kt:1163`).
3. The Settings tab requests the binding list each time its picker opens (`KT/ui/SettingsScreen.kt:109-111`).

### W7. Decide what the bottom screen shows

Settings switches each HUD element off or on, and each container kind. Elements switched off are sent back to the game as `HUD:` (`KT/SecondScreenApp.kt:215-219`) so the top screen draws them instead.

### W8. Creative mode

In creative, an extra Creative tab appears (`KT/SecondScreenApp.kt:129-138`). It requests the item catalogue once per connection (`KT/net/HudRepository.kt:834-838`). Items are given with `GIVE:`, either tapped (first free slot) or dragged onto a hotbar slot.

### W9. Use the Linux companion instead of the Android app

The Linux companion runs under a Wayland/X session as a systemd user service (`linux/systemd/thor-mc-hud.service`). It draws the HUD with GTK and Cairo, sends the same digit and command strings, and has its own hotkey bar. Its feature set differs from the Android app; see [02](02-features.md#linux-companion) and [03](03-devices-platforms.md#linux-companion).

### W10. Start and stop the Linux companion from Game Mode

The Decky plugin's UI (`decky-plugin/dist/index.js`) calls the backend methods `get_status`, `load_hud`, `unload_hud` and `restart_hud` every 2 seconds while it is open. The backend (`decky-plugin/main.py`) starts or stops `thor-mc-hud.service` and the trackpad service through `systemctl`. See [03](03-devices-platforms.md#decky-plugin).

### W11. Drive the app without Minecraft

1. Run `tools/hud_sim.py` with the client jar and a scenario name.
2. Run `adb reverse tcp:48291 tcp:48291` (`tools/README.md`).
3. `tools/capture_hud.ps1` runs scenarios, switches tabs with `adb input`, and pulls screenshots.

Nothing in this workflow was run for this spec.
