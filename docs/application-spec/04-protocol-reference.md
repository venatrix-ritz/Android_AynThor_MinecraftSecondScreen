# 04 - Socket protocol reference

Everything the clients send and receive over the loopback socket, as this repository reads and writes it. The mod's side is **unverified** here unless the same fact is visible in the clients' parsing code.

## Transport and framing

| Item | Value | Source |
|---|---|---|
| Address | `127.0.0.1` | `KT/net/HudRepository.kt:27`; `linux/thor_mc_hud.py:52` |
| Port | 48291 | `KT/net/HudRepository.kt:28`; `linux/thor_mc_hud.py:53`; `tools/hud_sim.py:22` |
| Protocol | TCP stream | |
| Encoding | UTF-8 | `KT/net/HudRepository.kt:735`; `linux/thor_mc_hud.py:380` |
| Framing | One message per line, `\n`-terminated | |
| Inbound body | JSON object per line (android uses `JSONObject(line)`; Linux uses `json.loads`) | |
| Outbound body | Plain text, one command per line, no JSON | `KT/net/HudRepository.kt:1301-1313` |
| Multiple connections | The simulator serves each connection in a thread; the real mod is out of tree | `tools/hud_sim.py:1032-1037` |

Inbound messages with a `type` field are routed by that field. A message with no `type` is the HUD snapshot. An unknown `type` is also routed to the snapshot parser (`KT/net/HudRepository.kt:738-748`), where it fails on missing keys and is logged, not thrown.

Parse failures are caught per line and logged (`KT/net/HudRepository.kt:750-752`). A line that cannot be parsed never ends the connection.

## Inbound: mod to client

### Connection handshake

| Message | Shape | Handling |
|---|---|---|
| hello | `{"type":"hello","mod":<string>,"protocol":<int>}` | Sets `sawHello`, clears the silent-connection count and any port-conflict flag, and resets the reconnect counter (`KT/net/HudRepository.kt:888-894`). Android only; Linux does not read it. |
| asset | `{"type":"asset","assetId":<key>,"data":<base64 PNG>\|null}` | Stored in `assetCache` (`KT/net/HudRepository.kt:764-789`). `data` null or undecodable marks the key unavailable. |
| bindings | `{"type":"bindings","bindings":[{id,label,category,boundKey,unbound}]}` | Replaces the whole list (`KT/net/HudRepository.kt:802-819`). `label` falls back to `id`. |
| chat | `{"type":"chat","segments":[{"text":<string>,"color":<int>?}]}` | One line appended; history capped at 100 (`KT/net/HudRepository.kt:862-881`). An empty segment list is ignored. |

Asset keys the Android app requests, from `HudIcon.assetKey` (`KT/net/ResourcePackIconProvider.kt:50-115`) and `HudBackground.assetKey` (`KT/settings/HudBackground.kt:27-34`), are the wire contract with the mod's `HudAssetCatalog`. They are:

`heart_full`, `heart_half`, `heart_container`, `heart_absorbing_full`, `heart_absorbing_half`, the `heart_poisoned_*`, `heart_withered_*`, `heart_frozen_*`, `heart_hardcore_*`, `heart_poisoned_hardcore_*`, `heart_withered_hardcore_*`, `heart_frozen_hardcore_*`, `heart_container_hardcore`, `heart_absorbing_hardcore_*` families, `armor_full`, `armor_half`, `armor_empty`, `food_full`, `food_half`, `food_empty`, `xp_bar_background`, `xp_bar_progress`, `air`, `air_bursting`, `hotbar`, `hotbar_selection`, `hotbar_offhand_right`, `enchanted_glint_item`, `widget_button`, `widget_button_highlighted`, `widget_button_disabled`, `container_slot`, `font_ascii`, `background`, `map_background`; backgrounds `background_stone`, `background_cobblestone`, `background_mossy_cobblestone`, `background_stone_bricks`, `background_bricks`, `background_sand`, `background_oak_planks`, `background_dark_oak_planks`; sprite keys `container_slot_*`, `container_*` cooker and brewing progress, `container_crafter_disabled_slot`; and `mob_effect_<effect>` for the six beacon effects. The sprite lookup converts a sprite id to a key by replacing `/` with `_` after the namespace (`KT/net/ResourcePackIconProvider.kt:218-222`). The mod's catalog is **unverified** here.

### Snapshot (no type)

Sent repeatedly, one object per message. Required fields (Android uses `getInt`/`getDouble`, so a missing one fails the whole message, `KT/net/HudRepository.kt:1258-1290`):

`health` (float), `maxHealth` (float), `food` (int), `xpLevel` (int), `xpProgress` (float), `armor` (int), `selectedSlot` (int), `hotbar` (array of slot objects).

Optional fields, defaulted when absent: `absorption` (0), `air` (0), `maxAir` (0), `gameMode` (null; a null game mode is treated as survival), `heartType` ("NORMAL"), `hardcore` (false), `offhand` (null hides the off-hand box).

A slot object is `{itemId, count, damage, maxDamage, hasGlint}` (`KT/net/HudRepository.kt:1292-1298`). `itemId` `minecraft:air` is an empty slot.

Linux reads, in addition or instead: `armorToughness`, `saturation`, `exhaustion`, `poisoned`, `withered`, `gun`, `survival`, `target`, `effects`, `armorSlots` (`linux/thor_mc_hud.py:507-546`). None of these is read by Android. Their shapes are **unverified** here; the Linux code reads these keys from the object:

- `gun`: `active`, `gunName`, `ammo`, `maxAmmo`, `fireMode`, `reserveAmmo`.
- `survival`: `hasThirst`, `thirst`, `thirstSat` or `thirstSaturation`, `hasTemp`, `temperature`, `tempStatus`, `hasStamina`, `stamina`, `maxStamina`.
- `target`: `type` (`ENTITY` or block), `name`, `mod`, `health`, `maxHealth`.
- `effects`: list of `id`, `name`, `durationTicks`, `amplifier`, `beneficial`.
- `armorSlots`: list of `slot`, `itemId`, `damage`, `maxDamage`.

### Map

`{"type":"map","data":<base64 PNG>,"originX","originZ","playerX","playerZ","yaw","size"}` (`KT/net/HudRepository.kt:1185-1212`). `data` is a one-pixel-per-block tile. Android requires every field except `data` to be present. Linux defaults each missing field (`linux/thor_mc_hud.py:440-458`). `yaw` is in degrees, 0 facing south.

### Icon

- `{"type":"icon","itemId":<id>,"data":<base64 PNG>}` answers `ICON:<id>`.
- `{"type":"icon","itemId":<id>}` with no `data` (or `data` null) means the mod has no icon for the item (`KT/net/HudRepository.kt:1214-1238`).

### No player

`{"type":"noplayer"}` - the world is gone but the socket stays open. Android clears the HUD state, map, container, chat and in-flight icon requests, and keeps `isConnected` true (`KT/net/HudRepository.kt:910-921`). Linux resets its state to the defaults and clears the map and container (`linux/thor_mc_hud.py:475-481`).

### Container

```
{"type":"container","syncId":int,"handlerType":string|null,"title":string|null,
 "screenOpen":bool,"creativeScreen":bool,"cursor":slot,
 "slots":[{"stack":slot,"mayPlace":bool,"x":int,"y":int,"background":string?,"disabled":bool?}],
 "playerStart":int,"hotbarStart":int,"armorStart":int,"offhandIndex":int,
 "progress":{...}?,"extras":{...}?}
```

(`KT/net/HudRepository.kt:930-978`).

- `slots` is required; the message is ignored when it is absent.
- `cursor` is required (`getJSONObject`).
- `x`/`y` default to -1 when absent; `background` is optional; `disabled` is sent only when true.
- `screenOpen` and `creativeScreen` default to false.
- `progress` keys: `cookPixels`, `litPixels`, `fuelPixels`, `brewPixels`, `brewing` (`KT/net/HudRepository.kt:1087-1093`).
- `extras` keys: `enchanting` (`offers` of `{level,cost,lapisCost,name,available}`, `lapisCount`, `playerLevel`), `stonecutter` and `loom` (`options` of `{label,itemId?}`, `selected`), `merchant` (`trades` of `{firstCost,secondCost,sell,disabled,uses,maxUses}`, `experience`, `levelProgress`, `leveled`), `beacon` (`primaryOptions`, `secondaryOptions` of `{id,name,tier}`, `primary`, `secondary`, `level`, `hasPayment`), `anvil` (`levelCost`, `hasResult`) (`KT/net/HudRepository.kt:1005-1057`). An `extras` object with no known key is discarded.
- `screenOpen` is the field the Android app uses for the takeover (`KT/SecondScreenApp.kt:150`). Linux checks `isScreenOpen` (see the drift note in [06](06-testing-limitations-glossary.md#documentation-and-code-drift)).
- `handlerType` `null` on the player's own handler, `armorStart >= 0` identifies it (`KT/net/HudRepository.kt:379`).

### Creative catalogue

`{"type":"creative","groups":[{"id","name","icon"?,"items":[<id>,...]}]}` (`KT/net/HudRepository.kt:987-1001`). Replaces the previous catalogue. Requested by `CREATIVE`.

## Outbound: client to mod

All commands are single lines. The Android app sends each through `sendCommand`, which launches a coroutine per line on the IO dispatcher (`KT/net/HudRepository.kt:1301-1313`); see the ordering note in [design-spec/02](../design-spec/02-protocols-algorithms.md#send-ordering).

| Command | Shape | Sent by | Meaning per the client |
|---|---|---|---|
| Hotbar select | `1` ... `9` | Android HUD tap (`KT/ui/HudScreen.kt:277`); Linux hotbar tap | Select this hotbar slot, 1-based |
| SWAP | `SWAP` | Android off-hand tap (`KT/ui/HudScreen.kt:281`) | Swap hands; the mod's handling is unverified |
| ICON | `ICON:<itemId>` | `requestIcon` (`KT/net/HudRepository.kt:648`); Linux `request_icon` | Request a PNG icon |
| ASSETS | `ASSETS` | `requestAssets`, **never called** (`KT/net/HudRepository.kt:793`) | Resend the texture bundle |
| BINDINGS | `BINDINGS` | `requestBindings` (`KT/ui/SettingsScreen.kt:110`) | Resend the binding list |
| CREATIVE | `CREATIVE` | `requestCreative` (`KT/net/HudRepository.kt:837`) | Send the creative catalogue |
| GIVE | `GIVE:<itemId>` or `GIVE:<itemId>,<slot 0-8>` | `sendGive` (`KT/net/HudRepository.kt:852-853`) | Give the item, optionally into a hotbar index (0-based, unlike the digits) |
| CHAT | `CHAT:<text>` | `sendChat` (`KT/net/HudRepository.kt:1150-1154`) | Say in chat; a leading `/` makes a command (per `thor-hud-handoff.md` and `CLAUDE.md`, mod-side unverified) |
| BIND | `BIND:<bindingId>` | `sendBinding` (`KT/net/HudRepository.kt:1163`) | Press this key binding |
| HUD | `HUD:<key>,<key>,...` | `sendGameHudElements` (`KT/net/HudRepository.kt:1175-1176`) | Elements the game should draw on the top screen; empty list is `HUD:` |
| SLOT | `SLOT:<syncId>,<slotId>,<button>,<ACTION>` | `sendSlotClick` (`KT/net/HudRepository.kt:1111-1112`) | Click a slot. Android sends `PICKUP`, `QUICK_MOVE` and `QUICK_CRAFT`; the doc comment also lists `THROW`, `SWAP`, `CLONE`, `PICKUP_ALL`, which the app never sends. |
| BUTTON | `BUTTON:<syncId>,<buttonId>` | `sendButton` | Enchant offer, stonecutter recipe, or loom pattern (`KT/net/ContainerCommands.kt:35`) |
| TRADE | `TRADE:<syncId>,<index>` | `sendTrade` | Select a villager trade (`ContainerCommands.kt:38`) |
| SLOTSTATE | `SLOTSTATE:<syncId>,<slotId>,<0\|1>` | `sendSlotState`, **never called** (`ContainerCommands.kt:41`) | Enable or disable a crafter cell |
| BEACON | `BEACON:<syncId>,<primary>,<secondary>` | `sendBeaconEffects` | Both effects; an absent effect is `-` (`ContainerCommands.kt:31`, `46-47`) |
| RENAME | `RENAME:<syncId>,<name>` | `sendRename` | Rename the anvil result; commas kept, newlines become spaces (`ContainerCommands.kt:55-56`) |

Quick-craft packing for `SLOT` with `QUICK_CRAFT` (`KT/ui/InventoryMoves.kt:63`): stage in bits 0-1, mouse button in bits 2-3. Stage 0 starts, stage 1 paints a slot, stage 2 commits. The outside slot is `-999` (`InventoryMoves.kt:55`).

### Linux companion outbound

| Command | Shape | Source |
|---|---|---|
| Hotbar select | `1`-`9` | `linux/thor_mc_hud.py:669` |
| Slot click | `SLOT:<sync>,<slot>,<0 or 1>,PICKUP` or `QUICK_MOVE` | `linux/thor_mc_hud.py:659-661` (0 for left button, 1 otherwise) |
| Drop held stack | `SLOT:<sync>,-999,0,PICKUP` | `linux/thor_mc_hud.py:637-639` |
| Close container | `CLOSE_CONTAINER` | `linux/thor_mc_hud.py:636`, `648` |
| Sort and widgets | `SORT`, `WIDGET:<id>` | `linux/thor_mc_hud.py:640-641` |
| Hotkey bar commands | Whatever `cmd` the user chose, from `HOTKEY_PRESETS` (`E`, `Q`, `F`, `SORT`, `T`, `M`, `ESCAPE`, `F3`, `F5`, `SPRINT`, `SNEAK`, `JUMP`, `TAB`) | `linux/thor_mc_hud.py:62-86`, `652`, `677` |

`TOGGLE_SHIFT` and `OPEN_SETTINGS` are handled locally and never sent. Whether the mod recognises `CLOSE_CONTAINER`, `SORT`, `WIDGET:` and the bare key names is **unverified** here. `CLAUDE.md` describes a missing close verb on the mod side.

## Unknown and reserved names

- Android defines `ASSETS`, `SLOTSTATE` and `GameDirectoryAccess` but never sends or uses them (see [06](06-testing-limitations-glossary.md#dead-or-unused-code)).
- `tools/capture_protocol.py` is mentioned in `CLAUDE.md` but is not in the repository.
