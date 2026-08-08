package com.exojosh.minecraftsecondscreen.ui

import com.exojosh.minecraftsecondscreen.net.ContainerState

/** One slot placed on the panel's own canvas, at the top-left of its 18x18
 *  cell, in texture pixels. */
data class PlacedSlot(val slotIndex: Int, val x: Int, val y: Int)

/**
 * Every slot of the open handler, placed, plus what a decoration needs to land
 * in the right place over it.
 *
 * [width]/[height] are the canvas the caller scales to fit the panel. Working in
 * texture pixels rather than dp is what keeps one scale factor for the whole
 * screen: the slot sprite is 18x18, vanilla's progress art is positioned in the
 * same units, and a single divide gets all of it onto the display.
 */
data class ContainerPlan(
    val slots: List<PlacedSlot>,
    val width: Int,
    val height: Int,
    /**
     * Translation from vanilla's GUI coordinates into this canvas, so a
     * decoration vanilla draws at (79,34) goes at (79 + [decorationOffsetX],
     * 34 + [decorationOffsetY]).
     *
     * Only meaningful when [positionedByVanilla] — a fallback layout has no
     * relationship to vanilla's sheet, so there is nowhere correct to put an
     * arrow and none is drawn.
     */
    val decorationOffsetX: Int,
    val decorationOffsetY: Int,
    /** Whether the container's own region came from the game's coordinates
     *  rather than from the chunked fallback. */
    val positionedByVanilla: Boolean
) {
    /** The slot at a point on the canvas, or null between or outside the cells.
     *  Rect containment rather than arithmetic, because the container region is
     *  not on any regular grid — that's the point of it. */
    fun slotAt(x: Float, y: Float): Int? = slots.firstOrNull {
        x >= it.x && x < it.x + ContainerLayout.CELL &&
            y >= it.y && y < it.y + ContainerLayout.CELL
    }?.slotIndex
}

/**
 * Places the open screen handler's slots.
 *
 * **Compose- and Android-free so it can be unit tested** — same line as
 * [InventoryLayout], [HeartLayout], `InputSlotCodec` and [ChatLineWrapper], and
 * for the same reason: a wrong position here doesn't crash, it sends a click at
 * the wrong slot, which on a live server moves an item the player never touched.
 *
 * <h2>The container region comes from the game, not from a table here</h2>
 * Every non-chest container used to be laid out as a 9-wide grid of its slots,
 * because that is chest-shaped and a chest is what the code was written against.
 * Nothing else is: a crafting table came out as a strip of nine with its result
 * wrapped onto a phantom second row, a brewing stand as five identical cells
 * with no way to tell fuel from ingredient from bottle, a furnace as three
 * anonymous squares.
 *
 * The fix is not a per-container-type table on this side. That would only ever
 * cover the containers someone thought to enumerate, would need updating every
 * time Mojang adds a block, and would drop every modded container back onto the
 * strip. Instead the mod ships each slot's own `x`/`y` — vanilla's GUI
 * coordinates, straight off `Slot`'s public fields — and this places them where
 * the game itself would. A furnace's input lands above its fuel with the output
 * off to the right because that is literally where the handler put them; the
 * brewing stand's three bottles land in their arc for the same reason. No table,
 * and modded containers come out right for free.
 *
 * <h2>The player's own rows stay on their grid</h2>
 * The player's 36 slots (and the armor/crafting block of their own inventory
 * screen) keep [InventoryLayout]'s row arrangement rather than also being placed
 * by coordinate. Vanilla's sheet puts them at a fixed y with a good 13px of dead
 * space above, sized for a mouse and a 176x166 window; on a short handheld panel
 * that space is worth more as bigger slots. Laying the container region compactly
 * and stacking the player's rows straight underneath is why a furnace's three
 * slots come out large here and small in game.
 */
object ContainerLayout {

    /** Vanilla's slot pitch, and the size of the `container/slot` sprite. Cells
     *  sit edge to edge at this pitch, as vanilla's do — the sprite's own 1px
     *  bevel is the separator, so a gap between cells would be inventing a
     *  second one. */
    const val CELL = 18

    /** Between the player's main inventory and their hotbar. Vanilla's own gap
     *  (its main rows end at y=138 and the hotbar starts at 142). */
    const val GROUP_GAP = 4

    /**
     * Between the container's region and the player's.
     *
     * Deliberately smaller than vanilla's, which varies per container and is
     * generous because it has a whole window to spend. Here it only has to read
     * as "these are two different inventories", and every pixel not spent on it
     * makes every cell on the panel bigger.
     */
    const val REGION_GAP = 8

    fun plan(state: ContainerState): ContainerPlan {
        val placed = mutableListOf<PlacedSlot>()

        val container = containerCells(state)
        val containerWidth = container.maxOfOrNull { it.x + CELL } ?: 0
        val containerHeight = container.maxOfOrNull { it.y + CELL } ?: 0

        val playerRows = InventoryLayout.playerRows(state)
        val playerWidth = if (playerRows.isEmpty()) 0 else InventoryLayout.COLUMNS * CELL

        // The narrower region is centred over the wider one. A furnace's region
        // is 79px against the player's 162, and left-aligning it would hang the
        // whole container off one edge for no reason -- vanilla centres these
        // too.
        val width = maxOf(containerWidth, playerWidth)
        val containerShift = (width - containerWidth) / 2
        val playerShift = (width - playerWidth) / 2

        container.mapTo(placed) { it.copy(x = it.x + containerShift) }

        var y = if (container.isEmpty()) 0 else containerHeight + REGION_GAP
        playerRows.forEachIndexed { index, row ->
            if (index > 0) {
                y += CELL
                if (row.kind != playerRows[index - 1].kind) y += GROUP_GAP
            }
            row.cells.forEachIndexed { column, slotIndex ->
                if (slotIndex != null) {
                    placed += PlacedSlot(slotIndex, playerShift + column * CELL, y)
                }
            }
        }

        val height = placed.maxOfOrNull { it.y + CELL } ?: 0

        // The origin the container's cells were translated by, carried through
        // so a decoration expressed in vanilla's coordinates lands over the
        // slots it belongs to. Read back off the placed cells rather than
        // recomputed, so the two can't drift.
        val positioned = usesVanillaPositions(state)
        val originX = if (positioned) state.containerRange
            .mapNotNull { state.slots.getOrNull(it)?.x }
            .filter { it >= 0 }
            .minOrNull()?.minus(1) ?: 0
        else 0
        val originY = if (positioned) state.containerRange
            .mapNotNull { state.slots.getOrNull(it)?.y }
            .filter { it >= 0 }
            .minOrNull()?.minus(1) ?: 0
        else 0

        return ContainerPlan(
            slots = placed,
            width = width,
            height = height,
            decorationOffsetX = containerShift - originX,
            decorationOffsetY = -originY,
            positionedByVanilla = positioned
        )
    }

    private fun usesVanillaPositions(state: ContainerState) =
        !state.isPlayerInventory && state.hasSlotPositions

    /**
     * The container's own slots, normalised so the top-left cell sits at (0,0).
     *
     * Vanilla's `x`/`y` are the top-left of the 16x16 *item* area; the 18x18 cell
     * drawn around it starts one pixel up and to the left, which is where the -1
     * comes from. Getting that wrong would misalign every cell against its own
     * bevel by a pixel, and against the decorations by two.
     */
    private fun containerCells(state: ContainerState): List<PlacedSlot> {
        if (state.isPlayerInventory) return emptyList()

        val indices = state.containerRange.filter { state.slots.getOrNull(it) != null }
        if (indices.isEmpty()) return emptyList()

        if (!state.hasSlotPositions) {
            // A mod build predating slot coordinates. The old chunked strip is
            // wrong for most containers, but it is wrong in a way that still
            // lets every slot be tapped, which beats drawing nothing.
            return indices.mapIndexed { position, slotIndex ->
                PlacedSlot(
                    slotIndex,
                    (position % InventoryLayout.COLUMNS) * CELL,
                    (position / InventoryLayout.COLUMNS) * CELL
                )
            }
        }

        val cells = indices.mapNotNull { slotIndex ->
            val slot = state.slots[slotIndex]
            // A single slot with no coordinate inside a handler that has them is
            // not something vanilla does, but dropping it is the only safe
            // answer -- placing it at (0,0) would put it under a real one and
            // steal its taps.
            if (slot.x < 0 || slot.y < 0) null
            else PlacedSlot(slotIndex, slot.x - 1, slot.y - 1)
        }

        val minX = cells.minOf { it.x }
        val minY = cells.minOf { it.y }
        return cells.map { it.copy(x = it.x - minX, y = it.y - minY) }
    }
}
