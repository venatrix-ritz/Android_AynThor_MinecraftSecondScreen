package com.exojosh.minecraftsecondscreen.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.exojosh.minecraftsecondscreen.net.ContainerSlot
import com.exojosh.minecraftsecondscreen.net.ContainerState
import com.exojosh.minecraftsecondscreen.net.HotbarSlot
import com.exojosh.minecraftsecondscreen.net.ResourcePackIconProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drag-to-move, exercised by actually dragging.
 *
 * This is an *instrumented* test rather than a JVM one because the thing worth
 * checking is the gesture: `ContainerLayout` and `InventoryLayout` already have
 * the slot maths pinned in their own JVM tests, but the wiring from "a finger
 * moved from here to there" to "these two clicks went to the server" only exists
 * inside a real Compose pointer pipeline. A wrong answer here isn't a crash —
 * it's an item moving somewhere the player didn't ask for.
 *
 * Both layout shapes are exercised, because they are genuinely different
 * problems: the player's inventory is one regular grid, and a container is
 * scattered cells at vanilla's coordinates with real dead space between them.
 *
 * It also happens to be the only way this UI can be checked on the Thor at all
 * when the AYN dual-screen assistant panel is sitting over the `Presentation`;
 * this renders in the activity's own window on the primary display.
 */
@RunWith(AndroidJUnit4::class)
class InventoryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Click(val syncId: Int, val slotId: Int, val button: Int, val action: String)

    private val stone = HotbarSlot("minecraft:stone", 42, 0, 0, false)
    private val empty = HotbarSlot("minecraft:air", 0, 0, 0, false)

    /** The player's own inventory, with something in the first hotbar slot. */
    private fun playerInventory(cursor: HotbarSlot = empty) = ContainerState(
        syncId = 7,
        handlerType = null,
        cursor = cursor,
        slots = List(46) { index ->
            ContainerSlot(if (index == 36) stone else empty, mayPlace = true)
        },
        playerStart = 9,
        hotbarStart = 36,
        armorStart = 5,
        offhandIndex = 45
    )

    /**
     * A furnace, at vanilla's own slot coordinates: input (56,17) above fuel
     * (56,53), output (116,35) off to the side.
     *
     * Worth having here and not only in `ContainerLayoutTest` because the shape
     * changes what a *drag* has to cope with. On the player's inventory every
     * cell is on one regular grid; here the cells are scattered with real empty
     * space between them, which is the case the old row-and-column hit testing
     * could not have expressed.
     */
    private fun furnace() = ContainerState(
        syncId = 4,
        handlerType = "minecraft:furnace",
        title = "Furnace",
        cursor = empty,
        slots = List(39) { index ->
            ContainerSlot(
                stack = if (index == 36) stone else empty,
                mayPlace = index != 2,
                x = when (index) {
                    0 -> 56
                    1 -> 56
                    2 -> 116
                    // The player's slots: this layout takes their rows from
                    // InventoryLayout, so their coordinates go unread.
                    else -> 8
                },
                y = when (index) {
                    0 -> 17
                    1 -> 53
                    2 -> 35
                    else -> 84
                }
            )
        },
        playerStart = 3,
        hotbarStart = 30,
        armorStart = -1,
        offhandIndex = -1
    )

    /** No repository, so no socket-delivered sprites — every placeholder and
     *  every piece of progress art resolves to null, which is the same path a
     *  fresh install takes before the asset bundle lands. */
    private fun iconProvider() = ResourcePackIconProvider(
        InstrumentationRegistry.getInstrumentation().targetContext
    )

    private fun setContent(
        state: ContainerState,
        clicks: MutableList<Click>
    ) {
        val provider = iconProvider()
        rule.setContent {
            InventoryScreen(
                state = state,
                fontSheet = null,
                glintTexture = null,
                isConnected = true,
                itemIcon = { null },
                iconProvider = provider,
                onSlotClick = { syncId, slotId, button, action ->
                    clicks += Click(syncId, slotId, button, action)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    /**
     * An ordinary (fast) drag from one slot to another.
     *
     * Several moves rather than one so the gesture unambiguously passes touch
     * slop, and no dwell before the first move — pausing on the source is what
     * turns this into a spread drag instead.
     */
    private fun dragBetweenSlots(source: Int, target: Int) {
        val from = slotCentreInGrid(source)
        val to = slotCentreInGrid(target)
        rule.onNodeWithTag(GRID_TEST_TAG).performTouchInput {
            down(from)
            moveTo(from + Offset((to.x - from.x) / 3f, 0f))
            moveTo(from + Offset((to.x - from.x) * 2f / 3f, 0f))
            moveTo(to)
            up()
        }
        rule.waitForIdle()
    }

    /** Centre of a slot, in coordinates relative to the grid node. */
    private fun slotCentreInGrid(slotIndex: Int): Offset {
        val grid = rule.onNodeWithTag(GRID_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val slot = rule.onNodeWithTag(slotTestTag(slotIndex)).fetchSemanticsNode().boundsInRoot
        return Offset(slot.center.x - grid.left, slot.center.y - grid.top)
    }

    @Test
    fun tappingASlotSendsOnePickup() {
        val clicks = mutableListOf<Click>()
        setContent(playerInventory(), clicks)

        val target = slotCentreInGrid(36)
        rule.onNodeWithTag(GRID_TEST_TAG).performTouchInput {
            down(target)
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf(Click(7, 36, 0, "PICKUP")), clicks)
    }

    /**
     * The headline behaviour: a drag has to come out as *two* clicks — pick the
     * source up, put it down on the target — because that's the only sequence
     * the server understands as a move.
     */
    @Test
    fun draggingBetweenSlotsSendsPickupFromSourceThenTarget() {
        val clicks = mutableListOf<Click>()
        setContent(playerInventory(), clicks)

        val from = slotCentreInGrid(36)
        val to = slotCentreInGrid(38)

        rule.onNodeWithTag(GRID_TEST_TAG).performTouchInput {
            down(from)
            // Several moves so the gesture passes touch slop and is unambiguous.
            moveTo(from + Offset((to.x - from.x) / 3f, 0f))
            moveTo(from + Offset((to.x - from.x) * 2f / 3f, 0f))
            moveTo(to)
            up()
        }
        rule.waitForIdle()

        assertEquals(2, clicks.size)
        assertEquals(Click(7, 36, 0, "PICKUP"), clicks[0])
        assertEquals(Click(7, 38, 0, "PICKUP"), clicks[1])
    }

    /** A slipped finger must put the stack back, not drop it on the floor. */
    @Test
    fun draggingOffTheGridReturnsTheStackToItsSlot() {
        val clicks = mutableListOf<Click>()
        setContent(playerInventory(), clicks)

        val from = slotCentreInGrid(36)

        rule.onNodeWithTag(GRID_TEST_TAG).performTouchInput {
            down(from)
            moveTo(from + Offset(0f, -40f))
            // Well above the grid, into the gap the control bar occupies.
            moveTo(Offset(from.x, -400f))
            up()
        }
        rule.waitForIdle()

        assertEquals(2, clicks.size)
        assertEquals(Click(7, 36, 0, "PICKUP"), clicks[0])
        assertEquals("must go back where it came from", Click(7, 36, 0, "PICKUP"), clicks[1])
    }

    @Test
    fun halfModeSendsTheRightMouseButton() {
        val clicks = mutableListOf<Click>()
        setContent(playerInventory(), clicks)

        rule.onNodeWithText("Half").performClick()
        val target = slotCentreInGrid(36)
        rule.onNodeWithTag(GRID_TEST_TAG).performTouchInput {
            down(target)
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf(Click(7, 36, 1, "PICKUP")), clicks)
    }

    @Test
    fun moveModeSendsQuickMove() {
        val clicks = mutableListOf<Click>()
        setContent(playerInventory(), clicks)

        rule.onNodeWithText("Move").performClick()
        val target = slotCentreInGrid(36)
        rule.onNodeWithTag(GRID_TEST_TAG).performTouchInput {
            down(target)
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf(Click(7, 36, 0, "QUICK_MOVE")), clicks)
    }

    /**
     * The bug the mode buttons were reported for: picking "Half" and then
     * *dragging* used to move the whole stack, because every drag was a plain
     * two-step left click regardless of mode.
     */
    @Test
    fun halfModeDragTakesHalfAndPlacesAllOfIt() {
        val clicks = mutableListOf<Click>()
        setContent(playerInventory(), clicks)

        rule.onNodeWithText("Half").performClick()
        dragBetweenSlots(36, 38)

        assertEquals(2, clicks.size)
        assertEquals("right click takes half", Click(7, 36, 1, "PICKUP"), clicks[0])
        assertEquals("left click puts all of that half down", Click(7, 38, 0, "PICKUP"), clicks[1])
    }

    /**
     * No vanilla click picks up exactly one item, so a single-item drag is
     * three clicks: lift the stack, right-click one into the target, give the
     * remainder back to the source.
     */
    @Test
    fun singleModeDragLeavesOneItemAndReturnsTheRest() {
        val clicks = mutableListOf<Click>()
        setContent(playerInventory(), clicks)

        rule.onNodeWithText("Single").performClick()
        dragBetweenSlots(36, 38)

        assertEquals(
            listOf(
                Click(7, 36, 0, "PICKUP"),
                Click(7, 38, 1, "PICKUP"),
                Click(7, 36, 0, "PICKUP")
            ),
            clicks
        )
    }

    /**
     * Hold, then drag: vanilla's spread-drag, sent as one quick-craft over
     * every slot the finger painted. The clicks only go out on release, which
     * is also how the game's own `HandledScreen` does it.
     */
    @Test
    fun holdingThenDraggingSpreadsAcrossThePaintedSlots() {
        val clicks = mutableListOf<Click>()
        setContent(playerInventory(), clicks)

        // Resolved before the injection block: looking a node up from inside it
        // would re-enter the test framework mid-gesture.
        val from = slotCentreInGrid(36)
        val middle = slotCentreInGrid(37)
        val to = slotCentreInGrid(38)

        rule.onNodeWithTag(GRID_TEST_TAG).performTouchInput {
            down(from)
            // Still for longer than the hold threshold: this is what separates
            // a spread from an ordinary move.
            advanceEventTime(500)
            moveTo(middle)
            moveTo(to)
            up()
        }
        rule.waitForIdle()

        assertEquals(
            listOf(
                // The whole stack has to be on the cursor first, or the server
                // abandons the drag at its first click.
                Click(7, 36, 0, "PICKUP"),
                Click(7, -999, 0, "QUICK_CRAFT"),
                Click(7, 36, 1, "QUICK_CRAFT"),
                Click(7, 37, 1, "QUICK_CRAFT"),
                Click(7, 38, 1, "QUICK_CRAFT"),
                Click(7, -999, 2, "QUICK_CRAFT")
            ),
            clicks
        )
    }

    /** The held stack has to be visible and disposable, or a half-finished move
     *  leaves the player with no idea where their item went. */
    @Test
    fun theHeldStackIsShownAndCanBeDropped() {
        val clicks = mutableListOf<Click>()
        setContent(playerInventory(cursor = stone), clicks)

        rule.onNodeWithText("Holding").assertIsDisplayed()
        rule.onNodeWithText("Drop").performClick()
        rule.waitForIdle()

        assertEquals(listOf(Click(7, -999, 0, "PICKUP")), clicks)
    }

    /**
     * The interaction this whole change was for: dragging out of a furnace's
     * output into the player's inventory.
     *
     * The furnace's cells aren't on the player's grid — the output sits alone to
     * the right, with empty space around it — so this is the drag that the old
     * row/column arithmetic had no way to track. It also confirms the two regions
     * share one gesture handler: the finger starts in the container's region and
     * finishes in the player's.
     */
    @Test
    fun draggingFromAFurnaceOutputIntoTheInventoryWorks() {
        val clicks = mutableListOf<Click>()
        setContent(furnace(), clicks)

        val from = slotCentreInGrid(2)
        val to = slotCentreInGrid(30)

        rule.onNodeWithTag(GRID_TEST_TAG).performTouchInput {
            down(from)
            moveTo(from + (to - from) / 3f)
            moveTo(from + (to - from) * 2f / 3f)
            moveTo(to)
            up()
        }
        rule.waitForIdle()

        assertEquals(2, clicks.size)
        assertEquals(Click(4, 2, 0, "PICKUP"), clicks[0])
        assertEquals(Click(4, 30, 0, "PICKUP"), clicks[1])
    }

    /** The container's name is the only label on the screen saying which block
     *  the shape above belongs to. */
    @Test
    fun theContainerTitleIsShown() {
        setContent(furnace(), mutableListOf())
        rule.onNodeWithText("Furnace").assertIsDisplayed()
    }

    /**
     * A furnace's three cells all reachable, and the space between them dead.
     *
     * That second half is the point: a tap in the gap where vanilla draws its
     * arrow must do nothing. Snapping it to the nearest cell would make the
     * output slot a much bigger target than it looks, and on a furnace the
     * neighbouring target is "take the smelted item".
     */
    @Test
    fun aFurnacePlacesItsThreeSlotsApartWithDeadSpaceBetween() {
        val clicks = mutableListOf<Click>()
        setContent(furnace(), clicks)

        for (index in 0..2) {
            rule.onNodeWithTag(slotTestTag(index)).assertIsDisplayed()
        }

        val input = rule.onNodeWithTag(slotTestTag(0)).fetchSemanticsNode().boundsInRoot
        val fuel = rule.onNodeWithTag(slotTestTag(1)).fetchSemanticsNode().boundsInRoot
        val output = rule.onNodeWithTag(slotTestTag(2)).fetchSemanticsNode().boundsInRoot

        assertEquals("input and fuel share a column", input.left, fuel.left, 0.5f)
        assertTrue("fuel is below the input", fuel.top > input.bottom)
        assertTrue("the output is right of both", output.left > input.right)

        // Immediately right of the input, in the arrow's territory.
        val grid = rule.onNodeWithTag(GRID_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val gap = Offset(
            input.right - grid.left + (output.left - input.right) / 2f,
            input.center.y - grid.top
        )
        rule.onNodeWithTag(GRID_TEST_TAG).performTouchInput {
            down(gap)
            up()
        }
        rule.waitForIdle()

        assertTrue("a tap in the gap must not click a slot", clicks.isEmpty())
    }

    @Test
    fun everySlotOfThePlayerInventoryIsOnScreen() {
        setContent(playerInventory(), mutableListOf())
        for (index in 0..45) {
            rule.onNodeWithTag(slotTestTag(index)).assertIsDisplayed()
        }
        // ...and none of them overlap, which the layout test can't see.
        val bounds = (0..45).map { rule.onNodeWithTag(slotTestTag(it)).fetchSemanticsNode().boundsInRoot }
        for (i in bounds.indices) {
            for (j in i + 1 until bounds.size) {
                val a = bounds[i]
                val b = bounds[j]
                val overlaps = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                assertTrue("slots $i and $j overlap", !overlaps)
            }
        }
    }
}
