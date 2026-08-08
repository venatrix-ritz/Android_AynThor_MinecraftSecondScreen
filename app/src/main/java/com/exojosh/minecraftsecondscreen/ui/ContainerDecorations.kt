package com.exojosh.minecraftsecondscreen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.exojosh.minecraftsecondscreen.net.ContainerProgress
import com.exojosh.minecraftsecondscreen.net.ContainerState
import com.exojosh.minecraftsecondscreen.net.ResourcePackIconProvider
import kotlin.math.roundToInt

/**
 * The progress art vanilla draws between a container's slots — a furnace's flame
 * and arrow, a brewing stand's fuel bar, arrow and bubbles.
 *
 * <h2>Why this is worth drawing at all</h2>
 * The slot *positions* make a furnace read as a furnace, but not as a *working*
 * furnace. Without the flame there's no way to tell from the second screen that
 * the fuel ran out, and without the arrow no way to tell smelting from stuck.
 * Those are the two questions a player actually opens a furnace to answer.
 *
 * <h2>Positioned in vanilla's coordinates, not this app's</h2>
 * Every offset below is vanilla's own number, transcribed from the 1.21.11
 * screens rather than recalled: `AbstractFurnaceScreen` puts the flame at
 * (56,36) and the arrow at (79,34); `BrewingStandScreen` the fuel bar at (60,44),
 * the brew arrow at (97,16) and the bubbles at (63,14). They land correctly
 * because the slots around them were placed from the same sheet — see
 * [ContainerPlan.decorationOffsetX]. A layout that didn't come from vanilla's
 * coordinates has nowhere correct to put these, which is what
 * [ContainerPlan.positionedByVanilla] gates.
 *
 * <h2>Source-cropped, not scaled</h2>
 * A partly-filled bar is vanilla's sprite cropped to the filled pixels and drawn
 * into the same number of pixels, never the whole sprite squeezed — the same
 * trap `XpBar` documents. The crop is expressed against each sprite's *logical*
 * size and converted to the bitmap's real one, so a 2x resource pack crops at 2x
 * and still comes out the right shape.
 */
@Composable
fun ContainerDecorations(
    state: ContainerState,
    plan: ContainerPlan,
    iconProvider: ResourcePackIconProvider,
    /** Display size of one texture pixel — the same unit the slot cells use. */
    unit: Dp,
    modifier: Modifier = Modifier
) {
    val progress = state.progress ?: return
    if (!plan.positionedByVanilla) return

    val family = ContainerFamily.of(state.handlerType) ?: return

    // Resolved during composition rather than in the draw lambda: these come out
    // of a Compose-observable map, and reading them here is what makes the panel
    // recompose as the asset bundle streams in.
    val sprites = family.spriteIds(state.handlerType).associateWith { id ->
        iconProvider.getSpriteBitmap(id)?.asImageBitmap()
    }

    // Only the bubble column animates, and only while brewing. Nothing else here
    // moves on its own -- the rest changes when the mod says it changed.
    val animating = family == ContainerFamily.BREWING && progress.brewing
    val frameMillis = remember { mutableLongStateOf(0L) }
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        while (true) {
            withFrameMillis { frameMillis.longValue = it }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val pixel = unit.toPx()

        fun draw(
            spriteId: String,
            spriteWidth: Int,
            spriteHeight: Int,
            cropX: Int,
            cropY: Int,
            cropWidth: Int,
            cropHeight: Int,
            atX: Int,
            atY: Int
        ) {
            if (cropWidth <= 0 || cropHeight <= 0) return
            val image = sprites[spriteId] ?: return

            // A resource pack is free to ship these at any resolution, so the
            // crop is a fraction of the sprite rather than a pixel count.
            val scaleX = image.width.toFloat() / spriteWidth
            val scaleY = image.height.toFloat() / spriteHeight

            drawImage(
                image = image,
                srcOffset = IntOffset((cropX * scaleX).roundToInt(), (cropY * scaleY).roundToInt()),
                srcSize = IntSize(
                    (cropWidth * scaleX).roundToInt().coerceAtLeast(1),
                    (cropHeight * scaleY).roundToInt().coerceAtLeast(1)
                ),
                dstOffset = IntOffset(
                    ((atX + plan.decorationOffsetX) * pixel).roundToInt(),
                    ((atY + plan.decorationOffsetY) * pixel).roundToInt()
                ),
                dstSize = IntSize(
                    (cropWidth * pixel).roundToInt().coerceAtLeast(1),
                    (cropHeight * pixel).roundToInt().coerceAtLeast(1)
                ),
                filterQuality = FilterQuality.None
            )
        }

        when (family) {
            ContainerFamily.COOKING -> {
                val cooker = family.cookerName(state.handlerType)

                // The flame fills upward, so the crop is taken off the *bottom*
                // of the sprite and drawn at a top edge that moves down as it
                // shrinks. Getting this the naive way round leaves a flame that
                // burns from the top.
                val lit = (progress.litPixels ?: 0).coerceIn(0, FLAME_HEIGHT)
                draw(
                    spriteId = "minecraft:container/$cooker/lit_progress",
                    spriteWidth = FLAME_WIDTH, spriteHeight = FLAME_HEIGHT,
                    cropX = 0, cropY = FLAME_HEIGHT - lit,
                    cropWidth = FLAME_WIDTH, cropHeight = lit,
                    atX = FLAME_X, atY = FLAME_Y + FLAME_HEIGHT - lit
                )

                val cook = (progress.cookPixels ?: 0).coerceIn(0, ARROW_WIDTH)
                draw(
                    spriteId = "minecraft:container/$cooker/burn_progress",
                    spriteWidth = ARROW_WIDTH, spriteHeight = ARROW_HEIGHT,
                    cropX = 0, cropY = 0,
                    cropWidth = cook, cropHeight = ARROW_HEIGHT,
                    atX = ARROW_X, atY = ARROW_Y
                )
            }

            ContainerFamily.BREWING -> {
                val fuel = (progress.fuelPixels ?: 0).coerceIn(0, FUEL_WIDTH)
                draw(
                    spriteId = "minecraft:container/brewing_stand/fuel_length",
                    spriteWidth = FUEL_WIDTH, spriteHeight = FUEL_HEIGHT,
                    cropX = 0, cropY = 0,
                    cropWidth = fuel, cropHeight = FUEL_HEIGHT,
                    atX = FUEL_X, atY = FUEL_Y
                )

                val brew = (progress.brewPixels ?: 0).coerceIn(0, BREW_HEIGHT)
                draw(
                    spriteId = "minecraft:container/brewing_stand/brew_progress",
                    spriteWidth = BREW_WIDTH, spriteHeight = BREW_HEIGHT,
                    cropX = 0, cropY = 0,
                    cropWidth = BREW_WIDTH, cropHeight = brew,
                    atX = BREW_X, atY = BREW_Y
                )

                if (progress.brewing) {
                    val bubbles = bubbleHeight(frameMillis.longValue)
                    draw(
                        spriteId = "minecraft:container/brewing_stand/bubbles",
                        spriteWidth = BUBBLE_WIDTH, spriteHeight = BUBBLE_HEIGHT,
                        cropX = 0, cropY = BUBBLE_HEIGHT - bubbles,
                        cropWidth = BUBBLE_WIDTH, cropHeight = bubbles,
                        atX = BUBBLE_X, atY = BUBBLE_Y + BUBBLE_HEIGHT - bubbles
                    )
                }
            }
        }
    }
}

/**
 * The container families that have progress art.
 *
 * Deliberately a short closed list rather than something derived: unlike slot
 * *positions*, which the game reports and this app therefore needs no table for,
 * a progress indicator's position is drawn by a vanilla `Screen` subclass and
 * exists nowhere the mod can read it generically. So these are the ones whose
 * numbers were looked up, and any other container simply gets no decoration —
 * which is what every container got before.
 */
private enum class ContainerFamily {
    COOKING,
    BREWING;

    /** Which sprite folder a cooking handler's art lives in. The three cookers
     *  don't share one: a smoker's flame and a blast furnace's are separate
     *  sprites, and a resource pack may make them differ more. */
    fun cookerName(handlerType: String?): String = when (handlerType) {
        "minecraft:blast_furnace" -> "blast_furnace"
        "minecraft:smoker" -> "smoker"
        else -> "furnace"
    }

    fun spriteIds(handlerType: String?): List<String> = when (this) {
        COOKING -> cookerName(handlerType).let {
            listOf(
                "minecraft:container/$it/lit_progress",
                "minecraft:container/$it/burn_progress"
            )
        }

        BREWING -> listOf(
            "minecraft:container/brewing_stand/fuel_length",
            "minecraft:container/brewing_stand/brew_progress",
            "minecraft:container/brewing_stand/bubbles"
        )
    }

    companion object {
        fun of(handlerType: String?): ContainerFamily? = when (handlerType) {
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker" -> COOKING
            "minecraft:brewing_stand" -> BREWING
            else -> null
        }
    }
}

/**
 * Vanilla's bubble column heights, and its rate.
 *
 * The table is `BrewingStandScreen.BUBBLE_PROGRESS` verbatim. Vanilla indexes it
 * with `(brewTime / 2) % 7` — and brew *time* counts **down**, so the index walks
 * backwards through the table and the column grows before resetting. Stepping
 * forwards instead gives a column that drains, which is the wrong animation
 * played at the right speed.
 *
 * Driven from this app's frame clock rather than from `brewTime`, which the mod
 * deliberately doesn't send: it changes every other tick, and a message
 * broadcast on change would go out ten times a second to carry a phase that
 * means nothing. One tick is 50ms, so two ticks per step is [BUBBLE_STEP_MS] and
 * the rate matches the game's exactly.
 */
private val BUBBLE_PROGRESS = intArrayOf(29, 24, 20, 16, 11, 6, 0)
private const val BUBBLE_STEP_MS = 100L

private fun bubbleHeight(frameMillis: Long): Int {
    val step = (frameMillis / BUBBLE_STEP_MS).toInt()
    return BUBBLE_PROGRESS[(-step).mod(BUBBLE_PROGRESS.size)]
}

// Furnace family. Sprite sizes and offsets from AbstractFurnaceScreen.
private const val FLAME_X = 56
private const val FLAME_Y = 36
private const val FLAME_WIDTH = 14
private const val FLAME_HEIGHT = 14
private const val ARROW_X = 79
private const val ARROW_Y = 34
private const val ARROW_WIDTH = 24
private const val ARROW_HEIGHT = 16

// Brewing stand. From BrewingStandScreen.
private const val FUEL_X = 60
private const val FUEL_Y = 44
private const val FUEL_WIDTH = 18
private const val FUEL_HEIGHT = 4
private const val BREW_X = 97
private const val BREW_Y = 16
private const val BREW_WIDTH = 9
private const val BREW_HEIGHT = 28
private const val BUBBLE_X = 63
private const val BUBBLE_Y = 14
private const val BUBBLE_WIDTH = 12
private const val BUBBLE_HEIGHT = 29
