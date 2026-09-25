package dev.net64.paperboat

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
 * Native settings, written straight into paperboat.cfg.json. Only opened
 * while the game is closed ([MenuActivity] makes sure), so the engine picks
 * everything up the next time it starts.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var config: GameConfig
    private lateinit var tabs: LinearLayout
    private lateinit var sectionTitle: TextView
    private lateinit var list: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var help: TextView
    private var section = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = GameConfig.load(this)
        buildUi()
        showSection(0)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(32), dp(16), dp(32), dp(8))
            setBackgroundColor(MenuUi.BACKGROUND)
        }

        // Left: section tabs. Not focusable, so the D-pad stays in the list;
        // controllers switch sections with L1/R1 instead.
        tabs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabs.addView(TextView(this).apply {
            text = "Settings"
            textSize = 26f
            setTextColor(MenuUi.ACCENT)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setPadding(dp(8), 0, 0, dp(16))
        })
        MenuSettings.sections.forEachIndexed { index, s ->
            tabs.addView(TextView(this).apply {
                text = s.title
                textSize = 18f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isFocusable = false
                setOnClickListener { showSection(index) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(4) })
        }
        root.addView(tabs, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT))

        // Right: the rows of the current section, a help line and the hints.
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, 0, 0)
        }
        sectionTitle = TextView(this).apply {
            textSize = 20f
            setTextColor(MenuUi.TEXT)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(8))
        }
        right.addView(sectionTitle)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        right.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        help = TextView(this).apply {
            textSize = 15f
            setTextColor(MenuUi.SUBTLE)
            minLines = 2
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        right.addView(help)
        right.addView(hintBar("◀ ▶ Change    Ⓐ Next value    L1 / R1 Section    Ⓑ Back"))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        setContentView(root)
        hideSystemBars()
    }

    private val selectedTab by lazy {
        GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(48, 240, 190, 60))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun showSection(index: Int) {
        section = index
        val current = MenuSettings.sections[index]
        sectionTitle.text = current.title

        // Tab 0 is the "Settings" heading.
        for (i in 1 until tabs.childCount) {
            val tab = tabs.getChildAt(i) as TextView
            val selected = i - 1 == index
            tab.setTextColor(if (selected) MenuUi.ACCENT else MenuUi.SUBTLE)
            tab.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tab.background = if (selected) selectedTab else null
        }

        list.removeAllViews()
        val spacing = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(6) }
        current.settings.forEach { list.addView(SettingRow(this, it), spacing) }
        list.addView(bigButton("Reset ${current.title.lowercase()} to defaults") { confirmReset() }.apply {
            textSize = 16f
            setTextColor(MenuUi.SUBTLE)
            setOnFocusChangeListener { _, focused ->
                if (focused) help.text = "Puts every setting on this page back to the game's default."
            }
        }, spacing)

        scroller.scrollTo(0, 0)
        list.getChildAt(0)?.requestFocus()
        // Opened by touch, nothing shows focus until the first D-pad press, so
        // don't leave the help line blank until then.
        help.text = current.settings.firstOrNull()?.help.orEmpty()
    }

    private fun confirmReset() {
        val current = MenuSettings.sections[section]
        AlertDialog.Builder(this)
            .setTitle("Reset ${current.title}?")
            .setMessage("Every setting on this page goes back to its default.")
            .setPositiveButton("Reset") { _, _ ->
                current.settings.forEach { config.reset(it.key) }
                save()
                showSection(section)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        runCatching { config.save() }.onFailure {
            Toast.makeText(this, "Could not save settings: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sections = MenuSettings.sections.size
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (event.action == KeyEvent.ACTION_DOWN) showSection((section - 1 + sections) % sections)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (event.action == KeyEvent.ACTION_DOWN) showSection((section + 1) % sections)
                return true
            }
        }
        return handleGamepadKey(event) || super.dispatchKeyEvent(event)
    }

    /** One setting: label on the left, ◀ value ▶ on the right. */
    @SuppressLint("ViewConstructor")
    private inner class SettingRow(context: Context, private val setting: MenuSetting) : LinearLayout(context) {

        private val value = TextView(context).apply {
            textSize = 18f
            setTextColor(MenuUi.ACCENT)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        // Toggles and choices wrap around; sliders stop at their ends.
        private val wraps = setting is MenuSetting.Toggle || setting is MenuSetting.Choice

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(20), dp(6), dp(8), dp(6))
            background = focusBackground()
            isFocusable = true
            isClickable = true

            addView(TextView(context).apply {
                text = setting.label
                textSize = 18f
                setTextColor(MenuUi.TEXT)
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow("◀") { step(-1) })
            addView(value, LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(arrow("▶") { step(+1) })

            setOnClickListener { step(+1) }
            setOnFocusChangeListener { _, focused -> if (focused) help.text = setting.help }
            refresh()
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { step(+1); true }
            else -> super.onKeyDown(keyCode, event)
        }

        private fun step(delta: Int) {
            val count = setting.stepCount()
            val current = setting.currentStep(config)
            val next = if (wraps) (current + delta + count) % count else (current + delta).coerceIn(0, count - 1)
            if (next == current) return
            setting.applyStep(config, next)
            save()
            refresh()
        }

        private fun refresh() {
            value.text = setting.describe(config)
        }

        /** Big touch targets; not focusable, so the D-pad treats the row as one control. */
        private fun arrow(glyph: String, onClick: () -> Unit) = TextView(context).apply {
            text = glyph
            textSize = 20f
            setTextColor(MenuUi.SUBTLE)
            gravity = Gravity.CENTER
            isFocusable = false
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(dp(52), dp(48))
        }
    }
}
