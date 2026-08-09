package com.exojosh.minecraftsecondscreen

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.exojosh.minecraftsecondscreen.net.GameDirectoryAccess
import com.exojosh.minecraftsecondscreen.net.HudRepository
import com.exojosh.minecraftsecondscreen.net.ResourcePackIconProvider

/**
 * Host activity. **It deliberately draws nothing of its own on the Thor.**
 *
 * It used to paint a status line on the primary screen ("Waiting for mod on
 * 127.0.0.1:48291..."), which was the wrong thing in the wrong place: the
 * primary screen is the one running Minecraft, so the only time that line was
 * visible was when the game wasn't, and everything it reported -- connected or
 * not, and why -- the second screen already says for itself. Its window is now
 * translucent with no content, so launching the app leaves the top screen
 * showing whatever was already there and lights up the bottom panel instead.
 *
 * The activity still has to exist and stay alive: a `Presentation` is a Dialog
 * owned by an Activity, and it is dismissed when that Activity is destroyed.
 * So this class is purely the second screen's anchor and the owner of the one
 * [HudRepository] instance.
 *
 * Display detection: the Thor exposes its bottom panel as a standard Android
 * "presentation" category display, discoverable through [DisplayManager] like
 * any other secondary display (the same mechanism as HDMI-out or Chromecast).
 */
class MainActivity : ComponentActivity() {

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            GameDirectoryAccess.persist(this, it)
            iconProvider.refresh()
        }
    }
    private lateinit var iconProvider: ResourcePackIconProvider

    private lateinit var hudRepository: HudRepository
    private var presentation: SecondScreenPresentation? = null

    /**
     * Only ever true on a single-screen device -- see [showOnPrimary] below.
     * Compose state rather than a flag, so attaching the panel late clears the
     * primary-screen copy in the same frame it appears on the bottom screen.
     */
    private var showOnPrimary by mutableStateOf(false)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = attachSecondScreen()
        override fun onDisplayChanged(displayId: Int) = attachSecondScreen()
        override fun onDisplayRemoved(displayId: Int) {
            // A removed display dismisses its own Presentation; drop our
            // reference so the next attach doesn't think one is still up.
            if (presentation?.display?.displayId == displayId) {
                presentation = null
            }
            attachSecondScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hudRepository = HudRepository(lifecycleScope)

        // The mod pushes every HUD texture over the socket on connect, so the
        // provider reads from the repository first and only falls back to
        // bundled files. Constructed after the repository for that reason.
        iconProvider = ResourcePackIconProvider(this, hudRepository)
        // pickFolderLauncher.launch(null)

        hudRepository.start()

        // The single-screen fallback. On the Thor this composes nothing at all
        // and the window stays transparent; on a phone or emulator, where there
        // is no presentation display to attach to, it puts the *real* UI on the
        // only screen there is. That's the dev/test path, and it's strictly
        // more useful than the status line it replaced -- which showed a
        // connection state and none of the screen being built.
        setContent {
            if (showOnPrimary) {
                SecondScreenApp(
                    hudRepository = hudRepository,
                    iconProvider = iconProvider
                )
            }
        }

        // Registered before the first attach, so a panel that enumerates a beat
        // after launch is picked up rather than missed for the whole session.
        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        attachSecondScreen()
    }

    override fun onStart() {
        super.onStart()
        // Coming back from the background: the panel may have been re-enumerated
        // (the Thor's assistant overlay, a dock, a display reset) while we were
        // stopped, and no listener callback survives a missed window.
        attachSecondScreen()
    }

    /**
     * Idempotent: safe to call from onCreate, onStart and every display event.
     * Shows the Presentation on the bottom panel if one isn't already up, and
     * only falls back to the primary screen once there is provably no panel to
     * use -- so content lands on the bottom screen whenever a bottom screen
     * exists, and the fallback never flashes on the way there.
     */
    private fun attachSecondScreen() {
        if (isFinishing || isDestroyed) return

        presentation?.let {
            if (it.isShowing) {
                showOnPrimary = false
                return
            }
            it.dismiss()
            presentation = null
        }

        val display = findSecondScreenDisplay()
        if (display == null) {
            // Not on a dual-screen device, or the panel isn't exposed as a
            // secondary display -- render here instead of nowhere.
            showOnPrimary = true
            return
        }

        presentation = SecondScreenPresentation(
            activity = this,
            display = display,
            hudRepository = hudRepository,
            iconProvider = iconProvider
        ).also { shown ->
            shown.setOnDismissListener {
                // Dismissed by the system rather than by us (display pulled).
                if (presentation === shown) presentation = null
            }
            shown.show()
        }
        showOnPrimary = false
    }

    /**
     * The presentation *category* is the documented way to ask this, and on the
     * Thor it answers with the bottom panel. The second pass exists because
     * that category is a device-declared flag: a dual-screen device that fails
     * to set it would otherwise fall all the way back to the primary screen,
     * even though a perfectly usable second display is sitting right there.
     * Either way the default display is never a candidate -- that's the one
     * running the game.
     */
    private fun findSecondScreenDisplay(): Display? {
        val displayManager = getSystemService(DisplayManager::class.java) ?: return null

        return displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
            ?: displayManager.getDisplays()
                .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        presentation = null
        super.onDestroy()
    }
}
