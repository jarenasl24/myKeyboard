package co.com.onecontrol.mykeyboard.engine

import co.com.onecontrol.mykeyboard.dictionary.UserDictionaryStore

object NextWordPredictor {

    private val SPANISH_TRIGRAMS = mapOf(
        "fin de" to listOf("semana", "año", "mes"),
        "por favor" to listOf("me avisas", "gracias", "ayúdame"),
        "muchas gracias" to listOf("por todo", "parce", "amigo", "de verdad"),
        "qué más" to listOf("pues", "parce", "mk", "todo bien"),
        "que mas" to listOf("pues", "parce", "mk", "todo bien"),
        "todo bien" to listOf("o qué", "gracias", "parce", "por acá"),
        "a lo" to listOf("bien", "mejor", "maldita sea"),
        "en la" to listOf("casa", "oficina", "noche", "mañana", "tarde"),
        "en el" to listOf("trabajo", "camino", "carro", "centro"),
        "voy a" to listOf("ver", "salir", "ir", "comer", "dormir", "hacer"),
        "vamos a" to listOf("ver", "hacer", "salir", "parchar", "comer"),
        "no me" to listOf("digas", "deja", "acuerdo", "parece", "gusta"),
        "no te" to listOf("preocupes", "olvides", "vayas", "creo"),
        "no se" to listOf("puede", "sabe", "me olvida"),
        "no sé" to listOf("qué hacer", "si pueda", "todavía", "mk"),
        "lo que" to listOf("sea", "quieras", "pasa", "te dije"),
        "de la" to listOf("mañana", "tarde", "noche", "casa"),
        "base de" to listOf("datos"),
        "nos vemos" to listOf("mañana", "ahorita", "luego", "en la noche"),
        "me avisas" to listOf("cuando llegues", "cualquier cosa", "porfa")
    )

    private val ENGLISH_TRIGRAMS = mapOf(
        "let me" to listOf("know", "see", "check", "think"),
        "thank you" to listOf("so much", "very much", "for everything"),
        "how are" to listOf("you", "you doing", "things"),
        "on my" to listOf("way", "mind", "phone"),
        "talk to" to listOf("you later", "you soon", "me"),
        "see you" to listOf("soon", "later", "tomorrow"),
        "i am" to listOf("on my way", "here", "ready", "busy"),
        "do you" to listOf("want", "know", "have", "think")
    )

    private val SPANISH_BIGRAMS = mapOf(
        "hola" to listOf("parce", "¿cómo estás?", "¿qué tal?", "buenas", "mk"),
        "quiubo" to listOf("parce", "mk", "pues", "¿qué más?"),
        "qhubo" to listOf("parce", "mk", "pues", "¿qué más?"),
        "muchas" to listOf("gracias", "veces", "cosas"),
        "mucho" to listOf("gusto", "cuidado", "mejor", "tiempo"),
        "buenos" to listOf("días", "momentos"),
        "buenas" to listOf("tardes", "noches"),
        "por" to listOf("favor", "eso", "acá", "allá", "cierto", "supuesto", "fin"),
        "porfa" to listOf("me avisas", "ayúdame", "gracias"),
        "de" to listOf(" una", "acuerdo", "nada", "verdad", "pronto"),
        "en" to listOf("casa", "camino", "contacto", "serio", "línea"),
        "te" to listOf("quiero", "aviso", "escribo", "llamo", "amo", "cuento"),
        "me" to listOf("parece", "gusta", "avisas", "alegro", "voy", "llamas"),
        "que" to listOf("chimba", "más", "tal", "bacano", "paila", "haces", "gonorrea", "bueno"),
        "qué" to listOf("chimba", "más", "tal", "bacano", "paila", "haces", "gonorrea", "bueno"),
        "cómo" to listOf("estás", "vas", "te va", "siempre", "te fue"),
        "como" to listOf("estás", "vas", "te va", "siempre", "te fue"),
        "ya" to listOf("voy", "llegué", "casi", "estoy", "sé", "salí"),
        "feliz" to listOf("cumpleaños", "día", "navidad", "noche"),
        "gracias" to listOf("parce", "mk", "por todo", "amigo", "totales", "igualmente"),
        "no" to listOf("sé", "puedo", "te preocupes", "importa", "hay lío"),
        "si" to listOf("sisas", "claro", "de una", "obvio", "puedes"),
        "sí" to listOf("sisas", "claro", "de una", "obvio"),
        "sisas" to listOf("parce", "mk", "de una", "total"),
        "paila" to listOf("mk", "parce", "con eso", "entonces"),
        "parce" to listOf("todo bien", "qué más", "gracias", "melo"),
        "mk" to listOf("qué chimba", "no sé", "en serio", "vamos", "mirá"),
        "marica" to listOf("qué chimba", "no sé", "en serio", "mirá"),
        "chimba" to listOf("de parche", "parce", "mk", "eso"),
        "hágale" to listOf("pues", "de una", "parce", "sin mente"),
        "hagale" to listOf("pues", "de una", "parce", "sin mente"),
        "donde" to listOf("estás", "queda", "nos vemos", "está"),
        "dónde" to listOf("estás", "queda", "nos vemos", "está"),
        "cuando" to listOf("puedas", "llegues", "vengas", "quieras"),
        "cuándo" to listOf("puedas", "llegues", "vengas", "quieras"),
        "voy" to listOf("en camino", "saliendo", "para allá", "a ver"),
        "estoy" to listOf("en camino", "ocupado", "listo", "llegando", "mamado", "parchado"),
        "nos" to listOf("vemos", "hablamos", "escribimos", "vamos"),
        "todo" to listOf("bien", "melo", "tranquilo", "listo", "claro"),
        "hasta" to listOf("luego", "mañana", "pronto", "entonces"),
        "un" to listOf("abrazo", "favor", "momento", "rato", "parche"),
        "una" to listOf("chimba", "pola", "pregunta", "vaina", "vez"),
        "cuánto" to listOf("cuesta", "vale", "tiempo", "es"),
        "cuanto" to listOf("cuesta", "vale", "tiempo", "es"),
        "para" to listOf("ti", "nada", "eso", "ver", "allá"),
        "pa" to listOf("dónde", "allá", "eso", "ver", "cuándo"),
        "con" to listOf("gusto", "toda", "cuidado", "tiempo"),
        "lo" to listOf("siento", "mismo", "tengo", "sé", "hago"),
        "espero" to listOf("que estés bien", "tu respuesta", "verte pronto"),
        "avísame" to listOf("cuando llegues", "cualquier cosa", "porfa")
    )

    private val ENGLISH_BIGRAMS = mapOf(
        "thank" to listOf("you", "you so much", "very much"),
        "thanks" to listOf("a lot", "for your help", "bro", "again"),
        "how" to listOf("are you", "is it going", "about you"),
        "good" to listOf("morning", "afternoon", "night", "luck", "job"),
        "see" to listOf("you", "you soon", "you later"),
        "let" to listOf("me know", "us know", "it go"),
        "i" to listOf("am", "will", "have", "think", "love you", "know"),
        "you" to listOf("are", "too", "can", "know", "welcome"),
        "what" to listOf("do you think", "time is it", "happened", "is up"),
        "where" to listOf("are you", "is it", "were you"),
        "sounds" to listOf("good", "great", "like a plan"),
        "take" to listOf("care", "your time", "it easy"),
        "no" to listOf("problem", "worries", "way"),
        "have" to listOf("a good day", "a great time", "fun", "to go"),
        "on" to listOf("my way", "the way", "time"),
        "looking" to listOf("forward", "for", "at"),
        "please" to listOf("let me know", "help me", "call me")
    )

    fun predict(
        language: String,
        prevWord: String,
        userStore: UserDictionaryStore? = null,
        maxCount: Int = 5,
        prevWord1: String? = null
    ): List<String> {
        val clean2 = cleanToken(prevWord)
        if (clean2.isEmpty()) return emptyList()
        val clean1 = prevWord1?.let { cleanToken(it) } ?: ""

        val results = linkedSetOf<String>()
        val isEn = language.startsWith("en", ignoreCase = true)

        // 1. User learned Trigrams (highest priority - Stupid Backoff N=3)
        if (userStore != null && clean1.isNotEmpty()) {
            try {
                val learnedTrigrams = userStore.getLearnedTrigramsWithScores(language, clean1, clean2, maxCount)
                results.addAll(learnedTrigrams.keys)
            } catch (_: Exception) {
            }
        }

        // 2. User learned Bigrams (N=2)
        if (userStore != null && results.size < maxCount) {
            try {
                val learnedBigrams = userStore.getLearnedNextWords(language, clean2, maxCount)
                for (w in learnedBigrams) {
                    if (results.size >= maxCount) break
                    results.add(w)
                }
            } catch (_: Exception) {
            }
        }

        // 3. Curated static Trigrams
        if (clean1.isNotEmpty() && results.size < maxCount) {
            val trigramCatalog = if (isEn) ENGLISH_TRIGRAMS else SPANISH_TRIGRAMS
            trigramCatalog["$clean1 $clean2"]?.forEach { phrase ->
                if (results.size < maxCount) {
                    results.add(phrase.trim())
                }
            }
        }

        // 4. Curated static Bigrams
        if (results.size < maxCount) {
            val bigramCatalog = if (isEn) ENGLISH_BIGRAMS else SPANISH_BIGRAMS
            bigramCatalog[clean2]?.forEach { word ->
                if (results.size < maxCount) {
                    results.add(word.trim())
                }
            }
        }

        return results.take(maxCount)
    }

    /**
     * Returns a map of single-word next candidates -> contextual score boost
     * so T9DictionaryTrie can re-rank candidates in real-time while digits are being typed.
     */
    fun getContextualBoostMap(
        language: String,
        prevWord2: String?,
        prevWord1: String? = null,
        userStore: UserDictionaryStore? = null
    ): Map<String, Int> {
        val clean2 = prevWord2?.let { cleanToken(it) } ?: ""
        if (clean2.isEmpty()) return emptyMap()
        val clean1 = prevWord1?.let { cleanToken(it) } ?: ""
        val boosts = HashMap<String, Int>()
        val isEn = language.startsWith("en", ignoreCase = true)

        // 1. User Trigrams: +65,000 base + frequency * 15,000
        if (userStore != null && clean1.isNotEmpty()) {
            try {
                val tri = userStore.getLearnedTrigramsWithScores(language, clean1, clean2, 12)
                for ((word, count) in tri) {
                    val firstToken = word.split(' ').firstOrNull() ?: continue
                    val score = 65_000 + (count * 15_000).coerceAtMost(90_000)
                    boosts[firstToken] = maxOf(boosts[firstToken] ?: 0, score)
                }
            } catch (_: Exception) {
            }
        }

        // 2. User Bigrams: +35,000 base + frequency * 10,000
        if (userStore != null) {
            try {
                val bi = userStore.getLearnedBigramsWithScores(language, clean2, 15)
                for ((word, count) in bi) {
                    val firstToken = word.split(' ').firstOrNull() ?: continue
                    val score = 35_000 + (count * 10_000).coerceAtMost(75_000)
                    boosts[firstToken] = maxOf(boosts[firstToken] ?: 0, score)
                }
            } catch (_: Exception) {
            }
        }

        // 3. Static Trigrams: +18,000
        if (clean1.isNotEmpty()) {
            val trigramCatalog = if (isEn) ENGLISH_TRIGRAMS else SPANISH_TRIGRAMS
            trigramCatalog["$clean1 $clean2"]?.forEachIndexed { idx, phrase ->
                val firstToken = cleanToken(phrase.trim().split(' ').firstOrNull() ?: "")
                if (firstToken.isNotEmpty()) {
                    val score = (18_000 - idx * 2_500).coerceAtLeast(8_000)
                    boosts[firstToken] = maxOf(boosts[firstToken] ?: 0, score)
                }
            }
        }

        // 4. Static Bigrams: +12,000
        val bigramCatalog = if (isEn) ENGLISH_BIGRAMS else SPANISH_BIGRAMS
        bigramCatalog[clean2]?.forEachIndexed { idx, phrase ->
            val firstToken = cleanToken(phrase.trim().split(' ').firstOrNull() ?: "")
            if (firstToken.isNotEmpty()) {
                val score = (12_000 - idx * 1_500).coerceAtLeast(4_000)
                boosts[firstToken] = maxOf(boosts[firstToken] ?: 0, score)
            }
        }

        return boosts
    }

    private fun cleanToken(raw: String): String {
        return raw.trim().lowercase().trim(',', '.', '!', '?', ';', ':', '¿', '¡', '"', '\'', '(', ')')
    }
}
