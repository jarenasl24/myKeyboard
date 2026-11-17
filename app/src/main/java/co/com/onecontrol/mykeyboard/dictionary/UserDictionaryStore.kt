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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_WORDS")
        onCreate(db)
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
        private const val DB_VERSION = 1
        private const val TABLE_WORDS = "user_words"
        private const val DEFAULT_LEARNED_FREQ = 6000
    }
}
