package com.exojosh.minecraftsecondscreen.ui

import com.exojosh.minecraftsecondscreen.net.ContainerState

/** What a row is for, so the renderer can space the groups apart without
 *  knowing any slot indices itself. */
enum class InventoryRowKind { CONTAINER, EQUIPMENT, MAIN, HOTBAR }

/**
 * One row of the grid. [cells] is always [InventoryLayout.COLUMNS] long; a null
 * is a gap, which is how the equipment row keeps armor on the left and the
 * crafting grid on the right without either moving when the other changes.
 */
data class InventoryRow(val cells: List<Int?>, val kind: InventoryRowKind)

data class InventoryGrid(val rows: List<InventoryRow>) {
    val rowCount: Int get() = rows.size

    /** The slot at a grid position, or null for a gap or out of bounds. */
    fun slotAt(row: Int, column: Int): Int? =
        rows.getOrNull(row)?.cells?.getOrNull(column)
}

/**
 * Turns a screen handler's flat slot list into rows on a 9-wide grid.
 *
 * **Compose- and Android-free so it can be unit tested** — same line as
 * [HeartLayout], `InputSlotCodec` and [ChatLineWrapper]. Getting a slot index
 * wrong here doesn't crash: it silently sends a click at the wrong slot, which
 * on a live server means an item moving somewhere the player didn't ask for.
 * That is precisely the class of bug worth pinning with tests.
 *
 * The index roles come from the mod ([ContainerState.playerStart] and friends),
 * not from constants restated here — vanilla's own `PlayerScreenHandler`
 * constants live on that side and only that side.
 *
 * **This is now the *player's* half of the layout.** The container's own slots
 * are placed by [ContainerLayout] from the coordinates the game reports, because
 * a 9-wide grid is chest-shaped and nothing but a chest is — see that file. What
 * stays here is the part a 9-wide grid genuinely fits: the player's 36 slots, and
 * the armor/off-hand/crafting block of their own inventory screen.
 */
object InventoryLayout {

    const val COLUMNS = 9

    /**
     * The player's own rows: their equipment block when this *is* their
     * inventory screen, then the main three rows, then the hotbar.
     *
     * Split out from [compute] so [ContainerLayout] can stack these under a
     * container region it placed by coordinate, without this having to know
     * anything about how that was done.
     */
    fun playerRows(state: ContainerState): List<InventoryRow> {
        val rows = mutableListOf<InventoryRow>()

        if (state.isPlayerInventory) rows += equipmentRows(state)

        // The off-hand is excluded from the trailing range, not just placed
        // separately. On the player's handler it is slot 45 — *after* the
        // hotbar — so `hotbarStart until size` swallows it, and it would be
        // drawn twice: once in the equipment row and again as a stray tenth
        // hotbar cell that wrapped onto a second row.
        val skip = state.offhandIndex

        rows += chunkRows(
            (state.playerStart until state.hotbarStart).filter { it != skip },
            InventoryRowKind.MAIN
        )
        rows += chunkRows(
            (state.hotbarStart until state.slots.size).filter { it != skip },
            InventoryRowKind.HOTBAR
        )

        return rows
    }

    /**
     * The whole handler as rows, container region included, on one 9-wide grid.
     *
     * Superseded by [ContainerLayout.plan] for drawing — a container's slots are
     * placed by vanilla's coordinates now — and kept because the row form is
     * still the honest description of the player's half, and because
     * `InventoryLayoutTest` pins the off-hand and equipment index arithmetic
     * through it.
     */
    fun compute(state: ContainerState): InventoryGrid {
        val rows = mutableListOf<InventoryRow>()

        if (!state.isPlayerInventory) {
            rows += chunkRows(state.containerRange.toList(), InventoryRowKind.CONTAINER)
        }
        rows += playerRows(state)

        return InventoryGrid(rows)
    }

    /**
     * Armor, off-hand and the 2x2 crafting grid.
     *
     * Two rows rather than one, because the pieces don't share a shape: four
     * armor slots read as a column of equipment, the crafting grid is a square
     * that has to stay square, and cramming both into nine cells puts the
     * crafting output where a fifth armor slot would look like it belongs.
     * Splitting them keeps armor top-left and the crafting square top-right,
     * with the result beside it — the same relative arrangement as vanilla's
     * own inventory screen.
     */
    private fun equipmentRows(state: ContainerState): List<InventoryRow> {
        val armor = state.armorStart
        val craftResult = 0
        val craftInput = 1

        val top = MutableList<Int?>(COLUMNS) { null }
        val bottom = MutableList<Int?>(COLUMNS) { null }

        for (i in 0 until 4) top[i] = armor + i
        bottom[0] = state.offhandIndex

        // Crafting: inputs 1..4 as a 2x2, result at 0.
        top[5] = craftInput
        top[6] = craftInput + 1
        bottom[5] = craftInput + 2
        bottom[6] = craftInput + 3
        top[8] = craftResult

        return listOf(
            InventoryRow(top, InventoryRowKind.EQUIPMENT),
            InventoryRow(bottom, InventoryRowKind.EQUIPMENT)
        )
    }

    private fun chunkRows(indices: List<Int>, kind: InventoryRowKind): List<InventoryRow> =
        indices.chunked(COLUMNS).map { chunk ->
            InventoryRow(List(COLUMNS) { chunk.getOrNull(it) }, kind)
        }
}
