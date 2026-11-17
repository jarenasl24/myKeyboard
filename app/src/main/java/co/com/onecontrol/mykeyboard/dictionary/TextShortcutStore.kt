package co.com.onecontrol.mykeyboard.dictionary

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TextShortcut(
    val shortcut: String,
    val expansion: String,
    val digits: String
)

class TextShortcutStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "t9_text_shortcuts",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_SHORTCUTS = "shortcuts_json"

        val DEFAULT_SHORTCUTS = listOf(
            TextShortcut("micorreo", "usuario@ejemplo.com", "64267736"),
            TextShortcut("micel", "+57 300 123 4567", "64235"),
            TextShortcut("dir", "Calle 100 # 15-20", "347"),
            TextShortcut("cc", "1020304050", "22")
        )
    }

    fun getAllShortcuts(): List<TextShortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null)
        if (raw == null) {
            // First time: save and return defaults
            saveList(DEFAULT_SHORTCUTS)
            return DEFAULT_SHORTCUTS
        }
        if (raw.isBlank()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<TextShortcut>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val shortcut = obj.getString("shortcut").trim().lowercase()
                val expansion = obj.getString("expansion").trim()
                val digits = T9KeyMap.wordToDigits(shortcut) ?: ""
                list.add(TextShortcut(shortcut, expansion, digits))
            }
            list
        } catch (_: Exception) {
            DEFAULT_SHORTCUTS
        }
    }

    fun saveShortcut(shortcut: String, expansion: String): Boolean {
        val cleanShortcut = shortcut.trim().lowercase()
        val cleanExpansion = expansion.trim()
        if (cleanShortcut.isEmpty() || cleanExpansion.isEmpty()) return false

        val current = getAllShortcuts().toMutableList()
        current.removeAll { it.shortcut == cleanShortcut }
        val digits = T9KeyMap.wordToDigits(cleanShortcut) ?: ""
        current.add(0, TextShortcut(cleanShortcut, cleanExpansion, digits))
        saveList(current)
        return true
    }

    fun deleteShortcut(shortcut: String) {
        val cleanShortcut = shortcut.trim().lowercase()
        val current = getAllShortcuts().toMutableList()
        current.removeAll { it.shortcut == cleanShortcut }
        saveList(current)
    }

    private fun saveList(list: List<TextShortcut>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("shortcut", item.shortcut)
                put("expansion", item.expansion)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_SHORTCUTS, array.toString()).apply()
    }

    fun findMatchesForDigits(digits: String): List<TextShortcut> {
        if (digits.isEmpty()) return emptyList()
        return getAllShortcuts().filter { it.digits == digits }
    }

    fun findMatchesForWord(word: String): List<TextShortcut> {
        val clean = word.trim().lowercase()
        if (clean.isEmpty()) return emptyList()
        return getAllShortcuts().filter { it.shortcut == clean }
    }
}
