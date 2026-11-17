package co.com.onecontrol.mykeyboard.engine

import co.com.onecontrol.mykeyboard.dictionary.T9DictionaryTrie

enum class InputMode(val label: String) {
    T9_ES("T9 ES"),
    T9_EN("T9 EN"),
    MULTI_TAP("ABC"),
    NUMERIC("123");

    fun next(): InputMode = when (this) {
        T9_ES -> MULTI_TAP
        T9_EN -> MULTI_TAP
        MULTI_TAP -> NUMERIC
        NUMERIC -> T9_ES
    }

    fun toLanguage(): T9DictionaryTrie.Language = when (this) {
        T9_EN -> T9DictionaryTrie.Language.EN
        else -> T9DictionaryTrie.Language.ES
    }
}

enum class CaseMode(val label: String) {
    LOWER("abc"),
    CAPITALIZE("Abc"),
    UPPER("ABC");

    fun next(): CaseMode = when (this) {
        LOWER -> CAPITALIZE
        CAPITALIZE -> UPPER
        UPPER -> LOWER
    }

    fun applyToChar(ch: Char): Char = when (this) {
        LOWER -> ch.lowercaseChar()
        CAPITALIZE, UPPER -> ch.uppercaseChar()
    }

    fun applyToWord(word: String): String = when (this) {
        LOWER -> word.lowercase()
        CAPITALIZE -> word.replaceFirstChar { it.uppercaseChar() }
        UPPER -> word.uppercase()
    }
}
