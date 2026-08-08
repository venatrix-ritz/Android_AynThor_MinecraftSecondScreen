package com.exojosh.minecraftsecondscreen.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.exojosh.minecraftsecondscreen.net.CreativeGroup
import com.exojosh.minecraftsecondscreen.net.HotbarSlot

/**
 * The creative inventory, on the bottom screen.
 *
 * <h2>Why this isn't the container path</h2>
 * In creative, the game's inventory screen is `CreativeInventoryScreen`, whose
 * handler is a **client-side** `CreativeScreenHandler` with no type. Streaming it
 * as an ordinary container would get the app a 45-slot page of whichever tab the
 * game happened to be showing, with no way to reach any other tab — and no way to
 * click it either, because creative item selection doesn't go through `clickSlot`
 * at all. So the mod sends the catalogue itself (`CreativeCatalog`) and this draws
 * a picker over it.
 *
 * <h2>Search is a filter over ids, not a server query</h2>
 * The whole catalogue is already here, so searching is a local `contains` over the
 * item ids of every group at once — which is also why search results ignore the
 * selected tab. Typing "diamond" and getting only the diamonds that happen to live
 * in the tab you were on would be the wrong answer.
 *
 * Matching is against the **id**, not a display name, because ids are all the
 * catalogue carries. That reads slightly differently to vanilla's search (which
 * matches names and tooltips), and it's an honest limitation rather than a
 * shortcut: sending 1300 translated names would double the message for a feature
 * that already works on the word a player actually types.
 *
 * <h2>Icons come from the existing path</h2>
 * The catalogue is item **ids**. Icons arrive through the same on-demand `ICON:`
 * request the hotbar and inventory use, so they're rendered by the mod through the
 * real model pipeline and cached after the first look. A grid that has scrolled
 * past a few hundred items will have asked for a few hundred icons, which the
 * repository's LRU bound is there to survive.
 */
@Composable
fun CreativeScreen(
    groups: List<CreativeGroup>,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    isConnected: Boolean,
    itemIcon: (String) -> Bitmap?,
    onRequestCatalogue: () -> Unit,
    onGive: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Asked for from composition, so it has to tolerate being hit every frame --
    // the repository holds the once-only latch, same as requestIcon.
    LaunchedEffect(isConnected) { onRequestCatalogue() }

    // The game's own container panel, same as the Items tab. Without it the picker
    // floats on the tiled dirt while every other slot-bearing screen sits on the
    // panel, which reads as unfinished rather than as a deliberate difference.
    val panel = modifier
        .fillMaxSize()
        .padding(SCREEN_PADDING)
        .vanillaPanel()
        .padding(PANEL_INSET)

    if (groups.isEmpty()) {
        Box(modifier = panel, contentAlignment = Alignment.Center) {
            PickerLabel(
                if (isConnected) "Loading the item list…" else "Not connected.",
                fontSheet
            )
        }
        return
    }

    var selectedGroup by remember(groups.size) { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }

    // Searching deliberately spans every group -- see the note above. Recomputed
    // only when the query or the catalogue changes, not per frame: this walks
    // ~1300 ids.
    val shown = remember(groups, selectedGroup, query) {
        if (query.isBlank()) {
            groups.getOrNull(selectedGroup)?.items ?: emptyList()
        } else {
            val needle = query.lowercase().replace(' ', '_')
            groups.flatMap { it.items }.distinct().filter { needle in it }
        }
    }

    Column(modifier = panel) {
        CreativeTabs(
            groups = groups,
            selected = selectedGroup,
            searching = query.isNotBlank(),
            fontSheet = fontSheet,
            glintTexture = glintTexture,
            itemIcon = itemIcon,
            onSelect = { index ->
                selectedGroup = index
                // Picking a tab is a statement about wanting to browse it, which a
                // live filter across every group would immediately contradict.
                query = ""
                typing = false
            }
        )

        SearchRow(
            query = query,
            typing = typing,
            resultCount = shown.size,
            fontSheet = fontSheet,
            onToggleTyping = { typing = !typing },
            onClear = { query = ""; typing = false }
        )

        ItemGrid(
            items = shown,
            fontSheet = fontSheet,
            glintTexture = glintTexture,
            itemIcon = itemIcon,
            onGive = onGive,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        if (typing) {
            ChatKeyboard(
                onType = { typed -> query += typed },
                onBackspace = { query = query.dropLast(1) },
                onSend = { typing = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** The group tabs, each labelled by the item vanilla labels it with. */
@Composable
private fun CreativeTabs(
    groups: List<CreativeGroup>,
    selected: Int,
    searching: Boolean,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onSelect: (Int) -> Unit
) {
    val gui = LocalMinecraftGui.current

    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = ROW_GAP),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        groups.forEachIndexed { index, group ->
            item(key = group.id) {
                Box(
                    modifier = Modifier
                        .size(TAB_CELL)
                        .vanillaSlot(gui.slot)
                        // The group's name is the only text on a cell whose
                        // content is an item icon.
                        .semantics { contentDescription = group.name }
                        .clickable { onSelect(index) }
                ) {
                    group.icon?.let { icon ->
                        Box(modifier = Modifier.fillMaxSize().padding(TAB_CELL / SLOT_PIXELS)) {
                            ItemStackGraphic(
                                stack = HotbarSlot(icon, 1, 0, 0, false),
                                icon = itemIcon(icon),
                                fontSheet = fontSheet,
                                glintTexture = glintTexture,
                                unit = (TAB_CELL - TAB_CELL / SLOT_PIXELS * 2) / ITEM_AREA_PIXELS
                            )
                        }
                    }
                    // While a search is running no tab is the one being shown, so
                    // none is marked -- highlighting the last-picked tab would
                    // claim the results came from it.
                    if (!searching && index == selected) {
                        Box(modifier = Modifier.fillMaxSize().vanillaSlotHighlight())
                    }
                }
            }
        }
    }
}

/**
 * The search field and what it found.
 *
 * The keyboard is the app's own [ChatKeyboard] and appears only while typing, the
 * same arrangement the anvil's rename uses and for the same reason: Android puts an
 * IME for a presentation display on the *primary* screen, which here is the one
 * running the game.
 */
@Composable
private fun SearchRow(
    query: String,
    typing: Boolean,
    resultCount: Int,
    fontSheet: MinecraftFontSheet?,
    onToggleTyping: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(bottom = ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(FIELD_BACKGROUND)
                .clickable(onClick = onToggleTyping)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            PickerLabel(
                query.ifEmpty { "Tap to search" },
                fontSheet,
                if (query.isEmpty()) DIM_TEXT else Color.White
            )
        }

        PickerLabel("$resultCount", fontSheet)

        if (query.isNotEmpty()) {
            MinecraftButton(
                text = "Clear",
                onClick = onClear,
                modifier = Modifier.fillMaxHeight()
            )
        }
        MinecraftButton(
            text = if (typing) "Done" else "Search",
            onClick = onToggleTyping,
            selected = typing,
            modifier = Modifier.fillMaxHeight()
        )
    }
}

/**
 * The items, as a scrolling grid of slots. A tap hands one over.
 *
 * `GridCells.Adaptive` rather than a fixed column count, so the grid fills
 * whatever width it's given at a cell size that stays thumb-sized — the panel is
 * a different shape with the keyboard open than with it closed.
 */
@Composable
private fun ItemGrid(
    items: List<String>,
    fontSheet: MinecraftFontSheet?,
    glintTexture: Bitmap?,
    itemIcon: (String) -> Bitmap?,
    onGive: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val gui = LocalMinecraftGui.current

    if (items.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            PickerLabel("Nothing matches", fontSheet, DIM_TEXT)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ITEM_CELL),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        // Keyed by id so scrolling doesn't re-request icons for cells that only
        // moved -- the ids are distinct within a group by construction.
        items(items, key = { it }) { itemId ->
            Box(
                modifier = Modifier
                    .size(ITEM_CELL)
                    .vanillaSlot(gui.slot)
                    .semantics { contentDescription = itemId }
                    .clickable { onGive(itemId) }
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(ITEM_CELL / SLOT_PIXELS)) {
                    ItemStackGraphic(
                        stack = HotbarSlot(itemId, 1, 0, 0, false),
                        icon = itemIcon(itemId),
                        fontSheet = fontSheet,
                        glintTexture = glintTexture,
                        unit = (ITEM_CELL - ITEM_CELL / SLOT_PIXELS * 2) / ITEM_AREA_PIXELS
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerLabel(
    text: String,
    fontSheet: MinecraftFontSheet?,
    color: Color = PANEL_TEXT
) {
    if (fontSheet != null) {
        MinecraftText(
            text = text,
            fontSheet = fontSheet,
            pixelSize = LABEL_PIXEL,
            color = color,
            style = MinecraftTextStyle.SHADOW
        )
    } else {
        androidx.compose.material3.Text(text = text, color = color)
    }
}

/** Matched to the Items tab, so the two panels line up when tabbing between
 *  them rather than shifting by a few pixels. */
private val SCREEN_PADDING = 4.dp
private val PANEL_INSET = 6.dp
private val ROW_HEIGHT = 26.dp
private val ROW_GAP = 3.dp
private val TAB_CELL = 34.dp
private val ITEM_CELL = 42.dp
private val LABEL_PIXEL = 1.5.dp

private val PANEL_TEXT = Color(0xFFDDDDDD)
private val DIM_TEXT = Color(0xFF8A8A8A)
private val FIELD_BACKGROUND = Color(0xFF000000)
