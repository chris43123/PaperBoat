package dev.net64.paperboat

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Look and input handling shared by the launcher menu screens. Everything is
 * sized for a handheld held at arm's length and navigable with a D-pad: focus
 * is always visible, A activates, B goes back.
 */
object MenuUi {
    val BACKGROUND = Color.rgb(12, 18, 34)
    val PANEL = Color.rgb(22, 30, 52)
    val ROW = Color.argb(28, 255, 255, 255)
    val ROW_PRESSED = Color.argb(60, 255, 255, 255)
    val ACCENT = Color.rgb(240, 190, 60)   // Star gold.
    val CURTAIN = Color.rgb(150, 30, 36)   // Theatre red.
    val TEXT = Color.WHITE
    val SUBTLE = Color.rgb(160, 172, 192)

    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Filled row that grows a gold outline when focused, so the D-pad cursor is obvious. */
    fun Context.focusBackground(radiusDp: Int = 14): StateListDrawable {
        fun shape(fill: Int, stroke: Int, strokeDp: Int) = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(ROW_PRESSED, ACCENT, 3))
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.argb(48, 240, 190, 60), ACCENT, 3))
            addState(intArrayOf(), shape(ROW, 0, 0))
        }
    }

    fun Context.bigButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), 0, dp(24), 0)
        minHeight = dp(52)
        background = focusBackground()
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun Context.hintBar(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(SUBTLE)
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, 0)
    }

    /**
     * Gamepad A presses the focused view and B backs out. Android's fallback
     * keymap usually does this already, but not on every device, and never for
     * a view that consumed the raw event first.
     * Returns true when the event was handled.
     */
    fun Activity.handleGamepadKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_UP) currentFocus?.performClick()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedCompat()
                return true
            }
        }
        return false
    }

    /** Full screen like the game itself; a swipe from the edge brings the bars back briefly. */
    fun Activity.hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Suppress("DEPRECATION")
    private fun Activity.onBackPressedCompat() = onBackPressed()
}
