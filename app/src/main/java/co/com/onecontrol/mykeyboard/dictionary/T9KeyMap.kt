package co.com.onecontrol.mykeyboard.dictionary

object T9KeyMap {

    /**
     * Exact 12-key Nokia T9 character mapping:
     * 1: . , ? ! ' " - ( ) @ / : _
     * 2: A B C Á
     * 3: D E F É
     * 4: G H I Í
     * 5: J K L
     * 6: M N O Ñ Ó
     * 7: P Q R S
     * 8: T U V Ú Ü
     * 9: W X Y Z
     */
    val MULTI_TAP_CHARS: Map<Char, String> = mapOf(
        '1' to ".,?!'\"-()@/:_",
        '2' to "abcá",
        '3' to "defé",
        '4' to "ghií",
        '5' to "jkl",
        '6' to "mnoñó",
        '7' to "pqrs",
        '8' to "tuvúü",
        '9' to "wxyz"
    )

    val FREQUENT_SYMBOLS: List<String> = listOf(
        "@", "?", "!", ".", ",", "-", "_", ":", "/", "(", ")", "\"", "'", "¿", "¡", "#", "$", "%", "&"
    )

    val POPUP_SYMBOLS: List<String> = listOf(
        "@", "?", "!", ",", ".", "-", "_", ":", "/", "(", ")", "\"", "'", "😊"
    )

    val POPULAR_EMOJIS: List<String> = listOf(
        "😂", "😍", "👍", "🙏", "😊", "🔥", "❤️", "👌", "😎", "😭",
        "🤔", "🎉", "💪", "👏", "👋", "😜", "🥳", "🥺", "🤣", "😅",
        "😬", "🤷", "🤦", "🚀", "✨", "💯", "😴", "👀", "🙌", "💔"
    )

    val EMAIL_DOMAINS: List<String> = listOf(
        "@gmail.com", "@hotmail.com", "@outlook.com", "@yahoo.com", "@icloud.com"
    )

    val URL_SHORTCUTS: List<String> = listOf(
        ".com", ".co", ".org", ".net", "https://", "www."
    )

    val KEY_SUB_LABELS: Map<Char, String> = mapOf(
        '1' to ".,?!@",
        '2' to "ABC",
        '3' to "DEF",
        '4' to "GHI",
        '5' to "JKL",
        '6' to "MNOÑ",
        '7' to "PQRS",
        '8' to "TUV",
        '9' to "WXYZ"
    )

    private val CHAR_TO_DIGIT: Map<Char, Char> = buildMap {
        put('a', '2'); put('b', '2'); put('c', '2'); put('á', '2')
        put('d', '3'); put('e', '3'); put('f', '3'); put('é', '3')
        put('g', '4'); put('h', '4'); put('i', '4'); put('í', '4')
        put('j', '5'); put('k', '5'); put('l', '5')
        put('m', '6'); put('n', '6'); put('o', '6'); put('ñ', '6'); put('ó', '6')
        put('p', '7'); put('q', '7'); put('r', '7'); put('s', '7')
        put('t', '8'); put('u', '8'); put('v', '8'); put('ú', '8'); put('ü', '8')
        put('w', '9'); put('x', '9'); put('y', '9'); put('z', '9')
    }

    fun charToDigit(ch: Char): Char? = CHAR_TO_DIGIT[ch.lowercaseChar()]

    fun wordToDigits(word: String): String? {
        if (word.isEmpty()) return null
        val sb = StringBuilder(word.length)
        for (ch in word) {
            val d = charToDigit(ch) ?: return null
            sb.append(d)
        }
        return sb.toString()
    }

    fun digitsToDefaultLetters(digits: String): String {
        val sb = StringBuilder(digits.length)
        for (d in digits) {
            val chars = MULTI_TAP_CHARS[d]
            sb.append(chars?.firstOrNull() ?: d)
        }
        return sb.toString()
    }

    fun getLongPressOptions(digit: Char, isUpper: Boolean = false): List<String> {
        val base = when (digit) {
            '1' -> listOf("1", ".", ",", "?", "!", "¿", "¡", "@", "-", "_", "/", ":", "'", "\"", "(", ")")
            '2' -> listOf("2", "a", "b", "c", "á", "à", "ä")
            '3' -> listOf("3", "d", "e", "f", "é", "è", "ë")
            '4' -> listOf("4", "g", "h", "i", "í", "ì", "ï")
            '5' -> listOf("5", "j", "k", "l")
            '6' -> listOf("6", "m", "n", "o", "ñ", "ó", "ò", "ö")
            '7' -> listOf("7", "p", "q", "r", "s")
            '8' -> listOf("8", "t", "u", "v", "ú", "ù", "ü")
            '9' -> listOf("9", "w", "x", "y", "z")
            '0' -> listOf("0", "+", " ", "\n", "~")
            '*' -> listOf("*", "#", "(", ")", "[", "]", "{", "}", "%", "^")
            '#' -> listOf("#", "$", "%", "&", "=", "<", ">", "\\", "|", "€")
            else -> listOf(digit.toString())
        }
        return if (isUpper) {
            base.map { if (it.length == 1 && it[0].isLetter()) it.uppercase() else it }
        } else {
            base
        }
    }
}
