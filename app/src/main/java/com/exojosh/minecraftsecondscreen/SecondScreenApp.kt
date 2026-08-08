package com.exojosh.minecraftsecondscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.exojosh.minecraftsecondscreen.net.HudIcon
import com.exojosh.minecraftsecondscreen.net.HudRepository
import com.exojosh.minecraftsecondscreen.net.ResourcePackIconProvider
import com.exojosh.minecraftsecondscreen.ui.ChatScreen
import com.exojosh.minecraftsecondscreen.ui.CreativeScreen
import com.exojosh.minecraftsecondscreen.ui.HudScreen
import com.exojosh.minecraftsecondscreen.ui.InputGridScreen
import com.exojosh.minecraftsecondscreen.ui.InventoryScreen
import com.exojosh.minecraftsecondscreen.ui.LocalMinecraftGui
import com.exojosh.minecraftsecondscreen.ui.MapScreen
import com.exojosh.minecraftsecondscreen.ui.MinecraftButton
import com.exojosh.minecraftsecondscreen.ui.RepeatingTextureBackground
import com.exojosh.minecraftsecondscreen.ui.SettingsScreen
import com.exojosh.minecraftsecondscreen.ui.rememberChatDraftState
import com.exojosh.minecraftsecondscreen.ui.rememberMinecraftFont
import com.exojosh.minecraftsecondscreen.ui.rememberMinecraftGuiTextures
import com.exojosh.minecraftsecondscreen.settings.ContainerKind
import com.exojosh.minecraftsecondscreen.settings.HudElement
import com.exojosh.minecraftsecondscreen.settings.rememberBackgroundSettings
import com.exojosh.minecraftsecondscreen.settings.rememberChatSettings
import com.exojosh.minecraftsecondscreen.settings.rememberContainerSettings
import com.exojosh.minecraftsecondscreen.settings.rememberHudSettings
import com.exojosh.minecraftsecondscreen.settings.rememberInputSettings

/**
 * Height reserved at the bottom for the tab strip.
 *
 * Kept tight on purpose: everything not spent here goes to the tab's panel,
 * and the map scales in whole-pixel steps, so ~16dp reclaimed off this strip
 * can be worth a full step of map size rather than a sliver.
 */
private val TAB_STRIP_HEIGHT = 44.dp

/** Vanilla's error red, for the one banner this screen ever shows. */
private val PORT_CONFLICT_COLOR = Color(0xCCB3261E)

/**
 * What fills the panel *below* the hotbar.
 *
 * The status stack (armor, hearts, hunger, XP, hotbar) is not part of this --
 * on almost every tab it stays on screen and only the panel underneath swaps,
 * because the whole point of the second screen is that vital signs stay visible
 * while you do something else with the rest of the space. [showsStatusStack] is
 * the one deliberate exception; see there.
 *
 * Adding a tab is one entry plus one `when` branch — the strip is generated
 * from this enum, so don't hand-write another Button.
 */
enum class SecondScreenTab(
    val label: String,
    /**
     * Whether the status stack stays on screen above this tab's panel.
     *
     * True everywhere except Items, and that exception is the point of the
     * flag rather than a loophole in it: the inventory is six rows of slots
     * that have to be draggable across without scrolling, and it simply does
     * not fit under the hearts, armor, XP bar and hotbar. It's also the one
     * tab you look at *deliberately* — you open your inventory, you don't
     * glance at it mid-fight — so the vital signs are worth less here than
     * anywhere else.
     */
    val showsStatusStack: Boolean = true,
    /**
     * Whether this tab only exists in creative mode.
     *
     * [CREATIVE] is the only one, and it's hidden rather than disabled in survival
     * because it isn't a thing that's temporarily unavailable — the mod refuses a
     * `GIVE:` outside creative, so a visible tab would be a button that looks
     * functional and does nothing. Six tabs across a 1240px strip is also about the
     * limit before the labels stop fitting.
     */
    val creativeOnly: Boolean = false
) {
    HUD("HUD"),
    CHAT("Chat"),
    ITEMS("Items", showsStatusStack = false),
    CREATIVE("Creative", showsStatusStack = false, creativeOnly = true),
    INPUT("Input"),
    SETTINGS("Settings")
}

@Composable
fun SecondScreenApp(
    hudRepository: HudRepository,
    iconProvider: ResourcePackIconProvider
) {
    val hudState by hudRepository.hudState.collectAsState()
    val mapTile by hudRepository.mapTile.collectAsState()
    val isConnected by hudRepository.isConnected.collectAsState()
    val bindings by hudRepository.bindings.collectAsState()
    val chatMessages by hudRepository.chatMessages.collectAsState()
    val container by hudRepository.container.collectAsState()
    val creativeGroups by hudRepository.creativeGroups.collectAsState()
    val portConflict by hudRepository.portConflict.collectAsState()
    var selectedTab by remember { mutableStateOf(SecondScreenTab.HUD) }
    val settings = rememberHudSettings()
    val inputSettings = rememberInputSettings()
    val chatSettings = rememberChatSettings()
    val containerSettings = rememberContainerSettings()
    val backgroundSettings = rememberBackgroundSettings()

    // Which tabs apply right now. The Creative tab exists only in creative, and a
    // player switching back to survival must not be left sitting on it -- the
    // guard below is what moves them off rather than leaving a blank panel with no
    // button to escape it.
    val visibleTabs = remember(hudState?.isCreative) {
        SecondScreenTab.entries.filter { !it.creativeOnly || hudState?.isCreative == true }
    }
    LaunchedEffect(visibleTabs) {
        if (selectedTab !in visibleTabs) selectedTab = SecondScreenTab.HUD
    }

    /**
     * The tab to go back to when the container closes, or null when the player got
     * to the Items tab on their own.
     *
     * Held here rather than derived, because "where was I?" is genuinely history:
     * once the takeover has switched tabs there is nothing left on screen that
     * remembers what was there before.
     */
    var tabBeforeContainer by remember { mutableStateOf<SecondScreenTab?>(null) }

    val screenOpen = container?.screenOpen == true
    val containerKind = container?.let {
        ContainerKind.of(it.handlerType, it.isPlayerInventory)
    }

    /**
     * Follow the game: when a container opens, show the Items tab; when it closes,
     * put back whatever was there before.
     *
     * **Keyed on `screenOpen` and the handler, not on the container state.** That
     * message arrives on every slot change, and re-running this on each of them
     * would drag the player back to Items every time an item moved — including
     * while they were deliberately reading the map.
     *
     * A manual tab change while the container is open clears the memory, so the
     * takeover never fights a deliberate choice: if you open a chest and then go
     * to Chat, closing the chest leaves you on Chat.
     *
     * Which containers do this is per-kind and defaults to on
     * ([ContainerSettings]) — a grindstone is often a two-second visit, and having
     * the screen jump off the map and back for it is worse than not helping.
     */
    LaunchedEffect(screenOpen, containerKind) {
        if (screenOpen && containerKind != null) {
            if (selectedTab != SecondScreenTab.ITEMS &&
                containerSettings.opensAutomatically(containerKind)
            ) {
                tabBeforeContainer = selectedTab
                selectedTab = SecondScreenTab.ITEMS
            }
        } else {
            // Closed. Restore only if we were the reason the tab changed.
            tabBeforeContainer?.let { previous ->
                if (selectedTab == SecondScreenTab.ITEMS) selectedTab = previous
            }
            tabBeforeContainer = null
        }
    }

    // Held here rather than inside ChatScreen because a tab switch disposes the
    // panel's content: a draft owned down there would be lost by glancing at
    // the map mid-sentence.
    val chatDraft = rememberChatDraftState()

    // The chat log draws in Minecraft's own bitmap font, same as the XP level
    // and stack counts, so a resource pack restyling the font applies here too.
    val chatFont = rememberMinecraftFont(iconProvider.getFontSheet())

    // Anything switched off down here goes back to the game's own HUD on the
    // top screen, so an element is never simply lost -- between the two
    // displays the full HUD is always drawn exactly once.
    //
    // Keyed on isConnected so a reconnect re-sends: the mod resets to "draw
    // nothing" when it restarts, and snapshotFlow emits its current value
    // immediately on collection, which covers both that and every later toggle.
    LaunchedEffect(hudRepository, isConnected, settings) {
        if (!isConnected) return@LaunchedEffect
        snapshotFlow { HudElement.entries.filterNot(settings::isVisible).map { it.key } }
            .collect(hudRepository::sendGameHudElements)
    }

    // Same source as every other texture: the mod pushes it over the socket,
    // resolved through Minecraft's resource manager, so a resource pack applies
    // here too. This used to read the bundled copy directly while HudScreen
    // used the socket one, which meant a pack changed the HUD's background but
    // not the background behind it.
    val backdropBitmap = remember(backgroundSettings.background,
        iconProvider.getBackground(backgroundSettings.background)) {
        iconProvider.getBackground(backgroundSettings.background)?.asImageBitmap()
    }

    // The game's GUI chrome — button states and the inventory slot — provided
    // once for the whole window rather than threaded through every control that
    // draws with it. Same source as everything else: the socket, so a resource
    // pack restyling the GUI restyles these too.
    val guiTextures = rememberMinecraftGuiTextures(iconProvider, chatFont)

    val content: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = TAB_STRIP_HEIGHT)
            ) {
                // Always on top, on every tab. Wraps its own height.
                //
                // Raised above the panel because the off-hand box below can
                // overhang into it (see offhandOverflowsBelow). Compose already
                // routes taps in the overlap to the box -- the panel has no
                // pointer input there -- so without this the box could be a tap
                // target hidden under the map, which is worse than either
                // drawing on top. The clearance is comfortable at any normal
                // HUD height; this makes it not depend on that arithmetic.
                if (selectedTab.showsStatusStack) Box(modifier = Modifier.zIndex(1f)) {
                    HudScreen(
                        state = hudState,
                        hudRepository = hudRepository,
                        settings = settings,
                        iconProvider = iconProvider,
                        // The off-hand box sits on its own line below the
                        // hotbar, hard against the right edge, and it's only 29
                        // of the hotbar's 182 logical pixels wide -- so charging
                        // a full row of height for it leaves a wide empty band
                        // on either side. On the HUD tab that row is given to
                        // the panel and the box floats over it: the map sheet is
                        // square, centred and height-bound, so it stays clear of
                        // the corner the box occupies. The other two tabs lay
                        // out content across the full width (the grid's
                        // top-right button, Settings' "Reset"), and the box
                        // would cover it and eat its taps.
                        offhandOverflowsBelow = selectedTab == SecondScreenTab.HUD
                    )
                }

                // Everything left over goes to the tab's panel.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (selectedTab) {
                        SecondScreenTab.HUD -> MapScreen(
                            tile = mapTile,
                            isConnected = isConnected,
                            backgroundBitmap = iconProvider.getMapBackground()
                        )

                        SecondScreenTab.CHAT -> ChatScreen(
                            messages = chatMessages,
                            fontSheet = chatFont,
                            isConnected = isConnected,
                            draft = chatDraft,
                            onSend = hudRepository::sendChat,
                            useSystemKeyboard = chatSettings.useSystemKeyboard
                        )

                        SecondScreenTab.ITEMS -> InventoryScreen(
                            state = container,
                            fontSheet = chatFont,
                            glintTexture = iconProvider.getIcon(HudIcon.ENCHANTED_GLINT),
                            isConnected = isConnected,
                            itemIcon = hudRepository::requestIcon,
                            // For the slot placeholders and progress art, which
                            // are named by the game at runtime rather than by a
                            // HudIcon entry -- see getSpriteBitmap.
                            iconProvider = iconProvider,
                            onSlotClick = hudRepository::sendSlotClick,
                            // The containers whose function isn't a slot click:
                            // enchanting offers, stonecutter recipes and loom
                            // patterns go through the button verb, while trades,
                            // beacon effects and the anvil rename each have their
                            // own packet behind them.
                            onButton = hudRepository::sendButton,
                            onTrade = hudRepository::sendTrade,
                            onBeaconEffects = hudRepository::sendBeaconEffects,
                            onRename = hudRepository::sendRename
                        )

                        SecondScreenTab.CREATIVE -> CreativeScreen(
                            groups = creativeGroups,
                            fontSheet = chatFont,
                            glintTexture = iconProvider.getIcon(HudIcon.ENCHANTED_GLINT),
                            isConnected = isConnected,
                            itemIcon = hudRepository::requestIcon,
                            onRequestCatalogue = hudRepository::requestCreative,
                            onGive = hudRepository::sendGive
                        )

                        SecondScreenTab.INPUT -> InputGridScreen(
                            settings = inputSettings,
                            bindings = bindings,
                            onPressBinding = hudRepository::sendBinding
                        )

                        SecondScreenTab.SETTINGS -> SettingsScreen(
                            settings = settings,
                            inputSettings = inputSettings,
                            chatSettings = chatSettings,
                            containerSettings = containerSettings,
                            backgroundSettings = backgroundSettings,
                            iconProvider = iconProvider,
                            bindings = bindings,
                            onRequestBindings = hudRepository::requestBindings
                        )
                    }
                }
            }

            // Named rather than left as a mystery. Something else accepting on
            // 48291 -- in practice a leftover `adb reverse` putting adbd there
            // -- used to read as an ordinary flapping connection, so the screen
            // alternated "Not connected" with "Waiting for map data..." and
            // said nothing about the real problem. Drawn over everything,
            // because whatever is underneath is wrong anyway.
            if (portConflict) {
                Text(
                    text = "Port 48291 is taken by something else — " +
                        "clear a leftover 'adb reverse tcp:48291 tcp:48291'.",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(2f)
                        .fillMaxWidth()
                        .background(PORT_CONFLICT_COLOR)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.4f))
                    // 2dp + a 40dp button + 2dp has to come to TAB_STRIP_HEIGHT
                    // exactly: the strip is overlaid on the Box, and the panel
                    // above only avoids it via a bottom padding of that height,
                    // so a taller strip silently covers the panel's last rows.
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Driven off the enum so adding a tab is one entry, not another
                // copy-pasted Button that can drift from its neighbours.
                //
                // The game's own button sprite, and its hover state for the
                // selected tab -- vanilla has no sticky "selected button", but
                // the highlighted sprite is exactly the "this is the one you're
                // on" treatment, and inventing a colour would have been the
                // only Material-looking thing left on the screen. Equal weights
                // rather than SpaceEvenly: nine-sliced buttons of five
                // different widths read as five different controls.
                visibleTabs.forEach { tab ->
                    MinecraftButton(
                        text = tab.label,
                        onClick = {
                            selectedTab = tab
                            // A deliberate choice outranks the container takeover:
                            // forgetting where we came from is what stops the
                            // closing container yanking the player off the tab
                            // they just picked.
                            tabBeforeContainer = null
                        },
                        selected = selectedTab == tab,
                        modifier = Modifier.weight(1f).height(40.dp)
                    )
                }
            }
        }
    }

    CompositionLocalProvider(LocalMinecraftGui provides guiTextures) {
        if (backdropBitmap != null) {
            RepeatingTextureBackground(
                texture = backdropBitmap,
                scaleFactor = 16f,
                modifier = Modifier.fillMaxSize(),
                content = content
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF222222)),
                content = { content() }
            )
        }
    }
}
