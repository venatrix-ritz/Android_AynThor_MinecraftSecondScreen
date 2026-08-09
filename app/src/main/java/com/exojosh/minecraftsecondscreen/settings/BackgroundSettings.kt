package com.exojosh.minecraftsecondscreen.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val PREFS_NAME = "background_settings"
private const val KEY_BACKGROUND = "background"

/**
 * Which block texture is tiled behind the second screen, persisted across
 * restarts.
 *
 * Same shape and reasoning as [ChatSettings]: one value read at startup and
 * written on a tap doesn't justify a DataStore dependency, and holding the live
 * copy in Compose state means the backdrop changes under the player's finger as
 * they pick — which is the whole point of choosing one visually rather than from a
 * list of names.
 */
class BackgroundSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Set through [choose], which persists as well as assigns — the two must not
     *  drift, so the setter is private. */
    var background by mutableStateOf(HudBackground.byKey(prefs.getString(KEY_BACKGROUND, null)))
        private set

    fun choose(next: HudBackground) {
        background = next
        prefs.edit().putString(KEY_BACKGROUND, next.key).apply()
    }

    val isDefault: Boolean get() = background == HudBackground.DEFAULT

    fun reset() = choose(HudBackground.DEFAULT)
}

/**
 * The single [BackgroundSettings] for this window.
 *
 * Keyed on the *application* context for the same reason [rememberHudSettings] is:
 * a `Presentation` runs against its own display context, but the preferences it
 * reads are app-scoped.
 */
@Composable
fun rememberBackgroundSettings(): BackgroundSettings {
    val context = LocalContext.current
    return remember(context.applicationContext) { BackgroundSettings(context) }
}
