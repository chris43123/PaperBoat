package dev.net64.paperboat

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.libsdl.app.SDLActivity

/**
 * The game. The on-screen controller is the engine's own, drawn inside the GL
 * surface (src/port/ui/TouchControls.cpp); the only thing layered over SDL is a
 * Mods button, shown while the engine's menu is up.
 *
 * [LauncherActivity] guarantees pm64.o2r exists before this activity starts.
 */
class MainActivity : SDLActivity() {

    private lateinit var modsButton: Button

    private var menuOpen = false

    private val pauseMenu by lazy { PauseMenu(this, ::pauseGame, ::resumeGame, ::quitToMenu) }
    private var lastBackUp = 0L
    private var backHint: Toast? = null
    private var openSettingsAfterQuit = false

    private val handler = Handler(Looper.getMainLooper())
    private val menuWatcher = object : Runnable {
        override fun run() {
            syncMenuState()
            handler.postDelayed(this, MENU_POLL_MS)
        }
    }

    // org/libsdl/app stays byte-identical to the SDL release libultraship
    // pins — SDLActivity refuses to start if the two disagree on version — so
    // the library is named here rather than patched in there.
    override fun getLibraries(): Array<String> = arrayOf("SDL2", "main")

    private external fun isMenuOpen(): Boolean

    private external fun requestQuit(): Boolean

    /**
     * Back twice opens the pause menu. Back reaches here from the navigation
     * gesture and from controllers alike, and the game has no use for it, so
     * it never goes on to SDL.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_UP) {
            val now = event.eventTime
            if (now - lastBackUp < DOUBLE_BACK_MS) {
                lastBackUp = 0L
                backHint?.cancel()
                pauseMenu.show()
            } else {
                lastBackUp = now
                backHint?.cancel()
                backHint = Toast.makeText(this, "Press Back again for the menu", Toast.LENGTH_SHORT).also { it.show() }
            }
        }
        return true
    }

    /** The same transition SDL makes when the app goes to the background. */
    private fun pauseGame() {
        mNextNativeState = NativeState.PAUSED
        handleNativeState()
    }

    /** Takes effect once the window has focus back, if it hasn't already. */
    private fun resumeGame() {
        mNextNativeState = NativeState.RESUMED
        handleNativeState()
    }

    /** Lets the engine shut down on its own so it saves; see onDestroy for the rest. */
    private fun quitToMenu(openSettings: Boolean) {
        openSettingsAfterQuit = openSettings
        if (!requestQuit()) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()
        addModsButton()
    }

    /**
     * Edge to edge, bars hidden. From API 35 the old fullscreen window flags are
     * ignored, so the bars go through the insets controller instead.
     * Transient-by-swipe keeps a stray swipe from resizing the window and
     * forcing the engine to rebuild its framebuffers.
     */
    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Render into the cutout too, rather than letterboxing beside it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The bars return on any focus loss; put them away again coming back.
        if (hasFocus) {
            goFullscreen()
        }
    }

    override fun onResume() {
        super.onResume()
        // Also re-sync now, so returning here never shows a stale menu state.
        syncMenuState()
        handler.removeCallbacks(menuWatcher)
        handler.postDelayed(menuWatcher, MENU_POLL_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(menuWatcher)
        super.onPause()
    }

    /**
     * Quitting the game returns to the main menu. The process ends too: the
     * engine keeps global state that can't be torn down and rebuilt, so the
     * next Play has to start in a fresh one.
     */
    override fun onDestroy() {
        handler.removeCallbacks(menuWatcher)
        val quitting = isFinishing
        super.onDestroy()
        if (quitting) {
            startActivity(
                Intent(this, MenuActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MenuActivity.EXTRA_OPEN_SETTINGS, openSettingsAfterQuit)
            )
            Runtime.getRuntime().exit(0)
        }
    }

    /**
     * The menu can be opened by its own button, a keyboard or a gamepad, and can
     * close itself, so ask the engine rather than track it here.
     */
    private fun syncMenuState() {
        val open = runCatching { isMenuOpen() }.getOrDefault(false)
        if (open == menuOpen) return

        menuOpen = open
        modsButton.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Bottom-left, clear of the engine's menu toggle and of the menu itself. */
    private fun addModsButton() {
        val margin = (16 * resources.displayMetrics.density).toInt()

        modsButton = Button(this).apply {
            text = getString(R.string.mods_button)
            textSize = 12f
            isAllCaps = false
            visibility = android.view.View.GONE
            setOnClickListener { startActivity(Intent(context, ModsActivity::class.java)) }
        }

        val layout = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(margin, margin, margin, margin) }

        findViewById<ViewGroup>(android.R.id.content).addView(modsButton, layout)
    }

    private companion object {
        /** Fast enough to feel immediate on a menu toggle, cheap enough to ignore. */
        const val MENU_POLL_MS = 150L

        /** Long enough for a relaxed double press, short enough that one press never lingers. */
        const val DOUBLE_BACK_MS = 600L
    }
}
