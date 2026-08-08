package com.exojosh.minecraftsecondscreen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exojosh.minecraftsecondscreen.net.GameBinding
import com.exojosh.minecraftsecondscreen.net.ResourcePackIconProvider
import com.exojosh.minecraftsecondscreen.settings.BackgroundSettings
import com.exojosh.minecraftsecondscreen.settings.ChatSettings
import com.exojosh.minecraftsecondscreen.settings.ContainerKind
import com.exojosh.minecraftsecondscreen.settings.ContainerSettings
import com.exojosh.minecraftsecondscreen.settings.HudBackground
import com.exojosh.minecraftsecondscreen.settings.HudElement
import com.exojosh.minecraftsecondscreen.settings.HudSettings
import com.exojosh.minecraftsecondscreen.settings.INPUT_SLOT_COUNT
import com.exojosh.minecraftsecondscreen.settings.InputSettings

/**
 * Every container toggle means the same thing, so they share one line rather
 * than each inventing a sentence about itself. The list is long enough that
 * fifteen near-identical descriptions would be harder to scan, not easier.
 */
private const val CONTAINER_TOGGLE_DESCRIPTION =
    "Switch to Items when this opens, and back when it closes"

/** Reset/Cancel: a game button, but not a thumb-sized one — these sit in a
 *  header beside text, not in a row of controls being aimed at. */
private val SMALL_BUTTON_HEIGHT = 32.dp

/** Big enough to read a 16x16 texture's pattern at a glance, small enough that
 *  all nine fit without scrolling on the Thor's panel. */
private val SWATCH_SIZE = 46.dp

private val ROW_BACKGROUND = Color.Black.copy(alpha = 0.35f)
private val SECONDARY_TEXT = Color(0xFF9A9A9A)
private val DIM_TEXT = Color(0xFF777777)
private val UNBOUND_TEXT = Color(0xFFD08A3A)

/**
 * The Settings tab: what the second screen shows, and what its buttons do.
 *
 * HUD placement is deliberately visibility-only rather than drag-to-place. The
 * status rows all share one alignment grid (see `STATUS_ICON_SIZE`/
 * `STATUS_ICON_COUNT` in [HudScreen]), and free placement would either break
 * that grid or need a layout engine to preserve it — for a fraction of the
 * value. Hiding the elements you don't care about is most of what
 * "customisable" means here.
 *
 * Changes apply instantly: [HudSettings] and [InputSettings] both hold their
 * state in Compose state containers, so the HUD above this panel recomposes as
 * each switch is flipped and the player can see what they're turning off while
 * they turn it off.
 *
 * **The binding picker replaces this panel rather than opening a dialog.** A
 * Compose `Dialog` creates its own window, and this whole tree lives inside a
 * `Presentation` whose window already sits outside the normal Activity
 * hierarchy — swapping the panel's content keeps everything in one window and
 * one lifecycle, with nothing extra to wire up.
 */
@Composable
fun SettingsScreen(
    settings: HudSettings,
    inputSettings: InputSettings,
    chatSettings: ChatSettings,
    containerSettings: ContainerSettings,
    backgroundSettings: BackgroundSettings,
    /** For the background swatches, which draw the real textures. */
    iconProvider: ResourcePackIconProvider,
    bindings: List<GameBinding>,
    onRequestBindings: () -> Unit
) {
    var pickingSlot by remember { mutableStateOf<Int?>(null) }

    // Re-ask on open: a player who rebinds a key in game pushes nothing, so
    // the key names in a list received at connect time can be stale. Ids can't
    // be, which is why this is a nicety rather than a correctness fix.
    LaunchedEffect(pickingSlot) {
        if (pickingSlot != null) onRequestBindings()
    }

    val slot = pickingSlot
    if (slot != null) {
        BindingPicker(
            slotIndex = slot,
            bindings = bindings,
            onPick = { bindingId ->
                inputSettings.setBindingAt(slot, bindingId)
                pickingSlot = null
            },
            onCancel = { pickingSlot = null }
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            SectionHeader(
                title = "Show on this screen",
                // Says where a switched-off element goes. Without this the
                // toggle reads as "hide", and nobody would guess the element
                // reappears in the game itself.
                subtitle = "Anything switched off goes back to the game's HUD on the top screen",
                onReset = if (settings.isDefault) null else settings::resetToDefaults
            )
        }

        items(HudElement.entries) { element ->
            ElementToggle(
                element = element,
                checked = settings.isVisible(element),
                onToggle = { settings.toggle(element) }
            )
        }

        item {
            SectionHeader(
                title = "Background",
                subtitle = "The block tiled behind everything on this screen",
                onReset = if (backgroundSettings.isDefault) null else backgroundSettings::reset
            )
        }

        item {
            BackgroundPicker(
                selected = backgroundSettings.background,
                iconProvider = iconProvider,
                onChoose = backgroundSettings::choose
            )
        }

        item {
            SectionHeader(
                title = "Open the Items tab automatically",
                // Says what "off" means, because the toggle otherwise reads as
                // "disable this container" -- which it isn't. The panel stays
                // reachable by hand either way; only the takeover stops.
                subtitle = "Anything switched off still works — you just have to " +
                    "tap Items yourself",
                onReset = containerSettings::reset
            )
        }

        items(ContainerKind.entries) { kind ->
            SettingToggle(
                label = kind.label,
                description = CONTAINER_TOGGLE_DESCRIPTION,
                checked = containerSettings.opensAutomatically(kind),
                onToggle = { containerSettings.toggle(kind) }
            )
        }

        item {
            SectionHeader(
                title = "Chat",
                subtitle = "How the Chat tab is typed on",
                onReset = null
            )
        }

        item {
            SettingToggle(
                label = "Use the Android keyboard",
                // Says what the risk is rather than promising it works: whether
                // an IME appears on a presentation display at all, and on which
                // screen, is the device's decision and not the app's.
                description = "Off: the keyboard this app draws. On: the system " +
                    "keyboard — nicer to type on where it comes up on this screen, " +
                    "but on some setups it appears over the game instead.",
                checked = chatSettings.useSystemKeyboard,
                onToggle = chatSettings::toggleSystemKeyboard
            )
        }

        item {
            SectionHeader(
                title = "Input buttons",
                subtitle = "What each button on the Input tab presses",
                onReset = if (inputSettings.isDefault) null else inputSettings::resetToDefaults
            )
        }

        items((0 until INPUT_SLOT_COUNT).toList()) { index ->
            InputSlotRow(
                index = index,
                binding = inputSettings.bindingAt(index)?.let { id ->
                    bindings.firstOrNull { it.id == id } ?: GameBinding(
                        id = id,
                        label = id,
                        category = "",
                        boundKey = "",
                        unbound = false
                    )
                },
                onClick = { pickingSlot = index }
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, onReset: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(text = subtitle, color = SECONDARY_TEXT, fontSize = 11.sp)
        }
        if (onReset != null) {
            MinecraftButton(
                text = "Reset",
                onClick = onReset,
                modifier = Modifier.height(SMALL_BUTTON_HEIGHT)
            )
        }
    }
}

/**
 * One row. The whole row is the tap target, not just the switch -- this is a
 * handheld being poked with a thumb, and a bare `Switch` is a small target
 * against a 1240px-wide panel.
 */
/**
 * The backdrop choices, as swatches of the actual texture.
 *
 * **Swatches rather than a list of names**, which is the only reason this isn't
 * another row of `SettingToggle`s: the thing being chosen is how the screen *looks*,
 * and "Mossy cobblestone" tells you far less than seeing it. Tapping one applies it
 * immediately — [BackgroundSettings] holds its value in Compose state, so the
 * backdrop changes under the player's finger and the choice is made by looking
 * rather than by guessing and going back.
 *
 * One tile each, scaled up, rather than a tiled patch. At this size a single 16x16
 * tile is already recognisable, and tiling a thumbnail would just make nine tiny
 * repeating patterns harder to tell apart than nine clear ones.
 *
 * A swatch whose texture hasn't arrived draws its name instead of an empty box, so
 * a fresh connection (or a mod build predating the extra backdrops) still shows a
 * usable picker rather than a row of holes.
 */
@Composable
private fun BackgroundPicker(
    selected: HudBackground,
    iconProvider: ResourcePackIconProvider,
    onChoose: (HudBackground) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(HudBackground.entries) { background ->
            val texture = iconProvider.getBackground(background)
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE)
                    // The name is the only text on a swatch, so it carries the
                    // accessible label as well as the fallback.
                    .semantics { contentDescription = background.label }
                    .clickable { onChoose(background) },
                contentAlignment = Alignment.Center
            ) {
                if (texture != null) {
                    Image(
                        bitmap = texture.asImageBitmap(),
                        contentDescription = null,
                        filterQuality = FilterQuality.None,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = background.label,
                        color = SECONDARY_TEXT,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(2.dp)
                    )
                }
                if (background == selected) {
                    Box(modifier = Modifier.fillMaxSize().vanillaSlotHighlight())
                }
            }
        }
    }
}

@Composable
private fun ElementToggle(
    element: HudElement,
    checked: Boolean,
    onToggle: () -> Unit
) = SettingToggle(
    label = element.label,
    description = element.description,
    checked = checked,
    onToggle = onToggle
)

@Composable
private fun SettingToggle(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ROW_BACKGROUND)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = Color.White, fontSize = 14.sp)
            Text(text = description, color = SECONDARY_TEXT, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            // null: the Row above already handles the tap, and letting the
            // switch handle it too gives the row two competing ripples.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF5B8A3A)
            )
        )
    }
}

/**
 * One grid slot. Numbered 1..9 left-to-right, top-to-bottom, matching how the
 * Input tab reads.
 *
 * The bound key is shown beside the action because it's the thing that makes
 * the row identifiable at a glance — and because seeing it here is how a
 * player notices the action they picked is unbound in game, which is the one
 * case where this button is the only way to trigger it.
 */
@Composable
private fun InputSlotRow(index: Int, binding: GameBinding?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ROW_BACKGROUND)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Button ${index + 1}",
            color = SECONDARY_TEXT,
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = binding?.label ?: "Empty",
                color = if (binding == null) DIM_TEXT else Color.White,
                fontSize = 14.sp
            )
            if (binding != null && binding.boundKey.isNotEmpty()) {
                Text(
                    text = if (binding.unbound) "Not bound to a key in game" else binding.boundKey,
                    color = if (binding.unbound) UNBOUND_TEXT else SECONDARY_TEXT,
                    fontSize = 11.sp
                )
            }
        }
        Text(text = "Change", color = Color(0xFFBBBBBB), fontSize = 12.sp)
    }
}

/**
 * Pick a key binding for one grid slot.
 *
 * The list is whatever the game reported, grouped by its own control
 * categories — including other mods' bindings, which arrive here for free
 * because the mod enumerates `GameOptions.allKeys` rather than a list it
 * maintains.
 */
@Composable
private fun BindingPicker(
    slotIndex: Int,
    bindings: List<GameBinding>,
    onPick: (String?) -> Unit,
    onCancel: () -> Unit
) {
    val grouped = remember(bindings) { bindings.groupBy(GameBinding::category) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Button ${slotIndex + 1}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                MinecraftButton(
                    text = "Cancel",
                    onClick = onCancel,
                    modifier = Modifier.height(SMALL_BUTTON_HEIGHT)
                )
            }
        }

        item {
            PickerRow(title = "Empty", subtitle = "Leave this button blank") { onPick(null) }
        }

        if (bindings.isEmpty()) {
            item {
                Text(
                    text = "No key bindings yet — they arrive when the game connects.",
                    color = SECONDARY_TEXT,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }

        grouped.forEach { (category, entries) ->
            item {
                Text(
                    text = category,
                    color = SECONDARY_TEXT,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )
            }
            items(entries) { binding ->
                PickerRow(
                    title = binding.label,
                    subtitle = if (binding.unbound) "Not bound to a key" else binding.boundKey,
                    subtitleColor = if (binding.unbound) UNBOUND_TEXT else SECONDARY_TEXT
                ) { onPick(binding.id) }
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    subtitleColor: Color = SECONDARY_TEXT,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ROW_BACKGROUND)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, color = Color.White, fontSize = 14.sp)
        if (subtitle.isNotEmpty()) {
            Text(text = subtitle, color = subtitleColor, fontSize = 12.sp)
        }
    }
}
