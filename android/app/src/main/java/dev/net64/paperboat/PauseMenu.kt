package dev.net64.paperboat

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.net64.paperboat.MenuUi.bigButton
import dev.net64.paperboat.MenuUi.dp
import dev.net64.paperboat.MenuUi.hintBar
import dev.net64.paperboat.MenuUi.pressFocused

/**
 * In-game pause menu, opened by pressing Back twice. The owner pauses the
 * game while it is up ([onOpened]/[onClosed]); focus loss alone doesn't, as
 * SDL keeps running unfocused on multi-window capable Android.
 *
 * Settings can't change under a running game (it would overwrite them), so
 * that entry quits first and reopens on the settings screen.
 */
class PauseMenu(
    private val activity: Activity,
    private val onOpened: () -> Unit,
    private val onClosed: () -> Unit,
    private val onQuit: (openSettings: Boolean) -> Unit,
) {
    private var defaultButton: android.view.View? = null

    private val dialog = Dialog(activity).apply {
        setCancelable(true)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setOnKeyListener { _, _, event -> handleKey(event) }
        setOnShowListener {
            window?.let(::hideBars)
            onOpened()
        }
        setOnDismissListener { onClosed() }
    }

    fun show() {
        showMain()
        dialog.show()
    }

    private fun showMain() {
        panel(
            "Paused",
            null,
            listOf(
                "Resume" to { dialog.dismiss() },
                "Settings" to {
                    confirm("Open settings?", "The game closes so the new settings can take effect.") {
                        quit(openSettings = true)
                    }
                },
                "Quit to main menu" to {
                    confirm("Quit to the main menu?", null) { quit(openSettings = false) }
                },
            ),
        )
    }

    private fun confirm(title: String, detail: String?, onYes: () -> Unit) {
        val message = listOfNotNull(detail, "Anything since your last in-game save is lost.").joinToString(" ")
        panel(title, message, listOf("Yes" to onYes, "No, go back" to { showMain() }), focusIndex = 1)
    }

    private fun quit(openSettings: Boolean) {
        dialog.dismiss()
        onQuit(openSettings)
    }

    private fun panel(title: String, message: String?, buttons: List<Pair<String, () -> Unit>>, focusIndex: Int = 0) {
        val a = activity
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(28), a.dp(20), a.dp(28), a.dp(16))
            background = GradientDrawable().apply {
                cornerRadius = a.dp(20).toFloat()
                setColor(MenuUi.PANEL)
                setStroke(a.dp(2), Color.argb(80, 240, 190, 60))
            }
        }
        root.addView(TextView(a).apply {
            text = title
            textSize = 24f
            setTextColor(MenuUi.ACCENT)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, a.dp(if (message == null) 14 else 6))
        })
        if (message != null) {
            root.addView(TextView(a).apply {
                text = message
                textSize = 15f
                setTextColor(MenuUi.SUBTLE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, a.dp(14))
            })
        }

        val spacing = LinearLayout.LayoutParams(a.dp(360), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = a.dp(8)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val views = buttons.map { (label, action) -> a.bigButton(label, action).also { root.addView(it, spacing) } }
        root.addView(a.hintBar("Ⓐ Select    Ⓑ Back"))

        dialog.setContentView(root)
        defaultButton = views.getOrNull(focusIndex)
        defaultButton?.requestFocus()
    }

    /** Same controls as the launcher screens: A presses, B and Back step out. */
    private fun handleKey(event: KeyEvent): Boolean {
        val up = event.action == KeyEvent.ACTION_UP
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (up) pressFocused(dialog.currentFocus) { defaultButton }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                if (up) dialog.dismiss()
                true
            }
            else -> false
        }
    }

    private fun hideBars(window: android.view.Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
