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

    private val roots = ConcurrentHashMap<Language, TrieNode>().apply {
        put(Language.ES, TrieNode())
        put(Language.EN, TrieNode())
    }

    private val userStore = UserDictionaryStore(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loaded = false

    fun loadAsync(onLoaded: (() -> Unit)? = null) {
        if (loaded) {
            onLoaded?.invoke()
            return
        }
        scope.launch {
            Language.entries.forEach { lang ->
                val newRoot = TrieNode()
                loadFromAssets(lang, newRoot)
                loadFromUserStore(lang, newRoot)
                roots[lang] = newRoot
            }
            loaded = true
            withContext(Dispatchers.Main) {
                onLoaded?.invoke()
            }
        }
    }

    private fun loadFromAssets(language: Language, root: TrieNode) {
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
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Keep service resilient if asset is missing
        }
    }

    private fun loadFromUserStore(language: Language, root: TrieNode) {
        userStore.getAllWords(language.code).forEach { learned ->
            insertIntoTrie(root, learned.word, learned.frequency)
        }
    }

    private fun insertIntoTrie(root: TrieNode, word: String, frequency: Int) {
        val digits = T9KeyMap.wordToDigits(word) ?: return
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

        var node: TrieNode = root
        for (d in digitSequence) {
            val idx = d - '2'
            if (idx !in 0..7) return emptyList()
            node = node.children[idx] ?: return emptyList()
        }

        val exactMatches = synchronized(node.words) {
            node.words.map { it.word }
        }

        if (exactMatches.size >= limit) {
            return exactMatches.take(limit)
        }

        // Fill remaining slots with prefix-completion candidates
        val prefixMatches = mutableListOf<WordEntry>()
        collectPrefixWords(node, prefixMatches, maxCollect = 30)
        val exactSet = exactMatches.toHashSet()
        val extra = prefixMatches
            .asSequence()
            .filter { it.word !in exactSet }
            .sortedByDescending { it.frequency }
            .map { it.word }
            .distinct()
            .take(limit - exactMatches.size)
            .toList()

        return exactMatches + extra
    }

    private fun collectPrefixWords(
        node: TrieNode,
        out: MutableList<WordEntry>,
        maxCollect: Int
    ) {
        if (out.size >= maxCollect) return
        for (child in node.children) {
            if (child != null) {
                synchronized(child.words) {
                    out.addAll(child.words.take(4))
                }
                if (out.size < maxCollect) {
                    collectPrefixWords(child, out, maxCollect)
                }
            }
        }
    }

    fun learnWord(language: Language, word: String) {
        val clean = word.trim().lowercase()
        if (clean.length < 2) return
        if (T9KeyMap.wordToDigits(clean) == null) return
        val root = roots[language] ?: return
        insertIntoTrie(root, clean, 6500)
        scope.launch {
            userStore.saveOrBoostWord(language.code, clean)
        }
    }
}
