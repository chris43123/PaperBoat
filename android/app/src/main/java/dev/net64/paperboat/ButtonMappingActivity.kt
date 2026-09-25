package dev.net64.paperboat

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.net64.paperboat.MenuUi.bigButton
import dev.net64.paperboat.MenuUi.dp
import dev.net64.paperboat.MenuUi.focusBackground
import dev.net64.paperboat.MenuUi.handleGamepadKey
import dev.net64.paperboat.MenuUi.hideSystemBars
import dev.net64.paperboat.MenuUi.hintBar

/**
 * Rebinds each N64 button to a button on the handheld: pick a row, press the
 * new button. Opened from Settings, so like it only runs while the game is
 * closed, and the engine loads the new bindings when it next starts.
 */
class ButtonMappingActivity : ComponentActivity() {

    private lateinit var config: GameConfig
    private lateinit var mappings: ButtonMappings
    private lateinit var list: LinearLayout
    private val rows = mutableListOf<MappingRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = GameConfig.load(this)
        mappings = ButtonMappings(config)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(16), dp(32), dp(8))
            setBackgroundColor(MenuUi.BACKGROUND)
        }
        root.addView(TextView(this).apply {
            text = "Button mapping"
            textSize = 26f
            setTextColor(MenuUi.ACCENT)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setPadding(dp(8), 0, 0, dp(8))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        root.addView(TextView(this).apply {
            text = "Back is kept for the pause menu, so it can't be assigned. The left stick always moves Mario."
            textSize = 15f
            setTextColor(MenuUi.SUBTLE)
            setPadding(dp(4), dp(10), dp(4), 0)
        })
        root.addView(hintBar("Ⓐ Change    Ⓑ Back"))

        setContentView(root)
        hideSystemBars()
        fillList()
    }

    private fun fillList() {
        list.removeAllViews()
        rows.clear()
        val spacing = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(6) }

        if (!mappings.hasConfig) {
            list.addView(TextView(this).apply {
                text = "Start the game once first. It sets up the default controls the first time it runs, " +
                    "and they can be changed here after that."
                textSize = 18f
                setTextColor(MenuUi.TEXT)
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
            return
        }

        ButtonMappings.BUTTONS.forEach { button ->
            val row = MappingRow(this, button)
            rows += row
            list.addView(row, spacing)
        }
        list.addView(bigButton("Reset all buttons to defaults") { confirmReset() }.apply {
            textSize = 16f
            setTextColor(MenuUi.SUBTLE)
        }, spacing)
        rows.first().requestFocus()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset button mapping?")
            .setMessage("Every button goes back to the game's default.")
            .setPositiveButton("Reset") { _, _ ->
                mappings.resetAll()
                save()
                rows.forEach { it.refresh() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun assign(button: N64Button, input: PadInput) {
        val swapped = mappings.assign(button, input)
        save()
        rows.forEach { it.refresh() }

        val messages = mutableListOf<String>()
        if (swapped != null) {
            val now = mappings.inputsFor(swapped).joinToString(" / ") { it.label }.ifEmpty { "nothing" }
            messages += "${input.label} was on ${swapped.label}, which is now on $now."
        }
        val dpad = input is PadInput.Button && input.sdl in PadInput.SDL_BUTTON_DPAD_UP..PadInput.SDL_BUTTON_DPAD_RIGHT
        if (dpad && button.label.startsWith("D-pad").not() && config.getInt(DPAD_AS_STICK, 0) != 0) {
            messages += "\"D-pad moves Mario\" is on, so ${input.label} also moves him."
        }
        if (messages.isNotEmpty()) Toast.makeText(this, messages.joinToString("\n"), Toast.LENGTH_LONG).show()
    }

    private fun save() {
        runCatching { config.save() }.onFailure {
            Toast.makeText(this, "Could not save settings: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleGamepadKey(event) { rows.firstOrNull() } || super.dispatchKeyEvent(event)

    /** Waits for the next button press and binds it to [button]. Back, B on screen, or Cancel gives up. */
    private fun capture(button: N64Button) {
        lateinit var dialog: Dialog
        // Keys bind on release, and only if they went down while this was
        // open: the press that opened it, or the release of the bound key,
        // must not leak back into the list and act on it.
        val downs = mutableSetOf<Int>()
        var done = false

        fun finish(input: PadInput?) {
            if (done) return
            done = true
            dialog.dismiss()
            if (input != null) assign(button, input)
        }

        dialog = object : Dialog(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) finish(null)
                    return true
                }
                val input = PadInput.fromKey(event.keyCode) ?: return super.dispatchKeyEvent(event)
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> downs += event.keyCode
                    KeyEvent.ACTION_UP -> if (event.keyCode in downs) finish(input)
                }
                return true
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                val input = PadInput.fromMotion(event) ?: return super.dispatchGenericMotionEvent(event)
                finish(input)
                return true
            }
        }.apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setContentView(capturePanel(button) { finish(null) })
        }
        dialog.show()
    }

    private fun capturePanel(button: N64Button, onCancel: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(32), dp(24), dp(32), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(MenuUi.PANEL)
            setStroke(dp(2), Color.argb(80, 240, 190, 60))
        }
        addView(TextView(context).apply {
            text = "Press the button for ${button.label}"
            textSize = 24f
            setTextColor(MenuUi.ACCENT)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Back cancels."
            textSize = 15f
            setTextColor(MenuUi.SUBTLE)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(14))
        })
        // Touch only: a focusable button would take the D-pad press meant for binding.
        addView(bigButton("Cancel", onCancel).apply {
            isFocusable = false
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    /** One N64 button: its name on the left, what it's bound to on the right. */
    @SuppressLint("ViewConstructor")
    private inner class MappingRow(context: Context, private val button: N64Button) : LinearLayout(context) {

        private val value = TextView(context).apply {
            textSize = 18f
            setTextColor(MenuUi.ACCENT)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.END
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(20), dp(6), dp(20), dp(6))
            background = focusBackground()
            isFocusable = true
            isClickable = true

            addView(TextView(context).apply {
                text = button.label
                textSize = 18f
                setTextColor(MenuUi.TEXT)
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            setOnClickListener { capture(button) }
            refresh()
        }

        fun refresh() {
            value.text = mappings.inputsFor(button).joinToString(" / ") { it.label }.ifEmpty { "—" }
        }
    }

    private companion object {
        const val DPAD_AS_STICK = "gSettings.Controls.DPadAsLeftStick"
    }
}
