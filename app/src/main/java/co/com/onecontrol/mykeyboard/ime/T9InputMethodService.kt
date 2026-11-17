package co.com.onecontrol.mykeyboard.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import co.com.onecontrol.mykeyboard.dictionary.T9DictionaryTrie
import co.com.onecontrol.mykeyboard.engine.CaseMode
import co.com.onecontrol.mykeyboard.engine.InputMode
import co.com.onecontrol.mykeyboard.engine.MultiTapEngine
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

    private var container: ResizableKeyboardContainer? = null
    private var keyboardView: T9KeyboardView? = null

    private var currentConfig = KeyboardConfig()
    private var inputMode = InputMode.T9_ES
    private var caseMode = CaseMode.CAPITALIZE
    private var lastT9LanguageMode = InputMode.T9_ES
    private var returnToT9AfterWord: InputMode? = null
    private val multiTapWordBuffer = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        preferences = KeyboardPreferences(applicationContext)
        dictionary = T9DictionaryTrie(applicationContext)
        dictionary.loadAsync {
            refreshT9Suggestions()
        }
        t9Engine = T9PredictiveEngine(dictionary)
        multiTapEngine = MultiTapEngine(
            onComposingChanged = { text ->
                currentInputConnection?.setComposingText(text, 1)
            },
            onCommitChar = { ch ->
                currentInputConnection?.commitText(ch, 1)
                if (ch.length == 1 && ch[0].isLetter()) {
                    multiTapWordBuffer.append(ch[0])
                } else {
                    flushMultiTapWordToDictionary()
                }
                if (caseMode == CaseMode.CAPITALIZE) {
                    caseMode = CaseMode.LOWER
                    keyboardView?.updateState(inputMode, caseMode)
                }
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

        val inputType = info?.inputType ?: 0
        val variation = inputType and InputType.TYPE_MASK_CLASS
        if (variation == InputType.TYPE_CLASS_NUMBER || variation == InputType.TYPE_CLASS_PHONE) {
            inputMode = InputMode.NUMERIC
        } else {
            inputMode = currentConfig.inputMode
        }

        val capsFlags = inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        caseMode = when {
            (capsFlags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0 -> CaseMode.UPPER
            (capsFlags and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS)) != 0 -> CaseMode.CAPITALIZE
            else -> CaseMode.LOWER
        }

        keyboardView?.updateImeAction(info?.imeOptions ?: EditorInfo.IME_ACTION_NONE)
        keyboardView?.updateState(inputMode, caseMode)
        keyboardView?.showSuggestions(emptyList(), false, "")
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
        when (inputMode) {
            InputMode.NUMERIC -> {
                ic.commitText(digit.toString(), 1)
            }
            InputMode.MULTI_TAP -> {
                multiTapEngine.onKeyTap(digit, caseMode)
            }
            InputMode.T9_ES, InputMode.T9_EN -> {
                if (digit == '1') {
                    commitT9Composing(ic, learn = true)
                    multiTapEngine.onKeyTap('1', caseMode)
                } else {
                    if (multiTapEngine.hasPending()) {
                        multiTapEngine.commitPending()
                    }
                    t9Engine.appendDigit(digit)
                    refreshT9Suggestions()
                }
            }
        }
    }

    override fun onStarKey() {
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
        if (inputMode == InputMode.NUMERIC) {
            currentInputConnection?.commitText("*", 1)
            return
        }
        keyboardView?.showSymbolStrip()
    }

    override fun onZeroKey() {
        val ic = currentInputConnection ?: return
        when (inputMode) {
            InputMode.NUMERIC -> ic.commitText("0", 1)
            InputMode.MULTI_TAP -> {
                multiTapEngine.commitPending()
                flushMultiTapWordToDictionary()
                ic.commitText(" ", 1)
                if (returnToT9AfterWord != null) {
                    val target = returnToT9AfterWord!!
                    returnToT9AfterWord = null
                    setInputMode(target, persist = false)
                }
                applyAutoCapIfSentenceEnd(ic)
            }
            InputMode.T9_ES, InputMode.T9_EN -> {
                if (!t9Engine.isEmpty()) {
                    commitT9Composing(ic, learn = true)
                } else if (multiTapEngine.hasPending()) {
                    multiTapEngine.commitPending()
                }
                ic.commitText(" ", 1)
                applyAutoCapIfSentenceEnd(ic)
                refreshT9Suggestions()
            }
        }
    }

    override fun onZeroLongPress() {
        commitAllComposing()
        currentInputConnection?.commitText("0", 1)
    }

    override fun onHashKey() {
        commitAllComposing()
        returnToT9AfterWord = null
        val next = inputMode.next()
        setInputMode(next, persist = true)
    }

    override fun onQuickLanguageToggle() {
        commitAllComposing()
        returnToT9AfterWord = null
        val next = when (inputMode) {
            InputMode.T9_ES -> InputMode.T9_EN
            InputMode.T9_EN -> InputMode.T9_ES
            else -> lastT9LanguageMode
        }
        setInputMode(next, persist = true)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if ((inputMode == InputMode.T9_ES || inputMode == InputMode.T9_EN) && !t9Engine.isEmpty()) {
            t9Engine.backspaceDigit()
            if (t9Engine.isEmpty()) {
                ic.setComposingText("", 1)
            }
            refreshT9Suggestions()
            return
        }
        if (multiTapEngine.cancelPending()) {
            return
        }
        if (multiTapWordBuffer.isNotEmpty()) {
            multiTapWordBuffer.deleteCharAt(multiTapWordBuffer.length - 1)
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    override fun onEnterKey() {
        val ic = currentInputConnection ?: return
        commitAllComposing()
        flushMultiTapWordToDictionary()

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
        ic.commitText("$word ", 1)
        t9Engine.learnWord(inputMode.toLanguage(), word)
        t9Engine.clear()
        applyAutoCapIfSentenceEnd(ic)
        refreshT9Suggestions()
    }

    override fun onFallbackMultiTapRequested() {
        val ic = currentInputConnection ?: return
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
        commitAllComposing()
        ic.commitText(symbol, 1)
        refreshT9Suggestions()
    }

    override fun onSwipeDeleteWord() {
        val ic = currentInputConnection ?: return
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
    }

    override fun onSwipeSpace() {
        onZeroKey()
    }

    override fun onResizeToggleRequested() {
        val c = container ?: return
        c.setResizeMode(!c.isInResizeMode())
    }

    // --- Helpers ---

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
        if (inputMode != InputMode.T9_ES && inputMode != InputMode.T9_EN) {
            keyboardView?.showSuggestions(emptyList(), false, "")
            return
        }
        if (t9Engine.isEmpty()) {
            keyboardView?.showSuggestions(emptyList(), false, "")
            return
        }
        val suggestions = t9Engine.getSuggestions(inputMode.toLanguage(), caseMode)
        val preview = suggestions.firstOrNull() ?: t9Engine.currentDigits
        ic?.setComposingText(preview, 1)
        keyboardView?.showSuggestions(
            suggestions = suggestions,
            hasPendingDigits = true,
            digits = t9Engine.currentDigits
        )
    }

    private fun commitT9Composing(ic: InputConnection, learn: Boolean) {
        if (t9Engine.isEmpty()) return
        val primary = t9Engine.getPrimarySuggestion(inputMode.toLanguage(), caseMode)
        val toCommit = primary ?: t9Engine.currentDigits
        ic.commitText(toCommit, 1)
        if (learn && primary != null) {
            t9Engine.learnWord(inputMode.toLanguage(), primary)
        }
        t9Engine.clear()
        if (caseMode == CaseMode.CAPITALIZE) {
            caseMode = CaseMode.LOWER
            keyboardView?.updateState(inputMode, caseMode)
        }
    }

    private fun commitAllComposing() {
        val ic = currentInputConnection ?: return
        if (!t9Engine.isEmpty()) {
            commitT9Composing(ic, learn = true)
        }
        if (multiTapEngine.hasPending()) {
            multiTapEngine.commitPending()
        }
        keyboardView?.showSuggestions(emptyList(), false, "")
    }

    private fun flushMultiTapWordToDictionary() {
        if (multiTapWordBuffer.length >= 2) {
            val lang = (returnToT9AfterWord ?: lastT9LanguageMode).toLanguage()
            t9Engine.learnWord(lang, multiTapWordBuffer.toString())
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
