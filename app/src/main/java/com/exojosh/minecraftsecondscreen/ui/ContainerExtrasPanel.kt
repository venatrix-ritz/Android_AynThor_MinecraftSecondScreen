package com.exojosh.minecraftsecondscreen.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.exojosh.minecraftsecondscreen.net.AnvilState
import com.exojosh.minecraftsecondscreen.net.BeaconState
import com.exojosh.minecraftsecondscreen.net.ContainerExtras
import com.exojosh.minecraftsecondscreen.net.EnchantingOffers
import com.exojosh.minecraftsecondscreen.net.HotbarSlot
import com.exojosh.minecraftsecondscreen.net.MerchantTrades
import com.exojosh.minecraftsecondscreen.net.SelectableList

/**
 * The controls for a container whose function isn't its slots, as a band across
 * the top of the Items tab.
 *
 * <h2>Why these exist</h2>
 * Slot geometry made every container legible; it did nothing for the six whose
 * actual function is a *choice*. An enchanting table is three offers, a
 * stonecutter a recipe list, a loom a pattern list, a villager a trade list, a
 * beacon a pair of effects, an anvil a name. Those were drawable but dead — the
 * second screen had no way to describe the choice or send it.
 *
 * <h2>A band, not a side panel</h2>
 * These controls used to sit in a column beside the grid, splitting the width
 * roughly in half. That was the wrong axis to spend. The slot plan is at most 162
 * texture pixels wide and its cell size is bound by whichever axis runs out
 * first — so halving the width halved the cell size, and the grid ended up small
 * with wide empty margins while the panel's own lists had far more room than a
 * short list needs.
 *
 * Across the top instead, every list runs horizontally and scrolls, the band
 * takes only the height its content needs, and the grid below gets the entire
 * width. A stonecutter's recipes read as a filmstrip rather than a column, which
 * also happens to suit a thumb better than a vertical list on a screen this
 * shape.
 *
 * The band wraps its own height, so a beacon (which genuinely needs two rows) can
 * have them without every other container paying for the space.
 *
 * <h2>Built from the vocabulary that already exists</h2>
 * Every control here is [MinecraftButton], [Modifier.vanillaSlot] and
 * [MinecraftText] — deliberately, and it's why this whole feature needed **no new
 * textures**. Vanilla has bespoke sprites for each of these lists
 * (`container/stonecutter/recipe`, `container/enchanting_table/level_1`,
 * `container/beacon/button`), and using them would have meant shipping ~25 more
 * assets to reproduce widgets the app already draws in the game's own style.
 *
 * Icons come through the existing `ICON:` request path, so a stonecutter's
 * recipes and a villager's trades show real items with no new machinery.
 */
@Composable
fun ContainerExtrasBand(
    extras: ContainerExtras,
    syncId: Int,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onButton: (syncId: Int, buttonId: Int) -> Unit,
    onTrade: (syncId: Int, index: Int) -> Unit,
    onBeaconEffects: (syncId: Int, primary: String?, secondary: String?) -> Unit,
    onRename: (syncId: Int, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        when {
            extras.enchanting != null -> EnchantingRow(
                offers = extras.enchanting,
                fontSheet = fontSheet,
                onPick = { row -> onButton(syncId, row) }
            )

            extras.stonecutter != null -> OptionStrip(
                list = extras.stonecutter,
                title = "Cut",
                fontSheet = fontSheet,
                glintTexture = glintTexture,
                itemIcon = itemIcon,
                onPick = { index -> onButton(syncId, index) }
            )

            // Patterns aren't items, so there's nothing to draw a strip of icons
            // from -- named buttons are the honest version rather than a row of
            // blanks. See ContainerOption.itemId.
            extras.loom != null -> LabelStrip(
                list = extras.loom,
                title = "Pattern",
                fontSheet = fontSheet,
                onPick = { index -> onButton(syncId, index) }
            )

            extras.merchant != null -> TradeStrip(
                trades = extras.merchant,
                fontSheet = fontSheet,
                glintTexture = glintTexture,
                itemIcon = itemIcon,
                onPick = { index -> onTrade(syncId, index) }
            )

            extras.beacon != null -> BeaconRows(
                beacon = extras.beacon,
                fontSheet = fontSheet,
                onApply = { primary, secondary -> onBeaconEffects(syncId, primary, secondary) }
            )

            extras.anvil != null -> AnvilRow(
                anvil = extras.anvil,
                fontSheet = fontSheet,
                onRename = { name -> onRename(syncId, name) }
            )
        }
    }
}

/**
 * The enchanting table's three offers, side by side.
 *
 * Three is a small fixed number, so they get equal thirds of the width rather
 * than a scroller — an offer that needed scrolling to reach would be worse than
 * one that's simply narrow.
 *
 * **The name already carries its level.** `Enchantment.getName(entry, level)` on
 * the mod side returns "Sharpness II", numeral included; appending one here
 * printed "Sharpness II II". Nothing on this side formats a level.
 *
 * A row the game would refuse is disabled rather than hidden, because "you need
 * 3 more levels" is the answer the player came for — hiding it would just look
 * like the table was broken.
 */
@Composable
private fun EnchantingRow(
    offers: EnchantingOffers,
    fontSheet: MinecraftFontSheet?,
    onPick: (Int) -> Unit
) {
    BandHeader("Lapis ${offers.lapisCount} - Level ${offers.playerLevel}", fontSheet)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        offers.offers.forEachIndexed { row, offer ->
            // A row with no offer at all: nothing in the slot, or not enough
            // bookshelves. Kept as an empty disabled cell so the table holds its
            // three-across shape rather than reflowing under the finger.
            val empty = offer.cost <= 0

            Column(modifier = Modifier.weight(1f)) {
                MinecraftButton(
                    text = if (empty) "-" else offer.name,
                    onClick = { onPick(row) },
                    enabled = offer.available,
                    modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)
                )
                if (!empty) {
                    BandLabel(
                        "${offer.lapisCost} lapis, ${offer.cost} levels",
                        fontSheet,
                        color = if (offer.available) PANEL_TEXT else PANEL_TEXT_DIM
                    )
                }
            }
        }
    }
}

/** A stonecutter's recipes, as a horizontal filmstrip of item icons. Icons rather
 *  than names because a player recognises the shape of a stair block faster than
 *  they read "Stone Brick Stairs", which is why vanilla's own stonecutter is a
 *  grid of them. */
@Composable
private fun OptionStrip(
    list: SelectableList,
    title: String,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onPick: (Int) -> Unit
) {
    val gui = LocalMinecraftGui.current

    if (list.options.isEmpty()) {
        BandHeader("$title - put something in", fontSheet)
        return
    }
    BandHeader(title, fontSheet)

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        // Iterated as sent: the index *is* the button id, so this must never be
        // sorted or filtered (see ContainerOption).
        itemsIndexed(list.options) { index, option ->
            Box(
                modifier = Modifier
                    .size(OPTION_CELL)
                    .vanillaSlot(gui.slot)
                    // The only text a test or a screen reader can find here --
                    // the cell's content is a bitmap icon.
                    .semantics { contentDescription = option.label }
                    .clickable { onPick(index) }
            ) {
                if (option.itemId != null) {
                    Box(modifier = Modifier.fillMaxSize().padding(OPTION_CELL / SLOT_PIXELS)) {
                        ItemStackGraphic(
                            stack = HotbarSlot(option.itemId, 1, 0, 0, false),
                            icon = itemIcon(option.itemId),
                            fontSheet = fontSheet,
                            glintTexture = glintTexture,
                            unit = (OPTION_CELL - OPTION_CELL / SLOT_PIXELS * 2) / ITEM_AREA_PIXELS
                        )
                    }
                }
                // A border rather than a wash: the previous 50%-white overlay
                // obliterated the icon it was marking.
                if (index == list.selected) SelectionBorder()
            }
        }
    }
}

/** A loom's patterns, by name — there is no icon to show for a banner pattern. */
@Composable
private fun LabelStrip(
    list: SelectableList,
    title: String,
    fontSheet: MinecraftFontSheet?,
    onPick: (Int) -> Unit
) {
    if (list.options.isEmpty()) {
        BandHeader("$title - add a banner and a dye", fontSheet)
        return
    }
    BandHeader(title, fontSheet)

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        itemsIndexed(list.options) { index, option ->
            MinecraftButton(
                text = option.label,
                onClick = { onPick(index) },
                selected = index == list.selected,
                modifier = Modifier.height(ROW_HEIGHT)
            )
        }
    }
}

/**
 * A villager's trades, as horizontal cards.
 *
 * Tapping one selects the trade, which moves the container's own cost and result
 * slots — the exchange itself still happens by taking the result out of the grid
 * below. That split is vanilla's, not a shortcut: selecting a trade and
 * completing it are two different actions in game too.
 */
@Composable
private fun TradeStrip(
    trades: MerchantTrades,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onPick: (Int) -> Unit
) {
    val gui = LocalMinecraftGui.current

    BandHeader(
        if (trades.leveled) "Trades - ${trades.experience} xp" else "Trades",
        fontSheet
    )
    if (trades.trades.isEmpty()) {
        BandLabel("No trades", fontSheet)
        return
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP * 2)
    ) {
        itemsIndexed(trades.trades) { index, trade ->
            Row(
                modifier = Modifier
                    .height(TRADE_CELL + ROW_GAP * 2)
                    // Out of stock stays on screen and greyed. Removing the row
                    // would renumber every trade after it while the player was
                    // looking at it, and the index is what gets sent.
                    .background(if (trade.disabled) TRADE_DISABLED else Color.Transparent)
                    .padding(horizontal = ROW_GAP)
                    .semantics { contentDescription = "Trade ${index + 1}" }
                    .clickable(enabled = !trade.disabled) { onPick(index) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TradeSlot(trade.firstCost, gui.slot, fontSheet, glintTexture, itemIcon)
                if (trade.secondCost.itemId != InventoryMoves.AIR_ITEM_ID) {
                    TradeSlot(trade.secondCost, gui.slot, fontSheet, glintTexture, itemIcon)
                }
                // ASCII only: the bitmap font sheet covers 0x00-0xFF, and an
                // arrow glyph came out as a literal '?'.
                BandLabel(">", fontSheet)
                TradeSlot(trade.sell, gui.slot, fontSheet, glintTexture, itemIcon)
                if (trade.disabled) BandLabel("out", fontSheet, PANEL_TEXT_DIM)
            }
        }
    }
}

/** One stack in a trade card. */
@Composable
private fun TradeSlot(
    stack: HotbarSlot,
    slotTexture: ImageBitmap?,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?
) {
    Box(modifier = Modifier.size(TRADE_CELL).vanillaSlot(slotTexture)) {
        if (stack.itemId != InventoryMoves.AIR_ITEM_ID && stack.count > 0) {
            Box(modifier = Modifier.fillMaxSize().padding(TRADE_CELL / SLOT_PIXELS)) {
                ItemStackGraphic(
                    stack = stack,
                    icon = itemIcon(stack.itemId),
                    fontSheet = fontSheet,
                    glintTexture = glintTexture,
                    unit = (TRADE_CELL - TRADE_CELL / SLOT_PIXELS * 2) / ITEM_AREA_PIXELS
                )
            }
        }
    }
}

/**
 * The beacon's two effect choices.
 *
 * The one container that genuinely needs two rows, which is why the band wraps
 * its height rather than fixing it: primary effects scroll along the first, and
 * the secondary pair plus Apply sit on the second.
 *
 * **Both effects go at once and the selection is local until Apply.** The packet
 * behind this carries the pair, so sending on each tap would fire a packet per tap
 * and — worse — clear the secondary the moment the player touched the primary.
 *
 * Locked options are shown disabled with the level they need rather than omitted,
 * so a player with a half-built beacon can see what finishing it would buy.
 */
@Composable
private fun BeaconRows(
    beacon: BeaconState,
    fontSheet: MinecraftFontSheet?,
    onApply: (String?, String?) -> Unit
) {
    // Keyed on what the server says, so a change made on the top screen replaces
    // the pending selection rather than being overwritten by it on next Apply.
    var primary by remember(beacon.primary, beacon.level) { mutableStateOf(beacon.primary) }
    var secondary by remember(beacon.secondary, beacon.level) { mutableStateOf(beacon.secondary) }

    BandHeader(
        "Beacon - level ${beacon.level}" +
            if (beacon.hasPayment) "" else " - needs payment",
        fontSheet
    )

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        items(beacon.primaryOptions) { effect ->
            val unlocked = beacon.level >= effect.tier
            MinecraftButton(
                text = if (unlocked) effect.name else "${effect.name} (${effect.tier})",
                onClick = { primary = effect.id },
                enabled = unlocked,
                selected = primary == effect.id,
                modifier = Modifier.height(ROW_HEIGHT)
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        beacon.secondaryOptions.forEach { effect ->
            val unlocked = beacon.level >= effect.tier
            MinecraftButton(
                text = if (unlocked) effect.name else "${effect.name} (${effect.tier})",
                onClick = { secondary = effect.id },
                enabled = unlocked,
                selected = secondary != null && secondary == effect.id,
                modifier = Modifier.height(ROW_HEIGHT)
            )
        }
        // Vanilla's second secondary option: spend the payment on doubling the
        // primary rather than adding regeneration. It's the primary's own id in
        // both slots, which is why the mod doesn't list it as an effect.
        MinecraftButton(
            text = "Amplify",
            onClick = { secondary = primary },
            enabled = beacon.level >= AMPLIFY_LEVEL && primary != null,
            selected = secondary != null && secondary == primary,
            modifier = Modifier.height(ROW_HEIGHT)
        )
        Spacer(modifier = Modifier.weight(1f))
        MinecraftButton(
            text = "Apply",
            onClick = { onApply(primary, secondary) },
            // Vanilla's own gate: no payment, no effect change.
            enabled = beacon.hasPayment && primary != null,
            modifier = Modifier.height(ROW_HEIGHT)
        )
    }
}

/**
 * The anvil: a name field, the price, and a keyboard only while typing.
 *
 * **The keyboard is no longer part of the panel.** It used to be pinned open
 * underneath, which cost four rows of height permanently and left the grid with
 * half a screen — for a control the player touches once per repair. Now the field
 * sits in the band and the keyboard appears under it only after the field is
 * tapped, so the grid keeps the space the rest of the time.
 *
 * It is still the app's own [ChatKeyboard] rather than an IME, for the reason that
 * component exists: Android shows a soft keyboard for a presentation display on
 * the *primary* screen, which on this device is the one running the game.
 *
 * The name goes out as it's typed rather than on a confirm, because the anvil's
 * *price* depends on it and that's what the player needs to see. Vanilla does the
 * same.
 */
@Composable
private fun AnvilRow(
    anvil: AnvilState,
    fontSheet: MinecraftFontSheet?,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }

    fun update(next: String) {
        name = next
        onRename(next)
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(FIELD_BACKGROUND)
                .clickable { typing = !typing }
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BandLabel(
                name.ifEmpty { "Tap to name" },
                fontSheet,
                if (name.isEmpty()) PANEL_TEXT_DIM else Color.White
            )
        }

        // Zero cost with no result means "nothing to do", not "free".
        BandLabel(
            when {
                !anvil.hasResult -> "No result"
                anvil.levelCost <= 0 -> "Free"
                else -> "${anvil.levelCost} levels"
            },
            fontSheet
        )

        MinecraftButton(
            text = if (typing) "Done" else "Name",
            onClick = { typing = !typing },
            selected = typing,
            modifier = Modifier.height(ROW_HEIGHT)
        )
    }

    if (typing) {
        ChatKeyboard(
            onType = { typed -> update(name + typed) },
            onBackspace = { if (name.isNotEmpty()) update(name.dropLast(1)) },
            // The name is already sent as it's typed, so there is nothing left
            // to transmit -- this only puts the keyboard away.
            onSend = { typing = false },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Vanilla's slot highlight is a wash, which works over an empty cell and ruins a
 *  full one. A border marks the selected recipe without hiding what was chosen. */
@Composable
private fun SelectionBorder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .vanillaSlotHighlight()
    )
}

@Composable
private fun BandHeader(text: String, fontSheet: MinecraftFontSheet?) {
    BandLabel(text, fontSheet, Color.White, TITLE_PIXEL)
}

@Composable
private fun BandLabel(
    text: String,
    fontSheet: MinecraftFontSheet?,
    color: Color = PANEL_TEXT,
    pixelSize: Dp = LABEL_PIXEL
) {
    if (fontSheet != null) {
        MinecraftText(
            text = text,
            fontSheet = fontSheet,
            pixelSize = pixelSize,
            color = color,
            style = MinecraftTextStyle.SHADOW
        )
    } else {
        // The asset bundle is still streaming in. Plain Compose text rather than
        // nothing, so the band is usable on a fresh connection.
        androidx.compose.material3.Text(text = text, color = color)
    }
}

/** The beacon level at which the secondary slot unlocks. Vanilla's secondary tier
 *  is `EFFECTS_BY_LEVEL` entry 3, i.e. a full four-layer pyramid. */
private const val AMPLIFY_LEVEL = 4

private val ROW_HEIGHT = 26.dp
private val ROW_GAP = 3.dp
private val OPTION_CELL = 38.dp
private val TRADE_CELL = 26.dp
private val TITLE_PIXEL = 1.75.dp
private val LABEL_PIXEL = 1.5.dp

private val PANEL_TEXT = Color(0xFFDDDDDD)
private val PANEL_TEXT_DIM = Color(0xFF8A8A8A)
private val TRADE_DISABLED = Color(0x40000000)
private val FIELD_BACKGROUND = Color(0xFF000000)
