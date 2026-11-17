package co.com.onecontrol.mykeyboard.engine

import co.com.onecontrol.mykeyboard.dictionary.T9DictionaryTrie

class T9PredictiveEngine(
    private val dictionary: T9DictionaryTrie
) {
    private val digitBuffer = StringBuilder()

    val currentDigits: String
        get() = digitBuffer.toString()

    fun isEmpty(): Boolean = digitBuffer.isEmpty()

    fun appendDigit(digit: Char): Boolean {
        if (digit !in '2'..'9') return false
        digitBuffer.append(digit)
        return true
    }

    fun backspaceDigit(): Boolean {
        if (digitBuffer.isEmpty()) return false
        digitBuffer.deleteCharAt(digitBuffer.length - 1)
        return true
    }

    fun clear() {
        digitBuffer.clear()
    }

    fun getSuggestions(language: T9DictionaryTrie.Language, caseMode: CaseMode): List<String> {
        if (digitBuffer.isEmpty()) return emptyList()
        val primary = dictionary.query(language, digitBuffer.toString(), limit = 8)
        if (language == T9DictionaryTrie.Language.ES && primary.size < 4) {
            val fallbackEn = dictionary.query(T9DictionaryTrie.Language.EN, digitBuffer.toString(), limit = 4)
            val combined = (primary + fallbackEn).distinct().take(8)
            return combined.map { caseMode.applyToWord(it) }
        }
        return primary.map { caseMode.applyToWord(it) }
    }

    fun getPrimarySuggestion(language: T9DictionaryTrie.Language, caseMode: CaseMode): String? {
        return getSuggestions(language, caseMode).firstOrNull()
    }

    fun learnWord(language: T9DictionaryTrie.Language, word: String) {
        dictionary.learnWord(language, word)
    }
}
