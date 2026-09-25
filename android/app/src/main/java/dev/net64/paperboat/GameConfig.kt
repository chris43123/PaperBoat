package dev.net64.paperboat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads and writes the engine's paperboat.cfg.json while the game is not
 * running, so the launcher menu can change settings natively.
 *
 * CVars are nested objects under "CVars": gSettings.InternalResolution is
 * CVars → gSettings → InternalResolution. The engine only stores values that
 * differ from their defaults, so a missing key means "default".
 *
 * The engine picks a CVar's type from the JSON number itself: 1 loads as an
 * integer and 1.0 as a float, and a float CVar read back as an integer quietly
 * falls back to its default. org.json writes 1.0 as 1, so [write] serializes
 * by hand and keeps every Double with a decimal point.
 */
class GameConfig private constructor(private val file: File, private val root: JSONObject) {

    private val cvars: JSONObject
        get() = root.optJSONObject(CVARS) ?: JSONObject().also { root.put(CVARS, it) }

    fun getInt(key: String, default: Int): Int = when (val v = lookup(key)) {
        is Boolean -> if (v) 1 else 0
        is Int -> v
        is Long -> v.toInt()
        else -> default // Includes a float stored under an int key, as the engine does.
    }

    fun getFloat(key: String, default: Float): Float = when (val v = lookup(key)) {
        is Double -> v.toFloat()
        is Float -> v
        else -> default
    }

    fun getString(key: String, default: String): String = lookup(key) as? String ?: default

    fun setInt(key: String, value: Int) = store(key, value)

    fun setString(key: String, value: String) = store(key, value)

    // Via the string so 0.6f is stored as 0.6, not 0.6000000238418579.
    fun setFloat(key: String, value: Float) = store(key, value.toString().toDouble())

    /** Drops a key so the engine falls back to its built-in default. */
    fun reset(key: String) {
        val (parent, leaf) = walk(key, create = false) ?: return
        parent.remove(leaf)
    }

    /** Writes to a temporary file first so a crash never leaves half a config. */
    fun save() {
        val temp = File(file.parentFile, "${file.name}.menu.tmp")
        temp.writeText(StringBuilder().also { write(root, it, 0) }.append('\n').toString())
        check(temp.renameTo(file)) { "Could not replace ${file.name}." }
    }

    private fun lookup(key: String): Any? {
        val (parent, leaf) = walk(key, create = false) ?: return null
        return parent.opt(leaf)
    }

    private fun store(key: String, value: Any) {
        val (parent, leaf) = walk(key, create = true)!!
        parent.put(leaf, value)
    }

    private fun walk(key: String, create: Boolean): Pair<JSONObject, String>? {
        val parts = key.split('.')
        var node = cvars
        for (part in parts.dropLast(1)) {
            node = node.optJSONObject(part) ?: if (create) JSONObject().also { node.put(part, it) } else return null
        }
        return node to parts.last()
    }

    companion object {
        private const val CVARS = "CVars"
        const val FILE_NAME = "paperboat.cfg.json"

        fun file(context: Context) = File(GameAssets.gameDir(context), FILE_NAME)

        /** A missing or unreadable file starts empty; the engine fills in the rest on first run. */
        fun load(context: Context): GameConfig {
            val file = file(context)
            val root = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
            return GameConfig(file, root)
        }

        private fun write(value: Any?, out: StringBuilder, depth: Int) {
            when (value) {
                null, JSONObject.NULL -> out.append("null")
                is JSONObject -> writeObject(value, out, depth)
                is JSONArray -> writeArray(value, out, depth)
                is String -> out.append(JSONObject.quote(value))
                is Boolean -> out.append(value)
                is Double, is Float -> {
                    val d = (value as Number).toDouble()
                    val text = if (d.isNaN() || d.isInfinite()) "0.0" else d.toString()
                    // Double.toString always has a '.' or an exponent ('1.0E-4'); both parse as floats.
                    out.append(text)
                }
                is Number -> out.append(value.toString())
                else -> out.append(JSONObject.quote(value.toString()))
            }
        }

        private fun writeObject(obj: JSONObject, out: StringBuilder, depth: Int) {
            if (obj.length() == 0) {
                out.append("{}")
                return
            }
            out.append("{\n")
            // Sorted, like the engine's own writer, so diffs of the file stay readable.
            val keys = obj.keys().asSequence().sorted().toList()
            keys.forEachIndexed { i, key ->
                indent(out, depth + 1)
                out.append(JSONObject.quote(key)).append(": ")
                write(obj.opt(key), out, depth + 1)
                if (i < keys.lastIndex) out.append(',')
                out.append('\n')
            }
            indent(out, depth)
            out.append('}')
        }

        private fun writeArray(array: JSONArray, out: StringBuilder, depth: Int) {
            if (array.length() == 0) {
                out.append("[]")
                return
            }
            out.append("[\n")
            for (i in 0 until array.length()) {
                indent(out, depth + 1)
                write(array.opt(i), out, depth + 1)
                if (i < array.length() - 1) out.append(',')
                out.append('\n')
            }
            indent(out, depth)
            out.append(']')
        }

        private fun indent(out: StringBuilder, depth: Int) {
            repeat(depth * 4) { out.append(' ') }
        }
    }
}
