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

    fun setDigits(digits: String) {
        digitBuffer.clear()
        for (ch in digits) {
            if (ch in '2'..'9') {
                digitBuffer.append(ch)
            }
        }
    }

    fun backspaceDigit(): Boolean {
        if (digitBuffer.isEmpty()) return false
        digitBuffer.deleteCharAt(digitBuffer.length - 1)
        return true
    }

    fun clear() {
        digitBuffer.clear()
    }

    fun getSuggestions(
        language: T9DictionaryTrie.Language,
        caseMode: CaseMode,
        prevWord2: String? = null,
        prevWord1: String? = null
    ): List<String> {
        if (digitBuffer.isEmpty()) return emptyList()
        val digits = digitBuffer.toString()
        val primary = dictionary.query(
            language = language,
            digitSequence = digits,
            limit = 8,
            prevWord2 = prevWord2,
            prevWord1 = prevWord1
        )
        if (language == T9DictionaryTrie.Language.ES && primary.size < 4) {
            val fallbackEn = dictionary.query(
                language = T9DictionaryTrie.Language.EN,
                digitSequence = digits,
                limit = 4,
                prevWord2 = prevWord2,
                prevWord1 = prevWord1
            )
            val combined = (primary + fallbackEn).distinct().take(8)
            return combined.map { caseMode.applyToWord(it) }
        }
        return primary.map { caseMode.applyToWord(it) }
    }

    fun getPrimarySuggestion(
        language: T9DictionaryTrie.Language,
        caseMode: CaseMode,
        prevWord2: String? = null,
        prevWord1: String? = null
    ): String? {
        return getSuggestions(language, caseMode, prevWord2, prevWord1).firstOrNull()
    }

    fun learnWord(language: T9DictionaryTrie.Language, word: String, explicit: Boolean = false) {
        dictionary.learnWord(language, word, explicit)
    }

    fun penalizeWord(language: T9DictionaryTrie.Language, word: String) {
        dictionary.penalizeWord(language, word)
    }
}
