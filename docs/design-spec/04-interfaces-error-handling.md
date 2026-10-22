# 04 - Interfaces and error handling

## Public interfaces

### Android: `HudRepository` (`KT/net/HudRepository.kt:467`)

| Member | Kind | Purpose |
|---|---|---|
| `hudState`, `isConnected`, `portConflict`, `mapTile`, `bindings`, `chatMessages`, `container`, `creativeGroups` | `StateFlow` | Read by composables |
| `iconCache`, `assetCache` | `SnapshotStateMap` | Compose-observable caches |
| `start()` | Function | Starts the connection loop once (`:612-614`) |
| `requestIcon(itemId): Bitmap?` | Function | Cached bitmap or null, sends `ICON:` once per timeout window (`:620`) |
| `isAssetUnavailable(assetId)` | Function | Unused (see [06](../application-spec/06-testing-limitations-glossary.md#dead-or-unused-code)) |
| `requestAssets()`, `requestBindings()`, `requestCreative()` | Functions | `ASSETS`, `BINDINGS`, `CREATIVE` (`:793`, `823`, `834`); the first is never called |
| `sendGive(itemId, hotbarSlot?)` | Function | `GIVE:` (`:852`) |
| `sendSlotClick(syncId, slotId, button, action)` | Function | `SLOT:` (`:1111`) |
| `sendButton`, `sendRename`, `sendTrade`, `sendBeaconEffects` | Functions | Container verbs (`:1127-1137`) |
| `sendSlotState` | Function | `SLOTSTATE:` (`:1139`); never called |
| `sendChat(message)` | Function | `CHAT:`, flattens newlines (`:1150`) |
| `sendBinding(bindingId)` | Function | `BIND:` (`:1163`) |
| `sendGameHudElements(keys)` | Function | `HUD:` (`:1175`) |
| `sendCommand(code)` | Function | Raw line; drops with a log when not connected (`:1301-1313`) |

Callers reach the repository through function references (`SecondScreenApp.kt:292-348`), so a screen never names a socket detail.

### Android: `ReconnectPolicy` (`KT/net/ReconnectPolicy.kt:20`)

`delayMs(failedAttempts: Int): Long`. Pure. Tested by `ReconnectPolicyTest` (see [06](../application-spec/06-testing-limitations-glossary.md#android-jvm-unit-tests-test)).

### Android: `ContainerCommands` (`KT/net/ContainerCommands.kt:27`)

`button`, `trade`, `slotState`, `beaconEffects`, `rename` build the wire strings. `NO_EFFECT = "-"` for an absent beacon effect (`:31`).

### Android: layout and gesture pure functions

| Object | Functions | File |
|---|---|---|
| `HeartLayout` | `compute(health, maxHealth, absorption): List<List<HeartSlot>>` | `KT/ui/HeartLayout.kt:111` |
| `BubbleLayout` | `compute(air, maxAir): BubbleCounts` | `KT/ui/HeartLayout.kt:170` |
| `ChatLineWrapper` | `wrap(runs, maxWidth, advanceOf): List<List<ChatRun>>` | `KT/ui/ChatLineWrapper.kt:38` |
| `InventoryLayout` | `playerRows(state)`, `compute(state)` | `KT/ui/InventoryLayout.kt:55`, `88` |
| `ContainerLayout` | `plan(state): ContainerPlan` | `KT/ui/ContainerLayout.kt:103` |
| `InventoryMoves` | `tap`, `pickUp`, `drop`, `returnToSource`, `distribute`, `dropCursor` | `KT/ui/InventoryMoves.kt:76-155` |
| `InputSlotCodec` | `encode`, `decode(stored, defaults, slotCount)` | `KT/settings/InputSlotCodec.kt:27-45` |
| `ContainerKind` | `of(handlerType, isPlayerInventory, creativeScreen)`, `byKey` | `KT/settings/ContainerKind.kt:83-101` |

### Android: settings objects

Each is a class with `remember`-keyed factory in Compose. Methods: `HudSettings.isVisible/setVisible/toggle/resetToDefaults/isDefault`; `ChatSettings.useTheSystemKeyboard/toggleSystemKeyboard`; `ContainerSettings.opensAutomatically/setOpensAutomatically/toggle/reset`; `BackgroundSettings.choose/reset`; `InputSettings.bindingAt/setBindingAt/clearSlot/resetToDefaults` (`KT/settings/*.kt`).

### Linux: `IconRequests` and `ThorMcHud`

`IconRequests(pending_timeout, failure_backoff, clock)` with `should_request`, `answered`, `failed`, `reset` (`linux/thor_mc_hud.py:123-158`). `ThorMcHud(host, port, demo_mode)` is the window; `send_command(cmd)` appends to the queue under the lock (`:361-363`).

### Decky backend: `Plugin` (`decky-plugin/main.py`)

| Method | Returns | Effect |
|---|---|---|
| `get_status()` | `{active, connected, description}` | Reads two `systemctl` states and a port probe |
| `load_hud()` | Status | Stops trackpad units, starts `thor-mc-hud.service` |
| `unload_hud()` | Status | Stops `thor-mc-hud.service`, starts trackpad, falls back to the second unit |
| `restart_hud()` | Status | Restarts `thor-mc-hud.service` |

`load_hud`, `unload_hud` and `restart_hud` do not check the return code of their `systemctl` stop and start calls, except that `unload_hud` falls back to the second trackpad unit when starting the first fails (`main.py`, `_load`, `_unload`, `_restart`). Each then returns a fresh `get_status()`, so a failed start shows as a status that did not change.

### Tools

`hud_sim.py <client.jar> <scenario>` (`tools/hud_sim.py:999`). Scenarios are the keys of `SCENARIOS` plus one per container (`:419-527`). `capture_hud.ps1 -Jar -Scenarios [-OutDir] [-Tab] ...` (`tools/capture_hud.ps1:21-38`).

## Error handling

| Situation | Behaviour | Where |
|---|---|---|
| Socket refused or closed | Log, mark disconnected, clear player state, wait by policy, retry | `KT/net/HudRepository.kt:679-731` |
| Line is not JSON | Logged, line dropped, connection kept | `KT/net/HudRepository.kt:750-752` |
| Required field missing in a typed message | Logged, message dropped, state unchanged | Same; for example `container` without `slots` returns early (`:931`) |
| Snapshot missing a required field | Whole snapshot dropped; previous state kept | `KT/net/HudRepository.kt:1258-1290` |
| Unknown `type` | Treated as a snapshot; fails on missing keys and is logged | `:748` |
| Bitmap fails to decode | Asset or icon recorded as unavailable or failed; map logged and dropped | `:782-788`, `:1198-1200`, `:1234-1237` |
| Command while disconnected | Logged and dropped, no queue | `:1302-1304` |
| Icon request unanswered | Retried after 3 s (Android) or 5 s (Linux) | `:640` |
| Icon reported missing | Not requested again for 60 s (Android) or 5 s (Linux) | `:634-636`; `linux/thor_mc_hud.py:131-143` |
| Stale container click | Refused by the mod through `syncId` (mod side unverified) | `:1095-1110` |
| Container closes while items are held | Cursor is shown as "Holding" until the next message | `KT/ui/InventoryScreen.kt:316-320` |
| Unknown container kind or handler type | Mapped to Other, which has its own toggle | `KT/settings/ContainerKind.kt:95-96` |
| Unknown extras object | Discarded when no known kind is present | `KT/net/HudRepository.kt:972-976` |
| Persisted input id not known to the game | Button shows the id's last segment; press sends the id | `KT/ui/InputGridScreen.kt:33-34`, `:92` |
| Persisted value from a newer build (background key, container key) | Falls back to the default | `KT/settings/HudBackground.kt:47-48`, `ContainerKind.kt:101` |
| Linux JSON parse error | Line ignored | `linux/thor_mc_hud.py:431-435` |
| Linux socket error | Caught; socket closed; reconnect after 1.5 s | `:409-422` |
| Linux hotkey file invalid | Defaults used, error printed | `:104-113` |
| Decky `systemctl` failure | Result ignored in control actions; status re-read | `decky-plugin/main.py` |

## Port conflict

Detection (Android): a connection counts as silent if `sawHello` is false after the attempt. Three silent connections set `portConflict` (`KT/net/HudRepository.kt:689-698`). A hello clears it (`:893`).

Banner: "Port 48291 is taken by something else - clear a leftover 'adb reverse tcp:48291 tcp:48291'." Drawn over the whole screen at the top (`KT/SecondScreenApp.kt:360-372`).

Consequence on cold start: because a refused connection also leaves `sawHello` false, the banner appears after three refused attempts when the game has never answered this process (computed from the policy as about 3 s; **unverified** at runtime). The code's own comment describes the case as something else holding the port (`:685-688`). The two cases are not distinguished in code. Documented in [application spec 06](../application-spec/06-testing-limitations-glossary.md#port-conflict-banner-on-cold-start).

## Fallbacks

Each fallback exists so that a missing texture or an older mod changes appearance, not function.

| Need | Primary | Fallback | Last resort |
|---|---|---|---|
| HUD sprites | Socket asset | Bundled file under `assets/minecraft/...` (not tracked) | Drawn shapes (hearts, shield, drumstick, XP bar) (`KT/ui/HudScreen.kt:639-679`) |
| Font | Socket `font_ascii` | Bundled `font/ascii.png` (not tracked) | Compose text (`KT/ui/ChatScreen.kt:190-193`, `KT/ui/HudScreen.kt`, `KT/ui/InventoryScreen.kt`) |
| Panel and slot | Socket `widget_*`, `container_slot` | Drawn palette (`KT/ui/MinecraftGui.kt:264-288`, `drawVanillaPanel`, `vanillaSlot`) | Always drawn |
| Map paper | Socket `map_background` | Flat colour `#D6BE96` (`KT/ui/MapScreen.kt:53`) | |
| Backdrop | Chosen socket background | Dirt background (`KT/net/ResourcePackIconProvider.kt:194-195`), then a flat `#222222` (`KT/SecondScreenApp.kt:425-428`) | |
| Hotbar | Socket `hotbar` | Per-slot bordered boxes (`KT/ui/HotbarRow.kt:134-164`) | |
| Off-hand box | Socket `hotbar_offhand_right` | Bordered cell (`KT/ui/HotbarRow.kt:283-287`) | |
| Enchant glint | Socket `enchanted_glint_item` | No glint (`KT/ui/ItemGlint.kt:95`) | |
| Beacon effect icon | Socket `mob_effect_*` | Text button (`KT/ui/ContainerExtrasPanel.kt:504-513`) | |
| Item icon | Mod render | Placeholder text of the id (Linux) or nothing | |
| Linux textures | Client jar | Procedural shapes (`linux/thor_mc_hud.py:302`) | |
| Linux LSO overlay | PNG | Drawn droplets (`:1790`) | |

## Logging

Android uses `android.util.Log` with tags `ThorHudRepository` (`KT/net/HudRepository.kt:26`) and `ResourcePackIcons` (`KT/net/ResourcePackIconProvider.kt:14`). `ResourcePackIconProvider.loadIconFromAssets` logs a debug line each call (`:242`), which will be noisy for every missing sprite (code observation). Linux prints to stdout with `[ThorHud]` prefixes (`linux/thor_mc_hud.py:302`, `317`, and others).
