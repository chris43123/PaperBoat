package dev.net64.paperboat

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.net64.paperboat.MenuUi.bigButton
import dev.net64.paperboat.MenuUi.dp
import dev.net64.paperboat.MenuUi.handleGamepadKey
import dev.net64.paperboat.MenuUi.hideSystemBars
import dev.net64.paperboat.MenuUi.hintBar

/**
 * Main menu, shown once the game data is ready: play, change settings, or
 * manage mods and saves, all by touch or controller.
 *
 * Runs in its own process. The game's process can't be restarted in place,
 * so the menu must never share it; see [MainActivity.onDestroy].
 */
class MenuActivity : ComponentActivity() {

    private lateinit var playButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        // "Settings" in the pause menu quits the game and lands here.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        playButton.text = if (isGameRunning()) "Resume" else "Play"
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(16), dp(48), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(MenuUi.PANEL, MenuUi.BACKGROUND)
            )
        }

        // Left: the title card.
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(TextView(this).apply {
            text = "PAPER MARIO"
            textSize = 44f
            letterSpacing = 0.06f
            setTextColor(MenuUi.ACCENT)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setShadowLayer(dp(6).toFloat(), 0f, dp(3).toFloat(), MenuUi.CURTAIN)
        })
        title.addView(TextView(this).apply {
            text = "PaperBoat · v${packageManager.getPackageInfo(packageName, 0).versionName}"
            textSize = 16f
            setTextColor(MenuUi.SUBTLE)
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Right: the actions, with a hint bar under them.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val spacing = LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }

        playButton = bigButton("Play") { play() }
        actions.addView(playButton, spacing)
        actions.addView(bigButton("Settings") { openSettings() }, spacing)
        actions.addView(bigButton("Mods") { startActivity(Intent(this, ModsActivity::class.java)) }, spacing)
        actions.addView(bigButton("Saves") { startActivity(Intent(this, SavesActivity::class.java)) }, spacing)
        actions.addView(bigButton("Exit") { finishAndRemoveTask() }, spacing)
        actions.addView(hintBar("Ⓐ Select    Ⓑ Back"))
        root.addView(actions)

        setContentView(root)
        hideSystemBars()
        playButton.requestFocus()
    }

    private fun play() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * The engine writes its config as it runs and again on exit, so editing it
     * underneath a live game would be silently undone.
     */
    private fun openSettings() {
        if (!isGameRunning()) return startActivity(Intent(this, SettingsActivity::class.java))

        AlertDialog.Builder(this)
            .setTitle("The game is still running")
            .setMessage(
                "Settings can only change while the game is closed, or it will overwrite them. " +
                    "Close it now? Anything since your last in-game save is lost."
            )
            .setPositiveButton("Close game") { _, _ ->
                gamePid()?.let(Process::killProcess)
                // Killed first, so removing the task can't run the game's onDestroy.
                gameTasks().forEach { it.finishAndRemoveTask() }
                playButton.text = "Play"
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * By its task, not its process: Mods and Saves share the game's default
     * process, so that process can be alive with no game in it.
     */
    private fun isGameRunning(): Boolean = gameTasks().isNotEmpty() && gamePid() != null

    private fun gameTasks(): List<ActivityManager.AppTask> {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.appTasks.filter { it.taskInfo.baseActivity?.className == MainActivity::class.java.name }
    }

    /** The game runs in the app's default process; the menu and launcher never do. */
    private fun gamePid(): Int? {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses
            ?.firstOrNull { it.processName == packageName }
            ?.pid
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleGamepadKey(event) { playButton } || super.dispatchKeyEvent(event)

    companion object {
        const val EXTRA_OPEN_SETTINGS = "dev.net64.paperboat.OPEN_SETTINGS"
    }
}
