# 02 - Protocols and algorithms

## Framing

- Inbound and outbound lines are UTF-8 and end in `\n`. Nothing else frames the stream.
- Android reads with `BufferedReader.readLine()` over `InputStreamReader` with no explicit charset (`KT/net/HudRepository.kt:667-677`), so decoding uses the platform default. The charset is **unverified** as UTF-8 on the device. Linux is explicit about UTF-8 (see below).
- Linux receives in 16384-byte chunks and decodes with an incremental UTF-8 decoder so a split character is not corrupted. It scans for `\n` by index and keeps the unconsumed tail (`linux/thor_mc_hud.py:379-407`).
- Outbound writes are one line per call. Linux joins its queue with `\n` and sends one `sendall` per flush (`linux/thor_mc_hud.py:384-388`). Android `PrintWriter` with auto-flush writes each `println` as one call (`KT/net/HudRepository.kt:660`, `1308`).
- A command or chat string containing `\n` would split into two commands. Chat and rename flatten `\n` and `\r` (`KT/net/HudRepository.kt:1151`; `KT/net/ContainerCommands.kt:56`). Other outbound strings are built from item ids and numbers and are not escaped.

## Connection and reconnect policy

### Connect

Android: `Socket().connect(127.0.0.1:48291, 1000 ms)`; then `soTimeout = 5000 ms` (`KT/net/HudRepository.kt:656-657`). A read timeout does not close the socket: the loop simply reads again (`:670-674`). A silent but open connection therefore never triggers a reconnect; only a close, an exception or a null read does. **Unverified** whether the mod ever stops sending for long periods.

Linux: `socket.settimeout(2.0)` for connect, then `setblocking(False)`; select with 50 ms timeout (`linux/thor_mc_hud.py:369-393`). The socket is non-blocking for reads and writes after connect, so `sendall` on it can raise under a full buffer; the outer handler then tears the connection down (see [04](04-protocol-reference.md)).

### Reconnect policy

The schedule is `ReconnectPolicy.delayMs(failedAttempts)` (`KT/net/ReconnectPolicy.kt:20-46`). Constants: `FAST_ATTEMPTS = 5`, `FAST_DELAY_MS = 1500`, `MAX_DELAY_MS = 30000`.

For `failedAttempts < 5`: 1500 ms. For `n >= 5`: `1500 << min(n - 5 + 1, 16)`, capped at 30000 ms.

| `failedAttempts` | Delay (ms) |
|---|---|
| 0 to 4 | 1500 |
| 5 | 3000 |
| 6 | 6000 |
| 7 | 12000 |
| 8 | 24000 |
| 9 and later | 30000 |

`failedConnections` is incremented once per loop iteration, after the wait (`KT/net/HudRepository.kt:729-730`). It is reset to 0 only when a hello arrives (`:892`). Consequence: the reconnect delay after a working session that then drops is still 1500 ms, and a connection that succeeds at the socket level but never says hello keeps the counter growing.

Linux uses a fixed `RECONNECT_INTERVAL = 1.5` s after every disconnect, with no counter (`linux/thor_mc_hud.py:54`, `421-422`).

### Silent connections and port conflict

Android counts iterations where `sawHello` is false after the attempt (`KT/net/HudRepository.kt:689-698`). The threshold is `SILENT_CONNECTIONS_BEFORE_SUSPECTING_CONFLICT = 3`. `silentConnections` resets to 0 only on hello (`:891`). `portConflict` is set when the count reaches 3 and cleared on hello (`:893`). See [04](04-interfaces-error-handling.md#port-conflict) for the consequence on cold start.

## Icon requests

The mod resolves item icons on demand. The client keeps three tables and one rule.

### Android

- `iconCache`: itemId to bitmap, LRU-bounded at 200 entries (`KT/net/HudRepository.kt:32`, `1241-1256`).
- `pendingIconRequests`: itemId to elapsed-realtime when the request was sent.
- `failedIconRequests`: itemId to elapsed-realtime when the mod said it had no icon.

`requestIcon(itemId)` (`:620-650`), called from composition every frame:

1. Cached: touch LRU order and return the bitmap.
2. `""` or `minecraft:air`: return null.
3. Failed within `ICON_FAILURE_RETRY_MS` (60 000 ms): return null. Expired entries are removed.
4. Pending within `ICON_REQUEST_TIMEOUT_MS` (3000 ms): return null.
5. No writer (not connected): return null without marking pending.
6. Otherwise mark pending and send `ICON:<itemId>`; return null.

`handleIconResponse` (`:1214-1238`): remove pending. No `data`: record failed. Data that does not decode: record failed. Data that decodes: store in cache, touch LRU, remove failed.

On disconnect or noplayer, pending and failed are cleared; the cache is kept (`:711-716`, `919-920`).

### Linux

`IconRequests` (`linux/thor_mc_hud.py:123-158`), timeouts 5.0 s pending and 5.0 s failure. `should_request` returns true and marks pending when neither timer is active. An answer removes pending and failed; a failure records a failure time. `reset` runs on connect and on disconnect (`:376`, `416`). The icon cache is an unbounded dictionary.

The difference in timers is listed in [03](../application-spec/03-devices-platforms.md#linux-companion-versus-android-app).

## Asset and map delivery

- **Assets.** The mod pushes one `asset` line per catalogue key after a connection (`tools/hud_sim.py:865-866` shows the order the simulator follows; mod side **unverified**). Each decodes with `inScaled = false` into ARGB_8888 (`KT/net/HudRepository.kt:774-780`). Missing data marks the key unavailable and `unavailableAssets` is cleared on disconnect (`:725`).
- **Late delivery.** Textures read from `assetCache` through `ResourcePackIconProvider`, which reads the observable map, so the UI recomposes as each arrives (`KT/net/ResourcePackIconProvider.kt:169-171`).
- **Fallback chain.** `getIcon(icon)` returns the socket asset, else a bundled file (`:169-170`). Bundled files are not in the tree (see [05](../application-spec/05-configuration-requirements.md)), so in practice a missing texture is simply null.
- **Map.** Each `map` line replaces the tile; nothing is queued (`KT/net/HudRepository.kt:1203-1211`). The tile is dropped on disconnect and noplayer.

## Gesture algorithms

### Items tab drag (`KT/ui/InventoryScreen.kt:443-549`)

One `pointerInput` handles the whole grid. For a down inside a placed cell (`slotAt`):

1. `withTimeoutOrNull(DISTRIBUTE_HOLD_MS = 300)` waits for touch slop. Slop consumed.
2. **Lift within the hold, no slop** (tap): `InventoryMoves.tap(mode, holdingCursor, slot)` sent (`:477-485`).
3. **Hold timeout with no slop** (distribute): if the cursor is empty, pick up the whole stack (`STACK`). Track painted slots while dragging. On release send `InventoryMoves.distribute(mode, painted)` (`:488-511`).
4. **Slop before the hold** (drag): if the cursor is empty, `InventoryMoves.pickUp(mode, start)` is sent. On release, `InventoryMoves.drop` for a different target, or `returnToSource` for the source or off-grid release (`:513-547`).

Tap, drag and distribute sequences are in `InventoryMoves` (`KT/ui/InventoryMoves.kt:76-156`). The rule that matters: nothing is sent during movement, and the sequence is produced on release.

### Creative picker long press (`KT/ui/CreativeScreen.kt:594-664`)

Built on raw pointer events so the hold uses the events' own `uptimeMillis` rather than a timer:

- Hold time is `change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis`, evaluated when the next event arrives.
- Before the hold is earned, movement larger than `touchSlop * HOLD_SLOP_FACTOR` (2) breaks out so the grid's scroller handles it. Smaller movement is consumed.
- The lift event starts the drag and is not also applied as a delta (`:641-644`).
- On release: if lifted and completed, `onDragEnd`; if lifted and not completed, `onDragCancel`; a short unmoved touch is a tap (`:617-626`, `660-662`).

### Hotbar and creative drop target

The hovered hotbar slot is the first `Rect` containing the drag position (`KT/ui/CreativeScreen.kt:217-218`). Release over no slot sends nothing (`:220-226`).

## Layout algorithms

### Health rows (`KT/ui/HeartLayout.kt:111-152`)

With `healthPoints = ceil(health)`, `absorptionPoints = ceil(absorption)`, `healthSlots = ceil(maxHealth/2)`, `absorptionSlots = ceil(absorptionPoints/2)`, total slots capped at `10 * 3 = 30`:

- Normal slot `k` (points = 2k): EMPTY if `points >= healthPoints`; HALF if `points + 1 == healthPoints`; else FULL.
- Absorption slot `k` (`covered = 2k - 2*healthSlots`): EMPTY if `covered >= absorptionPoints`; HALF if `covered + 1 == absorptionPoints`; else FULL.
- Row `r` holds slots `10r` to `10r + 9`; row 0 is the bottom row.

Example: health 20, max 20, absorption 3: `healthSlots = 10`, `absorptionPoints = 3`, `absorptionSlots = 2`. Slot 10 covers 0 of 3 points and is FULL; slot 11 covers 2 and is HALF. Absorption slots appear in the second row (row 1, slots 10-11).

### Breath bubbles (`KT/ui/HeartLayout.kt:170-176`)

`full = ceil((air - 2) * 10 / maxAir)` clamped at 0; `throughBursting = ceil(air * 10 / maxAir)`. Bubble display position is `10 - index` for index 0 to 9, so the highest-numbered bubble is on the left and empties first (`KT/ui/HudScreen.kt:619-634`). A bubble is full if its position is at most `full`, bursting if it equals `throughBursting` and the two differ.

### Chat wrap (`KT/ui/ChatLineWrapper.kt:38-95`)

Character by character. When the next glyph would overflow and the current line is not empty:

- Break at the last space if one lies after the line start; otherwise hard-break before the glyph.
- Trailing spaces of the break are dropped, and leading spaces of the next line are skipped.
- A single glyph wider than the line still occupies its own line (`i > lineStart` guard).

### Container placement (`KT/ui/ContainerLayout.kt:103-207`)

- Container cells: `x - 1` and `y - 1` for each slot with non-negative coordinates, then shifted so the minimum is 0. Slots with a negative coordinate are dropped (`:194-202`). A container without coordinates falls back to a 9-wide strip (`:181-192`).
- Player rows keep `InventoryLayout` order. Vertical pitch is `CELL` (18), plus `GROUP_GAP` (4) between a change of kind (`:124-128`).
- The container region sits above the player rows with `REGION_GAP` (8) between them (`:123`).
- The narrower region is centred over the wider (`:117-119`).
- Decoration offset `x = containerShift - originX`, `y = -originY`, where `origin` is `min(slot coordinate) - 1` (`:138-159`). Used only when `positionedByVanilla` is true (`:142`).

### Scaling

- Items tab: one texture pixel is `floor(min(width / planWidth, height / planHeight))` device pixels, at least 1 (`KT/ui/InventoryScreen.kt:398-405`). This keeps the one-pixel bevel crisp.
- Map: scale is fractional; the sheet is square and height-bound (`KT/ui/MapScreen.kt:116-127`). No whole-pixel snapping, by decision (see [05](05-constraints-decisions.md)).
- Hotbar: `unit = maxWidth / 182` with logical offsets (`KT/ui/HotbarRow.kt:169-175`).

### Animation clocks

- Enchant glint: `scrolled = frameMillis * 8`; X offset period 110 000, Y offset period 30 000; texture rotated 10 degrees; alpha 0.55 (`KT/ui/ItemGlint.kt:67-77`, `125-139`).
- Brewing bubbles: a 7-entry table indexed backwards by `floor(frameMillis / 100) mod 7` (`KT/ui/ContainerDecorations.kt:244-250`).
- Cursor blink in the chat draft: 500 ms on, 500 ms off (`KT/ui/ChatScreen.kt:321-332`).

## Timing summary

| Item | Value | Source |
|---|---|---|
| Android connect timeout | 1000 ms | `KT/net/HudRepository.kt:656` |
| Android read timeout | 5000 ms (no reconnect on timeout) | `:657`, `:670-674` |
| Icon pending timeout / failure back-off | 3000 ms / 60000 ms | `:36`, `:39` |
| Reconnect delays | 1500 ms (first 5), then doubling to 30000 ms | `ReconnectPolicy.kt` |
| Linux connect timeout / select / reconnect | 2.0 s / 50 ms / 1.5 s | `linux/thor_mc_hud.py:370`, `391`, `422` |
| Linux icon pending / failure | 5.0 s / 5.0 s | `:124` |
| Linux redraw timer | 33 ms | `:289` |
| Linux touch debounce | 120 ms and 10 px | `:586-588` |
| Items hold for distribute | 300 ms | `KT/ui/InventoryScreen.kt:95` |
| Creative hold | `longPressTimeoutMillis`; wander limit 2 x touch slop | `KT/ui/CreativeScreen.kt:603-607` |
| Decky status poll | 2000 ms | `decky-plugin/dist/index.js` |
| Decky port probe timeout | 0.5 s | `decky-plugin/main.py`, `_is_mc_port_open` |
| Simulator snapshot rate | 2 Hz (`sleep(0.5)`) | `tools/hud_sim.py:993` |
| Simulator chat tick | every 20 snapshots (about 10 s) | `tools/hud_sim.py:974-975` |

The mod's own broadcast rate is not in this tree (`thor-hud-handoff.md` says about 20/s; unverified against the current mod).

## Send ordering

Android launches one coroutine per `sendCommand`, on `Dispatchers.IO` (`KT/net/HudRepository.kt:1306`). Coroutines on the IO pool can run in any order, so a quick sequence such as the distribute clicks (`InventoryMoves.distribute`) is not guaranteed to reach the socket in the order it was produced. The code has no mutex or channel to enforce it. Real-world effect on the mod: **unverified**; the mod's handling of out-of-order `SLOT:` clicks is outside this tree.

Linux queues commands into a list and flushes the whole list in one `sendall` on the network thread, which keeps order (`linux/thor_mc_hud.py:361-363`, `384-388`).

## Container click round trip

1. The player taps a cell. The client sends `SLOT:<syncId>,<slot>,<button>,<action>` (`KT/net/HudRepository.kt:1111-1112`).
2. Nothing changes locally.
3. The mod applies the click to the open handler (if `syncId` still matches) and, if the state changed, sends a new `container` message (mod side **unverified**).
4. The client replaces `container` with the new message and recomposes.

The `syncId` is the staleness check: a click for an old handler is refused by the mod (`KT/net/HudRepository.kt:1095-1110`, documentation; mod side unverified).
