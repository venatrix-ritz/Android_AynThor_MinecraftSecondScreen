package com.exojosh.minecraftsecondscreen.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

private const val TAG = "ResourcePackIcons"

/** Where vanilla resource packs store the individual HUD sprite PNGs as of the
 *  1.21.2+ HUD sprite-split. Verify exact filenames (e.g. whether it's
 *  "full.png"/"half.png" nested under a "heart/" folder, vs. flat
 *  "heart_full.png") by unzipping any real resource pack and checking
 *  assets/minecraft/textures/gui/sprites/hud/ directly -- don't trust this
 *  blind, we've been burned by guessed Minecraft internals all session. */
private const val HUD_SPRITE_DIR = "minecraft/textures/gui/sprites/hud"

/** Minecraft's in-game font is a bitmap sheet, not a font file. Unlike the
 *  HUD sprites this hasn't moved around across versions -- it's been at
 *  textures/font/ascii.png for a long time. */
private const val FONT_SHEET_PATH = "minecraft/textures/font/ascii.png"

/** Tiled behind the whole HUD. */
private const val BACKGROUND_PATH = "minecraft/textures/block/dirt.png"

/** The paper sheet a held map is drawn on. 64x64, stretched by the caller. */
private const val MAP_BACKGROUND_PATH = "minecraft/textures/map/map_background.png"

/**
 * A HUD texture the app needs.
 *
 * [assetKey] is the primary source: the mod pushes every one of these over
 * the socket on connect, resolved through Minecraft's own resource manager,
 * so they already reflect the player's active resource pack. Keys must match
 * `HudAssetCatalog` on the mod side.
 *
 * [candidateNames] is the legacy fallback -- files hand-copied into
 * `assets/minecraft/textures/gui/sprites/hud/`. Kept so the app still renders
 * something against a mod build that predates asset delivery, but no longer
 * the expected setup. Note these guessed names were partly *wrong* (vanilla
 * nests hearts under `heart/` but keeps armor and food flat with an
 * underscore); the mod-side catalog is authoritative.
 */
enum class HudIcon(val assetKey: String, val candidateNames: List<String>) {
    HEART_FULL("heart_full", listOf("heart/full.png", "heart/full.webp", "heart_full.png")),
    HEART_HALF("heart_half", listOf("heart/half.png", "heart/half.webp", "heart_half.png")),
    HEART_CONTAINER("heart_container", listOf("heart/container.png", "heart/container.webp", "heart_container.png")),
    HEART_ABSORBING_FULL("heart_absorbing_full", listOf("heart/absorbing_full.png", "heart/absorbing_full.webp")),
    HEART_ABSORBING_HALF("heart_absorbing_half", listOf("heart/absorbing_half.png", "heart/absorbing_half.webp")),
    // Heart variants. Vanilla picks a set from the player's state and then a
    // hardcore variant of that set from the world, so every combination is its
    // own sprite. No bundled fallbacks -- these postdate that path entirely.
    HEART_POISONED_FULL("heart_poisoned_full", emptyList()),
    HEART_POISONED_HALF("heart_poisoned_half", emptyList()),
    HEART_WITHERED_FULL("heart_withered_full", emptyList()),
    HEART_WITHERED_HALF("heart_withered_half", emptyList()),
    HEART_FROZEN_FULL("heart_frozen_full", emptyList()),
    HEART_FROZEN_HALF("heart_frozen_half", emptyList()),
    HEART_HARDCORE_FULL("heart_hardcore_full", emptyList()),
    HEART_HARDCORE_HALF("heart_hardcore_half", emptyList()),
    HEART_POISONED_HARDCORE_FULL("heart_poisoned_hardcore_full", emptyList()),
    HEART_POISONED_HARDCORE_HALF("heart_poisoned_hardcore_half", emptyList()),
    HEART_WITHERED_HARDCORE_FULL("heart_withered_hardcore_full", emptyList()),
    HEART_WITHERED_HARDCORE_HALF("heart_withered_hardcore_half", emptyList()),
    HEART_FROZEN_HARDCORE_FULL("heart_frozen_hardcore_full", emptyList()),
    HEART_FROZEN_HARDCORE_HALF("heart_frozen_hardcore_half", emptyList()),
    HEART_CONTAINER_HARDCORE("heart_container_hardcore", emptyList()),
    HEART_ABSORBING_HARDCORE_FULL("heart_absorbing_hardcore_full", emptyList()),
    HEART_ABSORBING_HARDCORE_HALF("heart_absorbing_hardcore_half", emptyList()),
    ARMOR_FULL("armor_full", listOf("armor/full.png", "armor/full.webp", "armor_full.png")),
    ARMOR_HALF("armor_half", listOf("armor/half.png", "armor/half.webp", "armor_half.png")),
    ARMOR_EMPTY("armor_empty", listOf("armor/empty.png", "armor/empty.webp", "armor_empty.png")),
    FOOD_FULL("food_full", listOf("food_full.png", "food/full.webp", "food/full.png")),
    FOOD_HALF("food_half", listOf("food_half.png", "food/half.webp", "food/half.png")),
    FOOD_EMPTY("food_empty", listOf("food_empty.png", "food/empty.webp", "food/empty.png")),
    EXPERIENCE_BAR_BACKGROUND("xp_bar_background", listOf("experience_bar_background.png", "experience_bar_background.webp")),
    EXPERIENCE_BAR_PROGRESS("xp_bar_progress", listOf("experience_bar_progress.png", "experience_bar_progress.webp")),
    // Breathing bubbles, 9x9 each. air_empty is only drawn during vanilla's
    // brief pop animation, which BubbleRow doesn't reproduce -- so it isn't
    // listed here.
    AIR("air", listOf("air.png", "air.webp")),
    AIR_BURSTING("air_bursting", listOf("air_bursting.png", "air_bursting.webp")),
    // Vanilla's hotbar strip -- 182x22px logically (1px border + 9*20px slots + 1px border) --
    // and the separate selected-slot highlight overlay, 24x23px, meant to be centered on
    // whichever slot is selected.
    HOTBAR_BACKGROUND("hotbar", listOf("hotbar.png", "hotbar.webp")),
    HOTBAR_SELECTION("hotbar_selection", listOf("hotbar_selection.png", "hotbar_selection.webp")),
    // The off-hand box, 29x24px, on its own line under the hotbar and flush
    // with the right edge. The *right* variant, because its 8 blank columns
    // fall on the left -- right-aligning the left variant would inset the cell
    // from the screen edge by those 8 columns.
    HOTBAR_OFFHAND("hotbar_offhand_right", listOf("hotbar_offhand_right.png", "hotbar_offhand_right.webp")),

    // The enchantment shimmer, 128x128. Not a hud/ sprite -- it lives under
    // textures/misc/ -- so the legacy bundled-fallback path can't reach it and
    // the candidate list is empty. Socket or nothing, which is fine: without it
    // [ItemGlint] draws no glint and the item still renders correctly.
    ENCHANTED_GLINT("enchanted_glint_item", emptyList()),

    // GUI chrome: the game's own button in its three states, and the generic
    // 18x18 inventory cell. All under gui/sprites/widget/ and
    // gui/sprites/container/ rather than hud/, so socket-only like the glint --
    // and like it, missing them costs appearance and not function
    // ([MinecraftButton] and the inventory grid both fall back to drawing
    // vanilla's palette themselves).
    BUTTON("widget_button", emptyList()),
    BUTTON_HIGHLIGHTED("widget_button_highlighted", emptyList()),
    BUTTON_DISABLED("widget_button_disabled", emptyList()),
    SLOT("container_slot", emptyList());

    companion object {
        /** Not under the hud/ sprite dir, so they're socket-or-nothing on the
         *  fallback path -- see [ResourcePackIconProvider.getFontSheet],
         *  [ResourcePackIconProvider.getBackground] and
         *  [ResourcePackIconProvider.getMapBackground]. */
        const val FONT_ASCII = "font_ascii"
        const val BACKGROUND = "background"
        const val MAP_BACKGROUND = "map_background"
    }
}

/**
 * Resolves and caches HUD icon bitmaps from the active resource pack stack,
 * falling back to null (caller decides the fallback -- e.g. a bundled
 * drawable or the original drawn placeholder) when no active pack overrides
 * a given sprite.
 *
 * Requires a granted folder tree from GameDirectoryAccess pointing at the
 * .minecraft root (the folder containing resourcepacks/ and options.txt).
 */
class ResourcePackIconProvider(
    private val context: Context,
    /** Where socket-delivered textures come from. Null only in tests/previews
     *  with no live connection, which falls back to bundled assets. */
    private val repository: HudRepository? = null
) {

    private val cache = mutableMapOf<HudIcon, Bitmap?>()
    private var activePacksResolved = false
    private var activePackFolders: List<DocumentFile> = emptyList()

    /** Call once after confirming GameDirectoryAccess.hasValidAccess(). Cheap to call
     *  again later (e.g. after the player changes packs in-game) to pick up changes. */
    fun refresh() {
        cache.clear()
        //activePacksResolved = false
        //resolveActivePacks()
    }
    /*Old
    fun getIcon(icon: HudIcon): Bitmap? {
        if (!activePacksResolved) resolveActivePacks()
        return cache.getOrPut(icon) { searchActivePacksFor(icon) }
    }
    */

    /**
     * The requested HUD texture: the mod's copy if it has arrived over the
     * socket, otherwise a bundled fallback file.
     *
     * Reads `assetCache` directly rather than caching locally so Compose sees
     * the state read and recomposes as the bundle streams in.
     */
    fun getIcon(icon: HudIcon): Bitmap? =
        repository?.assetCache?.get(icon.assetKey) ?: loadIconFromAssets(icon)

    /**
     * The bitmap font sheet backing MinecraftText. Returned as a mutable
     * ARGB_8888 bitmap because the caller measures glyph widths by reading
     * pixels, which a hardware-backed bitmap won't allow.
     */
    fun getFontSheet(): Bitmap? =
        repository?.assetCache?.get(HudIcon.FONT_ASCII) ?: loadAsset(FONT_SHEET_PATH)

    /** The block texture tiled behind the HUD. */
    fun getBackground(): Bitmap? =
        repository?.assetCache?.get(HudIcon.BACKGROUND) ?: loadAsset(BACKGROUND_PATH)

    /** The paper sheet vanilla draws a held map on, used the same way behind
     *  the live map tile. Socket-only: it isn't under the hud/ sprite dir, so
     *  there's no bundled fallback candidate for it. */
    fun getMapBackground(): Bitmap? =
        repository?.assetCache?.get(HudIcon.MAP_BACKGROUND) ?: loadAsset(MAP_BACKGROUND_PATH)

    /**
     * A GUI sprite by its Minecraft sprite id, e.g.
     * `minecraft:container/slot/brewing_fuel`.
     *
     * **This is the one texture lookup with no enum entry, on purpose.** Every
     * other sprite here is one this app decided it wanted, so naming it in
     * [HudIcon] is right. These are named by the *game*, at runtime: the mod
     * reports whatever `Slot.getBackgroundSprite()` returned, and an enum can
     * only ever list what someone thought to enumerate. So the id is converted
     * to a catalog key by the same mechanical rule the mod registered it under
     * (`HudAssetCatalog.putSprite`), and a sprite the catalog doesn't serve — a
     * modded container's own placeholder — simply resolves to null and draws no
     * ghost. That degrades the way everything else here does: appearance, not
     * function.
     */
    fun getSpriteBitmap(spriteId: String?): Bitmap? {
        if (spriteId == null) return null
        val path = spriteId.substringAfter(':')
        return repository?.assetCache?.get(path.replace('/', '_'))
    }

    private fun loadIconFromAssets(icon: HudIcon): Bitmap? {
        Log.d(TAG, "Test, calling loadIconFromAssets")
        for (candidate in icon.candidateNames) {
            loadAsset("$HUD_SPRITE_DIR/$candidate")?.let { return it }
        }
        return null
    }

    private fun loadAsset(assetPath: String): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Pixel art: never let the decoder resample for screen density.
            inScaled = false
        }
        return try {
            context.assets.open(assetPath).use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)?.also {
                    Log.d(TAG, "SUCCESS: Loaded $assetPath")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "FAILED to load asset at path: $assetPath")
            null
        }
    }

    private fun resolveActivePacks() {
        activePacksResolved = true
        val root = GameDirectoryAccess.getSavedTreeUri(context)?.let {
            DocumentFile.fromTreeUri(context, it)
        } ?: return

        val optionsFile = root.findFile("options.txt") ?: run {
            Log.w(TAG, "options.txt not found in granted directory")
            return
        }
        val resourcePacksDir = root.findFile("resourcepacks")

        val activeNames = parseActivePackNames(optionsFile)
        // options.txt lists packs bottom-to-top (later entries override earlier
        // ones), so reverse to search highest-priority pack first.
        activePackFolders = activeNames.reversed().mapNotNull { name ->
            resolvePackEntry(resourcePacksDir, name)
        }
    }

    private fun parseActivePackNames(optionsFile: DocumentFile): List<String> {
        val text = context.contentResolver.openInputStream(optionsFile.uri)
            ?.bufferedReader()?.use { it.readText() } ?: return emptyList()

        val line = text.lineSequence().firstOrNull { it.startsWith("resourcePacks:") }
            ?: return emptyList()

        // Format looks like: resourcePacks:["vanilla","file/SomePack.zip"]
        return Regex("\"([^\"]+)\"").findAll(line)
            .map { it.groupValues[1] }
            .filter { it != "vanilla" } // vanilla isn't a real file to search
            .toList()
    }

    private fun resolvePackEntry(resourcePacksDir: DocumentFile?, name: String): DocumentFile? {
        val fileName = name.removePrefix("file/")
        return resourcePacksDir?.findFile(fileName)
    }

    private fun searchActivePacksFor(icon: HudIcon): Bitmap? {
        for (pack in activePackFolders) {
            for (candidate in icon.candidateNames) {
                val bitmap = if (pack.name?.endsWith(".zip") == true) {
                    findInZip(pack, candidate)
                } else {
                    findInFolder(pack, candidate)
                }
                if (bitmap != null) return bitmap
            }
        }
        return null // caller falls back to a bundled default
    }

    private fun findInFolder(packRoot: DocumentFile, relativePath: String): Bitmap? {
        var current: DocumentFile? = packRoot
        val parts = "$HUD_SPRITE_DIR/$relativePath".split("/")
        for (part in parts) {
            current = current?.findFile(part) ?: return null
        }
        return current?.uri?.let { decodeUri(it) }
    }

    private fun findInZip(packZip: DocumentFile, relativePath: String): Bitmap? {
        val targetEntry = "$HUD_SPRITE_DIR/$relativePath"
        val input: InputStream = context.contentResolver.openInputStream(packZip.uri) ?: return null
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == targetEntry) {
                    return BitmapFactory.decodeStream(zip)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun decodeUri(uri: android.net.Uri): Bitmap? =
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
}
