package com.exojosh.minecraftsecondscreen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.exojosh.minecraftsecondscreen.net.HudIcon
import com.exojosh.minecraftsecondscreen.net.ResourcePackIconProvider
import kotlin.math.roundToInt

/**
 * The game's own GUI chrome — buttons, inventory slots, the grey panel behind
 * them — so the second screen's controls look like part of Minecraft rather
 * than like an Android app pointed at it.
 *
 * **Textures come over the socket like every other one** (`widget/button`,
 * `widget/button_highlighted`, `widget/button_disabled`, `container/slot`),
 * which means a resource pack restyling the GUI restyles this screen too. Every
 * painter here falls back to drawing vanilla's palette itself when a sprite
 * hasn't arrived, so a mod build predating those catalog keys costs appearance
 * and not function.
 *
 * The panel is the one thing drawn rather than sampled: vanilla has no
 * nine-sliceable panel sprite — `gui/container/inventory.png` is a fixed
 * 176x166 sheet whose interior is slots and a player portrait, so there's
 * nothing to stretch. Its edge structure and palette are transcribed from that
 * file (verified against the 1.21.11 client jar, not recalled) in
 * [drawVanillaPanel].
 */

/** Vanilla GUI palette, read out of the 1.21.11 textures. */
private val PANEL_FILL = Color(0xFFC6C6C6)
private val PANEL_HIGHLIGHT = Color(0xFFFFFFFF)
private val PANEL_SHADOW = Color(0xFF555555)
private val PANEL_OUTLINE = Color(0xFF000000)

val SLOT_FILL = Color(0xFF8B8B8B)
private val SLOT_SHADOW = Color(0xFF373737)
private val SLOT_HIGHLIGHT = Color(0xFFFFFFFF)

private val BUTTON_FILL = Color(0xFF6F6F6F)
private val BUTTON_FILL_HIGHLIGHTED = Color(0xFF757575)
private val BUTTON_FILL_DISABLED = Color(0xFF2C2C2C)
private val BUTTON_EDGE_LIGHT = Color(0xFFAAAAAA)
private val BUTTON_EDGE_DARK = Color(0xFF565656)

/** Vanilla's button label colours (`ClickableWidget`: white, or 0xA0A0A0 when
 *  the button is inactive). */
private val BUTTON_TEXT = Color(0xFFFFFFFF)
private val BUTTON_TEXT_DISABLED = Color(0xFFA0A0A0)

/** One vanilla GUI pixel, in dp. Everything here is sized in whole multiples of
 *  it, the way the game's own GUI scale works. */
val GUI_PIXEL = 2.dp

/** A slot cell is 18 GUI pixels square: a 16x16 item with a 1px bevel. */
const val SLOT_PIXELS = 18

/**
 * A nine-sliced sprite: the image, plus the border width **in that image's own
 * pixels**.
 *
 * The border is resolved once here rather than at each draw because it isn't a
 * constant of the app — it comes from the sprite's `.mcmeta` (3 for `button`
 * and `button_highlighted`, but **1** for `button_disabled`), and it scales
 * with the texture, so a pack shipping a 2x button has a 6px border.
 */
@Immutable
class NineSlice(
    val image: ImageBitmap,
    /** Border width in the delivered image's pixels — where to cut the source. */
    val sourceBorder: Int,
    /** The same border in GUI pixels — how wide to draw it. */
    val logicalBorder: Int
) {
    companion object {
        /** [declaredWidth] is the sprite's nine-slice width from its `.mcmeta`,
         *  which is also its size at 1x — the ratio to the delivered image is
         *  the pack's resolution multiplier. */
        fun of(image: ImageBitmap?, declaredWidth: Int, declaredBorder: Int): NineSlice? {
            if (image == null || image.width <= 0) return null
            val scale = image.width.toFloat() / declaredWidth
            return NineSlice(
                image = image,
                sourceBorder = (declaredBorder * scale).roundToInt().coerceAtLeast(1),
                logicalBorder = declaredBorder
            )
        }
    }
}

/** Every GUI texture this file draws with, plus the font its labels use. */
@Immutable
class MinecraftGuiTextures(
    val button: NineSlice?,
    val buttonHighlighted: NineSlice?,
    val buttonDisabled: NineSlice?,
    val slot: ImageBitmap?,
    val fontSheet: MinecraftFontSheet?
)

/**
 * The GUI textures in scope.
 *
 * A composition local rather than a parameter on every control: these are the
 * same four bitmaps for the whole window, threading them through the tab strip,
 * the input grid and the inventory's mode row would be four identical parameter
 * lists, and nothing below ever wants a *different* set. Dynamic
 * (`compositionLocalOf`, not `staticCompositionLocalOf`) because the textures
 * arrive over the socket after the first frame, and only the controls that
 * actually draw them should recompose when they do.
 */
val LocalMinecraftGui = compositionLocalOf {
    MinecraftGuiTextures(null, null, null, null, null)
}

/**
 * Assembles the GUI texture set from the socket-delivered assets.
 *
 * Reads through [ResourcePackIconProvider.getIcon], which reads the repository's
 * observable asset cache — so this recomposes as the bundle streams in and the
 * controls swap from their drawn fallbacks to the real sprites.
 */
@Composable
fun rememberMinecraftGuiTextures(
    iconProvider: ResourcePackIconProvider,
    fontSheet: MinecraftFontSheet?
): MinecraftGuiTextures {
    val button = iconProvider.getIcon(HudIcon.BUTTON)
    val highlighted = iconProvider.getIcon(HudIcon.BUTTON_HIGHLIGHTED)
    val disabled = iconProvider.getIcon(HudIcon.BUTTON_DISABLED)
    val slot = iconProvider.getIcon(HudIcon.SLOT)

    return remember(button, highlighted, disabled, slot, fontSheet) {
        MinecraftGuiTextures(
            // 200x20 with a border of 3 for the two live states and 1 for the
            // disabled one -- vanilla's own .mcmeta values, not a guess.
            button = NineSlice.of(button?.asImageBitmap(), declaredWidth = 200, declaredBorder = 3),
            buttonHighlighted =
                NineSlice.of(highlighted?.asImageBitmap(), declaredWidth = 200, declaredBorder = 3),
            buttonDisabled =
                NineSlice.of(disabled?.asImageBitmap(), declaredWidth = 200, declaredBorder = 1),
            slot = slot?.asImageBitmap(),
            fontSheet = fontSheet
        )
    }
}

/**
 * A Minecraft button.
 *
 * [selected] draws the highlighted (hover) sprite permanently, which is how a
 * chosen tab or an active mode reads — vanilla has no "selected button" state
 * of its own, because a desktop GUI never needs one to be sticky.
 *
 * The label is drawn from the bitmap font sheet when one has arrived, so it
 * matches the game's text everywhere else on this screen; without it, a plain
 * Compose `Text` stands in rather than the button coming up blank.
 */
@Composable
fun MinecraftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    pixelSize: Dp = GUI_PIXEL,
    textColor: Color? = null
) {
    val gui = LocalMinecraftGui.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val state = when {
        !enabled -> ButtonState.DISABLED
        pressed || selected -> ButtonState.HIGHLIGHTED
        else -> ButtonState.NORMAL
    }

    Box(
        modifier = modifier
            // The label is usually a Canvas of bitmap glyphs, which carries no
            // text for a test or a screen reader to find. This is what keeps
            // the button identifiable when the font sheet has arrived.
            .semantics { contentDescription = text }
            .clickable(
                interactionSource = interactionSource,
                // No ripple: a Material ripple over a pixel-art sprite reads as
                // a rendering fault. The sprite swap is the press feedback.
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .drawBehind { drawButton(gui, state, pixelSize.toPx()) }
            // Vanilla insets a button's label by its 3px border plus a little
            // air; without this a long label paints over the bevel.
            .padding(horizontal = pixelSize * 4, vertical = pixelSize * 2),
        contentAlignment = Alignment.Center
    ) {
        val color = textColor ?: if (enabled) BUTTON_TEXT else BUTTON_TEXT_DISABLED
        if (gui.fontSheet != null) {
            // The label shrinks to fit rather than overflowing the sprite.
            // Bitmap text neither wraps nor ellipsises, and the input grid
            // labels are whatever the game calls a binding ("Toggle
            // Perspective"), so without this a long one paints straight out
            // through the button's bevel and over its neighbour.
            BoxWithConstraints(contentAlignment = Alignment.Center) {
                val labelWidth = gui.fontSheet.measure(text).coerceAtLeast(1)
                MinecraftText(
                    text = text,
                    fontSheet = gui.fontSheet,
                    pixelSize = (maxWidth / labelWidth).coerceIn(1.dp, pixelSize),
                    color = color,
                    style = MinecraftTextStyle.SHADOW
                )
            }
        } else {
            androidx.compose.material3.Text(
                text = text,
                color = color,
                maxLines = 1
            )
        }
    }
}

private enum class ButtonState { NORMAL, HIGHLIGHTED, DISABLED }

private fun DrawScope.drawButton(
    gui: MinecraftGuiTextures,
    state: ButtonState,
    pixel: Float
) {
    val sprite = when (state) {
        ButtonState.NORMAL -> gui.button
        ButtonState.HIGHLIGHTED -> gui.buttonHighlighted
        ButtonState.DISABLED -> gui.buttonDisabled
    }
    if (sprite != null) {
        drawNineSlice(sprite, pixel)
        return
    }

    // Drawn stand-in, in the same palette as the sprite it replaces: 1px
    // outline, a 1px light edge top and left, a 2px dark edge along the bottom.
    val fill = when (state) {
        ButtonState.NORMAL -> BUTTON_FILL
        ButtonState.HIGHLIGHTED -> BUTTON_FILL_HIGHLIGHTED
        ButtonState.DISABLED -> BUTTON_FILL_DISABLED
    }
    val outline = if (state == ButtonState.HIGHLIGHTED) Color.White else PANEL_OUTLINE

    drawRect(outline)
    drawRect(fill, topLeft = Offset(pixel, pixel), size = Size(size.width - 2 * pixel, size.height - 2 * pixel))
    if (state != ButtonState.DISABLED) {
        drawRect(BUTTON_EDGE_LIGHT, Offset(pixel, pixel), Size(size.width - 2 * pixel, pixel))
        drawRect(BUTTON_EDGE_LIGHT, Offset(pixel, pixel), Size(pixel, size.height - 2 * pixel))
        drawRect(
            BUTTON_EDGE_DARK,
            Offset(pixel, size.height - 3 * pixel),
            Size(size.width - 2 * pixel, 2 * pixel)
        )
        drawRect(
            BUTTON_EDGE_DARK,
            Offset(size.width - 2 * pixel, pixel),
            Size(pixel, size.height - 2 * pixel)
        )
    }
}

/**
 * Draws a nine-sliced sprite over the whole draw area.
 *
 * Corners keep their pixel size, edges and centre stretch. Vanilla tiles those
 * regions rather than stretching them, which matters for a sprite with a
 * pattern in it — a button's interior is uniform noise, so the difference isn't
 * visible and one `drawImage` per region beats a tiling loop per frame.
 */
fun DrawScope.drawNineSlice(sprite: NineSlice, pixel: Float) {
    val image = sprite.image
    val src = sprite.sourceBorder
    // The destination border is the sprite's *logical* border (its size at 1x)
    // in GUI pixels, so a 2x resource pack doesn't come out with fat corners.
    val dst = (sprite.logicalBorder * pixel).roundToInt().coerceAtLeast(1)

    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    if (w <= 0 || h <= 0) return

    // Too small to hold two borders and anything between: stretch the sprite
    // whole rather than drawing overlapping corners.
    if (w < dst * 2 || h < dst * 2) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.None
        )
        return
    }

    val srcRight = image.width - src
    val srcBottom = image.height - src
    val srcMidW = srcRight - src
    val srcMidH = srcBottom - src
    val dstMidW = w - dst * 2
    val dstMidH = h - dst * 2

    fun piece(sx: Int, sy: Int, sw: Int, sh: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return
        drawImage(
            image = image,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(sw, sh),
            dstOffset = IntOffset(dx, dy),
            dstSize = IntSize(dw, dh),
            filterQuality = FilterQuality.None
        )
    }

    piece(0, 0, src, src, 0, 0, dst, dst)
    piece(srcRight, 0, src, src, w - dst, 0, dst, dst)
    piece(0, srcBottom, src, src, 0, h - dst, dst, dst)
    piece(srcRight, srcBottom, src, src, w - dst, h - dst, dst, dst)

    piece(src, 0, srcMidW, src, dst, 0, dstMidW, dst)
    piece(src, srcBottom, srcMidW, src, dst, h - dst, dstMidW, dst)
    piece(0, src, src, srcMidH, 0, dst, dst, dstMidH)
    piece(srcRight, src, src, srcMidH, w - dst, dst, dst, dstMidH)

    piece(src, src, srcMidW, srcMidH, dst, dst, dstMidW, dstMidH)
}

/** The vanilla GUI panel — the grey sheet every container screen is drawn on. */
fun Modifier.vanillaPanel(pixelSize: Dp = GUI_PIXEL): Modifier =
    drawBehind { drawVanillaPanel(pixelSize.toPx()) }

/**
 * Vanilla's container-screen background, at any size.
 *
 * Transcribed from `gui/container/inventory.png`'s edges: a 1px black outline
 * whose corners step in by two pixels, 2px of white inside the top and left, 2px
 * of #555555 inside the bottom and right, and #C6C6C6 everywhere else. That
 * corner step is the whole reason this isn't a plain bordered rectangle — it's
 * what makes the panel read as Minecraft's rather than as a grey box.
 */
private fun DrawScope.drawVanillaPanel(pixel: Float) {
    val w = size.width
    val h = size.height
    if (w < pixel * 8 || h < pixel * 8) return

    fun px(x: Float, y: Float, wide: Float, tall: Float, color: Color) =
        drawRect(color, Offset(x * pixel, y * pixel), Size(wide * pixel, tall * pixel))

    val cols = w / pixel
    val rows = h / pixel

    // The fill goes down as two overlapping bands rather than one rectangle, so
    // the 2x2 notch at each corner is left unpainted -- that notch is the
    // rounding, and filling it and then drawing the outline around it gives a
    // square corner with a stray pixel outside it.
    px(1f, 3f, cols - 2f, rows - 6f, PANEL_FILL)
    px(3f, 1f, cols - 6f, rows - 2f, PANEL_FILL)

    // 2px of white inside the top and left, 2px of #555555 inside the bottom
    // and right. Each stops short of the far corner by the notch.
    px(1f, 1f, cols - 4f, 2f, PANEL_HIGHLIGHT)
    px(1f, 1f, 2f, rows - 4f, PANEL_HIGHLIGHT)
    px(3f, rows - 3f, cols - 6f, 2f, PANEL_SHADOW)
    px(cols - 3f, 3f, 2f, rows - 5f, PANEL_SHADOW)

    // The outline: four segments held clear of each corner, plus the single
    // pixels that step diagonally between them.
    px(2f, 0f, cols - 5f, 1f, PANEL_OUTLINE)
    px(0f, 2f, 1f, rows - 5f, PANEL_OUTLINE)
    px(3f, rows - 1f, cols - 5f, 1f, PANEL_OUTLINE)
    px(cols - 1f, 3f, 1f, rows - 5f, PANEL_OUTLINE)
    px(1f, 1f, 1f, 1f, PANEL_OUTLINE)
    px(cols - 3f, 1f, 1f, 1f, PANEL_OUTLINE)
    px(cols - 2f, 2f, 1f, 1f, PANEL_OUTLINE)
    px(1f, rows - 3f, 1f, 1f, PANEL_OUTLINE)
    px(2f, rows - 2f, 1f, 1f, PANEL_OUTLINE)
    px(cols - 2f, rows - 2f, 1f, 1f, PANEL_OUTLINE)
}

/**
 * One inventory cell: vanilla's `container/slot` sprite, or the same 18x18
 * bevel drawn by hand if it hasn't arrived.
 *
 * Drawn as a background modifier rather than an `Image` so the cell's contents
 * (item icon, count, glint) stack on top without another layout box.
 */
fun Modifier.vanillaSlot(slot: ImageBitmap?): Modifier = drawBehind {
    if (slot != null) {
        drawImage(
            image = slot,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(slot.width, slot.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.None
        )
        return@drawBehind
    }

    val pixel = size.width / SLOT_PIXELS
    drawRect(SLOT_FILL)
    drawRect(SLOT_SHADOW, Offset.Zero, Size(size.width, pixel))
    drawRect(SLOT_SHADOW, Offset.Zero, Size(pixel, size.height))
    drawRect(SLOT_HIGHLIGHT, Offset(pixel, size.height - pixel), Size(size.width - pixel, pixel))
    drawRect(SLOT_HIGHLIGHT, Offset(size.width - pixel, pixel), Size(pixel, size.height - pixel))
}

/**
 * A tint over a slot, for the states this screen has that vanilla's mouse
 * pointer doesn't need: the source of a drag, and every slot a distribute drag
 * has painted so far.
 */
@Composable
fun SlotOverlay(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) { drawRect(color) }
}

/**
 * Marks a slot as the chosen one, by outlining it rather than washing over it.
 *
 * [SlotOverlay] is the right treatment for a transient state on a cell that is
 * usually empty — a drag source, a painted distribute target. It is the wrong one
 * for a *selection* that sits over a full cell indefinitely: a 50%-white wash over
 * a stonecutter's chosen recipe obliterated the icon, so the one cell telling the
 * player what they had picked was the one they couldn't read.
 *
 * Two pixels of vanilla's own highlight white, inset by nothing so it reads as the
 * cell's own border rather than as something floating inside it.
 */
fun Modifier.vanillaSlotHighlight(): Modifier = drawBehind {
    val pixel = (size.width / SLOT_PIXELS).coerceAtLeast(1f)
    val thickness = pixel * 2
    drawRect(SELECTION_BORDER, Offset.Zero, Size(size.width, thickness))
    drawRect(SELECTION_BORDER, Offset(0f, size.height - thickness),
        Size(size.width, thickness))
    drawRect(SELECTION_BORDER, Offset.Zero, Size(thickness, size.height))
    drawRect(SELECTION_BORDER, Offset(size.width - thickness, 0f),
        Size(thickness, size.height))
}

/** Vanilla's slot highlight white, used at full opacity for a border. */
private val SELECTION_BORDER = Color(0xFFFFFFFF)
