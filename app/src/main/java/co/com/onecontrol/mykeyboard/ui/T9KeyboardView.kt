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
import android.widget.ScrollView
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
        fun onTogglePredictiveMode()
        fun onModeSelected(mode: InputMode)
        fun onSwipeType(digits: String)
        fun onCalculatorSelected(expression: String, result: String)
        fun onShortcutSelected(expansion: String)
        fun onShortcutsRequested()
        fun onDirectCommit(text: String)
    }

    var listener: Listener? = null

    private var hapticStrength: Int = KeyboardConfig.DEFAULT_HAPTIC_STRENGTH
    private var keyHeightDp: Int = KeyboardConfig.DEFAULT_KEY_HEIGHT_DP
    private var currentInputMode: InputMode = InputMode.T9_ES
    private var currentCaseMode: CaseMode = CaseMode.CAPITALIZE
    private var activePopup: android.widget.PopupWindow? = null
    private var isCalculatorActive = false
    private var calcExpression = ""
    private var calcCurrentResult = ""
    private lateinit var calcKeypadPanel: LinearLayout
    private val calcKeypadRows = mutableListOf<LinearLayout>()
    private lateinit var calcButton: TextView

    private val mainLayout: LinearLayout
    private val topBar: LinearLayout
    private val body: LinearLayout
    private val emojiMediaPanel: co.com.onecontrol.mykeyboard.ui.emoji.EmojiMediaPanelView
    private val candidateGridPanel: LinearLayout
    private val candidateGridScroll: ScrollView
    private val candidateGridRowsContainer: LinearLayout
    private val suggestionContainer: LinearLayout
    private val suggestionScroll: HorizontalScrollView
    private val utilityControlsLayout: LinearLayout
    private val langPill: TextView
    private val expandButton: TextView
    private val statusBadge: TextView
    private val clipboardButton: TextView
    private val emojiButton: TextView
    private val switchImeButton: TextView
    private val resizeButton: TextView
    private val enterButton: TextView
    private val modeButton: TextView
    private val symbolsButton: TextView
    private val backspaceButton: TextView
    private val gestureTrailView: GestureTrailView
    private val rightCol: LinearLayout
    private var lastSuggestions: List<String> = emptyList()

    // Hold & Slide popup for all keys
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
        clipChildren = false
        clipToPadding = false
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
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
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42f), 1f)
        }
        suggestionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        suggestionScroll.addView(suggestionContainer)
        topBar.addView(suggestionScroll)

        // Always-visible Language Pill (ES / EN)
        langPill = createTopChip("ES").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8f), dpToPx(5f), dpToPx(8f), dpToPx(5f))
            setOnClickListener {
                performHaptic()
                listener?.onQuickLanguageToggle()
            }
        }
        topBar.addView(langPill, marginEndParams(4))

        // Expand / Collapse candidate grid button
        expandButton = createTopChip("▼").apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dpToPx(9f), dpToPx(6f), dpToPx(9f), dpToPx(6f))
            setOnClickListener {
                performHaptic()
                if (isCandidateGridVisible()) {
                    hideCandidateGrid()
                } else {
                    showCandidateGrid()
                }
            }
        }
        topBar.addView(expandButton, marginEndParams(4))

        // Utility controls container (collapses when typing to give 100% space to suggestions)
        utilityControlsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Clipboard history button (Tap = Clipboard, Hold = Text Expander Shortcuts)
        clipboardButton = createTopChip("📋").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                performHaptic()
                listener?.onClipboardRequested()
            }
            setOnLongClickListener {
                performHaptic()
                listener?.onShortcutsRequested()
                true
            }
        }
        utilityControlsLayout.addView(clipboardButton, marginEndParams(4))

        // Direct Emoji button
        emojiButton = createTopChip("😊").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                performHaptic()
                showEmojiPanel()
            }
        }
        utilityControlsLayout.addView(emojiButton, marginEndParams(4))

        // Unified compact Mode / Case badge
        statusBadge = createTopChip("T9 ES", accent = true).apply {
            setOnClickListener {
                performHaptic()
                listener?.onQuickLanguageToggle()
            }
            setOnLongClickListener {
                performHaptic()
                showModePickerStrip()
                true
            }
        }
        utilityControlsLayout.addView(statusBadge, marginEndParams(4))

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
                listener?.onSwitchImeRequested()
            }
            setOnLongClickListener {
                performHaptic()
                listener?.onShowImePickerRequested()
                true
            }
        }
        utilityControlsLayout.addView(switchImeButton, marginEndParams(4))

        // Calculator toggle button
        calcButton = createTopChip("🧮").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                performHaptic()
                toggleCalculatorMode()
            }
        }
        utilityControlsLayout.addView(calcButton, marginEndParams(4))

        // Resize overlay trigger
        resizeButton = createTopChip("⤢").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener {
                performHaptic()
                listener?.onResizeToggleRequested()
            }
        }
        utilityControlsLayout.addView(resizeButton)
        topBar.addView(utilityControlsLayout)
        mainLayout.addView(topBar)

        // Candidate Grid Panel (expanded view of all alternative words)
        candidateGridPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val gridHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4f), 0, dpToPx(4f), dpToPx(6f))
        }
        val gridTitle = TextView(context).apply {
            text = "Palabras coincidentes"
            setTextColor(ContextCompat.getColor(context, R.color.kb_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val gridCloseBtn = createTopChip("▲ Cerrar").apply {
            setOnClickListener {
                performHaptic()
                hideCandidateGrid()
            }
        }
        gridHeader.addView(gridTitle)
        gridHeader.addView(gridCloseBtn)
        candidateGridPanel.addView(gridHeader)

        candidateGridScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx((keyHeightDp * 4).toFloat())
            )
        }
        candidateGridRowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(2f), 0, dpToPx(4f))
        }
        candidateGridScroll.addView(candidateGridRowsContainer)
        candidateGridPanel.addView(candidateGridScroll)
        mainLayout.addView(candidateGridPanel)

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
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
                val key = createNumericKey(digit.toString(), sub)
                setupKeySlideToSelect(key, digit)
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
            listener?.onTogglePredictiveMode()
            true
        })
        keyViews.add(starKey)
        bottomRow.addView(starKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })

        // Key 0: Space + Cursor trackpad slider
        val zeroKey = createNumericKey("0", "◄ ESPACIO ►")
        setupZeroCursorTrackpad(zeroKey)
        keyViews.add(zeroKey)
        bottomRow.addView(zeroKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })

        // Key #: Dedicated to Symbols with Hold & Slide Selector!
        val hashKey = createNumericKey("#", "SÍM / @")
        setupKeySlideToSelect(hashKey, '#')
        keyViews.add(hashKey)
        bottomRow.addView(hashKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })

        keypadRows.add(bottomRow)
        leftGrid.addView(bottomRow)

        val initialGridHeightPx = dpToPx(keyHeightDp * 4f + 15f)
        gestureTrailView = GestureTrailView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                initialGridHeightPx
            )
        }

        val leftGridContainer = object : FrameLayout(context) {
            private var startX = 0f
            private var startY = 0f
            private var isSwiping = false
            private var isHorizontalSwipe = false
            private val swipeDigits = StringBuilder()
            private var lastDigit: Char? = null

            private fun getDigitAt(x: Float, y: Float): Char? {
                val w = width.toFloat()
                val h = height.toFloat()
                if (w <= 0 || h <= 0) return null
                if (x < 0f || x > w || y < 0f || y > h) return null
                val col = (x / (w / 3f)).toInt().coerceIn(0, 2)
                val row = (y / (h / 4f)).toInt().coerceIn(0, 3)
                val grid = arrayOf(
                    charArrayOf('1', '2', '3'),
                    charArrayOf('4', '5', '6'),
                    charArrayOf('7', '8', '9'),
                    charArrayOf('*', '0', '#')
                )
                val ch = grid[row][col]
                return if (ch in '1'..'9') ch else null
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                val h = height.toFloat()
                if (h > 0 && (ev.y / (h / 4f)).toInt() >= 3) {
                    // Row 3 (*, 0, #) has its own dedicated trackpad and symbol popup touch handlers!
                    return false
                }
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = ev.x
                        startY = ev.y
                        isSwiping = false
                        isHorizontalSwipe = false
                        swipeDigits.clear()
                        lastDigit = getDigitAt(ev.x, ev.y)
                        if (lastDigit != null) {
                            swipeDigits.append(lastDigit)
                        }
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (h > 0 && (startY / (h / 4f)).toInt() >= 3) {
                            return false
                        }
                        val dx = ev.x - startX
                        val dy = ev.y - startY
                        val dist = kotlin.math.hypot(dx, dy)

                        // 1. Horizontal swipe across numbers (Left = delete word, Right = space)
                        if (abs(dx) > dpToPx(32f) && abs(dx) > abs(dy) * 1.4f) {
                            isHorizontalSwipe = true
                            isSwiping = true
                            hideSymbolPopupOverlay()
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }

                        // 2. Glide typing in T9 mode
                        if (currentInputMode == InputMode.T9_ES || currentInputMode == InputMode.T9_EN) {
                            if (dist > dpToPx(18f)) {
                                isSwiping = true
                                hideSymbolPopupOverlay()
                                parent?.requestDisallowInterceptTouchEvent(true)
                                gestureTrailView.startTrail(ev.x, ev.y)
                                val d = getDigitAt(ev.x, ev.y)
                                if (d != null && d != lastDigit) {
                                    swipeDigits.append(d)
                                    lastDigit = d
                                    performHaptic()
                                }
                                return true
                            }
                        }
                    }
                }
                return false
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (!isSwiping) return super.onTouchEvent(ev)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (!isHorizontalSwipe) {
                            gestureTrailView.addPoint(ev.x, ev.y)
                            val d = getDigitAt(ev.x, ev.y)
                            if (d != null && d != lastDigit) {
                                swipeDigits.append(d)
                                lastDigit = d
                                performHaptic()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isSwiping = false
                        gestureTrailView.finishTrail()
                        if (ev.actionMasked == MotionEvent.ACTION_UP) {
                            if (isHorizontalSwipe) {
                                val dx = ev.x - startX
                                if (abs(dx) > dpToPx(35f)) {
                                    performStrongHaptic()
                                    if (dx < 0) {
                                        listener?.onSwipeDeleteWord()
                                    } else {
                                        listener?.onSwipeSpace()
                                    }
                                }
                                isHorizontalSwipe = false
                                return true
                            }
                            if (swipeDigits.length >= 2) {
                                performStrongHaptic()
                                listener?.onSwipeType(swipeDigits.toString())
                                return true
                            }
                        }
                        isHorizontalSwipe = false
                        return true
                    }
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        }

        leftGridContainer.addView(leftGrid)
        leftGridContainer.addView(gestureTrailView)
        body.addView(leftGridContainer)

        // Right Action Column (explicitly matched to 4-row keypad height so it never expands to full screen)
        rightCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, initialGridHeightPx, 1f)
        }

        // Backspace: Tap deletes char; Swipe Left deletes whole word; Hold > 280ms deletes words repeatedly!
        backspaceButton = createActionKey("⌫").apply {
            var downX = 0f
            var isSwiped = false
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        isBackspacePressed = true
                        downX = event.x
                        isSwiped = false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        performHaptic()
                        listener?.onBackspace()
                        repeatHandler.removeCallbacks(wordDeleteRepeater)
                        repeatHandler.postDelayed(wordDeleteRepeater, 280L)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - downX
                        if (!isSwiped && dx < -dpToPx(24f)) {
                            isSwiped = true
                            repeatHandler.removeCallbacks(wordDeleteRepeater)
                            performStrongHaptic()
                            listener?.onSwipeDeleteWord()
                        }
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

        // Dedicated Symbols Button: Quick tap opens symbol strip, Long press opens Emoji panel
        symbolsButton = createActionKey("SÍM").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                performHaptic()
                showSymbolStrip()
            }
            setOnLongClickListener {
                performHaptic()
                showEmojiPanel()
                true
            }
        }
        actionButtons.add(symbolsButton)
        rightCol.addView(symbolsButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            bottomMargin = dpToPx(5f)
        })

        modeButton = createActionKey("ABC").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                performHaptic()
                listener?.onTogglePredictiveMode()
            }
        }
        actionButtons.add(modeButton)
        rightCol.addView(modeButton, LinearLayout.LayoutParams(
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
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        body.addView(rightCol)
        mainLayout.addView(body)

        calcKeypadPanel = buildCalculatorKeypad()
        calcKeypadPanel.visibility = View.GONE
        mainLayout.addView(calcKeypadPanel)

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
        addView(symbolPopupOverlay)
    }

    private fun getSlideOptionsForKey(digit: Char, caseMode: CaseMode): List<String> {
        val isUpper = (caseMode == CaseMode.UPPER || caseMode == CaseMode.CAPITALIZE)
        return when (digit) {
            '1' -> listOf(".", ",", "?", "!", "@", "1", "'", "-", ":", "/")
            '2' -> if (isUpper) listOf("A", "B", "C", "Á", "2") else listOf("a", "b", "c", "á", "2")
            '3' -> if (isUpper) listOf("D", "E", "F", "É", "3") else listOf("d", "e", "f", "é", "3")
            '4' -> if (isUpper) listOf("G", "H", "I", "Í", "4") else listOf("g", "h", "i", "í", "4")
            '5' -> if (isUpper) listOf("J", "K", "L", "5") else listOf("j", "k", "l", "5")
            '6' -> if (isUpper) listOf("M", "N", "O", "Ñ", "Ó", "6") else listOf("m", "n", "o", "ñ", "ó", "6")
            '7' -> if (isUpper) listOf("P", "Q", "R", "S", "7") else listOf("p", "q", "r", "s", "7")
            '8' -> if (isUpper) listOf("T", "U", "V", "Ú", "Ü", "8") else listOf("t", "u", "v", "ú", "ü", "8")
            '9' -> if (isUpper) listOf("W", "X", "Y", "Z", "9") else listOf("w", "x", "y", "z", "9")
            '#' -> T9KeyMap.POPUP_SYMBOLS
            else -> listOf(digit.toString())
        }
    }

    // --- Hold & Slide Selector for all keys (like #): Hold 250ms -> Slide to pick letter/symbol -> Release to commit! ---
    private fun setupKeySlideToSelect(keyView: View, digit: Char) {
        var isLongPressActive = false
        val longPressRunnable = Runnable {
            isLongPressActive = true
            keyView.parent?.requestDisallowInterceptTouchEvent(true)
            performHaptic()
            val options = getSlideOptionsForKey(digit, currentCaseMode)
            // Always position the popup 1 full row ABOVE the pressed key (just like # does!)
            // so the user's thumb/finger never covers the letters:
            // - Row 3 (#): floats over Row 2 (keyHeightDp * 1f + 18f)
            // - Row 2 (7,8,9): floats over Row 1 (keyHeightDp * 2f + 22f)
            // - Row 1 (4,5,6): floats over Row 0 (keyHeightDp * 3f + 26f)
            // - Row 0 (1,2,3): floats over Top Bar (keyHeightDp * 4f + 22f)
            val rowMargin = when (digit) {
                '1', '2', '3' -> dpToPx(keyHeightDp * 4f + 22f)
                '4', '5', '6' -> dpToPx(keyHeightDp * 3f + 26f)
                '7', '8', '9' -> dpToPx(keyHeightDp * 2f + 22f)
                else -> dpToPx(keyHeightDp * 1f + 18f)
            }
            showSymbolPopupOverlay(options, rowMargin)
        }

        keyView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressActive = false
                    v.isPressed = true
                    if (digit == '#') {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    repeatHandler.removeCallbacks(longPressRunnable)
                    repeatHandler.postDelayed(longPressRunnable, 250L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isLongPressActive) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        updateSymbolPopupSelection(event.rawX)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    repeatHandler.removeCallbacks(longPressRunnable)
                    if (isLongPressActive) {
                        val selected = commitSymbolPopupSelection()
                        if (selected == "😊") {
                            performHaptic()
                            showEmojiPanel()
                        } else if (selected != null) {
                            performStrongHaptic()
                            listener?.onSymbolSelected(selected)
                        }
                    } else {
                        // Quick tap on digit: normal typing!
                        performHaptic()
                        if (digit == '#') {
                            listener?.onSymbolSelected("#")
                        } else {
                            listener?.onDigitKey(digit)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
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
            if (!isDraggingCursor) {
                isLongPress = true
                performStrongHaptic()
            }
        }

        zeroKey.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    lastX = event.x
                    isDraggingCursor = false
                    isLongPress = false
                    v.isPressed = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    repeatHandler.removeCallbacks(longPressRunnable)
                    repeatHandler.postDelayed(longPressRunnable, 550L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.x - downX
                    if (abs(totalDx) > dpToPx(12f)) {
                        repeatHandler.removeCallbacks(longPressRunnable)
                        isDraggingCursor = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        val stepDx = event.x - lastX
                        val stepThreshold = dpToPx(11f)
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
                    v.isPressed = false
                    repeatHandler.removeCallbacks(longPressRunnable)
                    if (isDraggingCursor) {
                        // Dragged cursor: do NOT commit space or 0
                    } else if (isLongPress) {
                        // Held without moving: commit '0'
                        performStrongHaptic()
                        listener?.onZeroLongPress()
                    } else {
                        // Quick tap: commit space (or 0 if in numeric mode)
                        performHaptic()
                        listener?.onZeroKey()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeatHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun showSymbolPopupOverlay(options: List<String>, rowMargin: Int) {
        symbolPopupOverlay.removeAllViews()
        symbolPopupChips.clear()

        (symbolPopupOverlay.layoutParams as? LayoutParams)?.let { lp ->
            lp.bottomMargin = rowMargin
            symbolPopupOverlay.layoutParams = lp
        }

        val isCompactLetterStrip = options.size <= 6
        options.forEachIndexed { index, sym ->
            val tv = TextView(context).apply {
                text = sym
                setTextColor(Color.WHITE)
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    when {
                        sym == "😊" -> 18f
                        isCompactLetterStrip -> 18f
                        else -> 16f
                    }
                )
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                val hPad = when {
                    sym.length > 1 -> dpToPx(8f)
                    isCompactLetterStrip -> dpToPx(14f)
                    else -> dpToPx(10f)
                }
                val vPad = if (isCompactLetterStrip) dpToPx(9f) else dpToPx(7f)
                setPadding(hPad, vPad, hPad, vPad)
                background = GradientDrawable().apply {
                    setColor(if (index == 0) ContextCompat.getColor(context, R.color.kb_accent) else Color.TRANSPARENT)
                    cornerRadius = dpToPx(10f).toFloat()
                }
            }
            symbolPopupChips.add(tv)
            symbolPopupOverlay.addView(tv)
        }

        popupSelectedIndex = 0
        updateChipHighlights()
        symbolPopupOverlay.visibility = View.VISIBLE
        symbolPopupOverlay.bringToFront()
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
                chip.scaleX = 1.15f
                chip.scaleY = 1.15f
            } else {
                bg.setColor(Color.TRANSPARENT)
                chip.setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                chip.scaleX = 1.0f
                chip.scaleY = 1.0f
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
        val totalGridHeightPx = heightPx * 4 + dpToPx(15f)
        keypadRows.forEach { row ->
            val lp = row.layoutParams as LinearLayout.LayoutParams
            if (lp.height != heightPx) {
                lp.height = heightPx
                row.layoutParams = lp
            }
        }
        gestureTrailView.layoutParams = gestureTrailView.layoutParams.apply {
            height = totalGridHeightPx
        }
        rightCol.layoutParams = rightCol.layoutParams.apply {
            height = totalGridHeightPx
        }
        calcKeypadRows.forEach { row ->
            val lp = row.layoutParams as? LinearLayout.LayoutParams
            if (lp != null && lp.height != heightPx) {
                lp.height = heightPx
                row.layoutParams = lp
            }
        }
        val targetPanelScrollHeight = (heightPx * 4 - dpToPx(36f)).coerceAtLeast(dpToPx(160f))
        emojiMediaPanel.setPanelContentHeight(targetPanelScrollHeight)

        val gridLp = candidateGridScroll.layoutParams
        if (gridLp != null) {
            gridLp.height = (heightPx * 4 - dpToPx(32f)).coerceAtLeast(dpToPx(160f))
            candidateGridScroll.layoutParams = gridLp
        }
        requestLayout()
    }

    fun showEmojiPanel() {
        hideCandidateGrid()
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

    fun showCandidateGrid() {
        if (lastSuggestions.isEmpty()) return
        candidateGridPanel.visibility = View.VISIBLE
        body.visibility = View.GONE
        expandButton.text = "▲"
    }

    fun hideCandidateGrid() {
        candidateGridPanel.visibility = View.GONE
        if (!isEmojiPanelVisible()) {
            body.visibility = View.VISIBLE
        }
        expandButton.text = "▼"
    }

    fun isCandidateGridVisible(): Boolean = candidateGridPanel.visibility == View.VISIBLE

    private fun updateCandidateGridContent(suggestions: List<String>) {
        candidateGridRowsContainer.removeAllViews()
        if (suggestions.isEmpty()) return

        val columns = 3
        val rows = (suggestions.size + columns - 1) / columns

        for (r in 0 until rows) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(44f)
                ).apply {
                    bottomMargin = dpToPx(6f)
                }
            }

            for (c in 0 until columns) {
                val index = r * columns + c
                if (index < suggestions.size) {
                    val word = suggestions[index]
                    val isPrimary = (index == 0)
                    val chip = TextView(context).apply {
                        text = word
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isPrimary) Color.WHITE else ContextCompat.getColor(context, R.color.kb_text_primary))
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        background = GradientDrawable().apply {
                            setColor(
                                if (isPrimary) ContextCompat.getColor(context, R.color.kb_accent_soft)
                                else ContextCompat.getColor(context, R.color.kb_key_bg)
                            )
                            cornerRadius = dpToPx(10f).toFloat()
                            setStroke(dpToPx(1f), ContextCompat.getColor(context, R.color.kb_border))
                        }
                        setOnClickListener {
                            performHaptic()
                            hideCandidateGrid()
                            listener?.onSuggestionSelected(word)
                        }
                    }
                    row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = if (c < columns - 1) dpToPx(5f) else 0
                    })
                } else {
                    val dummy = View(context)
                    row.addView(dummy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                }
            }
            candidateGridRowsContainer.addView(row)
        }
    }

    fun updateState(mode: InputMode, caseMode: CaseMode) {
        currentInputMode = mode
        currentCaseMode = caseMode
        statusBadge.text = "${mode.label} · ${caseMode.label}"

        val isEnglish = (mode == InputMode.T9_EN)
        langPill.text = if (isEnglish) "EN" else "ES"
        langPill.setTextColor(if (isEnglish) Color.WHITE else ContextCompat.getColor(context, R.color.kb_accent))
        langPill.background = GradientDrawable().apply {
            setColor(if (isEnglish) Color.parseColor("#D35400") else ContextCompat.getColor(context, R.color.kb_key_bg))
            cornerRadius = dpToPx(10f).toFloat()
            setStroke(dpToPx(1.5f), if (isEnglish) Color.parseColor("#E67E22") else ContextCompat.getColor(context, R.color.kb_border))
        }

        val isMultiTap = (mode == InputMode.MULTI_TAP)
        modeButton.text = when (mode) {
            InputMode.T9_ES, InputMode.T9_EN -> "ABC"
            InputMode.MULTI_TAP -> "T9"
            InputMode.NUMERIC -> "T9"
        }
        modeButton.setTextColor(
            if (isMultiTap) ContextCompat.getColor(context, R.color.kb_accent)
            else ContextCompat.getColor(context, R.color.kb_text_primary)
        )
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
        digits: String,
        calculatorResult: co.com.onecontrol.mykeyboard.engine.MathEvaluator.CalculationResult? = null,
        shortcuts: List<co.com.onecontrol.mykeyboard.dictionary.TextShortcut> = emptyList()
    ) {
        lastSuggestions = suggestions
        suggestionContainer.removeAllViews()
        langPill.visibility = View.VISIBLE

        if (hasPendingDigits) {
            // Typing mode: collapse utility buttons to give 100% of topBar to suggestions!
            utilityControlsLayout.visibility = View.GONE
            if (suggestions.size >= 3 || shortcuts.isNotEmpty()) {
                expandButton.visibility = View.VISIBLE
                expandButton.text = if (isCandidateGridVisible()) "▲" else "▼"
            } else {
                expandButton.visibility = View.GONE
                hideCandidateGrid()
            }
        } else {
            // Idle mode: show utility buttons, hide expand button & grid
            utilityControlsLayout.visibility = View.VISIBLE
            expandButton.visibility = View.GONE
            hideCandidateGrid()
        }

        // Text Expander Shortcuts (e.g. ⚡ micorreo -> usuario@gmail.com)
        shortcuts.forEach { sc ->
            val scChip = createSuggestionChip("⚡ ${sc.expansion}", primary = true) {
                performStrongHaptic()
                hideCandidateGrid()
                listener?.onShortcutSelected(sc.expansion)
            }
            suggestionContainer.addView(scChip, marginEndParams(6))
        }

        // Calculator chip (if math expression detected in text)
        if (calculatorResult != null) {
            val calcChip = createSuggestionChip("🧮 = ${calculatorResult.formattedResult}", primary = true) {
                performStrongHaptic()
                listener?.onCalculatorSelected(calculatorResult.expression, calculatorResult.formattedResult)
            }
            suggestionContainer.addView(calcChip, marginEndParams(6))
        }

        if (suggestions.isNotEmpty()) {
            suggestions.forEachIndexed { index, word ->
                val isFirstInTyping = hasPendingDigits && index == 0
                val chip = createSuggestionChip(word, primary = isFirstInTyping) {
                    performHaptic()
                    hideCandidateGrid()
                    listener?.onSuggestionSelected(word)
                }
                suggestionContainer.addView(chip, marginEndParams(6))
            }
            if (hasPendingDigits && currentInputMode != InputMode.MULTI_TAP) {
                val spellChip = createSuggestionChip("🔤 ABC", primary = false) {
                    performHaptic()
                    hideCandidateGrid()
                    listener?.onFallbackMultiTapRequested()
                }
                suggestionContainer.addView(spellChip, marginEndParams(6))
            }
        } else if (hasPendingDigits) {
            val fallbackChip = createSuggestionChip("➕ Deletrear palabra (ABC)", primary = true) {
                performHaptic()
                hideCandidateGrid()
                listener?.onFallbackMultiTapRequested()
            }
            suggestionContainer.addView(fallbackChip, marginEndParams(6))
        } else if (calculatorResult == null) {
            val hintText = if (currentInputMode == InputMode.MULTI_TAP) {
                "Modo ABC (multitap) · Toca T9 para volver"
            } else {
                "◄ Desliza 0 para mover cursor ►"
            }
            val hint = TextView(context).apply {
                text = hintText
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
        updateCandidateGridContent(suggestions)
    }

    private fun prepareStripWithCloseChip(): TextView {
        utilityControlsLayout.visibility = View.GONE
        expandButton.visibility = View.GONE
        langPill.visibility = View.GONE
        hideCandidateGrid()
        suggestionContainer.removeAllViews()
        val closeChip = createSuggestionChip("✕", primary = false) {
            performHaptic()
            utilityControlsLayout.visibility = View.VISIBLE
            langPill.visibility = View.VISIBLE
            showSuggestions(emptyList(), false, "")
        }
        suggestionContainer.addView(closeChip, marginEndParams(6))
        return closeChip
    }

    fun showModePickerStrip() {
        prepareStripWithCloseChip()
        val modes = listOf(
            InputMode.T9_ES to "🇪🇸 T9 Español",
            InputMode.T9_EN to "🇬🇧 T9 English",
            InputMode.MULTI_TAP to "🔤 ABC Multi-tap",
            InputMode.NUMERIC to "123 Números"
        )
        modes.forEach { (mode, label) ->
            val isCurrent = (mode == currentInputMode)
            val chip = createSuggestionChip(label, primary = isCurrent) {
                performHaptic()
                utilityControlsLayout.visibility = View.VISIBLE
                langPill.visibility = View.VISIBLE
                listener?.onModeSelected(mode)
            }
            suggestionContainer.addView(chip, marginEndParams(6))
        }
        suggestionScroll.scrollTo(0, 0)
    }

    fun showEmailDomainStrip() {
        prepareStripWithCloseChip()
        T9KeyMap.EMAIL_DOMAINS.forEach { domain ->
            val chip = createSuggestionChip(domain, primary = true) {
                performHaptic()
                utilityControlsLayout.visibility = View.VISIBLE
                listener?.onSymbolSelected(domain)
            }
            suggestionContainer.addView(chip, marginEndParams(6))
        }
        suggestionScroll.scrollTo(0, 0)
    }

    fun showPasswordHelpStrip() {
        prepareStripWithCloseChip()
        val chip = createSuggestionChip("🔑 Generar contraseña segura", primary = true) {
            performStrongHaptic()
            utilityControlsLayout.visibility = View.VISIBLE
            listener?.onGeneratePasswordRequested()
        }
        suggestionContainer.addView(chip, marginEndParams(6))
        suggestionScroll.scrollTo(0, 0)
    }

    fun showUrlShortcutsStrip() {
        prepareStripWithCloseChip()
        T9KeyMap.URL_SHORTCUTS.forEach { shortcut ->
            val chip = createSuggestionChip(shortcut, primary = false) {
                performHaptic()
                utilityControlsLayout.visibility = View.VISIBLE
                listener?.onSymbolSelected(shortcut)
            }
            suggestionContainer.addView(chip, marginEndParams(5))
        }
        suggestionScroll.scrollTo(0, 0)
    }

    fun showClipboardStrip(items: List<String>) {
        prepareStripWithCloseChip()
        if (items.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "Portapapeles vacío"
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dpToPx(8f), 0, dpToPx(8f), 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            suggestionContainer.addView(emptyTv)
        } else {
            items.forEach { item ->
                val preview = if (item.length > 22) item.take(20) + "…" else item
                val chip = createSuggestionChip(preview, primary = false) {
                    performHaptic()
                    utilityControlsLayout.visibility = View.VISIBLE
                    listener?.onSymbolSelected(item)
                }
                suggestionContainer.addView(chip, marginEndParams(6))
            }
        }
        suggestionScroll.scrollTo(0, 0)
    }

    fun showShortcutsStrip(shortcuts: List<co.com.onecontrol.mykeyboard.dictionary.TextShortcut>) {
        prepareStripWithCloseChip()
        if (shortcuts.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "Sin atajos guardados (configura en la app)"
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dpToPx(8f), 0, dpToPx(8f), 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            suggestionContainer.addView(emptyTv)
        } else {
            shortcuts.forEach { sc ->
                val label = "⚡ ${sc.shortcut}: ${if (sc.expansion.length > 18) sc.expansion.take(16) + "…" else sc.expansion}"
                val chip = createSuggestionChip(label, primary = false) {
                    performStrongHaptic()
                    utilityControlsLayout.visibility = View.VISIBLE
                    listener?.onShortcutSelected(sc.expansion)
                }
                suggestionContainer.addView(chip, marginEndParams(6))
            }
        }
        suggestionScroll.scrollTo(0, 0)
    }

    fun showSymbolStrip() {
        prepareStripWithCloseChip()
        T9KeyMap.FREQUENT_SYMBOLS.forEach { sym ->
            val chip = createSuggestionChip(sym, primary = false) {
                performHaptic()
                utilityControlsLayout.visibility = View.VISIBLE
                listener?.onSymbolSelected(sym)
            }
            suggestionContainer.addView(chip, marginEndParams(5))
        }
        suggestionScroll.scrollTo(0, 0)
    }

    // Emojis grandes (24sp) y cómodos de pulsar
    fun showEmojiStrip() {
        prepareStripWithCloseChip()
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
                    utilityControlsLayout.visibility = View.VISIBLE
                    listener?.onSymbolSelected(emo)
                }
            }
            suggestionContainer.addView(chip, marginEndParams(6))
        }
        suggestionScroll.scrollTo(0, 0)
    }

    // --- Long-Press Options Popup & Strip ---

    private fun showKeyOptions(digit: Char, anchorView: View) {
        val isUpper = currentCaseMode == CaseMode.UPPER
        val options = T9KeyMap.getLongPressOptions(digit, isUpper)
        if (options.isEmpty()) return

        // 1. Show in Top Suggestion Bar
        prepareStripWithCloseChip()
        val title = TextView(context).apply {
            text = "$digit:"
            setTextColor(ContextCompat.getColor(context, R.color.kb_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4f), 0, dpToPx(4f), 0)
        }
        suggestionContainer.addView(title, marginEndParams(4))

        options.forEachIndexed { index, opt ->
            val isPrimary = index == 0
            val chip = createSuggestionChip(opt, primary = isPrimary) {
                performHaptic()
                listener?.onDirectCommit(opt)
                dismissActivePopup()
                utilityControlsLayout.visibility = View.VISIBLE
                langPill.visibility = View.VISIBLE
                showSuggestions(emptyList(), false, "")
            }
            suggestionContainer.addView(chip, marginEndParams(5))
        }
        suggestionScroll.scrollTo(0, 0)

        // 2. Also show floating popup directly above the key
        dismissActivePopup()
        if (anchorView.isAttachedToWindow && anchorView.windowToken != null) {
            val popupContent = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpToPx(6f), dpToPx(4f), dpToPx(6f), dpToPx(4f))
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.kb_action_bg))
                    cornerRadius = dpToPx(10f).toFloat()
                    setStroke(dpToPx(1f), ContextCompat.getColor(context, R.color.kb_accent_soft))
                }
            }

            options.take(7).forEachIndexed { idx, opt ->
                val tv = TextView(context).apply {
                    text = opt
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    typeface = if (idx == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(
                        if (idx == 0) ContextCompat.getColor(context, R.color.kb_accent)
                        else ContextCompat.getColor(context, R.color.kb_text_primary)
                    )
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(9f), dpToPx(6f), dpToPx(9f), dpToPx(6f))
                    isClickable = true
                    setOnClickListener {
                        performHaptic()
                        listener?.onDirectCommit(opt)
                        dismissActivePopup()
                        utilityControlsLayout.visibility = View.VISIBLE
                        langPill.visibility = View.VISIBLE
                        showSuggestions(emptyList(), false, "")
                    }
                }
                popupContent.addView(tv)
            }

            try {
                val popup = android.widget.PopupWindow(
                    popupContent,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                ).apply {
                    isOutsideTouchable = true
                    elevation = dpToPx(10f).toFloat()
                }
                popup.showAsDropDown(anchorView, 0, -anchorView.height - dpToPx(46f))
                activePopup = popup
            } catch (_: Exception) {}
        }
    }

    private fun dismissActivePopup() {
        activePopup?.let {
            if (it.isShowing) {
                try {
                    it.dismiss()
                } catch (_: Exception) {}
            }
        }
        activePopup = null
    }

    // --- Calculator Mode ---

    private fun toggleCalculatorMode() {
        isCalculatorActive = !isCalculatorActive
        dismissActivePopup()

        if (isCalculatorActive) {
            calcButton.setTextColor(ContextCompat.getColor(context, R.color.kb_accent))
            calcButton.background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.kb_accent_soft))
                cornerRadius = dpToPx(10f).toFloat()
            }
            body.visibility = View.GONE
            calcKeypadPanel.visibility = View.VISIBLE
            calcExpression = ""
            calcCurrentResult = ""
            updateCalcDisplay()
        } else {
            calcButton.setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            calcButton.background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.kb_surface))
                cornerRadius = dpToPx(10f).toFloat()
            }
            calcKeypadPanel.visibility = View.GONE
            body.visibility = View.VISIBLE
            utilityControlsLayout.visibility = View.VISIBLE
            langPill.visibility = View.VISIBLE
            showSuggestions(emptyList(), false, "")
        }
    }

    private fun buildCalculatorKeypad(): LinearLayout {
        val calcLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val rows = listOf(
            listOf("7", "8", "9", "÷"),
            listOf("4", "5", "6", "×"),
            listOf("1", "2", "3", "-"),
            listOf("C", "0", ".", "+")
        )

        rows.forEach { rowKeys ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(keyHeightDp.toFloat())
                ).apply {
                    bottomMargin = dpToPx(5f)
                }
            }
            rowKeys.forEachIndexed { idx, keyLabel ->
                val isOp = keyLabel in listOf("÷", "×", "-", "+")
                val isClear = keyLabel == "C"
                val btn = createActionKey(keyLabel, accent = isOp).apply {
                    if (isClear) {
                        setTextColor(Color.parseColor("#FF5252"))
                    }
                    setOnClickListener {
                        performHaptic()
                        handleCalcInput(keyLabel)
                    }
                }
                row.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (idx < rowKeys.size - 1) marginEnd = dpToPx(5f)
                })
            }
            calcKeypadRows.add(row)
            calcLayout.addView(row)
        }

        // Bottom action row for calculator: Volver (ABC), Backspace, Equals
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(keyHeightDp.toFloat())
            )
        }

        val btnExitCalc = createActionKey("Volver (ABC)").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                performHaptic()
                toggleCalculatorMode()
            }
        }
        val btnBackspace = createActionKey("⌫").apply {
            setOnClickListener {
                performHaptic()
                onCalcBackspace()
            }
        }
        val btnEquals = createActionKey("=", accent = true).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setOnClickListener {
                performHaptic()
                onCalcEquals()
            }
        }

        bottomRow.addView(btnExitCalc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f).apply {
            marginEnd = dpToPx(5f)
        })
        bottomRow.addView(btnBackspace, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })
        bottomRow.addView(btnEquals, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))

        calcKeypadRows.add(bottomRow)
        calcLayout.addView(bottomRow)

        return calcLayout
    }

    private fun handleCalcInput(input: String) {
        when (input) {
            "C" -> {
                calcExpression = ""
                calcCurrentResult = ""
            }
            "÷", "×", "-", "+" -> {
                if (calcExpression.isNotEmpty()) {
                    val last = calcExpression.last()
                    if (last in listOf('÷', '×', '-', '+', ' ')) {
                        calcExpression = calcExpression.trimEnd().dropLast(1)
                    }
                    calcExpression = "$calcExpression $input "
                }
            }
            "." -> {
                if (calcExpression.isEmpty() || calcExpression.endsWith(" ")) {
                    calcExpression += "0."
                } else if (!calcExpression.takeLastWhile { it != ' ' }.contains('.')) {
                    calcExpression += "."
                }
            }
            else -> {
                calcExpression += input
            }
        }

        val eval = co.com.onecontrol.mykeyboard.engine.MathEvaluator.evaluateSimpleExpression(calcExpression)
        if (eval != null) {
            calcCurrentResult = if (eval == eval.toLong().toDouble()) {
                eval.toLong().toString()
            } else {
                String.format(java.util.Locale.US, "%.4f", eval).trimEnd('0').trimEnd('.')
            }
        }
        updateCalcDisplay()
    }

    private fun onCalcBackspace() {
        if (calcExpression.isNotEmpty()) {
            calcExpression = if (calcExpression.endsWith(" ")) {
                calcExpression.dropLast(3)
            } else {
                calcExpression.dropLast(1)
            }
            val eval = co.com.onecontrol.mykeyboard.engine.MathEvaluator.evaluateSimpleExpression(calcExpression)
            calcCurrentResult = if (eval != null) {
                if (eval == eval.toLong().toDouble()) {
                    eval.toLong().toString()
                } else {
                    String.format(java.util.Locale.US, "%.4f", eval).trimEnd('0').trimEnd('.')
                }
            } else {
                ""
            }
            updateCalcDisplay()
        }
    }

    private fun onCalcEquals() {
        val eval = co.com.onecontrol.mykeyboard.engine.MathEvaluator.evaluateSimpleExpression(calcExpression)
        if (eval != null) {
            val formatted = if (eval == eval.toLong().toDouble()) {
                eval.toLong().toString()
            } else {
                String.format(java.util.Locale.US, "%.4f", eval).trimEnd('0').trimEnd('.')
            }
            calcExpression = formatted
            calcCurrentResult = formatted
            updateCalcDisplay()
        }
    }

    private fun updateCalcDisplay() {
        suggestionContainer.removeAllViews()
        utilityControlsLayout.visibility = View.GONE
        expandButton.visibility = View.GONE
        langPill.visibility = View.GONE

        val exprText = if (calcExpression.isEmpty()) "0" else calcExpression
        val resText = if (calcCurrentResult.isNotEmpty() && calcCurrentResult != calcExpression) {
            " = $calcCurrentResult"
        } else {
            ""
        }
        val displayTv = TextView(context).apply {
            text = "$exprText$resText"
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8f), 0, dpToPx(8f), 0)
        }
        suggestionContainer.addView(displayTv, marginEndParams(8))

        val toPaste = if (calcCurrentResult.isNotEmpty()) calcCurrentResult else calcExpression
        if (toPaste.isNotEmpty()) {
            val pasteChip = createSuggestionChip("↵ Pegar: $toPaste", primary = true) {
                performStrongHaptic()
                listener?.onDirectCommit(toPaste)
            }
            suggestionContainer.addView(pasteChip, marginEndParams(6))
        }

        val exitChip = createSuggestionChip("✕ Cerrar", primary = false) {
            performHaptic()
            toggleCalculatorMode()
        }
        suggestionContainer.addView(exitChip)
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

            var downX = 0f
            var downY = 0f
            var isFlickedUp = false

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        isFlickedUp = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.y - downY
                        val dx = event.x - downX
                        if (!isFlickedUp && dy < -dpToPx(24f) && abs(dy) > abs(dx) * 1.3f) {
                            isFlickedUp = true
                            performStrongHaptic()
                            listener?.onDirectCommit(mainLabel)
                            v.isPressed = false
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isFlickedUp) {
                            v.isPressed = false
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }

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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        activePopup?.dismiss()
        activePopup = null
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).roundToInt()
}
