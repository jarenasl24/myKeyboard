package co.com.onecontrol.mykeyboard.dictionary

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

class T9DictionaryTrie(private val context: Context) {

    enum class Language(val code: String, val assetFile: String) {
        ES("es", "dict_es.txt"),
        EN("en", "dict_en.txt")
    }

    data class WordEntry(
        val word: String,
        var frequency: Int
    )

    private class TrieNode {
        val children = arrayOfNulls<TrieNode>(8) // digits '2'..'9' -> index 0..7
        val words = mutableListOf<WordEntry>()
    }

    // Exact word T9 roots
    private val roots = ConcurrentHashMap<Language, TrieNode>().apply {
        put(Language.ES, TrieNode())
        put(Language.EN, TrieNode())
    }

    // Vowel-skipping / consonant-skeleton T9 roots
    private val skeletonRoots = ConcurrentHashMap<Language, TrieNode>().apply {
        put(Language.ES, TrieNode())
        put(Language.EN, TrieNode())
    }

    val userStore = UserDictionaryStore(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loaded = false

    companion object {
        private val SPANISH_VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'á', 'é', 'í', 'ó', 'ú', 'ü')
        private val ENGLISH_VOWELS = setOf('a', 'e', 'i', 'o', 'u')

        // Ultra-popular Spanish SMS / chat abbreviations
        private val CHAT_SHORTCUTS = mapOf(
            "vdd" to "verdad",
            "grax" to "gracias",
            "grx" to "gracias",
            "xq" to "porque",
            "pq" to "porque",
            "tmb" to "también",
            "tb" to "también",
            "xfa" to "por favor",
            "xfvr" to "por favor",
            "msj" to "mensaje",
            "cnt" to "cuenta",
            "ntp" to "no te preocupes",
            "tqm" to "te quiero mucho",
            "bno" to "bueno",
            "bb" to "bebé",
            "dnd" to "donde",
            "cm" to "cómo"
        )
    }

    fun loadAsync(onLoaded: (() -> Unit)? = null) {
        if (loaded) {
            onLoaded?.invoke()
            return
        }
        scope.launch {
            Language.entries.forEach { lang ->
                val newRoot = TrieNode()
                val newSkelRoot = TrieNode()
                loadFromAssets(lang, newRoot, newSkelRoot)
                loadFromUserStore(lang, newRoot, newSkelRoot)
                roots[lang] = newRoot
                skeletonRoots[lang] = newSkelRoot
            }
            loaded = true
            withContext(Dispatchers.Main) {
                onLoaded?.invoke()
            }
        }
    }

    private fun loadFromAssets(language: Language, root: TrieNode, skeletonRoot: TrieNode) {
        try {
            context.assets.open(language.assetFile).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isNotEmpty()) {
                            val parts = line.split('\t', ' ')
                            val word = parts[0].trim().lowercase()
                            val freq = parts.getOrNull(1)?.toIntOrNull() ?: 100
                            insertIntoTrie(root, word, freq)

                            // Index vowel-skipped / consonant skeletons
                            val skeletons = extractSkeletons(word, language)
                            for (skel in skeletons) {
                                val skelDigits = T9KeyMap.wordToDigits(skel) ?: continue
                                insertAtDigits(skeletonRoot, skelDigits, word, (freq * 0.95).toInt())
                            }
                        }
                    }
                }
            }

            // Index chat shortcuts for Spanish
            if (language == Language.ES) {
                CHAT_SHORTCUTS.forEach { (abbr, full) ->
                    val abbrDigits = T9KeyMap.wordToDigits(abbr) ?: return@forEach
                    insertAtDigits(skeletonRoot, abbrDigits, full, 180000)
                }
            }
        } catch (_: Exception) {
            // Keep service resilient if asset is missing
        }
    }

    private fun loadFromUserStore(language: Language, root: TrieNode, skeletonRoot: TrieNode) {
        userStore.getAllWords(language.code).forEach { learned ->
            insertIntoTrie(root, learned.word, learned.frequency)
            val skeletons = extractSkeletons(learned.word, language)
            for (skel in skeletons) {
                val skelDigits = T9KeyMap.wordToDigits(skel) ?: continue
                insertAtDigits(skeletonRoot, skelDigits, learned.word, learned.frequency)
            }
        }
    }

    private fun extractSkeletons(word: String, language: Language): Set<String> {
        if (word.length < 3) return emptySet()
        val vowels = if (language == Language.ES) SPANISH_VOWELS else ENGLISH_VOWELS
        val set = mutableSetOf<String>()

        // 1. Pure consonant skeleton (all vowels stripped)
        val pure = word.filter { it !in vowels }
        if (pure.length in 2 until word.length) {
            set.add(pure)
        }

        // 2. Initial vowel kept + subsequent consonants (e.g. amigo -> amg, usted -> ustd, entonces -> entncs)
        if (word.first() in vowels) {
            val initVowelSkel = buildString {
                append(word.first())
                for (i in 1 until word.length) {
                    if (word[i] !in vowels) append(word[i])
                }
            }
            if (initVowelSkel.length in 2 until word.length) {
                set.add(initVowelSkel)
            }
        }

        // 3. Interior vowels stripped, keeping first & last char (e.g. bueno -> bno, mucho -> mcho, hola -> hla)
        if (word.length >= 4 && word.last() in vowels) {
            val noInterior = buildString {
                append(word.first())
                for (i in 1 until word.length - 1) {
                    if (word[i] !in vowels) append(word[i])
                }
                append(word.last())
            }
            if (noInterior.length in 2 until word.length) {
                set.add(noInterior)
            }
        }

        return set
    }

    private fun insertIntoTrie(root: TrieNode, word: String, frequency: Int) {
        val digits = T9KeyMap.wordToDigits(word) ?: return
        insertAtDigits(root, digits, word, frequency)
    }

    private fun insertAtDigits(root: TrieNode, digits: String, word: String, frequency: Int) {
        var node = root
        for (d in digits) {
            val idx = d - '2'
            if (idx !in 0..7) return
            var child = node.children[idx]
            if (child == null) {
                child = TrieNode()
                node.children[idx] = child
            }
            node = child
        }
        synchronized(node.words) {
            val existing = node.words.firstOrNull { it.word == word }
            if (existing != null) {
                existing.frequency = maxOf(existing.frequency, frequency)
            } else {
                node.words.add(WordEntry(word, frequency))
            }
            node.words.sortByDescending { it.frequency }
        }
    }

    fun query(language: Language, digitSequence: String, limit: Int = 8): List<String> {
        if (digitSequence.isEmpty()) return emptyList()
        val root = roots[language] ?: return emptyList()
        val skelRoot = skeletonRoots[language]

        // 1. Exact matches always come first
        val exactWords = queryNode(root, digitSequence, limit)
        if (exactWords.size >= limit) {
            return exactWords
        }

        // 2. Vowel-skipped / consonant skeleton matches appended
        val skelWords = if (skelRoot != null) queryNode(skelRoot, digitSequence, limit) else emptyList()

        val combined = ArrayList<String>(exactWords.size + skelWords.size)
        combined.addAll(exactWords)
        val seen = HashSet<String>(exactWords)
        for (w in skelWords) {
            if (seen.add(w)) {
                combined.add(w)
                if (combined.size >= limit) return combined
            }
        }

        // 3. If still under 4 suggestions, provide completions from exact prefix
        if (combined.size < 4) {
            val node = findNode(root, digitSequence)
            if (node != null) {
                val completions = collectSubtreeCompletions(node, 4 - combined.size, seen)
                for (c in completions) {
                    if (seen.add(c)) {
                        combined.add(c)
                        if (combined.size >= limit) return combined
                    }
                }
            }
        }

        return combined
    }

    private fun findNode(root: TrieNode, digitSequence: String): TrieNode? {
        var node: TrieNode = root
        for (d in digitSequence) {
            val idx = d - '2'
            if (idx !in 0..7) return null
            node = node.children[idx] ?: return null
        }
        return node
    }

    private fun queryNode(root: TrieNode, digitSequence: String, limit: Int): List<String> {
        val node = findNode(root, digitSequence) ?: return emptyList()
        return synchronized(node.words) {
            node.words.map { it.word }.take(limit)
        }
    }

    private fun collectSubtreeCompletions(startNode: TrieNode, limit: Int, excluded: Set<String>): List<String> {
        val results = mutableListOf<WordEntry>()
        val queue = ArrayDeque<TrieNode>()
        for (child in startNode.children) {
            if (child != null) queue.add(child)
        }
        var iterations = 0
        while (queue.isNotEmpty() && results.size < limit * 2 && iterations < 60) {
            iterations++
            val current = queue.removeFirst()
            synchronized(current.words) {
                for (w in current.words) {
                    if (w.word !in excluded) {
                        results.add(w)
                    }
                }
            }
            for (child in current.children) {
                if (child != null) queue.add(child)
            }
        }
        results.sortByDescending { it.frequency }
        return results.map { it.word }.distinct().take(limit)
    }

    fun learnWord(language: Language, word: String) {
        val clean = word.trim().lowercase()
        if (clean.length < 2) return
        if (T9KeyMap.wordToDigits(clean) == null) return
        val root = roots[language] ?: return
        insertIntoTrie(root, clean, 6500)
        skeletonRoots[language]?.let { skelRoot ->
            for (skel in extractSkeletons(clean, language)) {
                val skelDigits = T9KeyMap.wordToDigits(skel) ?: continue
                insertAtDigits(skelRoot, skelDigits, clean, 6000)
            }
        }
        scope.launch {
            userStore.saveOrBoostWord(language.code, clean)
        }
    }

    fun recordWordPair(language: Language, prevWord: String, nextWord: String) {
        scope.launch {
            userStore.recordBigram(language.code, prevWord, nextWord)
        }
    }
}
