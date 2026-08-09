package com.exojosh.minecraftsecondscreen.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These strings are the whole of what the mod sees for the containers that aren't
 * driven by slot clicks. A wrong one doesn't crash — it enchants a different row,
 * selects a different trade, or clears a beacon the player had just set, on a live
 * world, and from the outside it looks like the button did nothing.
 */
class ContainerCommandsTest {

    @Test
    fun `button carries the sync id and the index`() {
        assertEquals("BUTTON:7,2", ContainerCommands.button(7, 2))
        // Row 0 is a real enchanting offer, not a missing one.
        assertEquals("BUTTON:7,0", ContainerCommands.button(7, 0))
    }

    @Test
    fun `trade and slot state encode as expected`() {
        assertEquals("TRADE:3,5", ContainerCommands.trade(3, 5))
        assertEquals("SLOTSTATE:3,4,1", ContainerCommands.slotState(3, 4, enabled = true))
        assertEquals("SLOTSTATE:3,4,0", ContainerCommands.slotState(3, 4, enabled = false))
    }

    /**
     * The trap: a null effect must be the dash sentinel, never an empty field.
     * `BEACON:1,,` is what a naive null renders to, and it's indistinguishable
     * from a deliberate clear — so a formatting slip would wipe a player's beacon.
     */
    @Test
    fun `a null beacon effect is a dash, not an empty field`() {
        assertEquals(
            "BEACON:1,minecraft:speed,minecraft:regeneration",
            ContainerCommands.beaconEffects(1, "minecraft:speed", "minecraft:regeneration")
        )
        assertEquals(
            "BEACON:1,minecraft:speed,-",
            ContainerCommands.beaconEffects(1, "minecraft:speed", null)
        )
        assertEquals("BEACON:1,-,-", ContainerCommands.beaconEffects(1, null, null))

        // No empty field anywhere, in any combination.
        for (primary in listOf(null, "minecraft:haste")) {
            for (secondary in listOf(null, "minecraft:regeneration")) {
                val command = ContainerCommands.beaconEffects(1, primary, secondary)
                assertFalse("empty field in '$command'", command.contains(",,"))
                assertFalse("trailing empty field in '$command'", command.endsWith(","))
            }
        }
    }

    /** A player's item name is arbitrary text; commas in it are ordinary, and the
     *  mod splits this command on the first comma only. */
    @Test
    fun `a rename keeps commas in the name`() {
        assertEquals("RENAME:2,Sting", ContainerCommands.rename(2, "Sting"))
        assertEquals(
            "RENAME:2,Bob's sword, mk II",
            ContainerCommands.rename(2, "Bob's sword, mk II")
        )
        // The syncId is still the first field, so the mod's split still finds it.
        assertTrue(ContainerCommands.rename(2, "a,b,c").startsWith("RENAME:2,"))
    }

    /** A newline would end the command early and have the remainder parsed as a
     *  different one — the protocol is newline-delimited. */
    @Test
    fun `a rename flattens newlines`() {
        assertEquals("RENAME:2,two lines", ContainerCommands.rename(2, "two\nlines"))
        assertEquals("RENAME:2,crlf  here", ContainerCommands.rename(2, "crlf\r\nhere"))
        for (name in listOf("a\nb", "a\rb", "a\r\nb")) {
            val command = ContainerCommands.rename(2, name)
            assertFalse(command.contains('\n'))
            assertFalse(command.contains('\r'))
        }
    }

    /** Clearing a rename is a real action — vanilla restores the item's own name
     *  for an empty string — so this must not be turned into a no-op. */
    @Test
    fun `an empty rename is still a command`() {
        assertEquals("RENAME:2,", ContainerCommands.rename(2, ""))
    }
}
