package com.exojosh.minecraftsecondscreen.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
// Both `items` overloads are needed: the grid one and the list one are extensions
// on different scopes, so Kotlin picks by receiver -- but importing only the grid
// one makes `items(aList)` inside a LazyColumn resolve to the grid's
// `items(count: Int)` and fail on the element type.
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.exojosh.minecraftsecondscreen.net.AnvilState
import com.exojosh.minecraftsecondscreen.net.BeaconState
import com.exojosh.minecraftsecondscreen.net.ContainerExtras
import com.exojosh.minecraftsecondscreen.net.ContainerOption
import com.exojosh.minecraftsecondscreen.net.EnchantingOffers
import com.exojosh.minecraftsecondscreen.net.HotbarSlot
import com.exojosh.minecraftsecondscreen.net.MerchantTrades
import com.exojosh.minecraftsecondscreen.net.SelectableList

/**
 * The controls for a container whose function isn't its slots.
 *
 * <h2>Why these exist</h2>
 * Slot geometry made every container legible; it did nothing for the five whose
 * actual function is a *choice*. An enchanting table is three offers, a
 * stonecutter a recipe list, a loom a pattern list, a villager a trade list, a
 * beacon a pair of effects. Those were drawable but dead — the second screen had
 * no way to describe the choice or send it — so opening one on the Thor meant
 * reaching for the top screen. Each panel here is one list and one command.
 *
 * <h2>Built from the vocabulary that already exists</h2>
 * Every one of these is [MinecraftButton], [Modifier.vanillaSlot] and
 * [MinecraftText] — deliberately, and it's the reason this whole tier needed **no
 * new textures**. Vanilla has bespoke sprites for each of these lists
 * (`container/stonecutter/recipe`, `container/enchanting_table/level_1`,
 * `container/beacon/button`), and using them would have meant shipping ~25 more
 * assets to reproduce widgets the app already draws in the game's own style. The
 * information a player needs — which recipe, what it costs, what's locked — is
 * carried by the item icon and the label either way.
 *
 * Icons come through the existing `ICON:` request path, so a stonecutter's recipe
 * grid and a villager's trades show real items with no new machinery.
 */
@Composable
fun ContainerExtrasPanel(
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
    Column(modifier = modifier.fillMaxSize()) {
        when {
            extras.enchanting != null -> EnchantingPanel(
                offers = extras.enchanting,
                fontSheet = fontSheet,
                onPick = { row -> onButton(syncId, row) }
            )

            extras.stonecutter != null -> OptionGrid(
                list = extras.stonecutter,
                title = "Cut",
                fontSheet = fontSheet,
                glintTexture = glintTexture,
                itemIcon = itemIcon,
                onPick = { index -> onButton(syncId, index) }
            )

            // Patterns aren't items, so there's nothing to draw a grid of icons
            // from -- a labelled list is the honest version rather than a grid of
            // blanks. See ContainerOption.itemId.
            extras.loom != null -> OptionList(
                list = extras.loom,
                title = "Pattern",
                fontSheet = fontSheet,
                onPick = { index -> onButton(syncId, index) }
            )

            extras.merchant != null -> TradeList(
                trades = extras.merchant,
                fontSheet = fontSheet,
                glintTexture = glintTexture,
                itemIcon = itemIcon,
                onPick = { index -> onTrade(syncId, index) }
            )

            extras.beacon != null -> BeaconPanel(
                beacon = extras.beacon,
                fontSheet = fontSheet,
                onApply = { primary, secondary -> onBeaconEffects(syncId, primary, secondary) }
            )

            extras.anvil != null -> AnvilPanel(
                anvil = extras.anvil,
                fontSheet = fontSheet,
                onRename = { name -> onRename(syncId, name) }
            )
        }
    }
}

/**
 * The enchanting table's three offers.
 *
 * Each row says the enchantment and both costs. A row the game would refuse is
 * disabled rather than hidden, because "you need 3 more levels" is the answer the
 * player came for — hiding the row would just look like the table was broken.
 *
 * **The name already carries its level.** `Enchantment.getName(entry, level)` on
 * the mod side returns "Sharpness II", numeral included, so appending one here
 * printed "Sharpness II II". Nothing on this side should be formatting a level.
 *
 * The header is the lapis/level summary rather than the word "Enchant": the
 * control bar is already showing the container's title two lines above, and
 * saying it twice in the same corner was just noise.
 */
@Composable
private fun ColumnScope.EnchantingPanel(
    offers: EnchantingOffers,
    fontSheet: MinecraftFontSheet?,
    onPick: (Int) -> Unit
) {
    PanelTitle("Lapis ${offers.lapisCount} - Level ${offers.playerLevel}", fontSheet)

    offers.offers.forEachIndexed { row, offer ->
        // A row with no offer at all: no item in the slot, or not enough
        // bookshelves. Drawn as an empty disabled row so the table keeps its
        // three-row shape rather than collapsing and shifting under the finger.
        val empty = offer.cost <= 0

        MinecraftButton(
            text = if (empty) "-" else offer.name,
            onClick = { onPick(row) },
            enabled = offer.available,
            modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(bottom = ROW_GAP)
        )

        if (!empty) {
            PanelLabel(
                "${offer.lapisCost} lapis, ${offer.cost} levels",
                fontSheet,
                color = if (offer.available) PANEL_TEXT else PANEL_TEXT_DIM
            )
        }
    }
}

/**
 * A stonecutter's recipes, as a grid of item icons.
 *
 * A grid rather than a list because these are items and a player recognises the
 * shape of a stair block faster than they read "Stone Brick Stairs" — which is
 * also why vanilla's own stonecutter is a grid.
 */
@Composable
private fun ColumnScope.OptionGrid(
    list: SelectableList,
    title: String,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onPick: (Int) -> Unit
) {
    val gui = LocalMinecraftGui.current

    PanelTitle(title, fontSheet)

    if (list.options.isEmpty()) {
        PanelLabel("Put something in to cut", fontSheet)
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = OPTION_CELL),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        // The index is the button id, so this deliberately iterates the list as
        // sent rather than sorting or filtering it (see ContainerOption).
        items(list.options.size) { index ->
            val option = list.options[index]
            Box(
                modifier = Modifier
                    .size(OPTION_CELL)
                    .vanillaSlot(gui.slot)
                    // The label is the only text a test or screen reader can find
                    // here -- the cell's content is a bitmap icon.
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
                if (index == list.selected) SlotOverlay(SELECTED_TINT)
            }
        }
    }
}

/** A loom's patterns, by name — there's no icon to show for a banner pattern. */
@Composable
private fun ColumnScope.OptionList(
    list: SelectableList,
    title: String,
    fontSheet: MinecraftFontSheet?,
    onPick: (Int) -> Unit
) {
    PanelTitle(title, fontSheet)

    if (list.options.isEmpty()) {
        PanelLabel("Add a banner and a dye", fontSheet)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        itemsIndexed(list.options) { index, option: ContainerOption ->
            MinecraftButton(
                text = option.label,
                onClick = { onPick(index) },
                selected = index == list.selected,
                modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)
            )
        }
    }
}

/**
 * A villager's trades: what it wants, what it gives, and whether it's out.
 *
 * Tapping a row selects the trade, which moves the container's own cost and
 * result slots — so the actual exchange still happens by taking the result out of
 * the slot grid beside this. That split is vanilla's, not a shortcut: selecting a
 * trade and completing it are two different actions in game too.
 */
@Composable
private fun ColumnScope.TradeList(
    trades: MerchantTrades,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onPick: (Int) -> Unit
) {
    val gui = LocalMinecraftGui.current

    PanelTitle(if (trades.leveled) "Trades - ${trades.experience} xp" else "Trades", fontSheet)

    if (trades.trades.isEmpty()) {
        PanelLabel("No trades", fontSheet)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        itemsIndexed(trades.trades) { index, trade ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TRADE_ROW_HEIGHT)
                    // Out of stock stays on screen and greyed. Removing the row
                    // would renumber every trade below it while the player was
                    // looking at it, and the indices are what get sent.
                    .background(if (trade.disabled) TRADE_DISABLED else Color.Transparent)
                    .semantics { contentDescription = "Trade ${index + 1}" }
                    .clickable(enabled = !trade.disabled) { onPick(index) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TradeSlot(trade.firstCost, gui.slot, fontSheet, glintTexture, itemIcon)
                TradeSlot(trade.secondCost, gui.slot, fontSheet, glintTexture, itemIcon)
                PanelLabel(">", fontSheet)
                TradeSlot(trade.sell, gui.slot, fontSheet, glintTexture, itemIcon)
                Spacer(modifier = Modifier.width(ROW_GAP))
                if (trade.disabled) PanelLabel("out", fontSheet, PANEL_TEXT_DIM)
            }
        }
    }
}

/** One stack in a trade row. An empty one still draws its cell, so a
 *  one-item trade lines up with a two-item one above it. */
@Composable
private fun TradeSlot(
    stack: HotbarSlot,
    slotTexture: androidx.compose.ui.graphics.ImageBitmap?,
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
 * <h2>Both effects go at once, and the selection is local until Apply</h2>
 * The packet behind this carries both choices together — there is no way to change
 * one and leave the other — so the panel holds a pending pair and sends it on
 * Apply. Sending on each tap would work but would fire a packet per tap and, worse,
 * would clear the secondary effect the moment the player touched the primary.
 *
 * Locked options are shown disabled with the level they need rather than omitted,
 * so a player with a half-built beacon can see what building it out would buy.
 */
@Composable
private fun ColumnScope.BeaconPanel(
    beacon: BeaconState,
    fontSheet: MinecraftFontSheet?,
    onApply: (String?, String?) -> Unit
) {
    // Keyed on what the server says, so a change made on the top screen -- or by
    // another player -- replaces the pending selection rather than being
    // overwritten by it on the next Apply.
    var primary by remember(beacon.primary, beacon.level) { mutableStateOf(beacon.primary) }
    var secondary by remember(beacon.secondary, beacon.level) { mutableStateOf(beacon.secondary) }

    PanelTitle("Beacon - level ${beacon.level}", fontSheet)

    if (!beacon.hasPayment) {
        PanelLabel("Needs an ingot, gold, emerald or diamond", fontSheet, PANEL_TEXT_DIM)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        item { PanelLabel("Primary", fontSheet) }
        items(beacon.primaryOptions) { effect ->
            val unlocked = beacon.level >= effect.tier
            MinecraftButton(
                text = if (unlocked) effect.name else "${effect.name} (lvl ${effect.tier})",
                onClick = { primary = effect.id },
                enabled = unlocked,
                selected = primary == effect.id,
                modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)
            )
        }

        item { PanelLabel("Secondary", fontSheet) }
        items(beacon.secondaryOptions) { effect ->
            val unlocked = beacon.level >= effect.tier
            MinecraftButton(
                text = if (unlocked) effect.name else "${effect.name} (lvl ${effect.tier})",
                onClick = { secondary = effect.id },
                enabled = unlocked,
                selected = secondary == effect.id,
                modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)
            )
        }
        // Vanilla's second secondary option: spend the payment on doubling the
        // primary instead of adding regeneration. It's the primary's own id in
        // both slots, which is why it isn't in the effect list the mod sends.
        item {
            MinecraftButton(
                text = "Amplify primary",
                onClick = { secondary = primary },
                enabled = beacon.level >= AMPLIFY_LEVEL && primary != null,
                selected = secondary != null && secondary == primary,
                modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)
            )
        }
    }

    MinecraftButton(
        text = "Apply",
        onClick = { onApply(primary, secondary) },
        // Vanilla's own gate: no payment, no effect change.
        enabled = beacon.hasPayment && primary != null,
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)
    )
}

/**
 * The anvil's rename field and its price.
 *
 * The keyboard is the app's own [ChatKeyboard], not an IME, for the reason that
 * component exists: Android shows a soft keyboard for a presentation display on
 * the *primary* screen, which on this device is the one running the game. It's
 * reused unchanged — its callbacks were already generic — so the anvil gets the
 * same typing surface as chat with no second keyboard to maintain.
 *
 * The name is sent as it's typed rather than on a confirm button, because the
 * anvil's *price* depends on it and the player needs to see the cost for the name
 * they've actually written. That is also what vanilla does.
 */
@Composable
private fun ColumnScope.AnvilPanel(
    anvil: AnvilState,
    fontSheet: MinecraftFontSheet?,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    fun update(next: String) {
        name = next
        onRename(next)
    }

    PanelTitle("Repair & Name", fontSheet)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(FIELD_BACKGROUND)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        PanelLabel(name.ifEmpty { "(item name)" }, fontSheet,
            if (name.isEmpty()) PANEL_TEXT_DIM else Color.White)
    }

    Spacer(modifier = Modifier.height(ROW_GAP))

    // Vanilla shows the cost in red when the player can't afford it; here the
    // level count is enough, since the result slot beside this already refuses
    // the take. Zero cost with no result means "nothing to do", not "free".
    PanelLabel(
        when {
            !anvil.hasResult -> "No result"
            anvil.levelCost <= 0 -> "Free"
            else -> "Cost: ${anvil.levelCost} levels"
        },
        fontSheet
    )

    Spacer(modifier = Modifier.height(ROW_GAP))

    ChatKeyboard(
        onType = { typed -> update(name + typed) },
        onBackspace = { if (name.isNotEmpty()) update(name.dropLast(1)) },
        // The keyboard's "send" is the commit here. The name has already been
        // sent as it was typed, so this only has to clear the field's focus --
        // there's nothing left to transmit.
        onSend = { },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PanelTitle(text: String, fontSheet: MinecraftFontSheet?) {
    PanelLabel(text, fontSheet, Color.White, TITLE_PIXEL)
    Spacer(modifier = Modifier.height(ROW_GAP))
}

@Composable
private fun PanelLabel(
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
        // No font sheet yet (the bundle is still streaming). Plain Compose text
        // rather than nothing, so the panel is usable on a fresh connection.
        androidx.compose.material3.Text(text = text, color = color)
    }
}

/** The beacon level at which the secondary slot unlocks. Vanilla's secondary tier
 *  is `EFFECTS_BY_LEVEL` entry 3, i.e. a full four-layer pyramid. */
private const val AMPLIFY_LEVEL = 4

private val ROW_HEIGHT = 24.dp
private val ROW_GAP = 3.dp
private val OPTION_CELL = 36.dp
private val TRADE_CELL = 26.dp
private val TRADE_ROW_HEIGHT = 30.dp
private val TITLE_PIXEL = 2.dp
private val LABEL_PIXEL = 1.5.dp

/** Vanilla's GUI label grey, the same one the Items tab's control bar uses. */
private val PANEL_TEXT = Color(0xFFDDDDDD)
private val PANEL_TEXT_DIM = Color(0xFF808080)
private val SELECTED_TINT = Color(0x80FFFFFF)
private val TRADE_DISABLED = Color(0x40000000)
private val FIELD_BACKGROUND = Color(0xFF000000)
