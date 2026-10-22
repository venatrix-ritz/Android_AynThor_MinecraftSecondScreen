# 02 - Features

What each screen offers, as implemented. The rationale for each choice is in [design-spec/05](../design-spec/05-constraints-decisions.md). Android is described first; the Linux companion follows.

## Android app

### Shell and tabs

The screen is a column: the status stack on top, a panel filling the rest, and a 44 dp tab strip at the bottom (`KT/SecondScreenApp.kt:59`, `374-412`). Tabs are generated from the `SecondScreenTab` enum (`KT/SecondScreenApp.kt:76-107`):

| Tab | Shows | Status stack visible | Visible when |
|---|---|---|---|
| HUD | Live minimap on paper background | Yes | Always |
| Chat | Chat log and draft line | Yes | Always |
| Items | Open container or player inventory | No | Always |
| Creative | Item picker, search, hotbar drop target | No | Only in creative game mode |
| Input | 3x3 grid of key-binding buttons | Yes | Always |
| Settings | Element, container, chat, background and input settings | Yes | Always |

If the selected tab disappears (leaving creative), the app returns to HUD (`KT/SecondScreenApp.kt:136-138`).

### Status stack

Elements, each separately switchable (`KT/settings/HudSettings.kt:31-47`):

| Key | Label | Content |
|---|---|---|
| `armor` | Armor | Armor sockets, drawn only when armor is above zero; the row keeps its height (`KT/ui/HudScreen.kt:170-190`) |
| `bubbles` | Breath | Breath bubbles, only while underwater (`air < maxAir`) |
| `hearts` | Health | Hearts with absorption rows (`KT/ui/HeartLayout.kt:111-152`) |
| `hunger` | Hunger | Drumsticks |
| `xp_bar` | XP bar | Progress bar and level number |
| `hotbar` | Hotbar | Nine slots with count, durability, glint and selection |
| `offhand` | Off-hand slot | Swap-hands box; never returned to the top screen |

Survival-style rules apply: hearts, hunger, armor, breath and XP are hidden in creative and spectator modes; the hotbar is hidden in spectator (`KT/net/HudRepository.kt:398-453`, `KT/ui/HudScreen.kt:138-143`, `266`). A missing game mode is treated as survival.

### Minimap (HUD tab)

- Scaled tile of the mod's map colours, on vanilla's paper sheet (`KT/ui/MapScreen.kt:43-168`).
- Player marker rotated by yaw, placed from the tile's own origin.
- Placeholder text: "Not connected", "Waiting for map data...", "Join a world to see the map" (`KT/ui/MapScreen.kt:89-102`).

### Chat (Chat tab)

- Log with per-run colour, word wrap at the panel width (`KT/ui/ChatLineWrapper.kt`).
- Newest message at the bottom; the log follows new messages until the player scrolls away (`KT/ui/ChatScreen.kt:246-261`).
- Draft line; tapping it opens the app keyboard (`KT/ui/ChatKeyboard.kt`): letters, shift, backspace, send, a `?123` symbol layer with `/ ~ ^ @ : {}`.
- Setting "Use the Android keyboard" swaps in a platform text field with IME Send (`KT/ui/ChatScreen.kt:381-448`, `KT/settings/ChatSettings.kt`). Default off.
- Non-ASCII text outside 0x00-0xFF renders as `?` (font limitation, `KT/ui/MinecraftFont.kt:69-70`).

### Items (Items tab)

- Open handler drawn on the game's container panel (`KT/ui/InventoryScreen.kt:123-270`).
- Mode buttons: **Stack**, **Half**, **Single**, **Move** (`KT/ui/InventoryMoves.kt:12-25`). Tap and drag use the mode (`KT/ui/InventoryMoves.kt:76-120`).
- Hold still for 300 ms, then drag, to distribute the held stack over painted slots (`KT/ui/InventoryScreen.kt:95`, `488-511`).
- Drop button (slot -999) when holding; "Holding" readout in the control bar.
- Container placement follows the game's slot coordinates (`KT/ui/ContainerLayout.kt`). Furnace and brewing progress art is drawn for those two families only (`KT/ui/ContainerDecorations.kt:192-227`).
- Creative inventory opened by the player shows a placeholder pointing to the Creative tab (`KT/ui/InventoryScreen.kt:157-165`).

### Container extras (band above the grid)

| Container | Control | Command |
|---|---|---|
| Enchanting table | Three offers with cost and lapis; locked rows disabled | `BUTTON:<sync>,<row>` |
| Stonecutter | Recipe icons, filmstrip | `BUTTON:<sync>,<index>` |
| Loom | Pattern names only | `BUTTON:<sync>,<index>` |
| Villager | Trade rail, stacked down the right | `TRADE:<sync>,<index>` |
| Beacon | Primary and secondary effect icons, Apply | `BEACON:<sync>,<primary>,<secondary>` |
| Anvil | Name field, price, Name/Done toggle | `RENAME:<sync>,<name>` |

Locked or unavailable rows are disabled rather than omitted (`KT/ui/ContainerExtrasPanel.kt:170`, `396-400`). Labels are ASCII only.

### Creative (Creative tab)

- Tabs for each catalogue group; search across all groups by item id (`KT/ui/CreativeScreen.kt:67-77`, `133-140`).
- Tap gives the item to the first free hotbar slot (`GIVE:<id>`); long-press then drag onto a hotbar cell gives it to that slot (`GIVE:<id>,<0-8>`) (`KT/ui/CreativeScreen.kt:121-130`).
- Catalogue requested once per connection (`KT/net/HudRepository.kt:834-838`).

### Input (Input tab)

- Nine fixed buttons (3x3). Each shows a binding label from the mod's list; empty slots show a disabled "Empty" button (`KT/ui/InputGridScreen.kt:58-100`).
- Defaults on first run: inventory, drop, swap offhand, use, attack, jump, sneak; slots 8 and 9 empty (`KT/settings/InputSettings.kt:29-33`).
- Changing a button opens a picker that replaces the Settings panel and groups bindings by the mod-supplied category (`KT/ui/SettingsScreen.kt:422-488`).

### Settings (Settings tab)

- Show-on-this-screen toggles for each HUD element, with reset (`KT/ui/SettingsScreen.kt:127-163`).
- Background: nine block textures, shown as swatches (`KT/settings/HudBackground.kt:26-35`).
- Automatic Items-tab takeover per container kind, 17 kinds (`KT/settings/ContainerKind.kt:23-71`).
- Chat keyboard choice.
- Input button assignments.

### Port-conflict banner

When the app decides something other than the mod holds the port, the top of the screen shows: "Port 48291 is taken by something else - clear a leftover 'adb reverse tcp:48291 tcp:48291'." (`KT/SecondScreenApp.kt:354-372`). The detection rule and its cold-start behaviour are in [design-spec/04](../design-spec/04-interfaces-error-handling.md#port-conflict) and [06](06-testing-limitations-glossary.md#port-conflict-banner-on-cold-start).

### Persistence

Settings survive restarts through `SharedPreferences` keys listed in [05](05-configuration-requirements.md#persisted-preferences-android). Connection state, HUD snapshot, container, map, icons and chat do not persist.

## Linux companion

`linux/thor_mc_hud.py` (2,161 lines) is a single GTK window at 1240x1080 (`linux/thor_mc_hud.py:56-57`, `225`). It implements these features:

- **Roaming screen:** header with status and a hotkey settings button; target inspector banner; 480x480 minimap with an armor-durability list and an effects list on the left; gun, survival (thirst, temperature, stamina) and level panels on the right; vitals (hearts, absorption, armor, food, thirst droplets, air bubbles) and XP bar above a 9-slot hotbar with an off-hand box; a bottom bar of six customisable hotkeys (`linux/thor_mc_hud.py:1116-1146`).
- **Container screen:** title header with hotkey settings and close buttons; toolbar with a Quick-Move toggle, a SORT INV button and any `widgets` the mod reports; slot grid for container, player inventory, hotbar and armor/crafting; a cursor badge with Drop; vitals bar; bottom bar with CLOSE (`linux/thor_mc_hud.py:707-942`).
- **Hotkey settings modal:** six slots, 14 presets, reset, saved to `~/.config/thor-mc-hud/hotkeys.json` (`linux/thor_mc_hud.py:45-86`, `1945-2081`).
- **Demo mode:** `--demo` fills the HUD with fixed data and no socket (`linux/thor_mc_hud.py:2083-2143`).
- **Touch and mouse:** a press or touch at a hit-tested rectangle sends the matching command; a 120 ms debounce drops duplicate events (`linux/thor_mc_hud.py:573-678`).

Differences from the Android app are listed in [03](03-devices-platforms.md#linux-companion-versus-android-app) and [06](06-testing-limitations-glossary.md#documentation-and-code-drift).

## Decky plugin

A Steam Game Mode quick-menu panel with a status line, a Load or Unload button, and a Restart button. The status line reads "Running (Connected to MC)", "Running (Waiting for MC)", or "Stopped (Trackpad Active)" (`decky-plugin/dist/index.js`, `Content`). Details are in [03](03-devices-platforms.md#decky-plugin).
