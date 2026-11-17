package co.com.onecontrol.mykeyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import co.com.onecontrol.mykeyboard.R
import co.com.onecontrol.mykeyboard.dictionary.T9KeyMap
import co.com.onecontrol.mykeyboard.engine.CaseMode
import co.com.onecontrol.mykeyboard.engine.InputMode
import co.com.onecontrol.mykeyboard.ime.KeyboardConfig
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
class T9KeyboardView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onDigitKey(digit: Char)
        fun onStarKey()
        fun onStarLongPress()
        fun onZeroKey()
        fun onZeroLongPress()
        fun onHashKey()
        fun onBackspace()
        fun onDeleteWord()
        fun onEnterKey()
        fun onQuickLanguageToggle()
        fun onSuggestionSelected(word: String)
        fun onFallbackMultiTapRequested()
        fun onSymbolSelected(symbol: String)
        fun onSwipeDeleteWord()
        fun onSwipeSpace()
        fun onMoveCursor(delta: Int)
        fun onResizeToggleRequested()
        fun onSwitchImeRequested()
        fun onShowImePickerRequested()
        fun onClipboardRequested()
        fun onGeneratePasswordRequested()
        fun onGifSelected(assetName: String, label: String)
    }

    var listener: Listener? = null

    private var hapticStrength: Int = KeyboardConfig.DEFAULT_HAPTIC_STRENGTH
    private var keyHeightDp: Int = KeyboardConfig.DEFAULT_KEY_HEIGHT_DP

    private val mainLayout: LinearLayout
    private val topBar: LinearLayout
    private val body: LinearLayout
    private val emojiMediaPanel: co.com.onecontrol.mykeyboard.ui.emoji.EmojiMediaPanelView
    private val suggestionContainer: LinearLayout
    private val suggestionScroll: HorizontalScrollView
    private val statusBadge: TextView
    private val clipboardButton: TextView
    private val emojiButton: TextView
    private val switchImeButton: TextView
    private val resizeButton: TextView
    private val enterButton: TextView
    private val langButton: TextView
    private val backspaceButton: TextView

    // Hold & Slide popup for # (Symbols)
    private val symbolPopupOverlay: LinearLayout
    private val symbolPopupChips = mutableListOf<TextView>()
    private var popupSelectedIndex = 0

    private val keyViews = mutableListOf<View>()
    private val actionButtons = mutableListOf<View>()
    private val keypadRows = mutableListOf<LinearLayout>()

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var isBackspacePressed = false

    // Backspace: tap = 1 char, hold > 280ms = delete whole word, then repeat whole words with strong haptics
    private val wordDeleteRepeater = object : Runnable {
        override fun run() {
            if (!isBackspacePressed) return
            performStrongHaptic()
            listener?.onDeleteWord()
            repeatHandler.postDelayed(this, 230L)
        }
    }

    private val swipeDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > dpToPx(56f) && abs(dx) > abs(dy) * 1.6f && abs(velocityX) > 250f) {
                performHaptic()
                if (dx < 0) {
                    listener?.onSwipeDeleteWord()
                } else {
                    listener?.onSwipeSpace()
                }
                return true
            }
            return false
        }
    })

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(12f))
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Top Suggestion + Control Bar
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(2f), 0, dpToPx(2f), dpToPx(5f))
        }

        suggestionScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(38f), 1f)
        }
        suggestionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        suggestionScroll.addView(suggestionContainer)
        topBar.addView(suggestionScroll)

        // Clipboard history button
        clipboardButton = createTopChip("📋").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                performHaptic()
                listener?.onClipboardRequested()
            }
        }
        topBar.addView(clipboardButton, marginEndParams(4))

        // Direct Emoji button
        emojiButton = createTopChip("😊").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                performHaptic()
                showEmojiPanel()
            }
        }
        topBar.addView(emojiButton, marginEndParams(4))

        // Unified compact Mode / Case badge
        statusBadge = createTopChip("T9 ES", accent = true).apply {
            setOnClickListener {
                performHaptic()
                listener?.onStarLongPress()
            }
        }
        topBar.addView(statusBadge, marginEndParams(4))

        // Switch to SwiftKey button
        val isSwiftKey = try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.any { it.packageName.contains("swiftkey", ignoreCase = true) } == true
        } catch (_: Exception) {
            false
        }
        val imeLabel = if (isSwiftKey) "⌨ SwiftKey" else "⌨ Cambiar"

        switchImeButton = createTopChip(imeLabel).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnClickListener {
                performHaptic()
                listener?.onShowImePickerRequested()
            }
            setOnLongClickListener {
                performHaptic()
                listener?.onShowImePickerRequested()
                true
            }
        }
        topBar.addView(switchImeButton, marginEndParams(4))

        // Resize overlay trigger
        resizeButton = createTopChip("⤢").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener {
                performHaptic()
                listener?.onResizeToggleRequested()
            }
        }
        topBar.addView(resizeButton)

        mainLayout.addView(topBar)

        // 2. Main Body: Left 3x4 numeric grid + Right action column
        body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val leftGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        }

        val rowsSpec = listOf(
            listOf('1' to ".,?!@", '2' to "ABC", '3' to "DEF"),
            listOf('4' to "GHI", '5' to "JKL", '6' to "MNOÑ"),
            listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ")
        )

        rowsSpec.forEach { rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(keyHeightDp.toFloat())
                ).apply {
                    bottomMargin = dpToPx(5f)
                }
            }
            rowItems.forEach { (digit, sub) ->
                val key = createNumericKey(digit.toString(), sub, onClick = {
                    performHaptic()
                    listener?.onDigitKey(digit)
                })
                keyViews.add(key)
                row.addView(key, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = dpToPx(5f)
                })
            }
            keypadRows.add(row)
            leftGrid.addView(row)
        }

        // Bottom row: *, 0, #
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(keyHeightDp.toFloat())
            )
        }

        // Key *: Dedicated to Case & Mode toggle
        val starKey = createNumericKey("*", "Aa / Modo", onClick = {
            performHaptic()
            listener?.onStarKey()
        }, onLongClick = {
            performHaptic()
            listener?.onStarLongPress()
            true
        })
        keyViews.add(starKey)
        bottomRow.addView(starKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })

        // Key 0: Space + Cursor trackpad slider
        val zeroKey = createNumericKey("0", "ESPACIO")
        setupZeroCursorTrackpad(zeroKey)
        keyViews.add(zeroKey)
        bottomRow.addView(zeroKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })

        // Key #: Dedicated to Symbols with Hold & Slide Selector!
        val hashKey = createNumericKey("#", "SÍM / @")
        setupHashSlideToSelect(hashKey)
        keyViews.add(hashKey)
        bottomRow.addView(hashKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })

        keypadRows.add(bottomRow)
        leftGrid.addView(bottomRow)
        body.addView(leftGrid)

        // Right Action Column
        val rightCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        // Backspace: Tap deletes char; Hold > 280ms deletes whole word and repeats word by word with strong haptic pulse!
        backspaceButton = createActionKey("⌫").apply {
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        isBackspacePressed = true
                        performHaptic()
                        listener?.onBackspace()
                        repeatHandler.removeCallbacks(wordDeleteRepeater)
                        repeatHandler.postDelayed(wordDeleteRepeater, 280L)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        isBackspacePressed = false
                        repeatHandler.removeCallbacks(wordDeleteRepeater)
                        true
                    }
                    else -> false
                }
            }
        }
        actionButtons.add(backspaceButton)
        rightCol.addView(backspaceButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            bottomMargin = dpToPx(5f)
        })

        langButton = createActionKey("ES / EN").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnClickListener {
                performHaptic()
                listener?.onQuickLanguageToggle()
            }
        }
        actionButtons.add(langButton)
        rightCol.addView(langButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            bottomMargin = dpToPx(5f)
        })

        enterButton = createActionKey("↵", accent = true).apply {
            setOnClickListener {
                performHaptic()
                listener?.onEnterKey()
            }
        }
        actionButtons.add(enterButton)
        rightCol.addView(enterButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f
        ))

        body.addView(rightCol)
        mainLayout.addView(body)

        emojiMediaPanel = co.com.onecontrol.mykeyboard.ui.emoji.EmojiMediaPanelView(context).apply {
            visibility = View.GONE
            listener = object : co.com.onecontrol.mykeyboard.ui.emoji.EmojiMediaPanelView.Listener {
                override fun onEmojiSelected(emoji: String) {
                    this@T9KeyboardView.listener?.onSymbolSelected(emoji)
                }

                override fun onKaomojiSelected(kaomoji: String) {
                    this@T9KeyboardView.listener?.onSymbolSelected(kaomoji)
                }

                override fun onGifSelected(assetName: String, label: String) {
                    this@T9KeyboardView.listener?.onGifSelected(assetName, label)
                }

                override fun onCloseEmojiPanel() {
                    hideEmojiPanel()
                }

                override fun onBackspace() {
                    this@T9KeyboardView.listener?.onBackspace()
                }

                override fun onSpace() {
                    this@T9KeyboardView.listener?.onZeroKey()
                }
            }
        }
        mainLayout.addView(emojiMediaPanel)
        addView(mainLayout)

        // 3. Hold & Slide Symbol Popup Overlay for # (Floating Bar)
        symbolPopupOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            elevation = dpToPx(16f).toFloat()
            setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(6f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE161A22"))
                cornerRadius = dpToPx(14f).toFloat()
                setStroke(dpToPx(1.5f), ContextCompat.getColor(context, R.color.kb_accent))
            }
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dpToPx(60f)
            }
        }

        T9KeyMap.POPUP_SYMBOLS.forEachIndexed { index, sym ->
            val tv = TextView(context).apply {
                text = sym
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sym == "😊") 18f else 15f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dpToPx(8f), dpToPx(6f), dpToPx(8f), dpToPx(6f))
                background = GradientDrawable().apply {
                    setColor(if (index == 0) ContextCompat.getColor(context, R.color.kb_accent) else Color.TRANSPARENT)
                    cornerRadius = dpToPx(8f).toFloat()
                }
            }
            symbolPopupChips.add(tv)
            symbolPopupOverlay.addView(tv)
        }
        addView(symbolPopupOverlay)
    }

    // --- Key # (Symbols): Tap = symbols strip, Hold & Slide = symbol popup ---
    private fun setupHashSlideToSelect(hashKey: View) {
        var isLongPressActive = false
        val longPressRunnable = Runnable {
            isLongPressActive = true
            performHaptic()
            showSymbolPopupOverlay()
        }

        hashKey.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressActive = false
                    hashKey.isPressed = true
                    repeatHandler.postDelayed(longPressRunnable, 220L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isLongPressActive) {
                        updateSymbolPopupSelection(event.rawX)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hashKey.isPressed = false
                    repeatHandler.removeCallbacks(longPressRunnable)
                    if (isLongPressActive) {
                        val selected = commitSymbolPopupSelection()
                        if (selected == "😊") {
                            performHaptic()
                            showEmojiPanel()
                        } else if (selected != null) {
                            performHaptic()
                            listener?.onSymbolSelected(selected)
                        }
                    } else {
                        performHaptic()
                        showSymbolStrip()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hashKey.isPressed = false
                    repeatHandler.removeCallbacks(longPressRunnable)
                    hideSymbolPopupOverlay()
                    true
                }
                else -> false
            }
        }
    }

    // --- Key 0 (Space + Cursor Trackpad Slider) ---
    private fun setupZeroCursorTrackpad(zeroKey: View) {
        var downX = 0f
        var lastX = 0f
        var isDraggingCursor = false
        var isLongPress = false

        val longPressRunnable = Runnable {
            isLongPress = true
            performHaptic()
            listener?.onZeroLongPress()
        }

        zeroKey.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    lastX = event.x
                    isDraggingCursor = false
                    isLongPress = false
                    zeroKey.isPressed = true
                    repeatHandler.postDelayed(longPressRunnable, 400L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.x - downX
                    if (abs(totalDx) > dpToPx(16f)) {
                        repeatHandler.removeCallbacks(longPressRunnable)
                        isDraggingCursor = true
                        val stepDx = event.x - lastX
                        val stepThreshold = dpToPx(14f)
                        if (abs(stepDx) >= stepThreshold) {
                            val delta = if (stepDx > 0) 1 else -1
                            listener?.onMoveCursor(delta)
                            performHaptic()
                            lastX = event.x
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    zeroKey.isPressed = false
                    repeatHandler.removeCallbacks(longPressRunnable)
                    if (!isDraggingCursor && !isLongPress) {
                        performHaptic()
                        listener?.onZeroKey()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    zeroKey.isPressed = false
                    repeatHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun showSymbolPopupOverlay() {
        popupSelectedIndex = 0
        updateChipHighlights()
        symbolPopupOverlay.visibility = View.VISIBLE
    }

    private fun hideSymbolPopupOverlay() {
        symbolPopupOverlay.visibility = View.GONE
    }

    private fun updateSymbolPopupSelection(rawX: Float) {
        if (symbolPopupChips.isEmpty()) return
        var bestIndex = 0
        var minDistance = Float.MAX_VALUE

        val loc = IntArray(2)
        symbolPopupChips.forEachIndexed { i, chip ->
            chip.getLocationOnScreen(loc)
            val chipCenterX = loc[0] + chip.width / 2f
            val dist = abs(rawX - chipCenterX)
            if (dist < minDistance) {
                minDistance = dist
                bestIndex = i
            }
        }

        if (bestIndex != popupSelectedIndex) {
            popupSelectedIndex = bestIndex
            performHaptic()
            updateChipHighlights()
        }
    }

    private fun updateChipHighlights() {
        symbolPopupChips.forEachIndexed { i, chip ->
            val bg = chip.background as? GradientDrawable ?: return@forEachIndexed
            if (i == popupSelectedIndex) {
                bg.setColor(ContextCompat.getColor(context, R.color.kb_accent))
                chip.setTextColor(Color.WHITE)
            } else {
                bg.setColor(Color.TRANSPARENT)
                chip.setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            }
        }
    }

    private fun commitSymbolPopupSelection(): String? {
        hideSymbolPopupOverlay()
        return symbolPopupChips.getOrNull(popupSelectedIndex)?.text?.toString()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (swipeDetector.onTouchEvent(ev)) {
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (swipeDetector.onTouchEvent(event)) {
            return true
        }
        return super.onTouchEvent(event)
    }

    fun setHapticStrength(strength: Int) {
        hapticStrength = strength.coerceIn(0, 100)
    }

    fun setKeyHeightDp(heightDp: Int) {
        keyHeightDp = heightDp.coerceIn(
            KeyboardConfig.MIN_KEY_HEIGHT_DP,
            KeyboardConfig.MAX_KEY_HEIGHT_DP
        )
        val heightPx = dpToPx(keyHeightDp.toFloat())
        keypadRows.forEach { row ->
            val lp = row.layoutParams as LinearLayout.LayoutParams
            if (lp.height != heightPx) {
                lp.height = heightPx
                row.layoutParams = lp
            }
        }
        val targetPanelScrollHeight = (heightPx * 4 - dpToPx(36f)).coerceAtLeast(dpToPx(160f))
        emojiMediaPanel.setPanelContentHeight(targetPanelScrollHeight)
        requestLayout()
    }

    fun showEmojiPanel() {
        topBar.visibility = View.GONE
        body.visibility = View.GONE
        emojiMediaPanel.visibility = View.VISIBLE
    }

    fun hideEmojiPanel() {
        emojiMediaPanel.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        body.visibility = View.VISIBLE
    }

    fun isEmojiPanelVisible(): Boolean = emojiMediaPanel.visibility == View.VISIBLE

    fun updateState(mode: InputMode, caseMode: CaseMode) {
        statusBadge.text = "${mode.label} · ${caseMode.label}"
        langButton.text = when (mode) {
            InputMode.T9_ES -> "ES→EN"
            InputMode.T9_EN -> "EN→ES"
            InputMode.MULTI_TAP -> "ABC"
            InputMode.NUMERIC -> "123"
        }
    }

    fun updateImeAction(imeOptions: Int) {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        enterButton.text = when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_GO -> "Ir"
            EditorInfo.IME_ACTION_NEXT -> "Sig"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "↵"
        }
    }

    fun showSuggestions(
        suggestions: List<String>,
        hasPendingDigits: Boolean,
        digits: String
    ) {
        suggestionContainer.removeAllViews()
        if (suggestions.isNotEmpty()) {
            suggestions.forEachIndexed { index, word ->
                val chip = createSuggestionChip(word, primary = index == 0) {
                    performHaptic()
                    listener?.onSuggestionSelected(word)
                }
                suggestionContainer.addView(chip, marginEndParams(6))
            }
        } else if (hasPendingDigits) {
            val fallbackChip = createSuggestionChip("➕ Deletrear palabra (ABC)", primary = true) {
                performHaptic()
                listener?.onFallbackMultiTapRequested()
            }
            suggestionContainer.addView(fallbackChip, marginEndParams(6))
        } else {
            val hint = TextView(context).apply {
                text = "T9 · Mantén # para símbolos @"
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setPadding(dpToPx(6f), 0, dpToPx(6f), 0)
                includeFontPadding = false
                maxLines = 1
                isSingleLine = true
            }
            suggestionContainer.addView(hint)
        }
        suggestionScroll.scrollTo(0, 0)
    }

    fun showEmailDomainStrip() {
        suggestionContainer.removeAllViews()
        T9KeyMap.EMAIL_DOMAINS.forEach { domain ->
            val chip = createSuggestionChip(domain, primary = true) {
                performHaptic()
                listener?.onSymbolSelected(domain)
            }
            suggestionContainer.addView(chip, marginEndParams(6))
        }
        suggestionScroll.scrollTo(0, 0)
    }

    fun showPasswordHelpStrip() {
        suggestionContainer.removeAllViews()
        val chip = createSuggestionChip("🔑 Generar contraseña segura", primary = true) {
            performStrongHaptic()
            listener?.onGeneratePasswordRequested()
        }
        suggestionContainer.addView(chip, marginEndParams(6))
        suggestionScroll.scrollTo(0, 0)
    }

    fun showUrlShortcutsStrip() {
        suggestionContainer.removeAllViews()
        T9KeyMap.URL_SHORTCUTS.forEach { shortcut ->
            val chip = createSuggestionChip(shortcut, primary = false) {
                performHaptic()
                listener?.onSymbolSelected(shortcut)
            }
            suggestionContainer.addView(chip, marginEndParams(5))
        }
        suggestionScroll.scrollTo(0, 0)
    }

    fun showClipboardStrip(items: List<String>) {
        suggestionContainer.removeAllViews()
        if (items.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "Portapapeles vacío"
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dpToPx(8f), 0, dpToPx(8f), 0)
            }
            suggestionContainer.addView(emptyTv)
        } else {
            items.forEach { item ->
                val preview = if (item.length > 18) item.take(16) + "…" else item
                val chip = createSuggestionChip(preview, primary = false) {
                    performHaptic()
                    listener?.onSymbolSelected(item)
                }
                suggestionContainer.addView(chip, marginEndParams(6))
            }
        }
        suggestionScroll.scrollTo(0, 0)
    }

    fun showSymbolStrip() {
        suggestionContainer.removeAllViews()
        T9KeyMap.FREQUENT_SYMBOLS.forEach { sym ->
            val chip = createSuggestionChip(sym, primary = false) {
                performHaptic()
                listener?.onSymbolSelected(sym)
            }
            suggestionContainer.addView(chip, marginEndParams(5))
        }
        suggestionScroll.scrollTo(0, 0)
    }

    // Emojis grandes (24sp) y cómodos de pulsar
    fun showEmojiStrip() {
        suggestionContainer.removeAllViews()
        T9KeyMap.POPULAR_EMOJIS.forEach { emo ->
            val chip = TextView(context).apply {
                text = emo
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dpToPx(10f), dpToPx(4f), dpToPx(10f), dpToPx(4f))
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.kb_key_bg))
                    cornerRadius = dpToPx(12f).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    performHaptic()
                    listener?.onSymbolSelected(emo)
                }
            }
            suggestionContainer.addView(chip, marginEndParams(6))
        }
        suggestionScroll.scrollTo(0, 0)
    }

    private fun performHaptic() {
        if (hapticStrength <= 0) return
        val constant = if (hapticStrength >= 65) {
            HapticFeedbackConstants.VIRTUAL_KEY
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        @Suppress("DEPRECATION")
        performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    private fun performStrongHaptic() {
        if (hapticStrength <= 0) return
        @Suppress("DEPRECATION")
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    private fun createNumericKey(
        mainLabel: String,
        subLabel: String,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Boolean)? = null
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(2f), 0, dpToPx(2f))
            background = createKeyDrawable(
                normalColor = ContextCompat.getColor(context, R.color.kb_key_bg),
                pressedColor = ContextCompat.getColor(context, R.color.kb_key_pressed)
            )
            isClickable = true
            isFocusable = true

            val mainTv = TextView(context).apply {
                text = mainLabel
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val subTv = TextView(context).apply {
                text = subLabel
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
                maxLines = 1
                isSingleLine = true
                gravity = Gravity.CENTER
                includeFontPadding = false
                letterSpacing = 0.04f
            }
            addView(mainTv)
            addView(subTv)

            if (onClick != null) {
                setOnClickListener { onClick() }
            }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick() }
            }
        }
    }

    private fun createActionKey(label: String, accent: Boolean = false): TextView {
        val normalColor = if (accent) {
            ContextCompat.getColor(context, R.color.kb_accent_soft)
        } else {
            ContextCompat.getColor(context, R.color.kb_action_bg)
        }
        return TextView(context).apply {
            text = label
            setTextColor(
                if (accent) ContextCompat.getColor(context, R.color.kb_accent)
                else ContextCompat.getColor(context, R.color.kb_text_primary)
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            isSingleLine = true
            background = createKeyDrawable(
                normalColor = normalColor,
                pressedColor = ContextCompat.getColor(context, R.color.kb_key_pressed)
            )
            isClickable = true
            isFocusable = true
        }
    }

    private fun createTopChip(text: String, accent: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(
                if (accent) ContextCompat.getColor(context, R.color.kb_accent)
                else ContextCompat.getColor(context, R.color.kb_text_primary)
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            isSingleLine = true
            setPadding(dpToPx(7f), dpToPx(4f), dpToPx(7f), dpToPx(4f))
            background = GradientDrawable().apply {
                setColor(
                    if (accent) ContextCompat.getColor(context, R.color.kb_accent_soft)
                    else ContextCompat.getColor(context, R.color.kb_surface)
                )
                cornerRadius = dpToPx(9f).toFloat()
            }
        }
    }

    private fun createSuggestionChip(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(
                if (primary) Color.WHITE
                else ContextCompat.getColor(context, R.color.kb_text_primary)
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            if (primary) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            isSingleLine = true
            setPadding(dpToPx(10f), dpToPx(4f), dpToPx(10f), dpToPx(4f))
            background = GradientDrawable().apply {
                setColor(
                    if (primary) ContextCompat.getColor(context, R.color.kb_accent_soft)
                    else ContextCompat.getColor(context, R.color.kb_key_bg)
                )
                cornerRadius = dpToPx(10f).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createKeyDrawable(normalColor: Int, pressedColor: Int): StateListDrawable {
        val radius = dpToPx(10f).toFloat()
        val normal = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = radius
        }
        val pressed = GradientDrawable().apply {
            setColor(pressedColor)
            cornerRadius = radius
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun marginEndParams(dp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dpToPx(dp.toFloat())
        }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).roundToInt()
}
