package co.com.onecontrol.mykeyboard.engine

import android.os.Handler
import android.os.Looper
import co.com.onecontrol.mykeyboard.dictionary.T9KeyMap

class MultiTapEngine(
    private val onComposingChanged: (CharSequence) -> Unit,
    private val onCommitChar: (CharSequence) -> Unit
) {
    var timeoutMs: Long = 650L

    private val handler = Handler(Looper.getMainLooper())
    private var lastKey: Char? = null
    private var tapIndex: Int = 0
    private var pendingChar: Char? = null

    private val commitRunnable = Runnable {
        commitPending()
    }

    fun hasPending(): Boolean = pendingChar != null

    fun onKeyTap(digit: Char, caseMode: CaseMode): Boolean {
        val chars = T9KeyMap.MULTI_TAP_CHARS[digit] ?: return false
        handler.removeCallbacks(commitRunnable)

        if (lastKey == digit && pendingChar != null) {
            tapIndex = (tapIndex + 1) % chars.length
        } else {
            if (pendingChar != null) {
                commitPending()
            }
            lastKey = digit
            tapIndex = 0
        }

        val rawChar = chars[tapIndex]
        val styledChar = caseMode.applyToChar(rawChar)
        pendingChar = styledChar
        onComposingChanged(styledChar.toString())

        handler.postDelayed(commitRunnable, timeoutMs)
        return true
    }

    fun commitPending(): Char? {
        handler.removeCallbacks(commitRunnable)
        val ch = pendingChar ?: return null
        pendingChar = null
        lastKey = null
        tapIndex = 0
        onCommitChar(ch.toString())
        return ch
    }

    fun cancelPending(): Boolean {
        handler.removeCallbacks(commitRunnable)
        val hadPending = pendingChar != null
        pendingChar = null
        lastKey = null
        tapIndex = 0
        if (hadPending) {
            onComposingChanged("")
        }
        return hadPending
    }

    fun reset() {
        handler.removeCallbacks(commitRunnable)
        pendingChar = null
        lastKey = null
        tapIndex = 0
    }
}
