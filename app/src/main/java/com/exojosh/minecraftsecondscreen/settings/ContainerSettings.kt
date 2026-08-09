package com.exojosh.minecraftsecondscreen.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val PREFS_NAME = "container_settings"

/**
 * Which containers are allowed to take the bottom screen over, persisted across
 * restarts.
 *
 * Same shape and reasoning as [HudSettings]: a handful of booleans read at startup
 * and written on a tap don't justify a DataStore dependency, and holding the live
 * copy in a `mutableStateMapOf` means flipping a switch changes the behaviour
 * immediately rather than on the next launch.
 *
 * **Everything defaults to on**, so the takeover works out of the box and the
 * settings are there to *stop* it. The reason to stop it is specific and worth
 * recording: a grindstone or an anvil is often a two-second visit, and having the
 * second screen jump off the map or the chat log and back again for that is worse
 * than not helping at all. Which blocks feel that way depends entirely on how
 * someone plays, which is exactly when a setting is the right answer rather than a
 * cleverer heuristic.
 *
 * Note this governs the **takeover only**, not whether the container can be used:
 * switching the anvil off still leaves its panel there if the player goes to the
 * Items tab themselves. Turning off an automatic behaviour shouldn't remove a
 * capability.
 */
class ContainerSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Compose-observable so a flip is visible at once — same trick as
     *  `HudSettings` and `HudRepository.assetCache`. */
    private val opensAutomatically = mutableStateMapOf<String, Boolean>().apply {
        ContainerKind.entries.forEach { kind ->
            put(kind.key, prefs.getBoolean(kind.key, true))
        }
    }

    fun opensAutomatically(kind: ContainerKind): Boolean =
        opensAutomatically[kind.key] ?: true

    fun setOpensAutomatically(kind: ContainerKind, enabled: Boolean) {
        opensAutomatically[kind.key] = enabled
        prefs.edit().putBoolean(kind.key, enabled).apply()
    }

    fun toggle(kind: ContainerKind) =
        setOpensAutomatically(kind, !opensAutomatically(kind))

    /** Puts every container back to taking over, for the Settings tab's reset. */
    fun reset() {
        ContainerKind.entries.forEach { setOpensAutomatically(it, true) }
    }
}

/**
 * The single [ContainerSettings] for this window.
 *
 * Keyed on the *application* context for the same reason [rememberHudSettings] is:
 * a `Presentation` runs against its own display context, but the preferences it
 * reads are app-scoped.
 */
@Composable
fun rememberContainerSettings(): ContainerSettings {
    val context = LocalContext.current
    return remember(context.applicationContext) { ContainerSettings(context) }
}
