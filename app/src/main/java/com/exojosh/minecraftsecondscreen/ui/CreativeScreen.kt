package com.exojosh.minecraftsecondscreen.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import com.exojosh.minecraftsecondscreen.net.CreativeGroup
import com.exojosh.minecraftsecondscreen.net.HotbarSlot

/**
 * The creative inventory, on the bottom screen.
 *
 * <h2>Why this isn't the container path</h2>
 * In creative, the game's inventory screen is `CreativeInventoryScreen`, whose
 * handler is a **client-side** `CreativeScreenHandler` with no type. Streaming it
 * as an ordinary container would get the app a 45-slot page of whichever tab the
 * game happened to be showing, with no way to reach any other tab — and no way to
 * click it either, because creative item selection doesn't go through `clickSlot`
 * at all. So the mod sends the catalogue itself (`CreativeCatalog`) and this draws
 * a picker over it.
 *
 * <h2>Search is a filter over ids, not a server query</h2>
 * The whole catalogue is already here, so searching is a local `contains` over the
 * item ids of every group at once — which is also why search results ignore the
 * selected tab. Typing "diamond" and getting only the diamonds that happen to live
 * in the tab you were on would be the wrong answer.
 *
 * Matching is against the **id**, not a display name, because ids are all the
 * catalogue carries. That reads slightly differently to vanilla's search (which
 * matches names and tooltips), and it's an honest limitation rather than a
 * shortcut: sending 1300 translated names would double the message for a feature
 * that already works on the word a player actually types.
 *
 * <h2>Icons come from the existing path</h2>
 * The catalogue is item **ids**. Icons arrive through the same on-demand `ICON:`
 * request the hotbar and inventory use, so they're rendered by the mod through the
 * real model pipeline and cached after the first look. A grid that has scrolled
 * past a few hundred items will have asked for a few hundred icons, which the
 * repository's LRU bound is there to survive.
 */
@Composable
fun CreativeScreen(
    groups: List<CreativeGroup>,
    /** The player's own hotbar, from the HUD snapshot. With the creative screen
     *  open the container message describes the *creative* handler, so this is the
     *  only place the player's real slots are available. */
    hotbar: List<HotbarSlot>,
    selectedSlot: Int,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    isConnected: Boolean,
    itemIcon: (String) -> Bitmap?,
    onRequestCatalogue: () -> Unit,
    /** Item id, and a hotbar slot 0..8 or null for "wherever there's room". */
    onGive: (String, Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    // Asked for from composition, so it has to tolerate being hit every frame --
    // the repository holds the once-only latch, same as requestIcon.
    LaunchedEffect(isConnected) { onRequestCatalogue() }

    // The game's own container panel, same as the Items tab. Without it the picker
    // floats on the tiled dirt while every other slot-bearing screen sits on the
    // panel, which reads as unfinished rather than as a deliberate difference.
    val panel = modifier
        .fillMaxSize()
        .padding(SCREEN_PADDING)
        .vanillaPanel()
        .padding(PANEL_INSET)

    if (groups.isEmpty()) {
        Box(modifier = panel, contentAlignment = Alignment.Center) {
            PickerLabel(
                if (isConnected) "Loading the item list…" else "Not connected.",
                fontSheet
            )
        }
        return
    }

    var selectedGroup by remember(groups.size) { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }

    // Searching deliberately spans every group -- see the note above. Recomputed
    // only when the query or the catalogue changes, not per frame: this walks
    // ~1300 ids.
    val shown = remember(groups, selectedGroup, query) {
        if (query.isBlank()) {
            groups.getOrNull(selectedGroup)?.items ?: emptyList()
        } else {
            val needle = query.lowercase().replace(' ', '_')
            groups.flatMap { it.items }.distinct().filter { needle in it }
        }
    }

    Column(modifier = panel) {
        CreativeTabs(
            groups = groups,
            selected = selectedGroup,
            searching = query.isNotBlank(),
            fontSheet = fontSheet,
            glintTexture = glintTexture,
            itemIcon = itemIcon,
            onSelect = { index ->
                selectedGroup = index
                // Picking a tab is a statement about wanting to browse it, which a
                // live filter across every group would immediately contradict.
                query = ""
                typing = false
            }
        )

        SearchRow(
            query = query,
            typing = typing,
            resultCount = shown.size,
            fontSheet = fontSheet,
            onToggleTyping = { typing = !typing },
            onClear = { query = ""; typing = false }
        )

        // Drag state, in coordinates relative to the drag layer below.
        val hotbarBounds = remember { mutableMapOf<Int, Rect>() }
        var layerOrigin by remember { mutableStateOf(Offset.Zero) }
        var dragging by remember { mutableStateOf<String?>(null) }
        var dragPosition by remember { mutableStateOf(Offset.Zero) }
        var hoveredSlot by remember { mutableStateOf<Int?>(null) }

        val give by rememberUpdatedState(onGive)

        // One layer over the grid *and* the hotbar, so the dragged item can be
        // drawn anywhere the finger goes. It used to be drawn inside the grid's own
        // box, which clipped it: the item vanished exactly as it crossed onto the
        // hotbar, which is the moment the player most needs to see what they're
        // carrying. Having both children in one box also means one coordinate
        // space for the cell origins, the hotbar rects and the pointer.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { layerOrigin = it.boundsInRoot().topLeft }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ItemGrid(
                    items = shown,
                    fontSheet = fontSheet,
                    glintTexture = glintTexture,
                    itemIcon = itemIcon,
                    // A tap still gives to the first free slot; the drag is for
                    // choosing *which* slot, not a replacement for tapping.
                    onGive = { itemId -> give(itemId, null) },
                    rootOrigin = layerOrigin,
                    // Long-press, then drag. A plain drag has to stay available for
                    // scrolling -- it's a thousand items -- so the gesture that
                    // *takes* an item out is the deliberate one.
                    //
                    // The detector lives on each cell, not on a parent. A parent
                    // pointerInput never sees the pointer here: the grid's
                    // scrollable and the cell's own clickable are deeper in the tree
                    // and consume first, which is why the first version of this
                    // silently did nothing at all. Compose keeps delivering a drag
                    // to whichever node started it even after the finger leaves that
                    // node, so tracking across the whole screen still works.
                    onDragStart = { itemId, at ->
                        dragging = itemId
                        dragPosition = at
                        hoveredSlot = null
                    },
                    onDragMove = { delta ->
                        dragPosition += delta
                        hoveredSlot = hotbarBounds.entries
                            .firstOrNull { it.value.contains(dragPosition) }?.key
                    },
                    onDragEnd = {
                        val item = dragging
                        val slot = hoveredSlot
                        // Released off the hotbar: nothing happens. A creative give
                        // replaces whatever was in the slot, so a slipped finger
                        // must not have a slot guessed for it.
                        if (item != null && slot != null) give(item, slot)
                        dragging = null
                        hoveredSlot = null
                    },
                    onDragCancel = {
                        dragging = null
                        hoveredSlot = null
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                // The real hotbar, from the HUD snapshot rather than the container
                // message: while the creative screen is open the container describes
                // the *creative* handler, so the player's own hotbar isn't in it.
                // This is both the drop target and the only way to see what a give
                // actually did.
                CreativeHotbar(
                    hotbar = hotbar,
                    selectedSlot = selectedSlot,
                    hoveredSlot = hoveredSlot,
                    fontSheet = fontSheet,
                    glintTexture = glintTexture,
                    itemIcon = itemIcon,
                    rootOrigin = layerOrigin,
                    onSlotBounds = { index, rect -> hotbarBounds[index] = rect },
                    onTapSlot = { index -> dragging?.let { give(it, index) } }
                )
            }

            // The dragged item under the finger, so the gesture is visible.
            //
            // Offset in the *layout* lambda rather than as a Dp, so following the
            // finger invalidates only placement -- a Dp offset recomputed per frame
            // would recompose this subtree on every pointer move.
            dragging?.let { draggedItem ->
                val half = with(LocalDensity.current) { (ITEM_CELL / 2).toPx() }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (dragPosition.x - half).roundToInt(),
                                (dragPosition.y - half).roundToInt()
                            )
                        }
                        .size(ITEM_CELL)
                ) {
                    ItemStackGraphic(
                        stack = HotbarSlot(draggedItem, 1, 0, 0, false),
                        icon = itemIcon(draggedItem),
                        fontSheet = fontSheet,
                        glintTexture = glintTexture,
                        unit = ITEM_CELL / ITEM_AREA_PIXELS
                    )
                }
            }
        }

        if (typing) {
            ChatKeyboard(
                onType = { typed -> query += typed },
                onBackspace = { query = query.dropLast(1) },
                onSend = { typing = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The player's hotbar, as a drop target for the grid above.
 *
 * Drawn from the HUD snapshot, not from the container message: with the creative
 * screen open, `currentScreenHandler` is the creative handler, so the player's own
 * slots aren't in the container state at all. The snapshot always carries the
 * hotbar, which is what makes this possible without another wire addition.
 *
 * The hovered slot is outlined during a drag, because a creative give *replaces*
 * whatever was there — the player needs to see which slot they're about to
 * overwrite before they lift.
 *
 * **The cells are square, measured, and not a constant.** They used to be
 * `weight(1f)` wide against a fixed 46dp height, which is a ninth of the panel
 * against a number — the two agree only by accident, and on the Thor they don't:
 * the grid's cells above are square at that same ninth, so the hotbar came out
 * visibly flattened under a row of squares it is supposed to match. Measuring the
 * row and sizing the cell from it is what keeps the drop target the same shape as
 * the thing being dropped, at any panel width.
 */
@Composable
private fun CreativeHotbar(
    hotbar: List<HotbarSlot>,
    selectedSlot: Int,
    hoveredSlot: Int?,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    rootOrigin: Offset,
    onSlotBounds: (Int, Rect) -> Unit,
    onTapSlot: (Int) -> Unit
) {
    val gui = LocalMinecraftGui.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = ROW_GAP)) {
        // The same arithmetic LazyVerticalGrid does for the grid above: the width
        // left over once the eight gaps are taken out, split nine ways.
        val cell = (maxWidth - ROW_GAP * (HOTBAR_SLOTS - 1)) / HOTBAR_SLOTS
        val bevel = cell / SLOT_PIXELS

        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            for (index in 0 until HOTBAR_SLOTS) {
                val stack = hotbar.getOrNull(index)
                Box(
                    modifier = Modifier
                        .size(cell)
                        .onGloballyPositioned {
                            onSlotBounds(index, it.boundsInRoot().translate(-rootOrigin))
                        }
                        .vanillaSlot(gui.slot)
                        .semantics { contentDescription = "Hotbar slot ${index + 1}" }
                        .clickable { onTapSlot(index) }
                ) {
                    if (stack != null && stack.itemId != InventoryMoves.AIR_ITEM_ID) {
                        Box(modifier = Modifier.fillMaxSize().padding(bevel)) {
                            ItemStackGraphic(
                                stack = stack,
                                icon = itemIcon(stack.itemId),
                                fontSheet = fontSheet,
                                glintTexture = glintTexture,
                                unit = (cell - bevel * 2) / ITEM_AREA_PIXELS
                            )
                        }
                    }
                    // The drop target wins over the selected-slot marker: during a
                    // drag "where will this land" matters more than "what am I
                    // holding".
                    when {
                        index == hoveredSlot ->
                            Box(modifier = Modifier.fillMaxSize().vanillaSlotHighlight())
                        index == selectedSlot -> SlotOverlay(SELECTED_SLOT_TINT)
                    }
                }
            }
        }
    }
}

/** The group tabs, each labelled by the item vanilla labels it with. */
@Composable
private fun CreativeTabs(
    groups: List<CreativeGroup>,
    selected: Int,
    searching: Boolean,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onSelect: (Int) -> Unit
) {
    val gui = LocalMinecraftGui.current

    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = ROW_GAP),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        groups.forEachIndexed { index, group ->
            item(key = group.id) {
                Box(
                    modifier = Modifier
                        .size(TAB_CELL)
                        .vanillaSlot(gui.slot)
                        // The group's name is the only text on a cell whose
                        // content is an item icon.
                        .semantics { contentDescription = group.name }
                        .clickable { onSelect(index) }
                ) {
                    group.icon?.let { icon ->
                        Box(modifier = Modifier.fillMaxSize().padding(TAB_CELL / SLOT_PIXELS)) {
                            ItemStackGraphic(
                                stack = HotbarSlot(icon, 1, 0, 0, false),
                                icon = itemIcon(icon),
                                fontSheet = fontSheet,
                                glintTexture = glintTexture,
                                unit = (TAB_CELL - TAB_CELL / SLOT_PIXELS * 2) / ITEM_AREA_PIXELS
                            )
                        }
                    }
                    // While a search is running no tab is the one being shown, so
                    // none is marked -- highlighting the last-picked tab would
                    // claim the results came from it.
                    if (!searching && index == selected) {
                        Box(modifier = Modifier.fillMaxSize().vanillaSlotHighlight())
                    }
                }
            }
        }
    }
}

/**
 * The search field and what it found.
 *
 * The keyboard is the app's own [ChatKeyboard] and appears only while typing, the
 * same arrangement the anvil's rename uses and for the same reason: Android puts an
 * IME for a presentation display on the *primary* screen, which here is the one
 * running the game.
 */
@Composable
private fun SearchRow(
    query: String,
    typing: Boolean,
    resultCount: Int,
    fontSheet: MinecraftFontSheet?,
    onToggleTyping: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(bottom = ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(FIELD_BACKGROUND)
                .clickable(onClick = onToggleTyping)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            PickerLabel(
                query.ifEmpty { "Tap to search" },
                fontSheet,
                if (query.isEmpty()) DIM_TEXT else Color.White
            )
        }

        PickerLabel("$resultCount", fontSheet)

        if (query.isNotEmpty()) {
            MinecraftButton(
                text = "Clear",
                onClick = onClear,
                modifier = Modifier.fillMaxHeight()
            )
        }
        MinecraftButton(
            text = if (typing) "Done" else "Search",
            onClick = onToggleTyping,
            selected = typing,
            modifier = Modifier.fillMaxHeight()
        )
    }
}

/**
 * The items, as a scrolling grid of slots. A tap hands one over; a long-press and
 * drag onto the hotbar puts it in a chosen slot.
 *
 * **Nine columns, fixed.** It was `GridCells.Adaptive`, which filled the width at
 * whatever count fitted — eleven on the Thor — and that's wrong for this screen
 * rather than merely different: the creative inventory is nine wide in the game,
 * the hotbar under it is nine wide, and a grid on a different pitch to the row it
 * drops onto reads as two unrelated things. Cells now take a ninth of the width
 * each instead of a fixed size.
 */
@Composable
private fun ItemGrid(
    items: List<String>,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onGive: (String) -> Unit,
    rootOrigin: Offset,
    onDragStart: (String, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gui = LocalMinecraftGui.current

    if (items.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            PickerLabel("Nothing matches", fontSheet, DIM_TEXT)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        // Keyed by id so scrolling doesn't re-request icons for cells that only
        // moved -- the ids are distinct within a group by construction.
        items(items, key = { it }) { itemId ->
            // The cell's own top-left in the drag layer's coordinates. A drag
            // detector reports positions relative to its own node, so this is what
            // converts them into something comparable with the hotbar's rects.
            var cellOrigin by remember { mutableStateOf(Offset.Zero) }

            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .onGloballyPositioned {
                        cellOrigin = it.boundsInRoot().topLeft - rootOrigin
                    }
                    .vanillaSlot(gui.slot)
                    .semantics {
                        contentDescription = itemId
                        // The tap is detected in raw pointer input below, which
                        // leaves nothing for accessibility to activate.
                        onClick { onGive(itemId); true }
                    }
                    .pointerInput(itemId) {
                        detectTapOrHoldDrag(
                            onTap = { onGive(itemId) },
                            onDragStart = { local -> onDragStart(itemId, cellOrigin + local) },
                            onDragMove = onDragMove,
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel
                        )
                    }
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                    ItemStackGraphic(
                        stack = HotbarSlot(itemId, 1, 0, 0, false),
                        icon = itemIcon(itemId),
                        fontSheet = fontSheet,
                        glintTexture = glintTexture,
                        unit = ITEM_CELL / ITEM_AREA_PIXELS
                    )
                }
            }
        }
    }
}

/**
 * Tap, or hold-then-drag, decided from the pointer events' own timestamps.
 *
 * <h2>Why not `detectDragGesturesAfterLongPress` plus `clickable`</h2>
 * That's what this was, and it did not work on the device while passing every
 * test written against it. `awaitLongPressOrCancellation` arms a timeout on the
 * pointer-input scope's clock and needs it to *fire* while the finger sits still —
 * and a still finger produces no pointer events. In a Compose test
 * `advanceEventTime` drives that clock, so the long press always fired and the
 * drag always worked; on the Thor the first real movement reached the
 * `LazyVerticalGrid`'s scroller instead, and a held finger dragged the grid rather
 * than lifting the item. The test wasn't wrong about the code, it was driving a
 * clock production doesn't have.
 *
 * So the hold is measured against `uptimeMillis` **carried by the events
 * themselves** and evaluated when the next one arrives. Nothing has to fire in a
 * vacuum: the only moment the decision matters is the first significant movement,
 * and that movement is itself an event. It also makes the test drive the same code
 * path production does, rather than a different one that happens to agree.
 *
 * <h2>Why the tap is here too</h2>
 * Because the drag has to **consume** movement to keep the scroller out of it, and
 * a consumed move cancels `clickable`'s pending tap anyway. Two detectors racing
 * over one finger is what produced the original bug; one that knows about both
 * outcomes can just decide.
 *
 * The cost is the Material ripple, which this screen didn't want — every other
 * control here is drawn from the game's own sprites and `MinecraftButton` already
 * passes `indication = null` for the same reason.
 */
private suspend fun PointerInputScope.detectTapOrHoldDrag(
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val holdMillis = viewConfiguration.longPressTimeoutMillis
        // Generous on purpose: a thumb's contact patch wanders several pixels just
        // from the pressure changing, and treating that as a scroll is the failure
        // this whole detector exists to avoid. Past it, it really is a scroll.
        val bailSlop = viewConfiguration.touchSlop * HOLD_SLOP_FACTOR

        var lifted = false
        var completed = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break

            if (change.changedToUpIgnoreConsumed()) {
                if (lifted) {
                    completed = true
                } else if ((change.position - down.position).getDistance()
                    <= viewConfiguration.touchSlop
                ) {
                    // Short and still: an ordinary tap. A long press that never
                    // moved lands here too, which is right -- nothing was dragged.
                    onTap()
                    completed = true
                }
                break
            }

            if (!lifted) {
                // Someone outside took the gesture -- the grid began scrolling.
                if (change.isConsumed) break

                val travel = (change.position - down.position).getDistance()
                if (change.uptimeMillis - down.uptimeMillis >= holdMillis) {
                    // The lift *is* this event, so the drag starts where the finger
                    // already is. Falling through to the move below as well would
                    // count this event's travel twice and throw the carried item
                    // past wherever the finger actually is -- which reads as the
                    // drop missing the slot the player is plainly over.
                    lifted = true
                    onDragStart(change.position)
                    change.consume()
                    continue
                } else if (travel > bailSlop) {
                    // Moved before the hold was earned: let it scroll, untouched.
                    break
                } else {
                    // Held, but wandering. Swallow it so the scroller never
                    // accumulates enough to claim the gesture out from under us.
                    change.consume()
                    continue
                }
            }

            onDragMove(change.positionChange())
            change.consume()
        }

        if (lifted) {
            if (completed) onDragEnd() else onDragCancel()
        }
    }
}

@Composable
private fun PickerLabel(
    text: String,
    fontSheet: MinecraftFontSheet?,
    color: Color = PANEL_TEXT
) {
    if (fontSheet != null) {
        MinecraftText(
            text = text,
            fontSheet = fontSheet,
            pixelSize = LABEL_PIXEL,
            color = color,
            style = MinecraftTextStyle.SHADOW
        )
    } else {
        androidx.compose.material3.Text(text = text, color = color)
    }
}

/** Matched to the Items tab, so the two panels line up when tabbing between
 *  them rather than shifting by a few pixels. */
private val SCREEN_PADDING = 4.dp
private val PANEL_INSET = 6.dp
/** Vanilla's creative inventory is nine wide, and so is the hotbar it drops
 *  onto. A grid on a different pitch to the row beneath it reads as two
 *  unrelated things. */
private const val GRID_COLUMNS = 9
private const val HOTBAR_SLOTS = 9
private val ROW_HEIGHT = 26.dp
private val ROW_GAP = 3.dp
private val TAB_CELL = 34.dp
private val ITEM_CELL = 42.dp
private val LABEL_PIXEL = 1.5.dp
/** How far a finger may wander during the hold and still count as holding, as a
 *  multiple of the system's touch slop. One slop is the point the scroller claims
 *  the gesture, which is exactly what a resting thumb was tripping. */
private const val HOLD_SLOP_FACTOR = 2f

private val PANEL_TEXT = Color(0xFFDDDDDD)
private val DIM_TEXT = Color(0xFF8A8A8A)
private val FIELD_BACKGROUND = Color(0xFF000000)
/** The slot the player is actually holding. Dimmer than the drag target, which
 *  has to win while a drag is in flight. */
private val SELECTED_SLOT_TINT = Color(0x40FFFFFF)
