package com.exojosh.minecraftsecondscreen.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.exojosh.minecraftsecondscreen.net.CreativeGroup
import com.exojosh.minecraftsecondscreen.net.HotbarSlot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dragging an item out of the creative picker onto a hotbar slot, exercised by
 * actually dragging.
 *
 * Instrumented for the same reason [InventoryScreenTest] is: the part that breaks
 * isn't arithmetic anyone can unit test, it's whether a finger that left one
 * composable and finished over another produces the right call. Here the two ends
 * of the gesture are in *different subtrees* — a `LazyVerticalGrid` cell and a row
 * below it — so the drag has to be tracked in coordinates both agree on, and
 * getting that wrong means the drop silently does nothing.
 *
 * **These tests once passed against a build that did not work on the device**, and
 * that's worth keeping in mind before trusting them. The gesture was
 * `detectDragGesturesAfterLongPress`, whose hold is a timeout on the pointer-input
 * clock — a clock `advanceEventTime` drives here and a still finger does not drive
 * in production, so the long press fired in every test and never fired on the
 * Thor. `ui/CreativeScreen.detectTapOrHoldDrag` now measures the hold from the
 * *events' own* `uptimeMillis`, which is the same quantity in both worlds; that's
 * what makes these assertions mean something. The end-to-end check is still a real
 * gesture on the device (`adb shell input … motionevent`, watching the mod's
 * hotbar) — see the mod's harness notes.
 */
@RunWith(AndroidJUnit4::class)
class CreativeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Give(val itemId: String, val slot: Int?)

    private val items = listOf(
        "minecraft:stone",
        "minecraft:granite",
        "minecraft:diorite",
        "minecraft:andesite",
        "minecraft:dirt",
        "minecraft:cobblestone",
        "minecraft:oak_log",
        "minecraft:sand",
        "minecraft:gravel"
    )

    /** A full hotbar, which is the case the drop has to overwrite. */
    private fun fullHotbar() = List(9) { HotbarSlot("minecraft:torch", 64, 0, 0, false) }

    private fun setContent(gives: MutableList<Give>, hotbar: List<HotbarSlot> = fullHotbar()) {
        rule.setContent {
            CreativeScreen(
                groups = listOf(
                    CreativeGroup(
                        id = "minecraft:building_blocks",
                        name = "Building Blocks",
                        icon = "minecraft:bricks",
                        items = items
                    )
                ),
                hotbar = hotbar,
                selectedSlot = 0,
                fontSheet = null,
                glintTexture = null,
                isConnected = true,
                itemIcon = { null },
                onRequestCatalogue = {},
                onGive = { itemId, slot -> gives += Give(itemId, slot) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    /**
     * Long-press an item, drag it onto a hotbar slot, let go.
     *
     * The dwell is what separates this from a scroll: a plain drag has to stay
     * available for moving through a thousand items.
     */
    private fun dragItemOntoHotbarSlot(itemId: String, slotIndex: Int) {
        // Resolved before the injection block -- looking a node up from inside one
        // re-enters the test framework mid-gesture.
        val cell = rule.onNodeWithContentDescription(itemId).fetchSemanticsNode().boundsInRoot
        val slot = rule.onNodeWithContentDescription("Hotbar slot ${slotIndex + 1}")
            .fetchSemanticsNode().boundsInRoot

        val start = Offset(cell.width / 2f, cell.height / 2f)
        val end = Offset(slot.center.x - cell.left, slot.center.y - cell.top)

        rule.onNodeWithContentDescription(itemId).performTouchInput {
            down(start)
            advanceEventTime(500)
            moveTo(start + (end - start) / 3f)
            moveTo(start + (end - start) * 2f / 3f)
            moveTo(end)
            up()
        }
        rule.waitForIdle()
    }

    /**
     * The headline behaviour: the drop names the slot the finger ended on.
     *
     * A full hotbar deliberately, because that's the case the player reported —
     * the give replaces whatever was there, and an occupied slot should make no
     * difference to the drop.
     */
    @Test
    fun draggingOntoAHotbarSlotGivesToThatSlot() {
        val gives = mutableListOf<Give>()
        setContent(gives)

        dragItemOntoHotbarSlot("minecraft:stone", 3)

        assertEquals(listOf(Give("minecraft:stone", 3)), gives)
    }

    /** Every slot, not just the one the maths happens to line up with. */
    @Test
    fun draggingOntoTheLastHotbarSlotGivesToThatSlot() {
        val gives = mutableListOf<Give>()
        setContent(gives)

        dragItemOntoHotbarSlot("minecraft:dirt", 8)

        assertEquals(listOf(Give("minecraft:dirt", 8)), gives)
    }

    /** A tap still means "wherever there's room", and must not also fire a drop. */
    @Test
    fun tappingAnItemGivesWithNoSlot() {
        val gives = mutableListOf<Give>()
        setContent(gives)

        val cell = rule.onNodeWithContentDescription("minecraft:stone").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription("minecraft:stone").performTouchInput {
            down(Offset(cell.width / 2f, cell.height / 2f))
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf(Give("minecraft:stone", null)), gives)
    }

    /**
     * The same drag, but with the finger *moving* while it waits — which is what a
     * real one does. A synthetic dwell holds a pixel-perfect position for the whole
     * timeout; a thumb on a handheld does not, and the grid underneath is a
     * thousand-item scroller waiting to claim exactly that movement.
     */
    @Test
    fun draggingStillWorksWhenTheFingerDriftsDuringTheHold() {
        val gives = mutableListOf<Give>()
        setContent(gives)

        val cell = rule.onNodeWithContentDescription("minecraft:stone").fetchSemanticsNode().boundsInRoot
        val slot = rule.onNodeWithContentDescription("Hotbar slot 3").fetchSemanticsNode().boundsInRoot

        val start = Offset(cell.width / 2f, cell.height / 2f)
        val end = Offset(slot.center.x - cell.left, slot.center.y - cell.top)

        rule.onNodeWithContentDescription("minecraft:stone").performTouchInput {
            down(start)
            // A few pixels of drift, spread across the hold, as a thumb produces.
            advanceEventTime(120)
            moveTo(start + Offset(2f, 3f))
            advanceEventTime(120)
            moveTo(start + Offset(-1f, 5f))
            advanceEventTime(300)
            moveTo(start + Offset(0f, 8f))
            moveTo(start + (end - start) / 2f)
            moveTo(end)
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf(Give("minecraft:stone", 2)), gives)
    }

    /** Released off the hotbar, nothing happens: a creative give overwrites, so a
     *  slipped finger must not have a slot guessed for it. */
    @Test
    fun releasingAwayFromTheHotbarGivesNothing() {
        val gives = mutableListOf<Give>()
        setContent(gives)

        val cell = rule.onNodeWithContentDescription("minecraft:stone").fetchSemanticsNode().boundsInRoot
        val start = Offset(cell.width / 2f, cell.height / 2f)

        rule.onNodeWithContentDescription("minecraft:stone").performTouchInput {
            down(start)
            advanceEventTime(500)
            moveTo(start + Offset(0f, 20f))
            moveTo(start + Offset(0f, 60f))
            up()
        }
        rule.waitForIdle()

        assertEquals(emptyList<Give>(), gives)
    }
}
