package co.com.onecontrol.mykeyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import co.com.onecontrol.mykeyboard.R
import co.com.onecontrol.mykeyboard.ime.KeyboardAlignment
import co.com.onecontrol.mykeyboard.ime.KeyboardConfig
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
class ResizableKeyboardContainer(context: Context) : FrameLayout(context) {

    var onDimensionsChanged: ((widthPercent: Int, keyHeightDp: Int, alignment: KeyboardAlignment, bottomMarginDp: Int) -> Unit)? = null
    var onDimensionsCommitted: ((widthPercent: Int, keyHeightDp: Int, alignment: KeyboardAlignment, bottomMarginDp: Int) -> Unit)? = null

    private var widthPercent: Int = KeyboardConfig.DEFAULT_WIDTH_PERCENT
    private var keyHeightDp: Int = KeyboardConfig.DEFAULT_KEY_HEIGHT_DP
    private var alignment: KeyboardAlignment = KeyboardAlignment.RIGHT
    private var bottomMarginDp: Int = KeyboardConfig.DEFAULT_BOTTOM_MARGIN_DP
    private var isResizeMode: Boolean = false

    private val cardWrapper: FrameLayout
    private val contentSlot: FrameLayout
    private val overlayLayer: FrameLayout
    private val infoLabel: TextView
    private val alignButtons = mutableMapOf<KeyboardAlignment, TextView>()

    private val leftHandle: View
    private val rightHandle: View
    private val topHandle: View
    private val bottomHandle: View

    private val cardBg = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, R.color.kb_bg))
        cornerRadius = dpToPx(18f).toFloat()
        setStroke(dpToPx(1f), ContextCompat.getColor(context, R.color.kb_border))
    }

    private val overlayBorderBg = GradientDrawable().apply {
        setColor(Color.parseColor("#224C9AFF"))
        cornerRadius = dpToPx(18f).toFloat()
        setStroke(dpToPx(2.5f), ContextCompat.getColor(context, R.color.kb_resize_overlay))
    }

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false

        cardWrapper = FrameLayout(context).apply {
            background = cardBg
            elevation = dpToPx(8f).toFloat()
            clipChildren = false
            clipToPadding = false
        }

        contentSlot = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        cardWrapper.addView(contentSlot)

        overlayLayer = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = overlayBorderBg
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        // Top controls bar inside overlay (alignment + readout + Done)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(8f), dpToPx(14f), dpToPx(8f), dpToPx(8f))
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        infoLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dpToPx(10f), dpToPx(4f), dpToPx(10f), dpToPx(4f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC121418"))
                cornerRadius = dpToPx(10f).toFloat()
            }
        }
        topBar.addView(infoLabel)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(8f), 0, 0)
        }

        KeyboardAlignment.entries.forEach { align ->
            val btn = createPillButton(align.label) {
                alignment = align
                updateInfoAndHandles()
                applyLayout()
                commitDimensions()
            }
            alignButtons[align] = btn
            btnRow.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(3f)
                marginEnd = dpToPx(3f)
            })
        }

        val doneBtn = createPillButton("Listo ✓", accent = true) {
            setResizeMode(false)
            commitDimensions()
        }
        btnRow.addView(doneBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dpToPx(6f)
        })

        topBar.addView(btnRow)
        overlayLayer.addView(topBar)

        // Drag handles
        leftHandle = createHandleView(vertical = true)
        rightHandle = createHandleView(vertical = true)
        topHandle = createHandleView(vertical = false)
        bottomHandle = createHandleView(vertical = false)

        overlayLayer.addView(leftHandle, LayoutParams(dpToPx(24f), dpToPx(56f), Gravity.START or Gravity.CENTER_VERTICAL))
        overlayLayer.addView(rightHandle, LayoutParams(dpToPx(24f), dpToPx(56f), Gravity.END or Gravity.CENTER_VERTICAL))
        overlayLayer.addView(topHandle, LayoutParams(dpToPx(64f), dpToPx(22f), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        overlayLayer.addView(bottomHandle, LayoutParams(dpToPx(64f), dpToPx(22f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        setupHorizontalDrag(leftHandle, isLeftEdge = true)
        setupHorizontalDrag(rightHandle, isLeftEdge = false)
        setupKeyHeightDrag(topHandle)
        setupBottomMarginDrag(bottomHandle)

        cardWrapper.addView(overlayLayer)
        addView(cardWrapper)
        applyLayout()
    }

    fun setKeyboardView(view: View) {
        contentSlot.removeAllViews()
        contentSlot.addView(
            view,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    fun applyConfig(config: KeyboardConfig) {
        if (isResizeMode) return
        widthPercent = config.widthPercent
        keyHeightDp = config.keyHeightDp
        alignment = config.alignment
        bottomMarginDp = config.bottomMarginDp
        applyLayout()
        updateInfoAndHandles()
    }

    fun setResizeMode(enabled: Boolean) {
        isResizeMode = enabled
        overlayLayer.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            updateInfoAndHandles()
        }
    }

    fun isInResizeMode(): Boolean = isResizeMode

    private fun createPillButton(text: String, accent: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dpToPx(10f), dpToPx(6f), dpToPx(10f), dpToPx(6f))
            background = GradientDrawable().apply {
                setColor(
                    if (accent) ContextCompat.getColor(context, R.color.kb_accent)
                    else Color.parseColor("#DD1E2229")
                )
                cornerRadius = dpToPx(12f).toFloat()
                setStroke(dpToPx(1f), ContextCompat.getColor(context, R.color.kb_border))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createHandleView(vertical: Boolean): View {
        return FrameLayout(context).apply {
            val pill = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.kb_accent))
                    cornerRadius = dpToPx(4f).toFloat()
                }
            }
            val w = if (vertical) dpToPx(6f) else dpToPx(40f)
            val h = if (vertical) dpToPx(36f) else dpToPx(6f)
            addView(pill, LayoutParams(w, h, Gravity.CENTER))
        }
    }

    private fun setupHorizontalDrag(handle: View, isLeftEdge: Boolean) {
        var startRawX = 0f
        var startWidthPercent = widthPercent

        handle.setOnTouchListener { _, event ->
            val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startWidthPercent = widthPercent
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val factor = if (alignment == KeyboardAlignment.CENTER) 2f else 1f
                    val deltaPercent = ((dx * factor) / screenWidth * 100f).roundToInt()
                    val signedDelta = if (isLeftEdge) -deltaPercent else deltaPercent
                    widthPercent = (startWidthPercent + signedDelta).coerceIn(
                        KeyboardConfig.MIN_WIDTH_PERCENT,
                        KeyboardConfig.MAX_WIDTH_PERCENT
                    )
                    applyLayout()
                    updateInfoAndHandles()
                    notifyDimensionsChanged()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    commitDimensions()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupKeyHeightDrag(handle: View) {
        var startRawY = 0f
        var startKeyHeightDp = keyHeightDp

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    startKeyHeightDp = keyHeightDp
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dyDp = pxToDp(startRawY - event.rawY)
                    // 4 rows of keys -> divide vertical drag by 4 for smooth 1:1 feel
                    val deltaKeyDp = (dyDp / 3.5f).roundToInt()
                    keyHeightDp = (startKeyHeightDp + deltaKeyDp).coerceIn(
                        KeyboardConfig.MIN_KEY_HEIGHT_DP,
                        KeyboardConfig.MAX_KEY_HEIGHT_DP
                    )
                    applyLayout()
                    updateInfoAndHandles()
                    notifyDimensionsChanged()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    commitDimensions()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupBottomMarginDrag(handle: View) {
        var startRawY = 0f
        var startBottomMarginDp = bottomMarginDp

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    startBottomMarginDp = bottomMarginDp
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dyDp = pxToDp(startRawY - event.rawY).roundToInt()
                    bottomMarginDp = (startBottomMarginDp + dyDp).coerceIn(
                        KeyboardConfig.MIN_BOTTOM_MARGIN_DP,
                        KeyboardConfig.MAX_BOTTOM_MARGIN_DP
                    )
                    applyLayout()
                    updateInfoAndHandles()
                    notifyDimensionsChanged()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    commitDimensions()
                    true
                }
                else -> false
            }
        }
    }

    private fun applyLayout() {
        val screenWidth = resources.displayMetrics.widthPixels
        val targetWidth = (screenWidth * (widthPercent / 100f)).roundToInt()
        val gravity = when (alignment) {
            KeyboardAlignment.LEFT -> Gravity.START or Gravity.BOTTOM
            KeyboardAlignment.CENTER -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            KeyboardAlignment.RIGHT -> Gravity.END or Gravity.BOTTOM
        }
        val sideMargin = if (widthPercent < 100) dpToPx(6f) else 0
        val params = LayoutParams(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT, gravity).apply {
            bottomMargin = dpToPx(bottomMarginDp.toFloat())
            topMargin = dpToPx(4f)
            marginStart = if (alignment == KeyboardAlignment.LEFT) sideMargin else 0
            marginEnd = if (alignment == KeyboardAlignment.RIGHT) sideMargin else 0
        }
        cardWrapper.layoutParams = params

        val child = contentSlot.getChildAt(0)
        if (child is T9KeyboardView) {
            child.setKeyHeightDp(keyHeightDp)
        }
    }

    private fun updateInfoAndHandles() {
        infoLabel.text = "Ancho: $widthPercent% · Tecla: ${keyHeightDp}dp · Base: ${bottomMarginDp}dp"
        alignButtons.forEach { (align, btn) ->
            val bg = btn.background as? GradientDrawable ?: return@forEach
            bg.setColor(
                if (align == alignment) ContextCompat.getColor(context, R.color.kb_accent_soft)
                else Color.parseColor("#DD1E2229")
            )
        }
        leftHandle.visibility = if (alignment == KeyboardAlignment.LEFT) View.GONE else View.VISIBLE
        rightHandle.visibility = if (alignment == KeyboardAlignment.RIGHT) View.GONE else View.VISIBLE
    }

    private fun notifyDimensionsChanged() {
        onDimensionsChanged?.invoke(widthPercent, keyHeightDp, alignment, bottomMarginDp)
    }

    private fun commitDimensions() {
        onDimensionsCommitted?.invoke(widthPercent, keyHeightDp, alignment, bottomMarginDp)
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).roundToInt()

    private fun pxToDp(px: Float): Float =
        px / resources.displayMetrics.density
}
