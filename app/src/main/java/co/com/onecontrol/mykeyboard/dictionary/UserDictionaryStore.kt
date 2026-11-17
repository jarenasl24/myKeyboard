package co.com.onecontrol.mykeyboard.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LearnedWord(
    val language: String,
    val word: String,
    val digits: String,
    val frequency: Int
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
                frequency INTEGER NOT NULL DEFAULT 100,
                UNIQUE(language, word)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_lang_digits ON $TABLE_WORDS(language, digits)")
        createBigramsTable(db)
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
                UNIQUE(language, prev_word, next_word)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bigrams_lookup ON $TABLE_BIGRAMS(language, prev_word, frequency DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createBigramsTable(db)
        }
    }

    fun saveOrBoostWord(language: String, word: String, boost: Int = 500) {
        val clean = word.trim().lowercase()
        val digits = T9KeyMap.wordToDigits(clean) ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cursor = db.query(
                TABLE_WORDS,
                arrayOf("frequency"),
                "language = ? AND word = ?",
                arrayOf(language, clean),
                null,
                null,
                null
            )
            if (cursor.moveToFirst()) {
                val currentFreq = cursor.getInt(0)
                val values = ContentValues().apply {
                    put("frequency", currentFreq + boost)
                }
                db.update(TABLE_WORDS, values, "language = ? AND word = ?", arrayOf(language, clean))
            } else {
                val values = ContentValues().apply {
                    put("language", language)
                    put("word", clean)
                    put("digits", digits)
                    put("frequency", DEFAULT_LEARNED_FREQ)
                }
                db.insert(TABLE_WORDS, null, values)
            }
            cursor.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordBigram(language: String, prevWord: String, nextWord: String, boost: Int = 1) {
        val p = prevWord.trim().lowercase()
        val n = nextWord.trim().lowercase()
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
                    put("frequency", currentFreq + boost)
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
                    put("frequency", 1)
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

    fun getLearnedNextWords(language: String, prevWord: String, limit: Int = 5): List<String> {
        val p = prevWord.trim().lowercase()
        if (p.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_BIGRAMS,
            arrayOf("next_word"),
            "language = ? AND prev_word = ?",
            arrayOf(language, p),
            null,
            null,
            "frequency DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                result.add(it.getString(0))
            }
        }
        return result
    }

    fun getAllWords(language: String): List<LearnedWord> {
        val result = mutableListOf<LearnedWord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_WORDS,
            arrayOf("language", "word", "digits", "frequency"),
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
                        frequency = it.getInt(3)
                    )
                )
            }
        }
        return result
    }

    companion object {
        private const val DB_NAME = "t9_user_dictionary.db"
        private const val DB_VERSION = 2
        private const val TABLE_WORDS = "user_words"
        private const val TABLE_BIGRAMS = "user_bigrams"
        private const val DEFAULT_LEARNED_FREQ = 6000
    }
}
