package com.exojosh.minecraftsecondscreen.settings

/**
 * A block texture the second screen can tile behind everything.
 *
 * <h2>Why a fixed list rather than every block</h2>
 * The mod could serve any texture, and the app could offer a browser over all
 * ~800 of them. It doesn't, for two reasons that are properties of the job rather
 * than of the effort: a backdrop has to **tile seamlessly**, which most block
 * textures don't (anything with a directional or edge-matched pattern shows visible
 * seams at this scale), and it has to stay *quiet* — the HUD, the map and the
 * inventory are all drawn on top of it, and a busy backdrop costs legibility for
 * every one of them. These nine tile cleanly and span light to dark, warm to cool,
 * which is the range that actually changes how the screen reads.
 *
 * None of them carries a `.mcmeta` animation, which is a hard requirement rather
 * than a coincidence: `RepeatingTextureBackground` tiles a single still bitmap and
 * has no frame handling, so an animated texture would render as its whole vertical
 * filmstrip.
 *
 * [key] is persisted in `SharedPreferences` and must never change. [assetKey] must
 * match `HudAssetCatalog` on the mod side — note [DIRT] deliberately keeps the
 * plain `background` key it has always had, so the default costs no second copy of
 * the same PNG on the wire and older mod builds still serve it.
 */
enum class HudBackground(val key: String, val label: String, val assetKey: String) {
    DIRT("dirt", "Dirt", "background"),
    STONE("stone", "Stone", "background_stone"),
    COBBLESTONE("cobblestone", "Cobblestone", "background_cobblestone"),
    MOSSY_COBBLESTONE("mossy_cobblestone", "Mossy cobblestone", "background_mossy_cobblestone"),
    STONE_BRICKS("stone_bricks", "Stone bricks", "background_stone_bricks"),
    BRICKS("bricks", "Bricks", "background_bricks"),
    SAND("sand", "Sand", "background_sand"),
    OAK_PLANKS("oak_planks", "Oak planks", "background_oak_planks"),
    DARK_OAK_PLANKS("dark_oak_planks", "Dark oak planks", "background_dark_oak_planks");

    companion object {
        val DEFAULT = DIRT

        /**
         * Looks one up by its persisted key, falling back to [DEFAULT].
         *
         * An unknown key is what a preference written by a newer build looks like
         * after a downgrade, and the honest answer is the default rather than a
         * blank screen.
         */
        fun byKey(key: String?): HudBackground =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
