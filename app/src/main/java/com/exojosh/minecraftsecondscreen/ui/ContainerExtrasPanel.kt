package com.exojosh.minecraftsecondscreen.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.exojosh.minecraftsecondscreen.net.AnvilState
import com.exojosh.minecraftsecondscreen.net.BeaconEffect
import com.exojosh.minecraftsecondscreen.net.BeaconState
import com.exojosh.minecraftsecondscreen.net.ContainerExtras
import com.exojosh.minecraftsecondscreen.net.EnchantingOffers
import com.exojosh.minecraftsecondscreen.net.HotbarSlot
import com.exojosh.minecraftsecondscreen.net.MerchantTrades
import com.exojosh.minecraftsecondscreen.net.ResourcePackIconProvider
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
    /** For the beacon's status effect icons, which are named by the game rather
     *  than by a `HudIcon` entry. */
    iconProvider: ResourcePackIconProvider,
    onButton: (syncId: Int, buttonId: Int) -> Unit,
    onTrade: (syncId: Int, index: Int) -> Unit,
    onBeaconEffects: (syncId: Int, primary: String?, secondary: String?) -> Unit,
    onRename: (syncId: Int, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
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

            extras.merchant != null -> TradeRail(
                trades = extras.merchant,
                fontSheet = fontSheet,
                glintTexture = glintTexture,
                itemIcon = itemIcon,
                onPick = { index -> onTrade(syncId, index) }
            )

            extras.beacon != null -> BeaconRows(
                beacon = extras.beacon,
                fontSheet = fontSheet,
                iconProvider = iconProvider,
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
 * A villager's trades, stacked down a narrow rail.
 *
 * <h2>Why this one is a rail and not a strip</h2>
 * Laid out horizontally with the other containers, the trades were the worst panel
 * on the screen: a trade is *itself* three stacks side by side, so turning the list
 * sideways made every entry a wide card, only three or four fitted at once, and the
 * whole thing read as a dense clump of tiny slots with an easily-missed separator.
 *
 * A list of rows belongs in a column. Stacked, every trade fits, each one reads
 * left-to-right the way it does in game, and the rail only ever has to be as wide
 * as a single trade — which is why `SIDE_RAIL_WIDTH` is a fixed narrow value rather
 * than a weight. The grid keeps the rest.
 *
 * Tapping one selects the trade, which moves the container's own cost and result
 * slots; the exchange itself still happens by taking the result out of the grid.
 * That split is vanilla's, not a shortcut — selecting a trade and completing it are
 * two different actions in game too.
 */
@Composable
private fun TradeRail(
    trades: MerchantTrades,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onPick: (Int) -> Unit
) {
    val gui = LocalMinecraftGui.current

    BandHeader(if (trades.leveled) "${trades.experience} xp" else "Trades", fontSheet)
    if (trades.trades.isEmpty()) {
        BandLabel("No trades", fontSheet)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        itemsIndexed(trades.trades) { index, trade ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TRADE_CELL + ROW_GAP)
                    // Out of stock stays on screen and greyed. Removing the row
                    // would renumber every trade after it while the player was
                    // looking at it, and the index is what gets sent.
                    .background(if (trade.disabled) TRADE_DISABLED else Color.Transparent)
                    .semantics { contentDescription = "Trade ${index + 1}" }
                    .clickable(enabled = !trade.disabled) { onPick(index) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                TradeSlot(trade.firstCost, gui.slot, fontSheet, glintTexture, itemIcon)
                // An absent second cost still holds its place, so the result
                // column lines up down the rail instead of shuffling left on
                // every one-item trade.
                if (trade.secondCost.itemId != InventoryMoves.AIR_ITEM_ID) {
                    TradeSlot(trade.secondCost, gui.slot, fontSheet, glintTexture, itemIcon)
                } else {
                    Spacer(modifier = Modifier.size(TRADE_CELL))
                }
                // ASCII only: the bitmap font sheet covers 0x00-0xFF, and an
                // arrow glyph came out as a literal '?'.
                BandLabel(if (trade.disabled) "x" else ">", fontSheet,
                    if (trade.disabled) PANEL_TEXT_DIM else PANEL_TEXT)
                TradeSlot(trade.sell, gui.slot, fontSheet, glintTexture, itemIcon)
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
    iconProvider: ResourcePackIconProvider,
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        beacon.primaryOptions.forEach { effect ->
            EffectButton(
                effect = effect,
                icon = iconProvider.getStatusEffectIcon(effect.id),
                unlocked = beacon.level >= effect.tier,
                selected = primary == effect.id,
                fontSheet = fontSheet,
                onClick = { primary = effect.id }
            )
        }

        // A visible break between what the beacon does and what it does *extra* --
        // the two rows carry different meanings and vanilla separates them with a
        // whole panel divider.
        Spacer(modifier = Modifier.width(ROW_GAP * 3))

        beacon.secondaryOptions.forEach { effect ->
            EffectButton(
                effect = effect,
                icon = iconProvider.getStatusEffectIcon(effect.id),
                unlocked = beacon.level >= effect.tier,
                selected = secondary != null && secondary == effect.id,
                fontSheet = fontSheet,
                onClick = { secondary = effect.id }
            )
        }
        // Vanilla's second secondary option: spend the payment on doubling the
        // primary rather than adding regeneration. It's the primary's own id in
        // both slots, which is why the mod doesn't list it as an effect -- and why
        // it borrows the chosen primary's icon here rather than having one.
        EffectButton(
            effect = BeaconEffect(id = "", name = "Amplify", tier = AMPLIFY_LEVEL),
            icon = iconProvider.getStatusEffectIcon(primary),
            unlocked = beacon.level >= AMPLIFY_LEVEL && primary != null,
            selected = secondary != null && secondary == primary,
            fontSheet = fontSheet,
            badge = "2x",
            onClick = { secondary = primary }
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
 * One beacon effect as its game icon in a slot cell.
 *
 * These used to be text buttons — a row reading "Speed Haste Resistance Jump Boost
 * Strength" that was mostly words and looked nothing like the game's own picker.
 * The icons are vanilla's `textures/mob_effect/` sprites, served by the mod.
 *
 * A locked effect keeps its icon but dimmed, with the beacon level it needs in the
 * corner — omitting it would leave a player with a half-built pyramid wondering
 * what finishing it buys, and greying it without saying *why* is barely better.
 * The name still rides along as the content description, which is the only text a
 * test or a screen reader could find on a cell whose content is a bitmap.
 *
 * Falls back to a text button when the icon hasn't arrived (or the effect isn't one
 * of the six the catalog serves), so a datapack effect degrades to the old
 * appearance rather than to an empty cell.
 */
@Composable
private fun EffectButton(
    effect: BeaconEffect,
    icon: Bitmap?,
    unlocked: Boolean,
    selected: Boolean,
    fontSheet: MinecraftFontSheet?,
    badge: String? = null,
    onClick: () -> Unit
) {
    if (icon == null) {
        MinecraftButton(
            text = if (unlocked) effect.name else "${effect.name} (${effect.tier})",
            onClick = onClick,
            enabled = unlocked,
            selected = selected,
            modifier = Modifier.height(ROW_HEIGHT)
        )
        return
    }

    val gui = LocalMinecraftGui.current

    Box(
        modifier = Modifier
            .size(EFFECT_CELL)
            .vanillaSlot(gui.slot)
            .semantics { contentDescription = effect.name }
            .clickable(enabled = unlocked, onClick = onClick)
    ) {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            filterQuality = FilterQuality.None,
            alpha = if (unlocked) 1f else LOCKED_ALPHA,
            modifier = Modifier.fillMaxSize().padding(EFFECT_CELL / SLOT_PIXELS)
        )

        // The level it needs, bottom-right where a stack count goes -- the one
        // place on a slot a player already reads a number.
        if (!unlocked) {
            BandLabel(
                effect.tier.toString(),
                fontSheet,
                Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(1.dp)
            )
        } else if (badge != null) {
            BandLabel(
                badge,
                fontSheet,
                Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(1.dp)
            )
        }

        if (selected) SelectionBorder()
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
    BandLabel(text, fontSheet, Color.White, pixelSize = TITLE_PIXEL)
}

@Composable
private fun BandLabel(
    text: String,
    fontSheet: MinecraftFontSheet?,
    color: Color = PANEL_TEXT,
    modifier: Modifier = Modifier,
    pixelSize: Dp = LABEL_PIXEL
) {
    if (fontSheet != null) {
        MinecraftText(
            text = text,
            fontSheet = fontSheet,
            pixelSize = pixelSize,
            color = color,
            style = MinecraftTextStyle.SHADOW,
            modifier = modifier
        )
    } else {
        // The asset bundle is still streaming in. Plain Compose text rather than
        // nothing, so the band is usable on a fresh connection.
        androidx.compose.material3.Text(text = text, color = color, modifier = modifier)
    }
}

/** The beacon level at which the secondary slot unlocks. Vanilla's secondary tier
 *  is `EFFECTS_BY_LEVEL` entry 3, i.e. a full four-layer pyramid. */
private const val AMPLIFY_LEVEL = 4

private val ROW_HEIGHT = 26.dp
private val ROW_GAP = 3.dp
private val OPTION_CELL = 38.dp
/** Beacon effect cells. Bigger than a trade slot because they're the primary
 *  targets on that panel, and vanilla's own effect buttons are chunky too. */
private val EFFECT_CELL = 34.dp
/** A locked effect keeps its icon, dimmed enough to read as unavailable while
 *  still being recognisable -- the point is to show what finishing the pyramid
 *  would buy. */
private const val LOCKED_ALPHA = 0.3f
private val TRADE_CELL = 26.dp
private val TITLE_PIXEL = 1.75.dp
private val LABEL_PIXEL = 1.5.dp

private val PANEL_TEXT = Color(0xFFDDDDDD)
private val PANEL_TEXT_DIM = Color(0xFF8A8A8A)
private val TRADE_DISABLED = Color(0x40000000)
private val FIELD_BACKGROUND = Color(0xFF000000)
