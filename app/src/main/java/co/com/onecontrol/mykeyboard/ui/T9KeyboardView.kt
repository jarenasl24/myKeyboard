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
class T9KeyboardView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onDigitKey(digit: Char)
        fun onStarKey()
        fun onStarLongPress()
        fun onZeroKey()
        fun onZeroLongPress()
        fun onHashKey()
        fun onBackspace()
        fun onEnterKey()
        fun onQuickLanguageToggle()
        fun onSuggestionSelected(word: String)
        fun onFallbackMultiTapRequested()
        fun onSymbolSelected(symbol: String)
        fun onSwipeDeleteWord()
        fun onSwipeSpace()
        fun onResizeToggleRequested()
    }

    var listener: Listener? = null

    private var hapticStrength: Int = KeyboardConfig.DEFAULT_HAPTIC_STRENGTH
    private var keyHeightDp: Int = KeyboardConfig.DEFAULT_KEY_HEIGHT_DP

    private val suggestionContainer: LinearLayout
    private val suggestionScroll: HorizontalScrollView
    private val modeBadge: TextView
    private val caseBadge: TextView
    private val resizeButton: TextView
    private val enterButton: TextView
    private val langButton: TextView
    private val backspaceButton: TextView

    private val keyViews = mutableListOf<View>()
    private val actionButtons = mutableListOf<View>()
    private val keypadRows = mutableListOf<LinearLayout>()

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatCount = 0
    private var isBackspacePressed = false

    private val backspaceRepeater = object : Runnable {
        override fun run() {
            if (!isBackspacePressed) return
            performHaptic()
            listener?.onBackspace()
            repeatCount++
            val nextDelay = if (repeatCount > 8) 40L else 90L
            repeatHandler.postDelayed(this, nextDelay)
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
        orientation = VERTICAL
        setPadding(dpToPx(6f), dpToPx(6f), dpToPx(6f), dpToPx(8f))
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 1. Top Suggestion + Control Bar
        val topBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(2f), 0, dpToPx(2f), dpToPx(6f))
        }

        suggestionScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(0, dpToPx(34f), 1f)
        }
        suggestionContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        suggestionScroll.addView(suggestionContainer)
        topBar.addView(suggestionScroll)

        caseBadge = createTopChip("abc").apply {
            setOnClickListener {
                performHaptic()
                listener?.onStarKey()
            }
        }
        topBar.addView(caseBadge, marginEndParams(4))

        modeBadge = createTopChip("T9 ES", accent = true).apply {
            setOnClickListener {
                performHaptic()
                listener?.onHashKey()
            }
        }
        topBar.addView(modeBadge, marginEndParams(4))

        resizeButton = createTopChip("⤢").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener {
                performHaptic()
                listener?.onResizeToggleRequested()
            }
        }
        topBar.addView(resizeButton)

        addView(topBar)

        // 2. Main Body: Left 3x4 numeric grid + Right action column
        val body = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val leftGrid = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        }

        val rowsSpec = listOf(
            listOf('1' to ".,?!", '2' to "ABC", '3' to "DEF"),
            listOf('4' to "GHI", '5' to "JKL", '6' to "MNOÑ"),
            listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ")
        )

        rowsSpec.forEach { rowItems ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(keyHeightDp.toFloat())
                ).apply {
                    bottomMargin = dpToPx(5f)
                }
            }
            rowItems.forEach { (digit, sub) ->
                val key = createNumericKey(digit.toString(), sub) {
                    performHaptic()
                    listener?.onDigitKey(digit)
                }
                keyViews.add(key)
                row.addView(key, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = dpToPx(5f)
                })
            }
            keypadRows.add(row)
            leftGrid.addView(row)
        }

        // Bottom row: *, 0, #
        val bottomRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(keyHeightDp.toFloat())
            )
        }

        val starKey = createNumericKey("*", "Aa/Sím", onClick = {
            performHaptic()
            listener?.onStarKey()
        }, onLongClick = {
            performHaptic()
            listener?.onStarLongPress()
            true
        })
        keyViews.add(starKey)
        bottomRow.addView(starKey, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })

        val zeroKey = createNumericKey("0", "␣", onClick = {
            performHaptic()
            listener?.onZeroKey()
        }, onLongClick = {
            performHaptic()
            listener?.onZeroLongPress()
            true
        })
        keyViews.add(zeroKey)
        bottomRow.addView(zeroKey, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })

        val hashKey = createNumericKey("#", "Modo") {
            performHaptic()
            listener?.onHashKey()
        }
        keyViews.add(hashKey)
        bottomRow.addView(hashKey, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dpToPx(5f)
        })

        keypadRows.add(bottomRow)
        leftGrid.addView(bottomRow)
        body.addView(leftGrid)

        // Right Action Column
        val rightCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        backspaceButton = createActionKey("⌫").apply {
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        isBackspacePressed = true
                        repeatCount = 0
                        performHaptic()
                        listener?.onBackspace()
                        repeatHandler.removeCallbacks(backspaceRepeater)
                        repeatHandler.postDelayed(backspaceRepeater, 350L)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        isBackspacePressed = false
                        repeatHandler.removeCallbacks(backspaceRepeater)
                        true
                    }
                    else -> false
                }
            }
        }
        actionButtons.add(backspaceButton)
        rightCol.addView(backspaceButton, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            bottomMargin = dpToPx(5f)
        })

        langButton = createActionKey("ES/EN").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener {
                performHaptic()
                listener?.onQuickLanguageToggle()
            }
        }
        actionButtons.add(langButton)
        rightCol.addView(langButton, LayoutParams(
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
        rightCol.addView(enterButton, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f
        ))

        body.addView(rightCol)
        addView(body)
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
            val lp = row.layoutParams as LayoutParams
            if (lp.height != heightPx) {
                lp.height = heightPx
                row.layoutParams = lp
            }
        }
        requestLayout()
    }

    fun updateState(mode: InputMode, caseMode: CaseMode) {
        modeBadge.text = mode.label
        caseBadge.text = caseMode.label
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
            val fallbackChip = createSuggestionChip("Escribir \"$digits\" (ABC)", primary = true) {
                performHaptic()
                listener?.onFallbackMultiTapRequested()
            }
            suggestionContainer.addView(fallbackChip, marginEndParams(6))
        } else {
            val hint = TextView(context).apply {
                text = "Desliza ← borra palabra · → espacio"
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dpToPx(6f), 0, dpToPx(6f), 0)
            }
            suggestionContainer.addView(hint)
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

    private fun createNumericKey(
        mainLabel: String,
        subLabel: String,
        onClick: () -> Unit,
        onLongClick: (() -> Boolean)? = null
    ): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = createKeyDrawable(
                normalColor = ContextCompat.getColor(context, R.color.kb_key_bg),
                pressedColor = ContextCompat.getColor(context, R.color.kb_key_pressed)
            )
            isClickable = true
            isFocusable = true

            val mainTv = TextView(context).apply {
                text = mainLabel
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val subTv = TextView(context).apply {
                text = subLabel
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                gravity = Gravity.CENTER
            }
            addView(mainTv)
            addView(subTv)

            setOnClickListener { onClick() }
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(8f), dpToPx(5f), dpToPx(8f), dpToPx(5f))
            background = GradientDrawable().apply {
                setColor(
                    if (accent) ContextCompat.getColor(context, R.color.kb_accent_soft)
                    else ContextCompat.getColor(context, R.color.kb_surface)
                )
                cornerRadius = dpToPx(10f).toFloat()
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

    private fun marginEndParams(dp: Int): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dpToPx(dp.toFloat())
        }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).roundToInt()
}
