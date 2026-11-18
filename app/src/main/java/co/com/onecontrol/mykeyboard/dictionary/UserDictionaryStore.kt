package co.com.onecontrol.mykeyboard.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LearnedWord(
    val language: String,
    val word: String,
    val digits: String,
    val frequency: Int,
    val lastUsedMs: Long = 0L,
    val penalty: Int = 0
)

class UserDictionaryStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_WORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                language TEXT NOT NULL,
                word TEXT NOT NULL,
                digits TEXT NOT NULL,
                frequency INTEGER NOT NULL DEFAULT $INITIAL_IMPLICIT_FREQ,
                last_used INTEGER NOT NULL DEFAULT 0,
                penalty INTEGER NOT NULL DEFAULT 0,
                UNIQUE(language, word)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_lang_digits ON $TABLE_WORDS(language, digits)")
        createBigramsTable(db)
        createTrigramsTable(db)
    }

    private fun createBigramsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BIGRAMS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                language TEXT NOT NULL,
                prev_word TEXT NOT NULL,
                next_word TEXT NOT NULL,
                frequency INTEGER NOT NULL DEFAULT 1,
                last_used INTEGER NOT NULL DEFAULT 0,
                UNIQUE(language, prev_word, next_word)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bigrams_lookup ON $TABLE_BIGRAMS(language, prev_word, frequency DESC)")
    }

    private fun createTrigramsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRIGRAMS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                language TEXT NOT NULL,
                prev_word_1 TEXT NOT NULL,
                prev_word_2 TEXT NOT NULL,
                next_word TEXT NOT NULL,
                frequency INTEGER NOT NULL DEFAULT 1,
                last_used INTEGER NOT NULL DEFAULT 0,
                UNIQUE(language, prev_word_1, prev_word_2, next_word)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_trigrams_lookup ON $TABLE_TRIGRAMS(language, prev_word_1, prev_word_2, frequency DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createBigramsTable(db)
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE $TABLE_WORDS ADD COLUMN last_used INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {
            }
            try {
                db.execSQL("ALTER TABLE $TABLE_WORDS ADD COLUMN penalty INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {
            }
            try {
                db.execSQL("ALTER TABLE $TABLE_BIGRAMS ADD COLUMN last_used INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {
            }
            createTrigramsTable(db)
        }
    }

    fun saveOrBoostWord(
        language: String,
        word: String,
        explicit: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val clean = word.trim().lowercase()
        val digits = T9KeyMap.wordToDigits(clean) ?: return
        val boost = if (explicit) EXPLICIT_BOOST_STEP else IMPLICIT_BOOST_STEP
        val initialFreq = if (explicit) INITIAL_EXPLICIT_FREQ else INITIAL_IMPLICIT_FREQ

        val db = writableDatabase
        db.beginTransaction()
        try {
            val cursor = db.query(
                TABLE_WORDS,
                arrayOf("frequency", "penalty"),
                "language = ? AND word = ?",
                arrayOf(language, clean),
                null,
                null,
                null
            )
            if (cursor.moveToFirst()) {
                val currentFreq = cursor.getInt(0)
                val currentPenalty = cursor.getInt(1)
                val reducedPenalty = if (explicit) 0 else (currentPenalty - 2000).coerceAtLeast(0)
                val values = ContentValues().apply {
                    put("frequency", (currentFreq + boost).coerceAtMost(MAX_USER_FREQ))
                    put("last_used", nowMs)
                    put("penalty", reducedPenalty)
                }
                db.update(TABLE_WORDS, values, "language = ? AND word = ?", arrayOf(language, clean))
            } else {
                val values = ContentValues().apply {
                    put("language", language)
                    put("word", clean)
                    put("digits", digits)
                    put("frequency", initialFreq)
                    put("last_used", nowMs)
                    put("penalty", 0)
                }
                db.insert(TABLE_WORDS, null, values)
            }
            cursor.close()
            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            db.endTransaction()
        }
    }

    fun penalizeWord(language: String, word: String, penaltyAmount: Int = PENALTY_STEP) {
        val clean = word.trim().lowercase()
        val digits = T9KeyMap.wordToDigits(clean) ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cursor = db.query(
                TABLE_WORDS,
                arrayOf("frequency", "penalty"),
                "language = ? AND word = ?",
                arrayOf(language, clean),
                null,
                null,
                null
            )
            if (cursor.moveToFirst()) {
                val currentFreq = cursor.getInt(0)
                val currentPenalty = cursor.getInt(1)
                val values = ContentValues().apply {
                    put("frequency", (currentFreq - penaltyAmount / 2).coerceAtLeast(0))
                    put("penalty", (currentPenalty + penaltyAmount).coerceAtMost(MAX_PENALTY))
                }
                db.update(TABLE_WORDS, values, "language = ? AND word = ?", arrayOf(language, clean))
            } else {
                val values = ContentValues().apply {
                    put("language", language)
                    put("word", clean)
                    put("digits", digits)
                    put("frequency", 0)
                    put("last_used", 0L)
                    put("penalty", penaltyAmount)
                }
                db.insert(TABLE_WORDS, null, values)
            }
            cursor.close()
            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            db.endTransaction()
        }
    }

    fun recordBigram(
        language: String,
        prevWord: String,
        nextWord: String,
        boost: Int = 1,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val p = cleanToken(prevWord)
        val n = cleanToken(nextWord)
        if (p.isEmpty() || n.isEmpty() || p == n) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cursor = db.query(
                TABLE_BIGRAMS,
                arrayOf("frequency"),
                "language = ? AND prev_word = ? AND next_word = ?",
                arrayOf(language, p, n),
                null,
                null,
                null
            )
            if (cursor.moveToFirst()) {
                val currentFreq = cursor.getInt(0)
                val values = ContentValues().apply {
                    put("frequency", (currentFreq + boost).coerceAtMost(500))
                    put("last_used", nowMs)
                }
                db.update(
                    TABLE_BIGRAMS,
                    values,
                    "language = ? AND prev_word = ? AND next_word = ?",
                    arrayOf(language, p, n)
                )
            } else {
                val values = ContentValues().apply {
                    put("language", language)
                    put("prev_word", p)
                    put("next_word", n)
                    put("frequency", boost)
                    put("last_used", nowMs)
                }
                db.insert(TABLE_BIGRAMS, null, values)
            }
            cursor.close()
            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            db.endTransaction()
        }
    }

    fun recordTrigram(
        language: String,
        prevWord1: String,
        prevWord2: String,
        nextWord: String,
        boost: Int = 1,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val p1 = cleanToken(prevWord1)
        val p2 = cleanToken(prevWord2)
        val n = cleanToken(nextWord)
        if (p1.isEmpty() || p2.isEmpty() || n.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cursor = db.query(
                TABLE_TRIGRAMS,
                arrayOf("frequency"),
                "language = ? AND prev_word_1 = ? AND prev_word_2 = ? AND next_word = ?",
                arrayOf(language, p1, p2, n),
                null,
                null,
                null
            )
            if (cursor.moveToFirst()) {
                val currentFreq = cursor.getInt(0)
                val values = ContentValues().apply {
                    put("frequency", (currentFreq + boost).coerceAtMost(500))
                    put("last_used", nowMs)
                }
                db.update(
                    TABLE_TRIGRAMS,
                    values,
                    "language = ? AND prev_word_1 = ? AND prev_word_2 = ? AND next_word = ?",
                    arrayOf(language, p1, p2, n)
                )
            } else {
                val values = ContentValues().apply {
                    put("language", language)
                    put("prev_word_1", p1)
                    put("prev_word_2", p2)
                    put("next_word", n)
                    put("frequency", boost)
                    put("last_used", nowMs)
                }
                db.insert(TABLE_TRIGRAMS, null, values)
            }
            cursor.close()
            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            db.endTransaction()
        }
    }

    fun getLearnedNextWords(language: String, prevWord: String, limit: Int = 5): List<String> {
        return getLearnedBigramsWithScores(language, prevWord, limit).keys.toList()
    }

    fun getLearnedBigramsWithScores(
        language: String,
        prevWord: String,
        limit: Int = 12
    ): LinkedHashMap<String, Int> {
        val p = cleanToken(prevWord)
        val result = LinkedHashMap<String, Int>()
        if (p.isEmpty()) return result
        val db = readableDatabase
        val cursor = db.query(
            TABLE_BIGRAMS,
            arrayOf("next_word", "frequency"),
            "language = ? AND prev_word = ?",
            arrayOf(language, p),
            null,
            null,
            "frequency DESC, last_used DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                result[it.getString(0)] = it.getInt(1)
            }
        }
        return result
    }

    fun getLearnedTrigramsWithScores(
        language: String,
        prevWord1: String,
        prevWord2: String,
        limit: Int = 10
    ): LinkedHashMap<String, Int> {
        val p1 = cleanToken(prevWord1)
        val p2 = cleanToken(prevWord2)
        val result = LinkedHashMap<String, Int>()
        if (p1.isEmpty() || p2.isEmpty()) return result
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRIGRAMS,
            arrayOf("next_word", "frequency"),
            "language = ? AND prev_word_1 = ? AND prev_word_2 = ?",
            arrayOf(language, p1, p2),
            null,
            null,
            "frequency DESC, last_used DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                result[it.getString(0)] = it.getInt(1)
            }
        }
        return result
    }

    fun getAllWords(language: String): List<LearnedWord> {
        val result = mutableListOf<LearnedWord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_WORDS,
            arrayOf("language", "word", "digits", "frequency", "last_used", "penalty"),
            "language = ?",
            arrayOf(language),
            null,
            null,
            "frequency DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    LearnedWord(
                        language = it.getString(0),
                        word = it.getString(1),
                        digits = it.getString(2),
                        frequency = it.getInt(3),
                        lastUsedMs = it.getLong(4),
                        penalty = it.getInt(5)
                    )
                )
            }
        }
        return result
    }

    private fun cleanToken(raw: String): String {
        return raw.trim().lowercase().trim(',', '.', '!', '?', ';', ':', '¿', '¡', '"', '\'', '(', ')')
    }

    companion object {
        private const val DB_NAME = "t9_user_dictionary.db"
        private const val DB_VERSION = 3
        private const val TABLE_WORDS = "user_words"
        private const val TABLE_BIGRAMS = "user_bigrams"
        private const val TABLE_TRIGRAMS = "user_trigrams"

        const val INITIAL_IMPLICIT_FREQ = 4500
        const val INITIAL_EXPLICIT_FREQ = 16000
        const val IMPLICIT_BOOST_STEP = 3500
        const val EXPLICIT_BOOST_STEP = 12000
        const val MAX_USER_FREQ = 250000
        const val PENALTY_STEP = 8000
        const val MAX_PENALTY = 80000
    }
}
