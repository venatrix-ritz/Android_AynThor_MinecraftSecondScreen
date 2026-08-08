package com.exojosh.minecraftsecondscreen.ui

import com.exojosh.minecraftsecondscreen.net.ContainerSlot
import com.exojosh.minecraftsecondscreen.net.ContainerState
import com.exojosh.minecraftsecondscreen.net.HotbarSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong position here doesn't crash — it puts a cell somewhere the finger
 * isn't, or worse, somewhere it *is* but belonging to a different slot, which on
 * a live world moves an item the player never touched. Same reason
 * [InventoryLayoutTest] exists, on layout that's no longer a grid.
 *
 * The coordinates fed in are the real ones out of the 1.21.11 handlers — furnace
 * input (56,17), fuel (56,53), output (116,35); a brewing stand's bottles at
 * (56,51), (79,58), (102,51). They're written out here rather than derived so
 * the assertions describe *this* layout's behaviour given the game's input,
 * which is the part that can regress.
 */
class ContainerLayoutTest {

    private val air = HotbarSlot("minecraft:air", 0, 0, 0, false)

    private fun slot(x: Int = -1, y: Int = -1) =
        ContainerSlot(air, mayPlace = true, x = x, y = y)

    /**
     * A container whose own slots sit at [positions], followed by the player's
     * 36. The player's coordinates are left absent because this layout doesn't
     * use them — their rows come from [InventoryLayout].
     */
    private fun container(
        handlerType: String,
        positions: List<Pair<Int, Int>>
    ) = ContainerState(
        syncId = 7,
        handlerType = handlerType,
        cursor = air,
        slots = positions.map { (x, y) -> slot(x, y) } + List(36) { slot() },
        playerStart = positions.size,
        hotbarStart = positions.size + 27,
        armorStart = -1,
        offhandIndex = -1
    )

    private fun ContainerPlan.at(slotIndex: Int): PlacedSlot? =
        slots.firstOrNull { it.slotIndex == slotIndex }

    private fun furnace() = container(
        "minecraft:furnace",
        listOf(56 to 17, 56 to 53, 116 to 35)
    )

    private fun brewingStand() = container(
        "minecraft:brewing_stand",
        listOf(56 to 51, 79 to 58, 102 to 51, 79 to 17, 17 to 17)
    )

    private fun doubleChest() = container(
        "minecraft:generic_9x6",
        (0 until 6).flatMap { row -> (0 until 9).map { col -> (8 + col * 18) to (18 + row * 18) } }
    )

    @Test
    fun `every slot of the handler is placed exactly once`() {
        for (state in listOf(furnace(), brewingStand(), doubleChest())) {
            val plan = ContainerLayout.plan(state)
            val placed = plan.slots.map { it.slotIndex }
            assertEquals("no slot may be placed twice", placed.size, placed.toSet().size)
            assertEquals(state.slots.indices.toSet(), placed.toSet())
        }
    }

    /** The whole point of the feature: a furnace's fuel slot is *below* its
     *  input, not beside it, and the output is off to the right of both. */
    @Test
    fun `a furnace stacks input over fuel with the output to the side`() {
        val plan = ContainerLayout.plan(furnace())
        val input = plan.at(0)!!
        val fuel = plan.at(1)!!
        val output = plan.at(2)!!

        assertEquals("input and fuel share a column", input.x, fuel.x)
        assertTrue("fuel sits below the input", fuel.y > input.y)
        assertTrue("the output is right of both", output.x > input.x)
        assertTrue("the output sits between them vertically", output.y in (input.y + 1) until fuel.y)
    }

    /** Vanilla's three bottle slots are an arc, not a row — the middle one hangs
     *  lower. Getting this from the coordinates is free; a 9-wide grid could
     *  never have expressed it. */
    @Test
    fun `a brewing stand keeps its bottles in vanilla's arc`() {
        val plan = ContainerLayout.plan(brewingStand())
        val left = plan.at(0)!!
        val middle = plan.at(1)!!
        val right = plan.at(2)!!
        val ingredient = plan.at(3)!!
        val fuel = plan.at(4)!!

        assertEquals("the outer bottles are level", left.y, right.y)
        assertTrue("the middle bottle hangs lower", middle.y > left.y)
        assertEquals("the middle bottle is under the ingredient", ingredient.x, middle.x)
        assertTrue("the ingredient is above the bottles", ingredient.y < left.y)
        assertTrue("fuel is left of everything else", fuel.x < left.x)
    }

    /** The container's top-left cell defines the origin, so nothing is drawn at
     *  a negative coordinate and no space is wasted above or left of it. */
    @Test
    fun `the container region starts at the canvas origin`() {
        for (state in listOf(furnace(), brewingStand(), doubleChest())) {
            val plan = ContainerLayout.plan(state)
            val containerCells = state.containerRange.mapNotNull { plan.at(it) }
            assertEquals(0, containerCells.minOf { it.y })
            assertTrue("nothing may be placed off-canvas", plan.slots.all { it.x >= 0 && it.y >= 0 })
            assertTrue(plan.slots.all { it.x + ContainerLayout.CELL <= plan.width })
            assertTrue(plan.slots.all { it.y + ContainerLayout.CELL <= plan.height })
        }
    }

    /** A furnace's region is 78px against the player's 162. Left-aligning it
     *  would hang the whole container off one edge of the panel. */
    @Test
    fun `a container narrower than the player's rows is centred over them`() {
        val plan = ContainerLayout.plan(furnace())
        val containerLeft = (0..2).minOf { plan.at(it)!!.x }
        val containerRight = (0..2).maxOf { plan.at(it)!!.x + ContainerLayout.CELL }

        assertEquals(InventoryLayout.COLUMNS * ContainerLayout.CELL, plan.width)
        assertEquals(
            "equal slack either side",
            containerLeft,
            plan.width - containerRight
        )
    }

    @Test
    fun `the player's rows sit below the container with a gap`() {
        val plan = ContainerLayout.plan(furnace())
        val containerBottom = (0..2).maxOf { plan.at(it)!!.y + ContainerLayout.CELL }
        val playerTop = (3..38).minOf { plan.at(it)!!.y }

        assertEquals(containerBottom + ContainerLayout.REGION_GAP, playerTop)
    }

    /** Vanilla's own 4px break between the main inventory and the hotbar. */
    @Test
    fun `the hotbar is separated from the main inventory`() {
        val plan = ContainerLayout.plan(furnace())
        val lastMainRow = (3..29).maxOf { plan.at(it)!!.y }
        val hotbar = (30..38).map { plan.at(it)!! }

        assertEquals("the hotbar is one row", 1, hotbar.map { it.y }.toSet().size)
        assertEquals(
            lastMainRow + ContainerLayout.CELL + ContainerLayout.GROUP_GAP,
            hotbar.first().y
        )
    }

    /**
     * A decoration is expressed in vanilla's coordinates and has to land in the
     * gap between the slots it belongs to. The furnace flame at (56,36) sits
     * between the input and the fuel, in the input's column.
     */
    @Test
    fun `a decoration offset lands vanilla's flame between input and fuel`() {
        val plan = ContainerLayout.plan(furnace())
        assertTrue(plan.positionedByVanilla)

        val flameX = 56 + plan.decorationOffsetX
        val flameY = 36 + plan.decorationOffsetY
        val input = plan.at(0)!!
        val fuel = plan.at(1)!!

        assertTrue("below the input", flameY >= input.y + ContainerLayout.CELL)
        assertTrue("above the fuel", flameY + 14 <= fuel.y)
        assertTrue(
            "within the input's column",
            flameX >= input.x && flameX + 14 <= input.x + ContainerLayout.CELL
        )
    }

    /** The player's own inventory screen keeps InventoryLayout's arrangement,
     *  including the deliberate gaps in the equipment rows. */
    @Test
    fun `the player's own inventory places its equipment block and no container`() {
        val state = ContainerState(
            syncId = 0,
            handlerType = null,
            cursor = air,
            slots = List(46) { slot() },
            playerStart = 9,
            hotbarStart = 36,
            armorStart = 5,
            offhandIndex = 45
        )
        val plan = ContainerLayout.plan(state)

        assertFalse("there is no container region to position", plan.positionedByVanilla)
        assertEquals((0..45).toSet(), plan.slots.map { it.slotIndex }.toSet())
        // Armor is a run at the start of the top row; the off-hand is under it.
        val armor = (5..8).map { plan.at(it)!! }
        assertEquals("armor is one row", 1, armor.map { it.y }.toSet().size)
        assertTrue("the off-hand is below the armor", plan.at(45)!!.y > armor.first().y)
    }

    /**
     * A mod build predating slot coordinates. The old chunked strip is the wrong
     * *shape*, but every slot still has to be tappable — degrading to "draws
     * nothing" would be worse than degrading to "draws it as a chest".
     */
    @Test
    fun `a handler with no coordinates falls back to a nine-wide strip`() {
        val state = ContainerState(
            syncId = 1,
            handlerType = "minecraft:furnace",
            cursor = air,
            slots = List(39) { slot() },
            playerStart = 3,
            hotbarStart = 30,
            armorStart = -1,
            offhandIndex = -1
        )
        val plan = ContainerLayout.plan(state)

        assertFalse(plan.positionedByVanilla)
        assertEquals((0..38).toSet(), plan.slots.map { it.slotIndex }.toSet())

        // Three cells in one row, left to right on the 18px pitch, and centred
        // over the player's rows like any other container region — the fallback
        // differs from the real thing in shape, not in how it's framed.
        val row = (0..2).map { plan.at(it)!! }
        assertEquals(1, row.map { it.y }.toSet().size)
        assertEquals(0, row.first().y)
        assertEquals(
            listOf(0, ContainerLayout.CELL, ContainerLayout.CELL * 2),
            row.map { it.x - row.first().x }
        )
        assertEquals(
            "equal slack either side",
            row.first().x,
            plan.width - (row.last().x + ContainerLayout.CELL)
        )
    }

    @Test
    fun `slotAt finds the cell under a point and nothing between cells`() {
        val plan = ContainerLayout.plan(furnace())
        val output = plan.at(2)!!

        assertEquals(2, plan.slotAt(output.x + 9f, output.y + 9f))
        assertEquals("the top-left corner is inside", 2, plan.slotAt(output.x + 0f, output.y + 0f))
        assertNull(
            "the bottom-right corner belongs to the next cell",
            plan.slotAt(
                output.x + ContainerLayout.CELL.toFloat(),
                output.y + ContainerLayout.CELL.toFloat()
            )
        )
        assertNull("off the canvas", plan.slotAt(-1f, -1f))
        assertNull("past the end", plan.slotAt(plan.width + 1f, plan.height + 1f))
    }

    /** The gap a furnace leaves beside its slots is real empty space, and a tap
     *  there must do nothing rather than hit the nearest cell. */
    @Test
    fun `a tap in a furnace's empty space hits no slot`() {
        val plan = ContainerLayout.plan(furnace())
        val input = plan.at(0)!!
        // Immediately right of the input, where vanilla's arrow region is.
        assertNull(plan.slotAt(input.x + ContainerLayout.CELL + 2f, input.y + 4f))
    }

    @Test
    fun `a chest fills the full width with six rows above the player's`() {
        val plan = ContainerLayout.plan(doubleChest())

        assertEquals(InventoryLayout.COLUMNS * ContainerLayout.CELL, plan.width)
        val containerRows = (0..53).map { plan.at(it)!!.y }.toSortedSet()
        assertEquals(6, containerRows.size)
        assertNotNull(plan.at(53))
        assertTrue(
            "the player's first row is below the chest's last",
            plan.at(54)!!.y > containerRows.last()
        )
    }
}
