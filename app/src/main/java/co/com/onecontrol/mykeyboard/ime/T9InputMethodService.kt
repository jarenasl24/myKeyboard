package co.com.onecontrol.mykeyboard.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import co.com.onecontrol.mykeyboard.dictionary.T9DictionaryTrie
import co.com.onecontrol.mykeyboard.dictionary.T9KeyMap
import co.com.onecontrol.mykeyboard.engine.CaseMode
import co.com.onecontrol.mykeyboard.engine.InputMode
import co.com.onecontrol.mykeyboard.engine.MathEvaluator
import co.com.onecontrol.mykeyboard.engine.MultiTapEngine
import co.com.onecontrol.mykeyboard.engine.NextWordPredictor
import co.com.onecontrol.mykeyboard.engine.T9PredictiveEngine
import co.com.onecontrol.mykeyboard.ui.ResizableKeyboardContainer
import co.com.onecontrol.mykeyboard.ui.T9KeyboardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class T9InputMethodService : InputMethodService(), T9KeyboardView.Listener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var preferences: KeyboardPreferences
    private lateinit var dictionary: T9DictionaryTrie
    private lateinit var t9Engine: T9PredictiveEngine
    private lateinit var multiTapEngine: MultiTapEngine
    private lateinit var shortcutStore: co.com.onecontrol.mykeyboard.dictionary.TextShortcutStore

    private var container: ResizableKeyboardContainer? = null
    private var keyboardView: T9KeyboardView? = null

    private var currentConfig = KeyboardConfig()
    private var inputMode = InputMode.T9_ES
    private var caseMode = CaseMode.CAPITALIZE
    private var lastT9LanguageMode = InputMode.T9_ES
    private var returnToT9AfterWord: InputMode? = null

    // Contextual N-gram state (prevWord1 = two words ago, prevWord2 = immediately preceding word)
    private var prevContextWord1: String? = null
    private var prevContextWord2: String? = null

    // Undo / Negative Feedback tracking when Space auto-commits a T9 primary suggestion
    private var lastAutoCommittedWord: String? = null
    private var lastAutoCommittedDigits: String? = null

    private val multiTapWordBuffer = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        preferences = KeyboardPreferences(applicationContext)
        shortcutStore = co.com.onecontrol.mykeyboard.dictionary.TextShortcutStore(applicationContext)
        dictionary = T9DictionaryTrie(applicationContext)
        dictionary.loadAsync {
            shortcutStore.getAllShortcuts().forEach { sc ->
                dictionary.learnWord(T9DictionaryTrie.Language.ES, sc.shortcut, explicit = true)
            }
            refreshT9Suggestions()
        }
        t9Engine = T9PredictiveEngine(dictionary)
        multiTapEngine = MultiTapEngine(
            onComposingChanged = { text ->
                currentInputConnection?.setComposingText(text, 1)
                refreshMultiTapSuggestions(pendingChar = text.toString())
            },
            onCommitChar = { ch ->
                currentInputConnection?.commitText(ch, 1)
                if (ch.length == 1 && (ch[0].isLetter() || ch[0] == 'ñ' || ch[0] == 'Ñ')) {
                    multiTapWordBuffer.append(ch[0])
                } else {
                    flushMultiTapWordToDictionary()
                }
                if (caseMode == CaseMode.CAPITALIZE) {
                    caseMode = CaseMode.LOWER
                    keyboardView?.updateState(inputMode, caseMode)
                }
                refreshMultiTapSuggestions(pendingChar = "")
            }
        )

        serviceScope.launch {
            preferences.configFlow.collectLatest { config ->
                currentConfig = config
                inputMode = config.inputMode
                if (inputMode == InputMode.T9_ES || inputMode == InputMode.T9_EN) {
                    lastT9LanguageMode = inputMode
                }
                multiTapEngine.timeoutMs = config.multiTapTimeoutMs
                container?.applyConfig(config)
                keyboardView?.setHapticStrength(config.hapticStrength)
                keyboardView?.setKeyHeightDp(config.keyHeightDp)
                keyboardView?.updateState(inputMode, caseMode)
            }
        }
    }

    override fun onCreateInputView(): View {
        val kbd = T9KeyboardView(this).apply {
            listener = this@T9InputMethodService
            setHapticStrength(currentConfig.hapticStrength)
            setKeyHeightDp(currentConfig.keyHeightDp)
            updateState(inputMode, caseMode)
        }
        val wrapper = ResizableKeyboardContainer(this).apply {
            setKeyboardView(kbd)
            applyConfig(currentConfig)
            onDimensionsCommitted = { widthPct, keyHeight, align, bottomMargin ->
                serviceScope.launch {
                    preferences.updateDimensions(widthPct, keyHeight, align, bottomMargin)
                }
            }
        }
        keyboardView = kbd
        container = wrapper
        return wrapper
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        t9Engine.clear()
        multiTapEngine.reset()
        multiTapWordBuffer.clear()
        returnToT9AfterWord = null
        prevContextWord1 = null
        prevContextWord2 = null
        clearAutoCommitUndoState()
        keyboardView?.hideEmojiPanel()

        val inputType = info?.inputType ?: 0
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        val isPasswordField = (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            (inputClass == InputType.TYPE_CLASS_NUMBER && variation == 16))

        val isEmailField = (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)

        val isUrlField = (variation == InputType.TYPE_TEXT_VARIATION_URI)

        if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) {
            inputMode = InputMode.NUMERIC
        } else {
            inputMode = currentConfig.inputMode
        }

        val capsFlags = inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        caseMode = when {
            isEmailField || isPasswordField -> CaseMode.LOWER
            (capsFlags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0 -> CaseMode.UPPER
            (capsFlags and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS)) != 0 -> CaseMode.CAPITALIZE
            else -> CaseMode.LOWER
        }

        keyboardView?.updateImeAction(info?.imeOptions ?: EditorInfo.IME_ACTION_NONE)
        keyboardView?.updateState(inputMode, caseMode)

        syncContextWordsFromEditor()

        when {
            isEmailField -> keyboardView?.showEmailDomainStrip()
            isPasswordField -> keyboardView?.showPasswordHelpStrip()
            isUrlField -> keyboardView?.showUrlShortcutsStrip()
            else -> refreshT9Suggestions()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        val hasComposing = !t9Engine.isEmpty() || multiTapEngine.hasPending()
        if (hasComposing) {
            val isAtComposingEnd = (candidatesStart in 0..candidatesEnd &&
                newSelStart == candidatesEnd &&
                newSelEnd == candidatesEnd)

            if (!isAtComposingEnd) {
                currentInputConnection?.finishComposingText()
                if (!t9Engine.isEmpty()) {
                    val primary = t9Engine.getPrimarySuggestion(
                        inputMode.toLanguage(),
                        caseMode,
                        prevContextWord2,
                        prevContextWord1
                    )
                    if (primary != null) {
                        t9Engine.learnWord(inputMode.toLanguage(), primary, explicit = false)
                    }
                    t9Engine.clear()
                }
                if (multiTapEngine.hasPending()) {
                    multiTapEngine.reset()
                }
                multiTapWordBuffer.clear()
                syncContextWordsFromEditor()
                keyboardView?.showSuggestions(emptyList(), false, "")
            }
        }

        currentInputConnection?.let { ic ->
            applyAutoCapIfSentenceEnd(ic)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        commitAllComposing()
        container?.setResizeMode(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        multiTapEngine.reset()
        serviceScope.cancel()
    }

    // --- T9KeyboardView.Listener ---

    override fun onDigitKey(digit: Char) {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        when (inputMode) {
            InputMode.NUMERIC -> {
                ic.commitText(digit.toString(), 1)
            }
            InputMode.MULTI_TAP -> {
                multiTapEngine.onKeyTap(digit, caseMode)
            }
            InputMode.T9_ES, InputMode.T9_EN -> {
                if (digit == '1') {
                    commitT9Composing(ic, learn = true, explicit = false)
                    multiTapEngine.onKeyTap('1', caseMode)
                } else {
                    if (multiTapEngine.hasPending()) {
                        multiTapEngine.commitPending()
                    }
                    if (t9Engine.isEmpty()) {
                        syncContextWordsFromEditor()
                    }
                    t9Engine.appendDigit(digit)
                    refreshT9Suggestions()
                }
            }
        }
    }

    override fun onStarKey() {
        clearAutoCommitUndoState()
        if (inputMode == InputMode.NUMERIC) {
            currentInputConnection?.commitText("*", 1)
            return
        }
        caseMode = caseMode.next()
        keyboardView?.updateState(inputMode, caseMode)
        if ((inputMode == InputMode.T9_ES || inputMode == InputMode.T9_EN) && !t9Engine.isEmpty()) {
            refreshT9Suggestions()
        }
    }

    override fun onStarLongPress() {
        clearAutoCommitUndoState()
        if (inputMode == InputMode.NUMERIC) {
            currentInputConnection?.commitText("*", 1)
            return
        }
        keyboardView?.showSymbolStrip()
    }

    override fun onZeroKey() {
        val ic = currentInputConnection ?: return
        when (inputMode) {
            InputMode.NUMERIC -> {
                clearAutoCommitUndoState()
                ic.commitText("0", 1)
            }
            InputMode.MULTI_TAP -> {
                clearAutoCommitUndoState()
                multiTapEngine.commitPending()
                flushMultiTapWordToDictionary()
                learnCompletedWordBeforeCursor(ic, explicit = true)
                ic.commitText(" ", 1)
                syncContextWordsFromEditor()
                if (returnToT9AfterWord != null) {
                    val target = returnToT9AfterWord!!
                    returnToT9AfterWord = null
                    setInputMode(target, persist = false)
                }
                applyAutoCapIfSentenceEnd(ic)
            }
            InputMode.T9_ES, InputMode.T9_EN -> {
                if (!t9Engine.isEmpty()) {
                    val pendingDigits = t9Engine.currentDigits
                    val committedWord = commitT9Composing(ic, learn = true, explicit = false)
                    ic.commitText(" ", 1)
                    if (committedWord != null && pendingDigits.isNotEmpty()) {
                        lastAutoCommittedWord = committedWord
                        lastAutoCommittedDigits = pendingDigits
                    } else {
                        clearAutoCommitUndoState()
                    }
                } else {
                    clearAutoCommitUndoState()
                    if (multiTapEngine.hasPending()) {
                        multiTapEngine.commitPending()
                    }
                    // If the user spelled a custom word via Hold & Slide or manual edits, learn it on Space!
                    learnCompletedWordBeforeCursor(ic, explicit = true)
                    ic.commitText(" ", 1)
                    syncContextWordsFromEditor()
                }
                applyAutoCapIfSentenceEnd(ic)
                refreshT9Suggestions()
            }
        }
    }

    override fun onZeroLongPress() {
        clearAutoCommitUndoState()
        commitAllComposing()
        currentInputConnection?.commitText("0", 1)
    }

    override fun onHashKey() {
        clearAutoCommitUndoState()
        commitAllComposing()
        returnToT9AfterWord = null
        val next = inputMode.next()
        setInputMode(next, persist = true)
    }

    override fun onQuickLanguageToggle() {
        clearAutoCommitUndoState()
        returnToT9AfterWord = null
        val next = when (inputMode) {
            InputMode.T9_ES -> InputMode.T9_EN
            InputMode.T9_EN -> InputMode.T9_ES
            else -> if (lastT9LanguageMode == InputMode.T9_ES) InputMode.T9_EN else InputMode.T9_ES
        }
        setInputMode(next, persist = true)
    }

    override fun onTogglePredictiveMode() {
        clearAutoCommitUndoState()
        commitAllComposing()
        returnToT9AfterWord = null
        val nextMode = when (inputMode) {
            InputMode.T9_ES, InputMode.T9_EN -> InputMode.MULTI_TAP
            InputMode.MULTI_TAP -> lastT9LanguageMode
            InputMode.NUMERIC -> lastT9LanguageMode
        }
        setInputMode(nextMode, persist = true)
    }

    override fun onModeSelected(mode: InputMode) {
        clearAutoCommitUndoState()
        commitAllComposing()
        returnToT9AfterWord = null
        setInputMode(mode, persist = true)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return

        // 1. If actively typing T9 digits, delete the last digit
        if ((inputMode == InputMode.T9_ES || inputMode == InputMode.T9_EN) && !t9Engine.isEmpty()) {
            clearAutoCommitUndoState()
            t9Engine.backspaceDigit()
            if (t9Engine.isEmpty()) {
                ic.setComposingText("", 1)
            }
            refreshT9Suggestions()
            return
        }

        // 2. Smart Undo + Negative Feedback: if user just hit Space (auto-committing a T9 suggestion)
        // and immediately presses Backspace, penalize the wrong auto-committed word and restore the T9 digits
        // so they can pick the exact alternative suggestion they wanted!
        val undoWord = lastAutoCommittedWord
        val undoDigits = lastAutoCommittedDigits
        if ((inputMode == InputMode.T9_ES || inputMode == InputMode.T9_EN) &&
            undoWord != null && undoDigits != null
        ) {
            clearAutoCommitUndoState()
            val expectedSuffix = "$undoWord "
            val before = ic.getTextBeforeCursor(expectedSuffix.length + 4, 0)?.toString() ?: ""
            if (before.endsWith(expectedSuffix)) {
                val lang = inputMode.toLanguage()
                t9Engine.penalizeWord(lang, undoWord)
                ic.deleteSurroundingText(expectedSuffix.length, 0)
                syncContextWordsFromEditor()
                t9Engine.setDigits(undoDigits)
                refreshT9Suggestions()
                return
            }
        }

        clearAutoCommitUndoState()
        if (multiTapEngine.cancelPending()) {
            refreshMultiTapSuggestions(pendingChar = "")
            return
        }
        if (multiTapWordBuffer.isNotEmpty()) {
            multiTapWordBuffer.deleteCharAt(multiTapWordBuffer.length - 1)
            refreshMultiTapSuggestions(pendingChar = "")
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        syncContextWordsFromEditor()
        if (inputMode == InputMode.T9_ES || inputMode == InputMode.T9_EN) {
            refreshT9Suggestions()
        }
    }

    override fun onEnterKey() {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        commitAllComposing()
        flushMultiTapWordToDictionary()
        learnCompletedWordBeforeCursor(ic, explicit = true)

        val editorInfo = currentInputEditorInfo
        val imeOptions = editorInfo?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val isMultiLine = (editorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

        if (!isMultiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onSuggestionSelected(word: String) {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        val lang = (returnToT9AfterWord ?: inputMode).toLanguage()

        if (inputMode == InputMode.MULTI_TAP) {
            multiTapEngine.cancelPending()
            val bufLen = multiTapWordBuffer.length
            if (bufLen > 0) {
                ic.deleteSurroundingText(bufLen, 0)
                multiTapWordBuffer.clear()
            }
            ic.commitText("$word ", 1)
            t9Engine.learnWord(lang, word, explicit = true)
            recordWordInContext(lang, word, explicit = true)
            if (returnToT9AfterWord != null) {
                val target = returnToT9AfterWord!!
                returnToT9AfterWord = null
                setInputMode(target, persist = false)
            }
            applyAutoCapIfSentenceEnd(ic)
            refreshT9Suggestions()
            return
        }

        // If user explicitly chose a non-primary suggestion while typing T9 digits,
        // mildly penalize the rejected primary suggestion and strongly boost the chosen word!
        if (!t9Engine.isEmpty()) {
            val currentPrimary = t9Engine.getPrimarySuggestion(lang, caseMode, prevContextWord2, prevContextWord1)
            if (currentPrimary != null && !currentPrimary.equals(word, ignoreCase = true)) {
                t9Engine.penalizeWord(lang, currentPrimary)
            }
        }

        ic.commitText("$word ", 1)
        t9Engine.learnWord(lang, word, explicit = true)
        recordWordInContext(lang, word, explicit = true)
        t9Engine.clear()
        if (caseMode == CaseMode.CAPITALIZE) {
            caseMode = CaseMode.LOWER
            keyboardView?.updateState(inputMode, caseMode)
        }
        applyAutoCapIfSentenceEnd(ic)
        refreshT9Suggestions()
    }

    override fun onFallbackMultiTapRequested() {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        // Clear numeric T9 composing so user can spell the unknown word in Multi-tap and auto-learn it
        t9Engine.clear()
        ic.setComposingText("", 1)
        multiTapWordBuffer.clear()
        returnToT9AfterWord = inputMode
        setInputMode(InputMode.MULTI_TAP, persist = false)
        keyboardView?.showSuggestions(emptyList(), false, "")
    }

    override fun onSymbolSelected(symbol: String) {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()

        val isSingleLetter = symbol.length == 1 && (symbol[0].isLetter() || symbol[0] == 'ñ' || symbol[0] == 'Ñ')
        if (isSingleLetter) {
            // If user uses Hold & Slide to pick a specific letter while in T9 mode,
            // commit any pending T9 text without falsely boosting the wrong T9 word,
            // and append the chosen letter seamlessly.
            if (!t9Engine.isEmpty()) {
                commitT9Composing(ic, learn = false, explicit = false)
            }
            if (multiTapEngine.hasPending()) {
                multiTapEngine.commitPending()
            }
            ic.commitText(symbol, 1)
            if (inputMode == InputMode.MULTI_TAP) {
                multiTapWordBuffer.append(symbol[0])
                refreshMultiTapSuggestions(pendingChar = "")
            } else {
                if (caseMode == CaseMode.CAPITALIZE) {
                    caseMode = CaseMode.LOWER
                    keyboardView?.updateState(inputMode, caseMode)
                }
                refreshT9Suggestions()
            }
            return
        }

        commitAllComposing()
        learnCompletedWordBeforeCursor(ic, explicit = true)
        ic.commitText(symbol, 1)
        syncContextWordsFromEditor()
        refreshT9Suggestions()
    }

    override fun onSwipeDeleteWord() {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        if (!t9Engine.isEmpty()) {
            t9Engine.clear()
            ic.setComposingText("", 1)
            refreshT9Suggestions()
            return
        }
        if (multiTapEngine.cancelPending()) {
            return
        }
        multiTapWordBuffer.clear()

        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        if (before.isEmpty()) return

        var i = before.length - 1
        while (i >= 0 && before[i].isWhitespace()) {
            i--
        }
        while (i >= 0 && !before[i].isWhitespace()) {
            i--
        }
        val deleteCount = before.length - 1 - i
        if (deleteCount > 0) {
            ic.deleteSurroundingText(deleteCount, 0)
        }
        syncContextWordsFromEditor()
        refreshT9Suggestions()
    }

    override fun onDeleteWord() {
        onSwipeDeleteWord()
    }

    override fun onMoveCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        if (!t9Engine.isEmpty() || multiTapEngine.hasPending()) {
            ic.finishComposingText()
            if (!t9Engine.isEmpty()) {
                val primary = t9Engine.getPrimarySuggestion(
                    inputMode.toLanguage(),
                    caseMode,
                    prevContextWord2,
                    prevContextWord1
                )
                if (primary != null) {
                    t9Engine.learnWord(inputMode.toLanguage(), primary, explicit = false)
                    recordWordInContext(inputMode.toLanguage(), primary, explicit = false)
                }
                t9Engine.clear()
            }
            if (multiTapEngine.hasPending()) {
                multiTapEngine.reset()
            }
            multiTapWordBuffer.clear()
            refreshT9Suggestions()
        }
        val extracted = try {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        } catch (_: Exception) {
            null
        }
        if (extracted != null && extracted.selectionStart >= 0) {
            val cur = extracted.selectionStart
            val newPos = (cur + delta).coerceIn(0, extracted.text.length)
            ic.setSelection(newPos, newPos)
        } else {
            val keycode = if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))
        }
    }

    override fun onSwipeType(digits: String) {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        if (inputMode != InputMode.T9_ES && inputMode != InputMode.T9_EN) return
        if (digits.length < 2) return

        val matches = dictionary.querySwipe(inputMode.toLanguage(), digits, limit = 8)
        if (matches.isNotEmpty()) {
            commitAllComposing()
            val bestWord = caseMode.applyToWord(matches.first())
            val mappedMatches = matches.map { caseMode.applyToWord(it) }
            ic.commitText("$bestWord ", 1)
            dictionary.learnWord(inputMode.toLanguage(), matches.first(), explicit = false)
            recordWordInContext(inputMode.toLanguage(), bestWord, explicit = false)
            lastAutoCommittedWord = bestWord
            lastAutoCommittedDigits = T9KeyMap.wordToDigits(matches.first())
            if (caseMode == CaseMode.CAPITALIZE) {
                caseMode = CaseMode.LOWER
                keyboardView?.updateState(inputMode, caseMode)
            }
            applyAutoCapIfSentenceEnd(ic)
            keyboardView?.showSuggestions(
                suggestions = mappedMatches,
                hasPendingDigits = false,
                digits = ""
            )
        } else {
            commitT9Composing(ic, learn = true, explicit = false)
            t9Engine.clear()
            for (ch in digits) {
                t9Engine.appendDigit(ch)
            }
            refreshT9Suggestions()
        }
    }

    override fun onCalculatorSelected(expression: String, result: String) {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        val before = ic.getTextBeforeCursor(expression.length + 4, 0)?.toString() ?: ""
        if (before.trimEnd().endsWith("=")) {
            ic.commitText(" $result ", 1)
        } else {
            val idx = before.lastIndexOf(expression)
            if (idx >= 0) {
                val deleteCount = before.length - idx
                ic.deleteSurroundingText(deleteCount, 0)
                ic.commitText("$result ", 1)
            } else {
                ic.commitText(" = $result ", 1)
            }
        }
        refreshT9Suggestions()
    }

    override fun onClipboardRequested() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = clipboard?.primaryClip
        val items = mutableListOf<String>()
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val text = clip.getItemAt(i).text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    items.add(text)
                }
            }
        }
        keyboardView?.showClipboardStrip(items.distinct().take(6))
    }

    override fun onShortcutSelected(expansion: String) {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        if (inputMode == InputMode.MULTI_TAP) {
            val bufLen = multiTapWordBuffer.length
            if (bufLen > 0) {
                ic.deleteSurroundingText(bufLen, 0)
                multiTapWordBuffer.clear()
            }
        }
        ic.commitText("$expansion ", 1)
        t9Engine.clear()
        syncContextWordsFromEditor()
        applyAutoCapIfSentenceEnd(ic)
        refreshT9Suggestions()
    }

    override fun onShortcutsRequested() {
        keyboardView?.showShortcutsStrip(shortcutStore.getAllShortcuts())
    }

    override fun onGeneratePasswordRequested() {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val special = "!@#$%&*?"
        val all = upper + lower + digits + special
        val sr = java.security.SecureRandom()
        val pwdChars = mutableListOf(
            upper[sr.nextInt(upper.length)],
            lower[sr.nextInt(lower.length)],
            digits[sr.nextInt(digits.length)],
            special[sr.nextInt(special.length)]
        )
        for (i in 4 until 16) {
            pwdChars.add(all[sr.nextInt(all.length)])
        }
        pwdChars.shuffle(sr)
        val password = pwdChars.joinToString("")
        ic.commitText(password, 1)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clipData = android.content.ClipData.newPlainText("Generated Password", password)
        clipboard?.setPrimaryClip(clipData)
        android.widget.Toast.makeText(this, "Contraseña generada y copiada al portapapeles", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onSwipeSpace() {
        onZeroKey()
    }

    override fun onResizeToggleRequested() {
        val c = container ?: return
        c.setResizeMode(!c.isInResizeMode())
    }

    override fun onSwitchImeRequested() {
        switchToSwiftKeyOrPicker()
    }

    override fun onShowImePickerRequested() {
        switchToSwiftKeyOrPicker()
    }

    private fun switchToSwiftKeyOrPicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        try {
            val swiftKey = imm?.enabledInputMethodList?.firstOrNull {
                it.packageName.contains("swiftkey", ignoreCase = true)
            }
            if (swiftKey != null) {
                switchInputMethod(swiftKey.id)
                return
            }
        } catch (_: Exception) {
            // fallback
        }

        try {
            if (switchToPreviousInputMethod()) {
                return
            }
        } catch (_: Exception) {
            // fallback
        }

        try {
            imm?.showInputMethodPicker()
        } catch (_: Exception) {
            // ignore
        }
    }

    override fun onDirectCommit(text: String) {
        val ic = currentInputConnection ?: return
        clearAutoCommitUndoState()
        commitAllComposing()
        ic.commitText(text, 1)
        syncContextWordsFromEditor()
        applyAutoCapIfSentenceEnd(ic)
        refreshT9Suggestions()
    }

    override fun onGifSelected(assetName: String, label: String) {
        commitGif(assetName, label)
    }

    private fun commitGif(assetName: String, label: String) {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo ?: return

        try {
            val cacheDir = java.io.File(cacheDir, "gifs")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val destFile = java.io.File(cacheDir, assetName)
            if (!destFile.exists()) {
                assets.open("gifs/$assetName").use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "co.com.onecontrol.mykeyboard.fileprovider",
                destFile
            )

            val description = android.content.ClipDescription(label, arrayOf("image/gif"))
            val inputContentInfo = androidx.core.view.inputmethod.InputContentInfoCompat(
                contentUri,
                description,
                null
            )

            var flags = 0
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                flags = flags or androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            }

            val committed = androidx.core.view.inputmethod.InputConnectionCompat.commitContent(
                ic,
                editorInfo,
                inputContentInfo,
                flags,
                null
            )

            if (!committed) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clipData = android.content.ClipData.newUri(contentResolver, label, contentUri)
                clipboard?.setPrimaryClip(clipData)
                android.widget.Toast.makeText(
                    this,
                    "GIF copiado al portapapeles (la app no acepta inserción directa)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Error al enviar GIF: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // --- Helpers ---

    private fun clearAutoCommitUndoState() {
        lastAutoCommittedWord = null
        lastAutoCommittedDigits = null
    }

    private fun recordWordInContext(
        lang: T9DictionaryTrie.Language,
        newWord: String,
        explicit: Boolean
    ) {
        val w = newWord.trim()
        if (w.isEmpty()) return
        val p2 = prevContextWord2
        val p1 = prevContextWord1
        if (!p2.isNullOrBlank()) {
            dictionary.recordWordPair(
                language = lang,
                prevWord = p2,
                nextWord = w,
                prevWord1 = p1,
                explicit = explicit
            )
        }
        prevContextWord1 = p2
        prevContextWord2 = w
    }

    private fun syncContextWordsFromEditor() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(72, 0)?.toString() ?: return
        val tokens = before.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        prevContextWord2 = tokens.lastOrNull()
        prevContextWord1 = if (tokens.size >= 2) tokens[tokens.size - 2] else null
    }

    /**
     * Inspects the word immediately preceding the cursor (before a space or punctuation is committed).
     * If the user typed a custom word via Hold & Slide, Multi-Tap, or manual editing, learns it with explicit priority!
     */
    private fun learnCompletedWordBeforeCursor(ic: InputConnection, explicit: Boolean) {
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return
        if (before.isEmpty() || before.last().isWhitespace()) return
        val lastToken = before.trimEnd().split(Regex("\\s+")).lastOrNull() ?: return
        val clean = lastToken.trim(',', '.', '!', '?', ';', ':', '¿', '¡', '"', '\'', '(', ')')
        if (clean.length >= 2 && T9KeyMap.wordToDigits(clean) != null) {
            val lang = (returnToT9AfterWord ?: lastT9LanguageMode).toLanguage()
            t9Engine.learnWord(lang, clean, explicit = explicit)
            val tokens = before.trimEnd().split(Regex("\\s+")).filter { it.isNotBlank() }
            val p2 = if (tokens.size >= 2) tokens[tokens.size - 2] else null
            val p1 = if (tokens.size >= 3) tokens[tokens.size - 3] else null
            if (!p2.isNullOrBlank()) {
                dictionary.recordWordPair(lang, p2, clean, p1, explicit = explicit)
            }
        }
    }

    private fun refreshMultiTapSuggestions(pendingChar: String) {
        if (inputMode != InputMode.MULTI_TAP) return
        val currentWord = buildString {
            append(multiTapWordBuffer)
            append(pendingChar)
        }
        if (currentWord.isEmpty()) {
            keyboardView?.showSuggestions(emptyList(), false, "")
            return
        }
        val matchingShortcuts = shortcutStore.findMatchesForWord(currentWord)
        val digits = T9KeyMap.wordToDigits(currentWord)
        val lang = (returnToT9AfterWord ?: lastT9LanguageMode).toLanguage()
        val dictMatches = if (!digits.isNullOrEmpty()) {
            dictionary.query(lang, digits, limit = 6, prevWord2 = prevContextWord2, prevWord1 = prevContextWord1)
                .filter { it.startsWith(currentWord.lowercase()) && !it.equals(currentWord, ignoreCase = true) }
                .map { caseMode.applyToWord(it) }
        } else {
            emptyList()
        }
        val suggestions = buildList {
            add(currentWord)
            addAll(dictMatches)
        }.distinct().take(6)

        keyboardView?.showSuggestions(
            suggestions = suggestions,
            hasPendingDigits = true,
            digits = "",
            calculatorResult = null,
            shortcuts = matchingShortcuts
        )
    }

    private fun setInputMode(mode: InputMode, persist: Boolean) {
        inputMode = mode
        if (mode == InputMode.T9_ES || mode == InputMode.T9_EN) {
            lastT9LanguageMode = mode
        }
        keyboardView?.updateState(inputMode, caseMode)
        if (persist && mode != InputMode.NUMERIC) {
            serviceScope.launch {
                preferences.updateInputMode(mode)
            }
        }
        refreshT9Suggestions()
    }

    private fun refreshT9Suggestions() {
        val ic = currentInputConnection
        val before = ic?.getTextBeforeCursor(60, 0)?.toString() ?: ""
        val mathResult = MathEvaluator.findMathExpression(before)

        if (inputMode != InputMode.T9_ES && inputMode != InputMode.T9_EN) {
            val matchingShortcuts = if (multiTapWordBuffer.isNotEmpty()) {
                shortcutStore.findMatchesForWord(multiTapWordBuffer.toString())
            } else emptyList()

            if (mathResult != null || matchingShortcuts.isNotEmpty()) {
                keyboardView?.showSuggestions(
                    suggestions = emptyList(),
                    hasPendingDigits = matchingShortcuts.isNotEmpty(),
                    digits = "",
                    calculatorResult = mathResult,
                    shortcuts = matchingShortcuts
                )
            } else {
                keyboardView?.showSuggestions(emptyList(), false, "")
            }
            return
        }

        if (t9Engine.isEmpty()) {
            val tokens = before.trimEnd().split(Regex("\\s+")).filter { it.isNotBlank() }
            val lastWord = tokens.lastOrNull() ?: ""
            val secondLastWord = if (tokens.size >= 2) tokens[tokens.size - 2] else null
            val nextWords = if (lastWord.isNotEmpty()) {
                NextWordPredictor.predict(
                    language = inputMode.toLanguage().code,
                    prevWord = lastWord,
                    userStore = dictionary.userStore,
                    maxCount = 5,
                    prevWord1 = secondLastWord
                )
            } else {
                emptyList()
            }

            keyboardView?.showSuggestions(
                suggestions = nextWords,
                hasPendingDigits = false,
                digits = "",
                calculatorResult = mathResult,
                shortcuts = emptyList()
            )
            return
        }

        val matchingShortcuts = shortcutStore.findMatchesForDigits(t9Engine.currentDigits)
        val suggestions = t9Engine.getSuggestions(
            language = inputMode.toLanguage(),
            caseMode = caseMode,
            prevWord2 = prevContextWord2,
            prevWord1 = prevContextWord1
        )
        val fallbackText = caseMode.applyToWord(T9KeyMap.digitsToDefaultLetters(t9Engine.currentDigits))
        val preview = suggestions.firstOrNull() ?: fallbackText
        ic?.setComposingText(preview, 1)
        keyboardView?.showSuggestions(
            suggestions = suggestions,
            hasPendingDigits = true,
            digits = t9Engine.currentDigits,
            calculatorResult = null,
            shortcuts = matchingShortcuts
        )
    }

    private fun commitT9Composing(
        ic: InputConnection,
        learn: Boolean,
        explicit: Boolean = false
    ): String? {
        if (t9Engine.isEmpty()) return null
        val lang = inputMode.toLanguage()
        val primary = t9Engine.getPrimarySuggestion(lang, caseMode, prevContextWord2, prevContextWord1)
        val fallbackText = caseMode.applyToWord(T9KeyMap.digitsToDefaultLetters(t9Engine.currentDigits))
        val toCommit = primary ?: fallbackText
        ic.commitText(toCommit, 1)
        if (primary != null) {
            recordWordInContext(lang, primary, explicit = explicit)
            if (learn) {
                t9Engine.learnWord(lang, primary, explicit = explicit)
            }
        } else {
            prevContextWord1 = prevContextWord2
            prevContextWord2 = toCommit
        }
        t9Engine.clear()
        if (caseMode == CaseMode.CAPITALIZE) {
            caseMode = CaseMode.LOWER
            keyboardView?.updateState(inputMode, caseMode)
        }
        return toCommit
    }

    private fun commitAllComposing() {
        val ic = currentInputConnection ?: return
        if (!t9Engine.isEmpty()) {
            commitT9Composing(ic, learn = true, explicit = false)
        }
        if (multiTapEngine.hasPending()) {
            multiTapEngine.commitPending()
        }
        keyboardView?.showSuggestions(emptyList(), false, "")
    }

    private fun flushMultiTapWordToDictionary() {
        if (multiTapWordBuffer.length >= 2) {
            val lang = (returnToT9AfterWord ?: lastT9LanguageMode).toLanguage()
            val newWord = multiTapWordBuffer.toString()
            t9Engine.learnWord(lang, newWord, explicit = true)
            recordWordInContext(lang, newWord, explicit = true)
        }
        multiTapWordBuffer.clear()
    }

    private fun applyAutoCapIfSentenceEnd(ic: InputConnection) {
        val inputType = currentInputEditorInfo?.inputType ?: 0
        if ((inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) == 0) return
        val before = ic.getTextBeforeCursor(4, 0)?.toString()?.trimEnd() ?: return
        if (before.isEmpty() || before.endsWith(".") || before.endsWith("!") || before.endsWith("?")) {
            if (caseMode == CaseMode.LOWER) {
                caseMode = CaseMode.CAPITALIZE
                keyboardView?.updateState(inputMode, caseMode)
            }
        }
    }
}
