package dev.net64.paperboat

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * A gamepad input, named the way SDL's game controller API names it: that is
 * what libultraship stores, and SDL on Android maps Android's key codes and
 * axes onto it one to one.
 */
sealed class PadInput {
    abstract val label: String

    /** SDL_GameControllerButton. */
    data class Button(val sdl: Int) : PadInput() {
        override val label get() = BUTTON_NAMES.getOrElse(sdl) { "Button $sdl" }
    }

    /** SDL_GameControllerAxis pushed past its threshold; [direction] is 1 or -1. */
    data class Axis(val sdl: Int, val direction: Int) : PadInput() {
        override val label: String
            get() = when (sdl) {
                SDL_AXIS_LEFTX -> if (direction < 0) "Left stick ←" else "Left stick →"
                SDL_AXIS_LEFTY -> if (direction < 0) "Left stick ↑" else "Left stick ↓"
                SDL_AXIS_RIGHTX -> if (direction < 0) "Right stick ←" else "Right stick →"
                SDL_AXIS_RIGHTY -> if (direction < 0) "Right stick ↑" else "Right stick ↓"
                SDL_AXIS_TRIGGERLEFT -> "L2"
                SDL_AXIS_TRIGGERRIGHT -> "R2"
                else -> "Axis $sdl"
            }
    }

    companion object {
        const val SDL_BUTTON_DPAD_UP = 11
        const val SDL_BUTTON_DPAD_RIGHT = 14
        private const val SDL_AXIS_LEFTX = 0
        private const val SDL_AXIS_LEFTY = 1
        private const val SDL_AXIS_RIGHTX = 2
        private const val SDL_AXIS_RIGHTY = 3
        private const val SDL_AXIS_TRIGGERLEFT = 4
        private const val SDL_AXIS_TRIGGERRIGHT = 5

        // SDL_GameControllerButton order.
        private val BUTTON_NAMES = listOf(
            "A", "B", "X", "Y", "Select", "Home", "Start", "L3", "R3", "L1", "R1",
            "D-pad ↑", "D-pad ↓", "D-pad ←", "D-pad →",
        )

        /** How far a stick or trigger has to move to count as a press, as the engine's own capture uses. */
        private const val AXIS_THRESHOLD = 0.7f

        /**
         * The input a key press stands for, or null for keys the game can't
         * see. Back is deliberately missing: the game keeps it for its pause
         * menu, so it never reaches the engine.
         */
        fun fromKey(keyCode: Int): PadInput? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> Button(0)
            KeyEvent.KEYCODE_BUTTON_B -> Button(1)
            KeyEvent.KEYCODE_BUTTON_X -> Button(2)
            KeyEvent.KEYCODE_BUTTON_Y -> Button(3)
            KeyEvent.KEYCODE_BUTTON_SELECT -> Button(4)
            KeyEvent.KEYCODE_BUTTON_MODE -> Button(5)
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> Button(6)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> Button(7)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> Button(8)
            KeyEvent.KEYCODE_BUTTON_L1 -> Button(9)
            KeyEvent.KEYCODE_BUTTON_R1 -> Button(10)
            KeyEvent.KEYCODE_DPAD_UP -> Button(11)
            KeyEvent.KEYCODE_DPAD_DOWN -> Button(12)
            KeyEvent.KEYCODE_DPAD_LEFT -> Button(13)
            KeyEvent.KEYCODE_DPAD_RIGHT -> Button(14)
            // Digital triggers still reach the game as SDL's trigger axes.
            KeyEvent.KEYCODE_BUTTON_L2 -> Axis(SDL_AXIS_TRIGGERLEFT, 1)
            KeyEvent.KEYCODE_BUTTON_R2 -> Axis(SDL_AXIS_TRIGGERRIGHT, 1)
            else -> null
        }

        /**
         * The input a joystick motion stands for, or null while nothing is
         * pushed far enough. The left stick is left out: it always moves Mario,
         * and a resting stick drifting past the threshold would be caught instead.
         */
        fun fromMotion(event: MotionEvent): PadInput? {
            if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return null
            fun value(axis: Int) = event.getAxisValue(axis)

            val hatX = value(MotionEvent.AXIS_HAT_X)
            val hatY = value(MotionEvent.AXIS_HAT_Y)
            when {
                hatY < -0.5f -> return Button(11)
                hatY > 0.5f -> return Button(12)
                hatX < -0.5f -> return Button(13)
                hatX > 0.5f -> return Button(14)
            }
            if (maxOf(value(MotionEvent.AXIS_LTRIGGER), value(MotionEvent.AXIS_BRAKE)) > AXIS_THRESHOLD) {
                return Axis(SDL_AXIS_TRIGGERLEFT, 1)
            }
            if (maxOf(value(MotionEvent.AXIS_RTRIGGER), value(MotionEvent.AXIS_GAS)) > AXIS_THRESHOLD) {
                return Axis(SDL_AXIS_TRIGGERRIGHT, 1)
            }
            val rightX = value(MotionEvent.AXIS_Z)
            val rightY = value(MotionEvent.AXIS_RZ)
            if (maxOf(kotlin.math.abs(rightX), kotlin.math.abs(rightY)) > AXIS_THRESHOLD) {
                return if (kotlin.math.abs(rightX) >= kotlin.math.abs(rightY)) {
                    Axis(SDL_AXIS_RIGHTX, if (rightX < 0) -1 else 1)
                } else {
                    Axis(SDL_AXIS_RIGHTY, if (rightY < 0) -1 else 1)
                }
            }
            return null
        }
    }
}

/** An N64 button, by its bit in the pad's button mask. */
class N64Button(val bitmask: Int, val label: String)

/**
 * Reads and rewrites libultraship's gamepad bindings for controller port 1 in
 * [GameConfig], in the same shape its own input editor saves:
 *
 *     gSettings.Controllers.Port1.Buttons.<bitmask>ButtonMappingIds = "id,id,"
 *     gSettings.Controllers.ButtonMappings.<id> = { ButtonMappingClass, Bitmask, ... }
 *
 * Only SDL gamepad bindings are touched; keyboard ones stay as they are.
 */
class ButtonMappings(private val config: GameConfig) {

    /**
     * The engine writes its default bindings the first time it starts. Before
     * that there is nothing to edit, and anything written here would stop it
     * from adding the rest (sticks included).
     */
    val hasConfig get() = config.getInt("$PORT.HasConfig", 0) != 0

    fun inputsFor(button: N64Button): List<PadInput> = ids(button).mapNotNull(::parse)

    /**
     * Binds [input] to [button] alone, replacing its gamepad bindings. If
     * another button already used [input], that one takes over [button]'s old
     * bindings, so nothing is left doubled up. Returns the button swapped with.
     */
    fun assign(button: N64Button, input: PadInput): N64Button? {
        val previous = inputsFor(button)
        val other = BUTTONS.firstOrNull { it !== button && input in inputsFor(it) }
        if (other != null) {
            setInputs(other, inputsFor(other).filter { it != input } + previous.filter { it !in inputsFor(other) })
        }
        setInputs(button, listOf(input))
        return other
    }

    fun resetAll() {
        BUTTONS.forEach { setInputs(it, DEFAULTS.getValue(it.bitmask)) }
    }

    private fun setInputs(button: N64Button, inputs: List<PadInput>) {
        val kept = ids(button).filter { parse(it) == null }
        ids(button).filter { parse(it) != null }.forEach { config.reset("$DEFINITIONS.$it") }

        val added = inputs.map { input ->
            val id = idFor(button, input)
            val key = "$DEFINITIONS.$id"
            config.setInt("$key.Bitmask", button.bitmask)
            when (input) {
                is PadInput.Button -> {
                    config.setString("$key.ButtonMappingClass", "SDLButtonToButtonMapping")
                    config.setInt("$key.SDLControllerButton", input.sdl)
                }
                is PadInput.Axis -> {
                    config.setString("$key.ButtonMappingClass", "SDLAxisDirectionToButtonMapping")
                    config.setInt("$key.SDLControllerAxis", input.sdl)
                    config.setInt("$key.AxisDirection", input.direction)
                }
            }
            id
        }

        val all = added + kept
        if (all.isEmpty()) config.reset(idsKey(button)) else config.setString(idsKey(button), all.joinToString("") { "$it," })
    }

    private fun ids(button: N64Button) =
        config.getString(idsKey(button), "").split(',').filter { it.isNotBlank() }

    private fun idsKey(button: N64Button) = "$PORT.Buttons.${button.bitmask}ButtonMappingIds"

    private fun idFor(button: N64Button, input: PadInput) = when (input) {
        is PadInput.Button -> "P0-B${button.bitmask}-SDLB${input.sdl}"
        is PadInput.Axis -> "P0-B${button.bitmask}-SDLA${input.sdl}-AD${if (input.direction < 0) "N" else "P"}"
    }

    /** The gamepad input an id names, or null for keyboard, mouse and anything else. */
    private fun parse(id: String): PadInput? {
        BUTTON_ID.matchEntire(id)?.let { return PadInput.Button(it.groupValues[1].toInt()) }
        AXIS_ID.matchEntire(id)?.let {
            return PadInput.Axis(it.groupValues[1].toInt(), if (it.groupValues[2] == "N") -1 else 1)
        }
        return null
    }

    companion object {
        private const val CONTROLLERS = "gSettings.Controllers"
        private const val PORT = "$CONTROLLERS.Port1"
        private const val DEFINITIONS = "$CONTROLLERS.ButtonMappings"
        private val BUTTON_ID = Regex("""P0-B\d+-SDLB(\d+)""")
        private val AXIS_ID = Regex("""P0-B\d+-SDLA(\d+)-AD([PN])""")

        // Bits from libultraship's N64 pad (BTN_A and friends).
        val BUTTONS = listOf(
            N64Button(0x8000, "A"),
            N64Button(0x4000, "B"),
            N64Button(0x2000, "Z"),
            N64Button(0x1000, "Start"),
            N64Button(0x0020, "L"),
            N64Button(0x0010, "R"),
            N64Button(0x0008, "C ↑"),
            N64Button(0x0004, "C ↓"),
            N64Button(0x0002, "C ←"),
            N64Button(0x0001, "C →"),
            N64Button(0x0800, "D-pad ↑"),
            N64Button(0x0400, "D-pad ↓"),
            N64Button(0x0200, "D-pad ←"),
            N64Button(0x0100, "D-pad →"),
        )

        // libultraship's defaults (ControllerDefaultMappings.cpp).
        private val DEFAULTS = mapOf(
            0x8000 to listOf(PadInput.Button(0)),
            0x4000 to listOf(PadInput.Button(1)),
            0x2000 to listOf(PadInput.Axis(4, 1)),
            0x1000 to listOf(PadInput.Button(6)),
            0x0020 to listOf(PadInput.Button(9)),
            0x0010 to listOf(PadInput.Axis(5, 1)),
            0x0008 to listOf(PadInput.Axis(3, -1)),
            0x0004 to listOf(PadInput.Axis(3, 1)),
            0x0002 to listOf(PadInput.Axis(2, -1)),
            0x0001 to listOf(PadInput.Axis(2, 1)),
            0x0800 to listOf(PadInput.Button(11)),
            0x0400 to listOf(PadInput.Button(12)),
            0x0200 to listOf(PadInput.Button(13)),
            0x0100 to listOf(PadInput.Button(14)),
        )
    }
}
