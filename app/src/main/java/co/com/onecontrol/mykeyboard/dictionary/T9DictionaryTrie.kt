package co.com.onecontrol.mykeyboard.dictionary

import android.content.Context
import android.provider.UserDictionary
import co.com.onecontrol.mykeyboard.engine.NextWordPredictor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

class T9DictionaryTrie(private val context: Context) {

    enum class Language(val code: String, val assetFile: String) {
        ES("es", "dict_es.txt"),
        EN("en", "dict_en.txt")
    }

    data class WordEntry(
        val word: String,
        var baseFrequency: Int,
        var userFrequency: Int = 0,
        var lastUsedMs: Long = 0L,
        var penalty: Int = 0
    ) {
        fun effectiveScore(nowMs: Long = System.currentTimeMillis(), contextBoost: Int = 0): Int {
            val recencyBonus = if (lastUsedMs > 0L) {
                val ageHours = ((nowMs - lastUsedMs).coerceAtLeast(0L)) / (1000.0 * 3600.0)
                // Exponential decay with 7-day (168h) half-life
                val decay = exp(-ageHours / 168.0)
                (18_000 * decay).toInt()
            } else 0
            return (baseFrequency + userFrequency + recencyBonus + contextBoost - penalty).coerceAtLeast(1)
        }
    }

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

        // 3x3 T9 keypad physical neighbors for Fuzzy T9 typo recovery
        private val ADJACENT_DIGITS = mapOf(
            '2' to charArrayOf('3', '5'),
            '3' to charArrayOf('2', '6'),
            '4' to charArrayOf('5', '7'),
            '5' to charArrayOf('2', '4', '6', '8'),
            '6' to charArrayOf('3', '5', '9'),
            '7' to charArrayOf('4', '8'),
            '8' to charArrayOf('5', '7', '9'),
            '9' to charArrayOf('6', '8')
        )

        // Popular Spanish & Colombian SMS / chat abbreviations -> full expansion
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
            "porfa" to "por favor",
            "msj" to "mensaje",
            "cnt" to "cuenta",
            "ntp" to "no te preocupes",
            "tqm" to "te quiero mucho",
            "tkm" to "te quiero mucho",
            "bno" to "bueno",
            "bb" to "bebé",
            "dnd" to "dónde",
            "cm" to "cómo",
            "mk" to "marica",
            "mrc" to "marica",
            "chmb" to "chimba",
            "prc" to "parce",
            "prcr" to "parcero",
            "gnr" to "gonorrea",
            "bcn" to "bacano",
            "chvr" to "chévere",
            "hpta" to "hijueputa",
            "mp" to "malparido",
            "wvn" to "huevón",
            "gvn" to "güevón",
            "pl" to "pola"
        )

        private val CHAT_SHORTCUT_DIGITS: Map<String, List<String>> by lazy {
            val map = HashMap<String, MutableList<String>>()
            for ((abbr, full) in CHAT_SHORTCUTS) {
                val d = T9KeyMap.wordToDigits(abbr) ?: continue
                val list = map.getOrPut(d) { mutableListOf() }
                if (full !in list) list.add(full)
            }
            map
        }
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
                loadFromSystemUserDictionary(lang, newRoot, newSkelRoot)
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
                            insertBaseIntoTrie(root, word, freq)

                            // Index vowel-skipped / consonant skeletons
                            val skeletons = extractSkeletons(word, language)
                            for (skel in skeletons) {
                                val skelDigits = T9KeyMap.wordToDigits(skel) ?: continue
                                insertBaseAtDigits(skeletonRoot, skelDigits, word, (freq * 0.92).toInt())
                            }
                        }
                    }
                }
            }

            // Index chat shortcuts for Spanish
            if (language == Language.ES) {
                CHAT_SHORTCUTS.forEach { (abbr, full) ->
                    val abbrDigits = T9KeyMap.wordToDigits(abbr) ?: return@forEach
                    insertBaseAtDigits(skeletonRoot, abbrDigits, full, 4800)
                }
            }
        } catch (_: Exception) {
            // Keep service resilient if asset is missing
        }
    }

    private fun loadFromSystemUserDictionary(language: Language, root: TrieNode, skeletonRoot: TrieNode) {
        try {
            val resolver = context.contentResolver ?: return
            val cursor = resolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY, UserDictionary.Words.LOCALE),
                null,
                null,
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val rawWord = it.getString(0)?.trim()?.lowercase() ?: continue
                    val sysFreq = it.getInt(1).coerceIn(100, 2500)
                    val locale = it.getString(2)?.lowercase() ?: ""
                    if (locale.isEmpty() || locale.startsWith(language.code)) {
                        val digits = T9KeyMap.wordToDigits(rawWord) ?: continue
                        applyUserWordAtDigits(
                            root = root,
                            digits = digits,
                            word = rawWord,
                            userFrequency = 6000 + sysFreq * 4,
                            lastUsedMs = System.currentTimeMillis(),
                            penalty = 0
                        )
                        for (skel in extractSkeletons(rawWord, language)) {
                            val skelDigits = T9KeyMap.wordToDigits(skel) ?: continue
                            applyUserWordAtDigits(
                                root = skeletonRoot,
                                digits = skelDigits,
                                word = rawWord,
                                userFrequency = 5000 + sysFreq * 3,
                                lastUsedMs = System.currentTimeMillis(),
                                penalty = 0
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // System UserDictionary may be restricted on some OEM ROMs; ignore safely
        }
    }

    private fun loadFromUserStore(language: Language, root: TrieNode, skeletonRoot: TrieNode) {
        userStore.getAllWords(language.code).forEach { learned ->
            val digits = T9KeyMap.wordToDigits(learned.word) ?: return@forEach
            applyUserWordAtDigits(
                root = root,
                digits = digits,
                word = learned.word,
                userFrequency = learned.frequency,
                lastUsedMs = learned.lastUsedMs,
                penalty = learned.penalty
            )
            val skeletons = extractSkeletons(learned.word, language)
            for (skel in skeletons) {
                val skelDigits = T9KeyMap.wordToDigits(skel) ?: continue
                applyUserWordAtDigits(
                    root = skeletonRoot,
                    digits = skelDigits,
                    word = learned.word,
                    userFrequency = (learned.frequency * 0.95).toInt(),
                    lastUsedMs = learned.lastUsedMs,
                    penalty = learned.penalty
                )
            }
        }
    }

    private fun extractSkeletons(word: String, language: Language): Set<String> {
        if (word.length < 3) return emptySet()
        val vowels = if (language == Language.ES) SPANISH_VOWELS else ENGLISH_VOWELS
        val set = mutableSetOf<String>()

        // 1. Pure consonant skeleton (all vowels stripped, e.g. chimba -> chmb, marica -> mrc, parce -> prc)
        val pure = word.filter { it !in vowels }
        if (pure.length in 2 until word.length) {
            set.add(pure)
        }

        // 2. Initial vowel kept + subsequent consonants (e.g. amigo -> amg, usted -> ustd)
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

        // 3. Interior vowels stripped, keeping first & last char (e.g. bueno -> bno, marica -> mrca, chimba -> chmba)
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

    private fun insertBaseIntoTrie(root: TrieNode, word: String, baseFrequency: Int) {
        val digits = T9KeyMap.wordToDigits(word) ?: return
        insertBaseAtDigits(root, digits, word, baseFrequency)
    }

    private fun getOrCreateNode(root: TrieNode, digits: String): TrieNode? {
        var node = root
        for (d in digits) {
            val idx = d - '2'
            if (idx !in 0..7) return null
            var child = node.children[idx]
            if (child == null) {
                child = TrieNode()
                node.children[idx] = child
            }
            node = child
        }
        return node
    }

    private fun insertBaseAtDigits(root: TrieNode, digits: String, word: String, baseFrequency: Int) {
        val node = getOrCreateNode(root, digits) ?: return
        val now = System.currentTimeMillis()
        synchronized(node.words) {
            val existing = node.words.firstOrNull { it.word == word }
            if (existing != null) {
                existing.baseFrequency = maxOf(existing.baseFrequency, baseFrequency)
            } else {
                node.words.add(WordEntry(word = word, baseFrequency = baseFrequency))
            }
            node.words.sortByDescending { it.effectiveScore(now) }
        }
    }

    private fun applyUserWordAtDigits(
        root: TrieNode,
        digits: String,
        word: String,
        userFrequency: Int,
        lastUsedMs: Long,
        penalty: Int
    ) {
        val node = getOrCreateNode(root, digits) ?: return
        val now = System.currentTimeMillis()
        synchronized(node.words) {
            val existing = node.words.firstOrNull { it.word == word }
            if (existing != null) {
                existing.userFrequency = maxOf(existing.userFrequency, userFrequency)
                existing.lastUsedMs = maxOf(existing.lastUsedMs, lastUsedMs)
                existing.penalty = penalty
            } else {
                node.words.add(
                    WordEntry(
                        word = word,
                        baseFrequency = 1500,
                        userFrequency = userFrequency,
                        lastUsedMs = lastUsedMs,
                        penalty = penalty
                    )
                )
            }
            node.words.sortByDescending { it.effectiveScore(now) }
        }
    }

    private fun boostInMemoryAtDigits(
        root: TrieNode,
        digits: String,
        word: String,
        explicit: Boolean,
        nowMs: Long
    ) {
        val node = getOrCreateNode(root, digits) ?: return
        val boostStep = if (explicit) UserDictionaryStore.EXPLICIT_BOOST_STEP else UserDictionaryStore.IMPLICIT_BOOST_STEP
        val initialFreq = if (explicit) UserDictionaryStore.INITIAL_EXPLICIT_FREQ else UserDictionaryStore.INITIAL_IMPLICIT_FREQ

        synchronized(node.words) {
            val existing = node.words.firstOrNull { it.word == word }
            if (existing != null) {
                existing.userFrequency = if (existing.userFrequency == 0) {
                    initialFreq
                } else {
                    (existing.userFrequency + boostStep).coerceAtMost(UserDictionaryStore.MAX_USER_FREQ)
                }
                existing.lastUsedMs = nowMs
                existing.penalty = if (explicit) 0 else (existing.penalty - 2000).coerceAtLeast(0)
            } else {
                node.words.add(
                    WordEntry(
                        word = word,
                        baseFrequency = 1800,
                        userFrequency = initialFreq,
                        lastUsedMs = nowMs,
                        penalty = 0
                    )
                )
            }
            node.words.sortByDescending { it.effectiveScore(nowMs) }
        }
    }

    private fun penalizeInMemoryAtDigits(root: TrieNode, digits: String, word: String, nowMs: Long) {
        val node = findNode(root, digits) ?: return
        synchronized(node.words) {
            val existing = node.words.firstOrNull { it.word == word } ?: return
            existing.userFrequency = (existing.userFrequency - UserDictionaryStore.PENALTY_STEP / 2).coerceAtLeast(0)
            existing.penalty = (existing.penalty + UserDictionaryStore.PENALTY_STEP)
                .coerceAtMost(UserDictionaryStore.MAX_PENALTY)
            node.words.sortByDescending { it.effectiveScore(nowMs) }
        }
    }

    fun query(
        language: Language,
        digitSequence: String,
        limit: Int = 8,
        prevWord2: String? = null,
        prevWord1: String? = null
    ): List<String> {
        if (digitSequence.isEmpty()) return emptyList()
        val root = roots[language] ?: return emptyList()
        val skelRoot = skeletonRoots[language]
        val nowMs = System.currentTimeMillis()

        val contextBoosts = if (!prevWord2.isNullOrBlank()) {
            NextWordPredictor.getContextualBoostMap(
                language = language.code,
                prevWord2 = prevWord2,
                prevWord1 = prevWord1,
                userStore = userStore
            )
        } else {
            emptyMap()
        }

        // 1. Exact digit matches always come first, re-ranked by (Frecency + N-Gram Context Boost)
        val exactScored = queryNodeScored(root, digitSequence, nowMs, contextBoosts)

        val combined = ArrayList<String>(limit)
        val seen = HashSet<String>()
        for ((w, _) in exactScored) {
            if (seen.add(w)) {
                combined.add(w)
                if (combined.size >= limit) return combined
            }
        }

        // 1b. Direct chat abbreviations (e.g. xq -> porque, mk -> marica, chmb -> chimba, prc -> parce)
        if (language == Language.ES) {
            CHAT_SHORTCUT_DIGITS[digitSequence]?.forEach { expansion ->
                if (seen.add(expansion)) {
                    combined.add(expansion)
                    if (combined.size >= limit) return combined
                }
            }
        }

        // 2. If user has strong N-Gram contextual predictions that start with digitSequence (for 2+ digits),
        // surface them early so phrases like "qué chimba" or "fin de semana" appear after 2-3 keys!
        if (digitSequence.length >= 2 && contextBoosts.isNotEmpty()) {
            val ctxPrefixCandidates = mutableListOf<Pair<String, Int>>()
            for ((ctxWord, boost) in contextBoosts) {
                if (ctxWord !in seen) {
                    val ctxDigits = T9KeyMap.wordToDigits(ctxWord)
                    if (ctxDigits != null && ctxDigits.startsWith(digitSequence)) {
                        val lengthRatio = digitSequence.length.toDouble() / ctxDigits.length.toDouble()
                        if (lengthRatio >= 0.34 || boost >= 35_000) {
                            ctxPrefixCandidates.add(ctxWord to (boost * lengthRatio).toInt())
                        }
                    }
                }
            }
            ctxPrefixCandidates.sortByDescending { it.second }
            for ((cw, _) in ctxPrefixCandidates) {
                if (seen.add(cw)) {
                    combined.add(cw)
                    if (combined.size >= limit) return combined
                }
            }
        }

        // 3. Exact prefix subtree completions (surfaces verb conjugations like habl -> hablo, hablas, hablando, hablé)
        if (combined.size < limit) {
            val node = findNode(root, digitSequence)
            if (node != null) {
                val completions = collectSubtreeCompletions(node, limit - combined.size, seen, nowMs, contextBoosts)
                for (c in completions) {
                    if (seen.add(c)) {
                        combined.add(c)
                        if (combined.size >= limit) return combined
                    }
                }
            }
        }

        // 4. Vowel-skipped / consonant skeleton matches & chat abbreviations (e.g. chmb -> chimba, mrc -> marica, xq -> porque)
        if (combined.size < limit && skelRoot != null) {
            val skelScored = queryNodeScored(skelRoot, digitSequence, nowMs, contextBoosts, discount = 0.88)
            for ((sw, _) in skelScored) {
                if (seen.add(sw)) {
                    combined.add(sw)
                    if (combined.size >= limit) return combined
                }
            }
        }

        // 5. Fuzzy T9 fallback: if still under 3 suggestions for a 3+ digit sequence, check physical neighbor keys
        if (combined.size < 3 && digitSequence.length >= 3) {
            val fuzzyMatches = queryFuzzyNeighbors(root, digitSequence, seen, nowMs, contextBoosts, limit - combined.size)
            for (fw in fuzzyMatches) {
                if (seen.add(fw)) {
                    combined.add(fw)
                    if (combined.size >= limit) return combined
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

    private fun queryNodeScored(
        root: TrieNode,
        digitSequence: String,
        nowMs: Long,
        contextBoosts: Map<String, Int>,
        discount: Double = 1.0
    ): List<Pair<String, Int>> {
        val node = findNode(root, digitSequence) ?: return emptyList()
        return synchronized(node.words) {
            node.words.map { entry ->
                val ctxBoost = contextBoosts[entry.word] ?: 0
                val rawScore = entry.effectiveScore(nowMs, ctxBoost)
                entry.word to (rawScore * discount).toInt()
            }.sortedByDescending { it.second }
        }
    }

    private fun collectSubtreeCompletions(
        startNode: TrieNode,
        limit: Int,
        excluded: Set<String>,
        nowMs: Long,
        contextBoosts: Map<String, Int>
    ): List<String> {
        val results = mutableListOf<Pair<String, Int>>()
        val queue = ArrayDeque<Pair<TrieNode, Int>>()
        for (child in startNode.children) {
            if (child != null) queue.add(child to 1)
        }
        var iterations = 0
        while (queue.isNotEmpty() && results.size < limit * 4 && iterations < 80) {
            iterations++
            val (current, depth) = queue.removeFirst()
            // Slight depth discount so 1-2 extra letters rank above very long words unless user-boosted
            val depthFactor = 1.0 / (1.0 + depth * 0.18)
            synchronized(current.words) {
                for (w in current.words) {
                    if (w.word !in excluded) {
                        val ctxBoost = contextBoosts[w.word] ?: 0
                        val score = (w.effectiveScore(nowMs, ctxBoost) * depthFactor).toInt()
                        results.add(w.word to score)
                    }
                }
            }
            for (child in current.children) {
                if (child != null) queue.add(child to (depth + 1))
            }
        }
        results.sortByDescending { it.second }
        return results.map { it.first }.distinct().take(limit)
    }

    private fun queryFuzzyNeighbors(
        root: TrieNode,
        digitSequence: String,
        excluded: Set<String>,
        nowMs: Long,
        contextBoosts: Map<String, Int>,
        limit: Int
    ): List<String> {
        val candidates = mutableListOf<Pair<String, Int>>()
        val lastIdx = digitSequence.length - 1

        // Check substituting the last digit and (if length >= 4) the second-to-last digit
        val positionsToCheck = if (digitSequence.length >= 4) {
            intArrayOf(lastIdx, lastIdx - 1)
        } else {
            intArrayOf(lastIdx)
        }

        for (pos in positionsToCheck) {
            val origChar = digitSequence[pos]
            val neighbors = ADJACENT_DIGITS[origChar] ?: continue
            for (neighbor in neighbors) {
                val mutated = StringBuilder(digitSequence).apply { setCharAt(pos, neighbor) }.toString()
                val node = findNode(root, mutated) ?: continue
                synchronized(node.words) {
                    for (w in node.words) {
                        if (w.word !in excluded) {
                            val ctxBoost = contextBoosts[w.word] ?: 0
                            val score = (w.effectiveScore(nowMs, ctxBoost) * 0.75).toInt()
                            candidates.add(w.word to score)
                        }
                    }
                }
            }
        }

        candidates.sortByDescending { it.second }
        return candidates.map { it.first }.distinct().take(limit)
    }

    fun querySwipe(language: Language, traceDigits: String, limit: Int = 8): List<String> {
        if (traceDigits.length < 2) return emptyList()
        val root = roots[language] ?: return emptyList()
        val firstDigit = traceDigits.first()
        val lastDigit = traceDigits.last()
        val firstIdx = firstDigit - '2'
        if (firstIdx !in 0..7) return emptyList()
        val firstNode = root.children[firstIdx] ?: return emptyList()
        val nowMs = System.currentTimeMillis()

        // Collapse consecutive duplicate digits in trace (e.g. 22446622 -> 2462)
        val compressedTrace = buildString {
            for (ch in traceDigits) {
                if (isEmpty() || last() != ch) append(ch)
            }
        }

        val results = mutableListOf<Pair<String, Int>>()
        searchSwipeNode(
            node = firstNode,
            trace = compressedTrace,
            traceIdx = 1,
            lastDigit = lastDigit,
            nowMs = nowMs,
            results = results,
            maxLimit = 180
        )

        // Also check skeleton roots for vowel-skipped swipe matches
        skeletonRoots[language]?.children?.get(firstIdx)?.let { skelFirst ->
            searchSwipeNode(
                node = skelFirst,
                trace = compressedTrace,
                traceIdx = 1,
                lastDigit = lastDigit,
                nowMs = nowMs,
                results = results,
                maxLimit = 240
            )
        }

        results.sortByDescending { it.second }
        return results.map { it.first }.distinct().take(limit)
    }

    private fun searchSwipeNode(
        node: TrieNode,
        trace: String,
        traceIdx: Int,
        lastDigit: Char,
        nowMs: Long,
        results: MutableList<Pair<String, Int>>,
        maxLimit: Int
    ) {
        if (results.size >= maxLimit) return

        // Only collect words when we have consumed at least 65% of the swiped trajectory
        val traceCoverage = traceIdx.toDouble() / trace.length.toDouble()
        if (traceCoverage >= 0.65) {
            synchronized(node.words) {
                for (w in node.words) {
                    if (w.word.length >= 2) {
                        val wDigits = T9KeyMap.wordToDigits(w.word)
                        if (wDigits != null && wDigits.last() == lastDigit) {
                            // Reward words that consume the entire swipe trace
                            val coverageBonus = (traceCoverage * 10_000).toInt()
                            val score = w.effectiveScore(nowMs) + coverageBonus
                            results.add(w.word to score)
                        }
                    }
                }
            }
        }

        for (idx in 0..7) {
            val child = node.children[idx] ?: continue
            val childDigit = ('2' + idx)

            var nextTraceIdx = -1
            if (traceIdx > 0 && trace[traceIdx - 1] == childDigit) {
                nextTraceIdx = traceIdx
            } else {
                for (ti in traceIdx until trace.length) {
                    if (trace[ti] == childDigit) {
                        nextTraceIdx = ti + 1
                        break
                    }
                }
            }

            if (nextTraceIdx != -1) {
                searchSwipeNode(child, trace, nextTraceIdx, lastDigit, nowMs, results, maxLimit)
            }
        }
    }

    fun learnWord(language: Language, word: String, explicit: Boolean = false) {
        val clean = word.trim().lowercase().trim(',', '.', '!', '?', ';', ':', '¿', '¡', '"', '\'')
        if (clean.length < 2) return
        val digits = T9KeyMap.wordToDigits(clean) ?: return
        val root = roots[language] ?: return
        val nowMs = System.currentTimeMillis()

        boostInMemoryAtDigits(root, digits, clean, explicit, nowMs)
        skeletonRoots[language]?.let { skelRoot ->
            for (skel in extractSkeletons(clean, language)) {
                val skelDigits = T9KeyMap.wordToDigits(skel) ?: continue
                boostInMemoryAtDigits(skelRoot, skelDigits, clean, explicit, nowMs)
            }
        }
        scope.launch {
            userStore.saveOrBoostWord(language.code, clean, explicit = explicit, nowMs = nowMs)
        }
    }

    fun penalizeWord(language: Language, word: String) {
        val clean = word.trim().lowercase().trim(',', '.', '!', '?', ';', ':', '¿', '¡', '"', '\'')
        if (clean.length < 2) return
        val digits = T9KeyMap.wordToDigits(clean) ?: return
        val root = roots[language] ?: return
        val nowMs = System.currentTimeMillis()

        penalizeInMemoryAtDigits(root, digits, clean, nowMs)
        skeletonRoots[language]?.let { skelRoot ->
            for (skel in extractSkeletons(clean, language)) {
                val skelDigits = T9KeyMap.wordToDigits(skel) ?: continue
                penalizeInMemoryAtDigits(skelRoot, skelDigits, clean, nowMs)
            }
        }
        scope.launch {
            userStore.penalizeWord(language.code, clean)
        }
    }

    fun recordWordPair(
        language: Language,
        prevWord: String,
        nextWord: String,
        prevWord1: String? = null,
        explicit: Boolean = false
    ) {
        val boost = if (explicit) 2 else 1
        val nowMs = System.currentTimeMillis()
        scope.launch {
            userStore.recordBigram(language.code, prevWord, nextWord, boost = boost, nowMs = nowMs)
            if (!prevWord1.isNullOrBlank()) {
                userStore.recordTrigram(language.code, prevWord1, prevWord, nextWord, boost = boost, nowMs = nowMs)
            }
        }
    }
}
