package co.com.onecontrol.mykeyboard.engine

import co.com.onecontrol.mykeyboard.dictionary.UserDictionaryStore

object NextWordPredictor {

    private val SPANISH_BIGRAMS = mapOf(
        "hola" to listOf("¿cómo estás?", "¿qué tal?", "buenas", "amigo"),
        "muchas" to listOf("gracias", "veces", "cosas"),
        "mucho" to listOf("gusto", "cuidado", "mejor", "tiempo"),
        "buenos" to listOf("días", "momentos"),
        "buenas" to listOf("tardes", "noches"),
        "por" to listOf("favor", "ejemplo", "eso", "cierto", "supuesto", "fin"),
        "de" to listOf("acuerdo", "nada", "nuevo", "pronto", "una vez"),
        "en" to listOf("casa", "camino", "contacto", "cuenta", "línea"),
        "te" to listOf("quiero", "aviso", "escribo", "llamo", "amo", "cuento"),
        "me" to listOf("parece", "gusta", "avisas", "alegro", "voy", "llamas"),
        "que" to listOf("tal", "tengas", "bueno", "onda", "pasa", "hora es"),
        "cómo" to listOf("estás", "te va", "vas", "siempre", "te fue"),
        "como" to listOf("estás", "te va", "vas", "siempre", "te fue"),
        "ya" to listOf("voy", "llegué", "casi", "estoy", "sé", "salí"),
        "feliz" to listOf("cumpleaños", "día", "navidad", "semana"),
        "gracias" to listOf("por todo", "amigo", "totales", "igualmente", "a ti"),
        "no" to listOf("sé", "puedo", "te preocupes", "hay de qué", "importa"),
        "si" to listOf("claro", "por supuesto", "de una", "obvio", "puedes"),
        "sí" to listOf("claro", "por supuesto", "de una", "obvio"),
        "donde" to listOf("estás", "queda", "nos vemos", "está"),
        "dónde" to listOf("estás", "queda", "nos vemos", "está"),
        "cuando" to listOf("puedas", "llegues", "vengas", "sales"),
        "cuándo" to listOf("puedas", "llegues", "vengas", "sales"),
        "voy" to listOf("en camino", "saliendo", "para allá", "a ver"),
        "estoy" to listOf("en camino", "ocupado", "listo", "llegando", "bien"),
        "nos" to listOf("vemos", "hablamos", "escribimos", "vamos"),
        "todo" to listOf("bien", "tranquilo", "listo", "claro"),
        "hasta" to listOf("luego", "mañana", "pronto", "entonces"),
        "un" to listOf("abrazo", "favor", "momento", "saludo", "gusto"),
        "cuánto" to listOf("cuesta", "vale", "tiempo", "antes"),
        "cuanto" to listOf("cuesta", "vale", "tiempo", "antes"),
        "para" to listOf("ti", "nada", "eso", "ver", "siempre"),
        "con" to listOf("gusto", "mucho gusto", "cuidado", "tiempo"),
        "lo" to listOf("siento", "mismo", "tengo", "sé", "hago"),
        "espero" to listOf("que estés bien", "tu respuesta", "verte pronto"),
        "avísame" to listOf("cuando llegues", "cualquier cosa", "por favor")
    )

    private val ENGLISH_BIGRAMS = mapOf(
        "thank" to listOf("you", "you so much", "very much"),
        "thanks" to listOf("a lot", "for your help", "again"),
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
        maxCount: Int = 4
    ): List<String> {
        val clean = prevWord.trim().lowercase().trim(',', '.', '!', '?', ';', ':', '¿', '¡')
        if (clean.isEmpty()) return emptyList()

        val results = linkedSetOf<String>()

        // 1. Check user learned bigrams first
        if (userStore != null) {
            try {
                val learned = userStore.getLearnedNextWords(language, clean, maxCount)
                results.addAll(learned)
            } catch (_: Exception) {
            }
        }

        // 2. Check curated static bigrams
        val catalog = if (language.startsWith("en", ignoreCase = true)) ENGLISH_BIGRAMS else SPANISH_BIGRAMS
        catalog[clean]?.let { staticList ->
            for (word in staticList) {
                if (results.size >= maxCount) break
                results.add(word)
            }
        }

        return results.take(maxCount)
    }
}
