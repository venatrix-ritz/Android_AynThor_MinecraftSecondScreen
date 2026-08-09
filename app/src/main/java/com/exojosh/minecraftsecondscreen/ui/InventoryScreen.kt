package com.exojosh.minecraftsecondscreen.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.exojosh.minecraftsecondscreen.net.ContainerState
import com.exojosh.minecraftsecondscreen.net.HotbarSlot
import com.exojosh.minecraftsecondscreen.net.ResourcePackIconProvider
import kotlin.math.floor

private val SCREEN_PADDING = 4.dp

/** Clearance between the panel's bevel and the controls inside it. */
private val PANEL_INSET = 6.dp
private val CONTROL_BAR_HEIGHT = 40.dp

/**
 * Width of the villager's trade rail — the one container whose controls sit beside
 * the grid rather than above it.
 *
 * A fixed width, not a weight, and deliberately mean with it: one trade is three
 * 26dp stacks plus a separator, so this is about as narrow as the content can be.
 * A weight would have taken a *share* of the width, which is what made the old
 * side-by-side layout cost the grid so much.
 */
private val SIDE_RAIL_WIDTH = 124.dp


private val SLOT_BLOCKED = Color(0x55FF4444)
private val DRAG_SOURCE_TINT = Color(0x40FFFFFF)

/** A slot the handler has switched off — a crafter's disabled cell. Darker than
 *  [SLOT_BLOCKED] and colourless, because "can't go here right now" and "this
 *  cell isn't in play" are different things and shouldn't read the same. */
private val SLOT_DISABLED = Color(0x99000000)

/** Vanilla draws its empty-slot placeholders at reduced opacity so they read as
 *  a hint rather than as contents. */
private const val GHOST_ALPHA = 0.45f

/** Vanilla's own slot highlight (white at half alpha), reused to show which
 *  slots a distribute drag has painted so far. */
private val DISTRIBUTE_TINT = Color(0x80FFFFFF)

/** Vanilla draws its GUI labels in this grey on the panel; white would glare. */
private val PANEL_LABEL = Color(0xFF404040)

/**
 * How long a finger has to sit still before a drag becomes a *distribute* drag
 * rather than a move.
 *
 * Longer than Compose's own long-press timeout (500ms) would make the gesture
 * feel broken on a handheld; much shorter and an ordinary slow drag turns into
 * a spread. This is the one hidden gesture on this screen, and it can afford to
 * be: unlike a mis-fired half-click it doesn't do the *wrong* move, it does a
 * move the player can see building up slot by slot before they lift.
 */
private const val DISTRIBUTE_HOLD_MS = 300L

/** Test hooks. Drag-to-move can only be checked by actually dragging, and the
 *  gesture handler is on the grid rather than on any cell. */
const val GRID_TEST_TAG = "inventory-grid"

fun slotTestTag(slotIndex: Int) = "inventory-slot-$slotIndex"

/**
 * The Items tab: the open screen handler, with tap and drag to move items.
 *
 * With no container open this is the player's own inventory, because the mod
 * streams `currentScreenHandler` either way. A move is not a write to the
 * inventory — it's a click on a slot, which is the only thing the server will
 * honour, so every interaction here goes back as a `SLOT:` click and the
 * resulting state comes back from the game rather than being predicted locally.
 * Nothing is optimistically applied: what's drawn is always what the game says.
 *
 * The status stack is hidden on this tab (see `SecondScreenApp`) — six rows of
 * slots don't fit under it, and the inventory is a thing you look at
 * deliberately rather than glance at mid-fight.
 *
 * **The container's own slots are placed where the game places them** — see
 * [ContainerLayout]. This tab used to draw every container as a 9-wide strip,
 * which is right for a chest and wrong for everything else; a furnace was three
 * anonymous cells in a row and a crafting table was a strip of nine with the
 * result wrapped onto a phantom second row.
 */
@Composable
fun InventoryScreen(
    state: ContainerState?,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    isConnected: Boolean,
    itemIcon: (String) -> Bitmap?,
    /** For the empty-slot placeholders and the progress art, both of which are
     *  named by the game at runtime rather than by a `HudIcon` entry. */
    iconProvider: ResourcePackIconProvider,
    onSlotClick: (syncId: Int, slotId: Int, button: Int, action: String) -> Unit,
    /** The four commands for containers whose function isn't a slot click. See
     *  [ContainerExtrasPanel]. */
    onButton: (syncId: Int, buttonId: Int) -> Unit = { _, _ -> },
    onTrade: (syncId: Int, index: Int) -> Unit = { _, _ -> },
    onBeaconEffects: (syncId: Int, primary: String?, secondary: String?) -> Unit = { _, _, _ -> },
    onRename: (syncId: Int, name: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    if (state == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (isConnected) "Waiting for inventory…" else "Not connected.",
                color = Color(0xFF8A8A8A)
            )
        }
        return
    }

    // The creative screen's handler is the one thing this tab cannot draw usefully.
    // It has 45 item-page cells that `clickSlot` doesn't operate — creative
    // selection goes through a different packet entirely — so rendering them would
    // be a grid of controls that silently do nothing. The takeover routes here to
    // the Creative tab instead; this covers a player who taps Items by hand.
    if (state.creativeScreen) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Creative inventory — use the Creative tab",
                color = Color(0xFF8A8A8A)
            )
        }
        return
    }

    var moveMode by remember { mutableStateOf(MoveMode.STACK) }

    // Keyed on what can change the *shape*, not on the state itself: the slots'
    // contents change constantly and would rebuild the plan for nothing, while
    // opening a different container of the same size changes syncId.
    val plan = remember(state.syncId, state.slots.size, state.playerStart, state.armorStart) {
        ContainerLayout.plan(state)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(SCREEN_PADDING)
            // The whole tab sits on the game's own container panel, so the grid
            // reads as an inventory screen rather than as cells floating on the
            // dirt backdrop the rest of the app tiles.
            .vanillaPanel()
            .padding(PANEL_INSET)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ControlBar(
                state = state,
                moveMode = moveMode,
                fontSheet = fontSheet,
                glintTexture = glintTexture,
                itemIcon = itemIcon,
                onModeChange = { moveMode = it },
                onDropCursor = {
                    val drop = InventoryMoves.dropCursor()
                    onSlotClick(state.syncId, drop.slotId, drop.button, drop.action)
                }
            )

            // A container's own controls go in a band across the top, and the
            // grid gets the full width underneath.
            //
            // These used to sit in a column beside the grid, splitting the width
            // in half, and that was the wrong axis to spend. The slot plan is at
            // most 162 texture pixels wide and its cell size is bound by
            // whichever axis runs out first — so halving the width halved the
            // cells, leaving a small grid inside wide empty margins while short
            // lists had far more room than they needed. The band takes only the
            // height its content wants, so the cost is paid in the dimension
            // that has some to spare.
            //
            // The villager is the exception, and it's a shape argument rather
            // than a preference — see [ContainerExtras.prefersSideRail]. Its rail
            // is a *fixed narrow width* rather than a weight, so the grid keeps
            // everything else: one trade is three stacks across, and that is all
            // the rail ever needs to be.
            val extras = state.extras
            val sideRail = extras?.prefersSideRail == true

            if (extras != null && !sideRail) {
                ContainerExtrasBand(
                    extras = extras,
                    syncId = state.syncId,
                    fontSheet = fontSheet,
                    glintTexture = glintTexture,
                    itemIcon = itemIcon,
                    iconProvider = iconProvider,
                    onButton = onButton,
                    onTrade = onTrade,
                    onBeaconEffects = onBeaconEffects,
                    onRename = onRename,
                    modifier = Modifier.padding(bottom = PANEL_INSET)
                )
            }

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SlotGrid(
                    state = state,
                    plan = plan,
                    moveMode = moveMode,
                    fontSheet = fontSheet,
                    glintTexture = glintTexture,
                    itemIcon = itemIcon,
                    iconProvider = iconProvider,
                    onSlotClick = onSlotClick,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )

                if (extras != null && sideRail) {
                    ContainerExtrasBand(
                        extras = extras,
                        syncId = state.syncId,
                        fontSheet = fontSheet,
                        glintTexture = glintTexture,
                        itemIcon = itemIcon,
                        iconProvider = iconProvider,
                        onButton = onButton,
                        onTrade = onTrade,
                        onBeaconEffects = onBeaconEffects,
                        onRename = onRename,
                        modifier = Modifier
                            .width(SIDE_RAIL_WIDTH)
                            .fillMaxHeight()
                            .padding(start = PANEL_INSET)
                    )
                }
            }
        }
    }
}

/**
 * Mode buttons, plus whatever is currently on the cursor.
 *
 * The cursor readout is not decoration. A half-finished move — an item picked
 * up and not yet placed — is invisible otherwise, and the player is left
 * wondering where their diamonds went.
 */
@Composable
private fun ControlBar(
    state: ContainerState,
    moveMode: MoveMode,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onModeChange: (MoveMode) -> Unit,
    onDropCursor: () -> Unit
) {
    val gui = LocalMinecraftGui.current

    Row(
        modifier = Modifier.fillMaxWidth().height(CONTROL_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MoveMode.entries.forEach { mode ->
            MinecraftButton(
                text = mode.label,
                onClick = { onModeChange(mode) },
                selected = mode == moveMode,
                modifier = Modifier.height(CONTROL_BAR_HEIGHT - 4.dp)
            )
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Three things share this space, in this order of precedence,
            // because they're never all wanted at once.
            //
            // "Holding" wins outright: a half-finished move is invisible
            // otherwise. The container's name comes next -- with every block
            // now drawn in its own shape, saying *which* block reads as the
            // caption to that shape, and it's the only place the title appears.
            // The spread-gesture hint is last, and it doesn't lose much by it:
            // its job is discovery, and the case where it shows -- the player's
            // own inventory, which has no title -- is where a new player starts.
            val label = when {
                state.hasCursorStack -> "Holding"
                state.title != null -> state.title
                else -> "hold, then drag to spread"
            }
            if (fontSheet != null) {
                MinecraftText(
                    text = label,
                    fontSheet = fontSheet,
                    pixelSize = 1.5.dp,
                    color = PANEL_LABEL,
                    style = MinecraftTextStyle.PLAIN
                )
            } else {
                Text(text = label, color = PANEL_LABEL, fontSize = 10.sp)
            }
        }

        if (state.hasCursorStack) {
            Box(
                modifier = Modifier
                    .size(CONTROL_BAR_HEIGHT - 4.dp)
                    .vanillaSlot(gui.slot)
            ) {
                SlotContents(
                    stack = state.cursor,
                    fontSheet = fontSheet,
                    glintTexture = glintTexture,
                    itemIcon = itemIcon,
                    cellSize = CONTROL_BAR_HEIGHT - 4.dp
                )
            }
            MinecraftButton(
                text = "Drop",
                onClick = onDropCursor,
                modifier = Modifier.height(CONTROL_BAR_HEIGHT - 4.dp)
            )
        }
    }
}

/**
 * The grid, and the one gesture handler that drives it.
 *
 * **Hit testing is rect containment over the placed cells, driven by one gesture
 * handler.** It used to be arithmetic on a uniform row/column pitch, which no
 * longer describes anything: the container's cells are wherever vanilla puts
 * them, and a furnace's three are not on any grid. What stays load-bearing is
 * the *one handler* — giving each cell its own `pointerInput` would make a drag
 * stop existing the moment the finger left the cell it started in, which is
 * exactly the gesture this screen is for.
 *
 * The whole panel is drawn in texture pixels scaled by a single [unit], so the
 * cells, the group gaps and vanilla's progress art all share one coordinate
 * space and can't drift apart.
 */
@Composable
private fun SlotGrid(
    state: ContainerState,
    plan: ContainerPlan,
    moveMode: MoveMode,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    iconProvider: ResourcePackIconProvider,
    onSlotClick: (Int, Int, Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val gui = LocalMinecraftGui.current

        if (plan.width <= 0 || plan.height <= 0) return@BoxWithConstraints

        // One texture pixel, in device pixels, sized to whichever axis runs out
        // first so the whole thing is always on screen without scrolling -- an
        // inventory you have to scroll to drag across is worse than small slots.
        //
        // Floored to a whole device pixel because the slot sprite's bevel is one
        // texture pixel wide: at a fractional scale it lands two device pixels
        // wide on some cells and one on others, which reads as a rendering
        // fault rather than as a smaller inventory.
        val unitPx = with(density) {
            floor(
                minOf(
                    maxWidth.toPx() / plan.width,
                    maxHeight.toPx() / plan.height
                )
            ).coerceAtLeast(1f)
        }
        val unit = with(density) { unitPx.toDp() }
        val cell = unit * ContainerLayout.CELL

        // Centred in whatever's left over. The plan is a fixed aspect ratio and
        // one axis always has slack; pinning it top-left would leave the whole
        // inventory hanging off one corner of the panel.
        val originX = with(density) {
            ((maxWidth.toPx() - plan.width * unitPx) / 2f).coerceAtLeast(0f)
        }
        val originY = with(density) {
            ((maxHeight.toPx() - plan.height * unitPx) / 2f).coerceAtLeast(0f)
        }

        fun slotAt(position: Offset): Int? = plan.slotAt(
            (position.x - originX) / unitPx,
            (position.y - originY) / unitPx
        )

        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragPosition by remember { mutableStateOf(Offset.Zero) }
        var painted by remember { mutableStateOf(emptySet<Int>()) }

        // The gesture handler outlives the composition that started it, so
        // reading `state` or `moveMode` straight out of the closure would use
        // whatever they were when the pointer input last restarted. The keys
        // below deliberately don't include either -- restarting the handler
        // mid-drag would cancel the drag -- so they're read through these.
        val currentState by rememberUpdatedState(state)
        val currentMode by rememberUpdatedState(moveMode)
        val send by rememberUpdatedState(onSlotClick)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Tagged so the instrumented drag test can aim at the grid as a
                // whole -- the gesture handler lives here, not on the cells.
                .testTag(GRID_TEST_TAG)
                .pointerInput(state.syncId, plan, unitPx, originX, originY) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = slotAt(down.position) ?: return@awaitEachGesture

                        dragPosition = down.position

                        // Three outcomes race here, and only two of them are
                        // ambiguous: the finger moves (a drag), lifts (a tap),
                        // or does neither for long enough to mean "spread this
                        // stack". withTimeoutOrNull can't distinguish its own
                        // timeout from the lambda returning null, hence the
                        // flag -- a lift and a hold both come back null.
                        //
                        // This is AwaitPointerEventScope's *own* withTimeoutOrNull,
                        // not kotlinx.coroutines': it's driven by the pointer
                        // input frame clock, which is what makes the hold
                        // measurable from a test's virtual clock. Importing the
                        // coroutines one would compile and quietly break the
                        // instrumented test.
                        var lifted = false
                        val slopChange = withTimeoutOrNull(DISTRIBUTE_HOLD_MS) {
                            val change = awaitTouchSlopOrCancellation(down.id) { c, _ ->
                                c.consume()
                            }
                            if (change == null) lifted = true
                            change
                        }

                        val syncId = currentState.syncId
                        fun sendAll(clicks: List<SlotClick>) = clicks.forEach {
                            send(syncId, it.slotId, it.button, it.action)
                        }

                        if (slopChange == null && lifted) {
                            sendAll(
                                InventoryMoves.tap(
                                    mode = currentMode,
                                    holdingCursor = currentState.hasCursorStack,
                                    slot = start
                                )
                            )
                            return@awaitEachGesture
                        }

                        if (slopChange == null) {
                            // Held still: a distribute drag. The whole stack
                            // comes up first, because that's what gets divided,
                            // and the slots the finger paints are collected and
                            // sent as one quick-craft on release -- the same
                            // order vanilla's own HandledScreen uses.
                            if (!currentState.hasCursorStack) {
                                sendAll(listOf(InventoryMoves.pickUp(MoveMode.STACK, start)))
                            }
                            dragFrom = start
                            painted = setOf(start)

                            drag(down.id) { change ->
                                dragPosition = change.position
                                slotAt(change.position)?.let { painted = painted + it }
                                change.consume()
                            }

                            sendAll(InventoryMoves.distribute(currentMode, painted))

                            dragFrom = null
                            painted = emptySet()
                            return@awaitEachGesture
                        }

                        // An ordinary drag. The source is picked up immediately
                        // so the item visibly leaves its slot and follows the
                        // finger -- the game's own state does the moving, we
                        // just asked for the first half of it early. How *much*
                        // comes up is the mode's business.
                        val lifting = !currentState.hasCursorStack
                        if (lifting) {
                            sendAll(listOf(InventoryMoves.pickUp(currentMode, start)))
                        }
                        dragFrom = start
                        dragPosition = slopChange.position

                        drag(down.id) { change ->
                            dragPosition = change.position
                            change.consume()
                        }

                        val target = slotAt(dragPosition)
                        when {
                            target != null && target != start -> sendAll(
                                InventoryMoves.drop(
                                    mode = currentMode,
                                    source = start,
                                    target = target,
                                    targetIsEmpty = currentState.isEmptyAt(target),
                                    targetHasSameItem = currentState.itemsMatch(start, target)
                                )
                            )

                            // Released on the source, or off the grid entirely:
                            // put it back rather than dropping it on the floor,
                            // which is not what a slipped finger meant.
                            lifting -> sendAll(InventoryMoves.returnToSource(start))
                        }
                        dragFrom = null
                    }
                }
        ) {
            val originXDp = with(density) { originX.toDp() }
            val originYDp = with(density) { originY.toDp() }

            // Vanilla's progress art, behind the cells as vanilla draws it —
            // it's part of the container's background sheet there. Nothing in
            // it overlaps a slot, so the order is faithfulness rather than
            // necessity.
            ContainerDecorations(
                state = state,
                plan = plan,
                iconProvider = iconProvider,
                unit = unit,
                modifier = Modifier.offset(x = originXDp, y = originYDp)
            )

            plan.slots.forEach { placed ->
                val slot = state.slots.getOrNull(placed.slotIndex) ?: return@forEach

                Box(
                    modifier = Modifier
                        .offset(
                            x = originXDp + unit * placed.x,
                            y = originYDp + unit * placed.y
                        )
                        .size(cell)
                        // Cells carry no pointer input of their own; the tag
                        // is only so a test can read back where a slot ended
                        // up rather than recomputing the layout.
                        .testTag(slotTestTag(placed.slotIndex))
                ) {
                    SlotCell(
                        stack = slot.stack,
                        mayPlace = slot.mayPlace,
                        enabled = slot.enabled,
                        // Vanilla's own ghost placeholder for a slot that only
                        // takes one kind of thing. This is the single change
                        // that answers "which cell is the fuel" without this
                        // app inventing a label for it.
                        background = iconProvider.getSpriteBitmap(slot.background),
                        slotTexture = gui.slot,
                        highlight = when {
                            placed.slotIndex in painted -> DISTRIBUTE_TINT
                            dragFrom == placed.slotIndex -> DRAG_SOURCE_TINT
                            else -> null
                        },
                        fontSheet = fontSheet,
                        glintTexture = glintTexture,
                        itemIcon = itemIcon,
                        cellSize = cell
                    )
                }
            }

            // The dragged stack, under the finger. Drawn last and raised so it
            // passes over the slots rather than disappearing behind them.
            if (dragFrom != null && state.hasCursorStack) {
                Box(
                    modifier = Modifier
                        .zIndex(1f)
                        .offset(
                            x = with(density) { (dragPosition.x - unitPx * ContainerLayout.CELL / 2).toDp() },
                            y = with(density) { (dragPosition.y - unitPx * ContainerLayout.CELL / 2).toDp() }
                        )
                        .size(cell)
                ) {
                    SlotContents(
                        stack = state.cursor,
                        fontSheet = fontSheet,
                        glintTexture = glintTexture,
                        itemIcon = itemIcon,
                        cellSize = cell
                    )
                }
            }
        }
    }
}

/** Whether the slot at [index] is empty — an out-of-range index reads as empty
 *  rather than throwing, since it can only come from a stale grid. */
private fun ContainerState.isEmptyAt(index: Int): Boolean {
    val stack = slots.getOrNull(index)?.stack ?: return true
    return stack.itemId == InventoryMoves.AIR_ITEM_ID || stack.count <= 0
}

/** Whether two slots hold the same item — the question [InventoryMoves.drop]
 *  asks to decide whether a single item can be placed or the drag has to fall
 *  back to a swap. */
private fun ContainerState.itemsMatch(a: Int, b: Int): Boolean {
    val first = slots.getOrNull(a)?.stack ?: return false
    val second = slots.getOrNull(b)?.stack ?: return false
    return first.itemId == second.itemId
}

@Composable
private fun SlotCell(
    stack: HotbarSlot,
    mayPlace: Boolean,
    enabled: Boolean,
    /** Vanilla's empty-slot placeholder, or null for a slot that takes anything.
     *  Only drawn when the slot is actually empty — it's a hint about what goes
     *  here, and once something is here the hint is answered. */
    background: Bitmap?,
    slotTexture: ImageBitmap?,
    highlight: Color?,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    cellSize: Dp
) {
    val isEmpty = stack.itemId == InventoryMoves.AIR_ITEM_ID || stack.count <= 0

    Box(modifier = Modifier.fillMaxSize().vanillaSlot(slotTexture)) {
        if (isEmpty && background != null) {
            // Inset and sized exactly like an item, because that's what it
            // stands in for -- vanilla draws these into the same 16x16 hole.
            val inset = cellSize / SLOT_PIXELS
            Image(
                bitmap = background.asImageBitmap(),
                contentDescription = null,
                filterQuality = FilterQuality.None,
                alpha = GHOST_ALPHA,
                modifier = Modifier.fillMaxSize().padding(inset)
            )
        }

        SlotContents(stack, fontSheet, glintTexture, itemIcon, cellSize)

        // Drawn over the item rather than under it, the way vanilla's own hover
        // highlight is: a tint behind a full slot would be invisible.
        if (!enabled) SlotOverlay(SLOT_DISABLED)
        // A disabled slot won't take anything either, so saying both would
        // stack two tints and read as neither.
        else if (!mayPlace) SlotOverlay(SLOT_BLOCKED)
        if (highlight != null) SlotOverlay(highlight)
    }
}

/**
 * One stack drawn inside a cell: icon, glint, durability, count.
 *
 * Deliberately the same decorations, in the same order, as a hotbar slot —
 * they're the same thing on screen and a player shouldn't have to re-learn
 * them one tab over.
 */
@Composable
private fun SlotContents(
    stack: HotbarSlot,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    cellSize: Dp
) {
    if (stack.itemId == InventoryMoves.AIR_ITEM_ID || stack.count <= 0) return

    // Inset by the slot's own 1px bevel, so the 16x16 item sits in the 16x16
    // hole vanilla leaves for it inside an 18x18 cell.
    val inset = cellSize / SLOT_PIXELS
    val itemBox = cellSize - inset * 2

    Box(modifier = Modifier.fillMaxSize().padding(inset)) {
        ItemStackGraphic(
            stack = stack,
            icon = itemIcon(stack.itemId),
            fontSheet = fontSheet,
            glintTexture = glintTexture,
            unit = itemBox / ITEM_AREA_PIXELS
        )
    }
}
