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

    private var container: ResizableKeyboardContainer? = null
    private var keyboardView: T9KeyboardView? = null

    private var currentConfig = KeyboardConfig()
    private var inputMode = InputMode.T9_ES
    private var caseMode = CaseMode.CAPITALIZE
    private var lastT9LanguageMode = InputMode.T9_ES
    private var returnToT9AfterWord: InputMode? = null
    private var lastCommittedWord: String? = null
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
        lastCommittedWord = null
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
                    val primary = t9Engine.getPrimarySuggestion(inputMode.toLanguage(), caseMode)
                    if (primary != null) {
                        t9Engine.learnWord(inputMode.toLanguage(), primary)
                    }
                    t9Engine.clear()
                }
                if (multiTapEngine.hasPending()) {
                    multiTapEngine.reset()
                }
                multiTapWordBuffer.clear()
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

    override fun onTogglePredictiveMode() {
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
        commitAllComposing()
        returnToT9AfterWord = null
        setInputMode(mode, persist = true)
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
        if (lastCommittedWord != null) {
            dictionary.recordWordPair(inputMode.toLanguage(), lastCommittedWord!!, word)
        }
        lastCommittedWord = word
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

    override fun onDeleteWord() {
        onSwipeDeleteWord()
    }

    override fun onMoveCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        if (!t9Engine.isEmpty() || multiTapEngine.hasPending()) {
            ic.finishComposingText()
            if (!t9Engine.isEmpty()) {
                val primary = t9Engine.getPrimarySuggestion(inputMode.toLanguage(), caseMode)
                if (primary != null) {
                    t9Engine.learnWord(inputMode.toLanguage(), primary)
                    if (lastCommittedWord != null) {
                        dictionary.recordWordPair(inputMode.toLanguage(), lastCommittedWord!!, primary)
                    }
                    lastCommittedWord = primary
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
        if (inputMode != InputMode.T9_ES && inputMode != InputMode.T9_EN) return
        if (digits.length < 2) return
        commitT9Composing(ic, learn = true)
        t9Engine.clear()
        for (ch in digits) {
            t9Engine.appendDigit(ch)
        }
        refreshT9Suggestions()
    }

    override fun onCalculatorSelected(expression: String, result: String) {
        val ic = currentInputConnection ?: return
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

    override fun onGeneratePasswordRequested() {
        val ic = currentInputConnection ?: return
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
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    override fun onShowImePickerRequested() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
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
            if (mathResult != null) {
                keyboardView?.showSuggestions(
                    suggestions = emptyList(),
                    hasPendingDigits = false,
                    digits = "",
                    calculatorResult = mathResult
                )
            } else {
                keyboardView?.showSuggestions(emptyList(), false, "")
            }
            return
        }

        if (t9Engine.isEmpty()) {
            val lastWord = before.trimEnd().split(Regex("\\s+")).lastOrNull() ?: ""
            val nextWords = if (lastWord.isNotEmpty()) {
                NextWordPredictor.predict(
                    language = inputMode.toLanguage().code,
                    prevWord = lastWord,
                    userStore = dictionary.userStore
                )
            } else {
                emptyList()
            }

            keyboardView?.showSuggestions(
                suggestions = nextWords,
                hasPendingDigits = false,
                digits = "",
                calculatorResult = mathResult
            )
            return
        }

        val suggestions = t9Engine.getSuggestions(inputMode.toLanguage(), caseMode)
        val fallbackText = caseMode.applyToWord(T9KeyMap.digitsToDefaultLetters(t9Engine.currentDigits))
        val preview = suggestions.firstOrNull() ?: fallbackText
        ic?.setComposingText(preview, 1)
        keyboardView?.showSuggestions(
            suggestions = suggestions,
            hasPendingDigits = true,
            digits = t9Engine.currentDigits,
            calculatorResult = null
        )
    }

    private fun commitT9Composing(ic: InputConnection, learn: Boolean) {
        if (t9Engine.isEmpty()) return
        val primary = t9Engine.getPrimarySuggestion(inputMode.toLanguage(), caseMode)
        val fallbackText = caseMode.applyToWord(T9KeyMap.digitsToDefaultLetters(t9Engine.currentDigits))
        val toCommit = primary ?: fallbackText
        ic.commitText(toCommit, 1)
        if (lastCommittedWord != null && primary != null) {
            dictionary.recordWordPair(inputMode.toLanguage(), lastCommittedWord!!, primary)
        }
        lastCommittedWord = primary ?: toCommit
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
