package com.exojosh.minecraftsecondscreen.settings

/**
 * A container, as something the player can switch the takeover off for.
 *
 * **Compose- and Android-free so it can be unit tested** — same line as
 * `InputSlotCodec` and `ContainerLayout`. A wrong match here doesn't crash: it
 * makes the second screen take over for a block the player switched off, or fail
 * to for one they left on, and the setting looks broken rather than the matcher.
 *
 * <h2>Why the app maps these and not the mod</h2>
 * The mod sends a raw `handlerType` (`minecraft:generic_9x3`), which is the right
 * thing for it to send — it's the game's own identity for the handler and it
 * doesn't presume what anyone wants to do with it. But a *setting* needs a stable
 * key and a name a person recognises, and "generic_9x3" is neither. Grouping the
 * six chest sizes under one "Chests" toggle is a product decision, so it lives on
 * the product side.
 *
 * [key] is persisted in `SharedPreferences` and must never change — rename the
 * constant if you like, never the key, or you silently reset the toggle for
 * everyone who had it off.
 */
enum class ContainerKind(
    val key: String,
    val label: String,
    /** Handler types this covers. Empty for [INVENTORY], which is matched by the
     *  absence of a handler type rather than by one. */
    private val handlerTypes: Set<String> = emptySet()
) {
    /** The player's own inventory. The mod reports a null handler type for it,
     *  because vanilla's player handler genuinely has none. */
    INVENTORY("inventory", "Inventory"),

    CHEST(
        "chest", "Chests & barrels",
        setOf(
            "minecraft:generic_9x1", "minecraft:generic_9x2", "minecraft:generic_9x3",
            "minecraft:generic_9x4", "minecraft:generic_9x5", "minecraft:generic_9x6",
            "minecraft:shulker_box"
        )
    ),
    SMALL_CONTAINER(
        "small_container", "Hoppers & dispensers",
        setOf("minecraft:hopper", "minecraft:generic_3x3", "minecraft:crafter_3x3")
    ),
    FURNACE(
        "furnace", "Furnaces",
        setOf("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker")
    ),
    CRAFTING("crafting", "Crafting table", setOf("minecraft:crafting")),
    ANVIL("anvil", "Anvil", setOf("minecraft:anvil")),
    GRINDSTONE("grindstone", "Grindstone", setOf("minecraft:grindstone")),
    SMITHING("smithing", "Smithing table", setOf("minecraft:smithing")),
    STONECUTTER("stonecutter", "Stonecutter", setOf("minecraft:stonecutter")),
    LOOM("loom", "Loom", setOf("minecraft:loom")),
    CARTOGRAPHY("cartography", "Cartography table", setOf("minecraft:cartography_table")),
    ENCHANTING("enchanting", "Enchanting table", setOf("minecraft:enchantment")),
    BREWING("brewing", "Brewing stand", setOf("minecraft:brewing_stand")),
    BEACON("beacon", "Beacon", setOf("minecraft:beacon")),
    VILLAGER("villager", "Villagers", setOf("minecraft:merchant")),
    HORSE("horse", "Horses & llamas", setOf("minecraft:horse")),

    /**
     * Anything not named above — a modded container, or a vanilla one added after
     * this list was written.
     *
     * It gets a toggle of its own rather than being silently excluded, because the
     * alternative is a modded block that either always takes over or never can,
     * with nothing in the settings to say so.
     */
    OTHER("other", "Other containers");

    companion object {
        /**
         * Which kind the open handler is.
         *
         * [isPlayerInventory] rather than a null check on the type, because the
         * mod reports a null handler type for two different reasons: the player's
         * own handler (which genuinely has none) and a modded handler built
         * without one. Those want different toggles, and only the slot roles tell
         * them apart.
         */
        fun of(
            handlerType: String?,
            isPlayerInventory: Boolean,
            creativeScreen: Boolean = false
        ): ContainerKind {
            // The creative inventory is what the inventory key opens in creative,
            // so it answers to the same toggle -- a player who doesn't want E to
            // take the screen over means that in both game modes. It has to be
            // checked first: the creative handler has no type and isn't the
            // player's handler, so it would otherwise fall through to OTHER.
            if (creativeScreen) return INVENTORY
            if (isPlayerInventory) return INVENTORY
            if (handlerType == null) return OTHER
            return entries.firstOrNull { handlerType in it.handlerTypes } ?: OTHER
        }

        /** Looks a kind up by its persisted key, or null if the key is unknown —
         *  which is what a preference written by a newer build looks like. */
        fun byKey(key: String): ContainerKind? = entries.firstOrNull { it.key == key }
    }
}
