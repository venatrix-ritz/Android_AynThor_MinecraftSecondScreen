package com.exojosh.minecraftsecondscreen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong match here doesn't crash — the second screen takes over for a block the
 * player switched off, or refuses to for one they left on, and the setting looks
 * broken rather than the matcher. Which is why this is kept away from Compose and
 * pinned here, same line as `InputSlotCodec` and `ContainerLayout`.
 */
class ContainerKindTest {

    @Test
    fun `the player's own handler is the inventory, however it is reported`() {
        assertEquals(ContainerKind.INVENTORY, ContainerKind.of(null, isPlayerInventory = true))
        // A handler type alongside isPlayerInventory shouldn't happen, but the
        // slot roles are the authority on this and must win.
        assertEquals(
            ContainerKind.INVENTORY,
            ContainerKind.of("minecraft:anvil", isPlayerInventory = true)
        )
    }

    /**
     * The two reasons a handler type is null are different things and must not
     * collapse: the player's own handler genuinely has none, and a modded handler
     * built without one also has none. Only the slot roles separate them.
     */
    @Test
    fun `a null type that is not the player's handler is Other, not Inventory`() {
        assertEquals(ContainerKind.OTHER, ContainerKind.of(null, isPlayerInventory = false))
    }

    @Test
    fun `all six chest sizes and shulkers share one toggle`() {
        val chestTypes = listOf(
            "minecraft:generic_9x1", "minecraft:generic_9x2", "minecraft:generic_9x3",
            "minecraft:generic_9x4", "minecraft:generic_9x5", "minecraft:generic_9x6",
            "minecraft:shulker_box"
        )
        for (type in chestTypes) {
            assertEquals(type, ContainerKind.CHEST, ContainerKind.of(type, false))
        }
    }

    @Test
    fun `the three cooking blocks share one toggle`() {
        for (type in listOf("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker")) {
            assertEquals(type, ContainerKind.FURNACE, ContainerKind.of(type, false))
        }
    }

    /** The blocks named in the request, each individually switchable. */
    @Test
    fun `the workstation blocks each get their own kind`() {
        assertEquals(ContainerKind.ANVIL, ContainerKind.of("minecraft:anvil", false))
        assertEquals(ContainerKind.GRINDSTONE, ContainerKind.of("minecraft:grindstone", false))
        assertEquals(ContainerKind.SMITHING, ContainerKind.of("minecraft:smithing", false))
        assertEquals(ContainerKind.STONECUTTER, ContainerKind.of("minecraft:stonecutter", false))
        assertEquals(ContainerKind.LOOM, ContainerKind.of("minecraft:loom", false))
        assertEquals(ContainerKind.BEACON, ContainerKind.of("minecraft:beacon", false))
        assertEquals(ContainerKind.VILLAGER, ContainerKind.of("minecraft:merchant", false))
        assertEquals(
            ContainerKind.CARTOGRAPHY,
            ContainerKind.of("minecraft:cartography_table", false)
        )
        assertEquals(ContainerKind.BREWING, ContainerKind.of("minecraft:brewing_stand", false))
        // Vanilla calls the enchanting table's handler "enchantment", not
        // "enchanting_table" -- getting that wrong would silently file it as Other.
        assertEquals(ContainerKind.ENCHANTING, ContainerKind.of("minecraft:enchantment", false))
    }

    /**
     * The creative inventory is what the inventory key opens in creative, so it
     * answers to the Inventory toggle. It has to be checked before everything else:
     * its handler has no type *and* isn't the player's handler, so it looks exactly
     * like a modded container and would otherwise land in Other -- which is the bug
     * that had the app switching to the Items tab and drawing unclickable cells.
     */
    @Test
    fun `the creative screen is the Inventory kind, not Other`() {
        assertEquals(
            ContainerKind.INVENTORY,
            ContainerKind.of(null, isPlayerInventory = false, creativeScreen = true)
        )
        // Same inputs without the flag are what the bug looked like.
        assertEquals(
            ContainerKind.OTHER,
            ContainerKind.of(null, isPlayerInventory = false, creativeScreen = false)
        )
    }

    @Test
    fun `an unknown or modded container falls into Other`() {
        assertEquals(ContainerKind.OTHER, ContainerKind.of("somemod:magic_box", false))
        assertEquals(ContainerKind.OTHER, ContainerKind.of("minecraft:not_a_real_handler", false))
    }

    /** The keys are persisted preference names. Renaming one silently resets the
     *  toggle for everyone who had it off, so a duplicate or a change is a bug. */
    @Test
    fun `every key is unique and stable`() {
        val keys = ContainerKind.entries.map { it.key }
        assertEquals("keys must be unique", keys.size, keys.toSet().size)
        assertTrue("keys must be storable as-is", keys.all { it.isNotBlank() && ' ' !in it })
        // A spot-check of the exact strings, so a rename has to be deliberate.
        assertEquals("inventory", ContainerKind.INVENTORY.key)
        assertEquals("grindstone", ContainerKind.GRINDSTONE.key)
        assertEquals("anvil", ContainerKind.ANVIL.key)
        assertEquals("other", ContainerKind.OTHER.key)
    }

    @Test
    fun `byKey round-trips every kind and rejects an unknown one`() {
        for (kind in ContainerKind.entries) {
            assertEquals(kind, ContainerKind.byKey(kind.key))
        }
        assertNull(ContainerKind.byKey("a_kind_from_a_newer_build"))
    }

    /**
     * No handler type may be claimed by two kinds.
     *
     * `of` resolves with `firstOrNull`, so a type listed twice would silently
     * resolve to whichever kind is declared earlier in the enum — and reordering
     * the enum for readability would then change behaviour.
     */
    @Test
    fun `no handler type is claimed by two kinds`() {
        val seen = mutableMapOf<String, ContainerKind>()
        for (kind in ContainerKind.entries) {
            for (type in typesClaimedBy(kind)) {
                val existing = seen.put(type, kind)
                assertNull("$type is claimed by both $existing and $kind", existing)
            }
        }
        // And every claimed type really does resolve to its owner.
        for ((type, kind) in seen) {
            assertEquals(type, kind, ContainerKind.of(type, isPlayerInventory = false))
        }
    }

    /** The enum keeps its type sets private, so this reaches them the only way a
     *  test legitimately can: by asking `of` about every type any kind resolves. */
    private fun typesClaimedBy(kind: ContainerKind): List<String> = ALL_KNOWN_TYPES
        .filter { ContainerKind.of(it, isPlayerInventory = false) == kind }

    private companion object {
        /** Every handler type this app expects to see, so the uniqueness check has
         *  something concrete to sweep. Vanilla's full set of container types. */
        val ALL_KNOWN_TYPES = listOf(
            "minecraft:generic_9x1", "minecraft:generic_9x2", "minecraft:generic_9x3",
            "minecraft:generic_9x4", "minecraft:generic_9x5", "minecraft:generic_9x6",
            "minecraft:shulker_box", "minecraft:hopper", "minecraft:generic_3x3",
            "minecraft:crafter_3x3", "minecraft:furnace", "minecraft:blast_furnace",
            "minecraft:smoker", "minecraft:crafting", "minecraft:anvil",
            "minecraft:grindstone", "minecraft:smithing", "minecraft:stonecutter",
            "minecraft:loom", "minecraft:cartography_table", "minecraft:enchantment",
            "minecraft:brewing_stand", "minecraft:beacon", "minecraft:merchant",
            "minecraft:horse"
        )
    }
}
