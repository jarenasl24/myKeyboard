package co.com.onecontrol.mykeyboard.ui.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import co.com.onecontrol.mykeyboard.R

@SuppressLint("ClickableViewAccessibility")
class EmojiMediaPanelView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onEmojiSelected(emoji: String)
        fun onKaomojiSelected(kaomoji: String)
        fun onGifSelected(assetName: String, label: String)
        fun onCloseEmojiPanel()
        fun onBackspace()
        fun onSpace()
    }

    var listener: Listener? = null

    private enum class Tab(val label: String) {
        RECENTS("🕒"),
        SMILEYS("😃"),
        GESTURES("👍"),
        LOVE("❤️"),
        PARTY("🎉"),
        ANIMALS("🐶"),
        FOOD("🍔"),
        KAOMOJI("( ͡° ͜ʖ ͡°)"),
        GIFS("GIF")
    }

    private var currentTab = Tab.SMILEYS
    private var activeSearchTag: String? = null

    private val searchScroll: HorizontalScrollView
    private val searchChipsContainer: LinearLayout
    private val tabsScroll: HorizontalScrollView
    private val tabsContainer: LinearLayout
    private val contentScrollView: ScrollView
    private val gridContainer: LinearLayout
    private val bottomBar: LinearLayout

    private val tabViews = mutableMapOf<Tab, TextView>()
    private val searchChipViews = mutableListOf<TextView>()

    private val sharedPrefs = context.getSharedPreferences("t9_recent_emojis", Context.MODE_PRIVATE)

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(8f))

        // 1. Search filter chips row
        searchScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(34f)).apply {
                bottomMargin = dpToPx(4f)
            }
        }
        searchChipsContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchScroll.addView(searchChipsContainer)
        setupSearchChips()
        addView(searchScroll)

        // 2. Category tabs row
        tabsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(36f)).apply {
                bottomMargin = dpToPx(6f)
            }
        }
        tabsContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabsScroll.addView(tabsContainer)
        setupTabs()
        addView(tabsScroll)

        // 3. Middle scrollable content area
        contentScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(200f))
        }
        gridContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dpToPx(2f), dpToPx(2f), dpToPx(2f), dpToPx(4f))
        }
        contentScrollView.addView(gridContainer)
        addView(contentScrollView)

        // 4. Bottom action bar: [⌨ T9]   [␣ Espacio]   [⌫]
        bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(44f)).apply {
                topMargin = dpToPx(6f)
            }
        }
        setupBottomBar()
        addView(bottomBar)

        // Default display
        val recents = getRecentEmojis()
        if (recents.isNotEmpty()) {
            selectTab(Tab.RECENTS)
        } else {
            selectTab(Tab.SMILEYS)
        }
    }

    fun setPanelContentHeight(heightPx: Int) {
        val target = heightPx.coerceAtLeast(dpToPx(160f))
        val lp = contentScrollView.layoutParams
        if (lp.height != target) {
            lp.height = target
            contentScrollView.layoutParams = lp
        }
    }

    private fun setupSearchChips() {
        searchChipsContainer.removeAllViews()
        searchChipViews.clear()

        // "Todo" reset chip
        val allChip = createSearchChip("🔍 Todo", null)
        searchChipViews.add(allChip)
        searchChipsContainer.addView(allChip, marginEndParams(4))

        EmojiCatalog.SEARCH_CHIPS.forEach { (label, query) ->
            val chip = createSearchChip(label, query)
            searchChipViews.add(chip)
            searchChipsContainer.addView(chip, marginEndParams(4))
        }
    }

    private fun createSearchChip(label: String, query: String?): TextView {
        return TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dpToPx(9f), dpToPx(5f), dpToPx(9f), dpToPx(5f))
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.kb_key_bg))
                cornerRadius = dpToPx(12f).toFloat()
            }
            setOnClickListener {
                performHaptic()
                activeSearchTag = query
                updateSearchChipsHighlight()
                renderCurrentView()
            }
        }
    }

    private fun updateSearchChipsHighlight() {
        searchChipViews.forEachIndexed { index, chip ->
            val isSelected = if (activeSearchTag == null) index == 0 else {
                val tag = EmojiCatalog.SEARCH_CHIPS.getOrNull(index - 1)?.second
                tag == activeSearchTag
            }
            chip.setTextColor(if (isSelected) Color.WHITE else ContextCompat.getColor(context, R.color.kb_text_primary))
            (chip.background as? GradientDrawable)?.setColor(
                if (isSelected) ContextCompat.getColor(context, R.color.kb_accent)
                else ContextCompat.getColor(context, R.color.kb_key_bg)
            )
        }
    }

    private fun setupTabs() {
        tabsContainer.removeAllViews()
        tabViews.clear()

        Tab.values().forEach { tab ->
            val tv = TextView(context).apply {
                text = tab.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tab == Tab.KAOMOJI) 11f else 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dpToPx(12f), dpToPx(6f), dpToPx(12f), dpToPx(6f))
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dpToPx(10f).toFloat()
                }
                setOnClickListener {
                    performHaptic()
                    activeSearchTag = null
                    updateSearchChipsHighlight()
                    selectTab(tab)
                }
            }
            tabViews[tab] = tv
            tabsContainer.addView(tv, marginEndParams(4))
        }
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        tabViews.forEach { (t, view) ->
            val isSel = (t == tab)
            view.setTextColor(if (isSel) Color.WHITE else ContextCompat.getColor(context, R.color.kb_text_secondary))
            (view.background as? GradientDrawable)?.setColor(
                if (isSel) ContextCompat.getColor(context, R.color.kb_key_bg) else Color.TRANSPARENT
            )
        }
        renderCurrentView()
    }

    private fun renderCurrentView() {
        gridContainer.removeAllViews()
        contentScrollView.scrollTo(0, 0)

        // If a search tag is active, display search results
        if (activeSearchTag != null) {
            renderSearchResults(activeSearchTag!!)
            return
        }

        when (currentTab) {
            Tab.RECENTS -> {
                val recents = getRecentEmojis()
                if (recents.isEmpty()) {
                    showEmptyHint("No tienes emojis recientes aún")
                } else {
                    renderEmojiGrid(recents)
                }
            }
            Tab.SMILEYS -> renderEmojiGrid(EmojiCatalog.SMILEYS.map { it.char })
            Tab.GESTURES -> renderEmojiGrid(EmojiCatalog.GESTURES.map { it.char })
            Tab.LOVE -> renderEmojiGrid(EmojiCatalog.LOVE.map { it.char })
            Tab.PARTY -> renderEmojiGrid(EmojiCatalog.PARTY.map { it.char })
            Tab.ANIMALS -> renderEmojiGrid(EmojiCatalog.ANIMALS.map { it.char })
            Tab.FOOD -> renderEmojiGrid(EmojiCatalog.FOOD.map { it.char })
            Tab.KAOMOJI -> renderKaomojiGrid(EmojiCatalog.KAOMOJI_ITEMS)
            Tab.GIFS -> renderGifsList(EmojiCatalog.GIF_ITEMS)
        }
    }

    private fun renderSearchResults(query: String) {
        val emojis = EmojiCatalog.search(query)
        val gifs = EmojiCatalog.searchGifs(query)

        if (emojis.isEmpty() && gifs.isEmpty()) {
            showEmptyHint("No se encontraron resultados para \"$query\"")
            return
        }

        if (emojis.isNotEmpty()) {
            val title = createSectionHeader("Emojis (${emojis.size})")
            gridContainer.addView(title)
            renderEmojiGrid(emojis)
        }

        if (gifs.isNotEmpty()) {
            val title = createSectionHeader("GIFs / Stickers (${gifs.size})")
            gridContainer.addView(title)
            renderGifsList(gifs)
        }
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.kb_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(4f), dpToPx(8f), dpToPx(4f), dpToPx(4f))
        }
    }

    private fun showEmptyHint(message: String) {
        val tv = TextView(context).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dpToPx(16f), dpToPx(40f), dpToPx(16f), dpToPx(40f))
        }
        gridContainer.addView(tv)
    }

    private fun renderEmojiGrid(emojis: List<String>) {
        val columns = 7
        val rows = (emojis.size + columns - 1) / columns

        for (r in 0 until rows) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(44f)).apply {
                    bottomMargin = dpToPx(4f)
                }
            }

            for (c in 0 until columns) {
                val index = r * columns + c
                if (index < emojis.size) {
                    val emo = emojis[index]
                    val cell = TextView(context).apply {
                        text = emo
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        background = createKeyBackground()
                        setOnClickListener {
                            performHaptic()
                            addRecentEmoji(emo)
                            listener?.onEmojiSelected(emo)
                        }
                    }
                    row.addView(cell, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = if (c < columns - 1) dpToPx(3f) else 0
                    })
                } else {
                    val dummy = View(context)
                    row.addView(dummy, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                }
            }
            gridContainer.addView(row)
        }
    }

    private fun renderKaomojiGrid(kaomojis: List<String>) {
        val columns = 2
        val rows = (kaomojis.size + columns - 1) / columns

        for (r in 0 until rows) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(40f)).apply {
                    bottomMargin = dpToPx(5f)
                }
            }

            for (c in 0 until columns) {
                val index = r * columns + c
                if (index < kaomojis.size) {
                    val kao = kaomojis[index]
                    val cell = TextView(context).apply {
                        text = kao
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        background = createKeyBackground()
                        setOnClickListener {
                            performHaptic()
                            addRecentEmoji(kao)
                            listener?.onKaomojiSelected(kao)
                        }
                    }
                    row.addView(cell, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = if (c < columns - 1) dpToPx(6f) else 0
                    })
                } else {
                    val dummy = View(context)
                    row.addView(dummy, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                }
            }
            gridContainer.addView(row)
        }
    }

    private fun renderGifsList(gifs: List<GifItem>) {
        val columns = 2
        val rows = (gifs.size + columns - 1) / columns

        for (r in 0 until rows) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(88f)).apply {
                    bottomMargin = dpToPx(8f)
                }
            }

            for (c in 0 until columns) {
                val index = r * columns + c
                if (index < gifs.size) {
                    val item = gifs[index]
                    val card = createGifCard(item)
                    row.addView(card, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = if (c < columns - 1) dpToPx(6f) else 0
                    })
                } else {
                    val dummy = View(context)
                    row.addView(dummy, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                }
            }
            gridContainer.addView(row)
        }
    }

    private fun createGifCard(item: GifItem): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = createKeyBackground()
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            setOnClickListener {
                performHaptic()
                listener?.onGifSelected(item.assetName, item.title)
            }
        }

        // Live Animated GIF preview if API >= 28, else emoji badge
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.assets, "gifs/${item.assetName}")
                val drawable = ImageDecoder.decodeDrawable(source)
                val iv = ImageView(context).apply {
                    setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LayoutParams(dpToPx(52f), dpToPx(52f))
                }
                if (drawable is Animatable) {
                    drawable.start()
                }
                card.addView(iv)
            } catch (_: Exception) {
                val badge = TextView(context).apply {
                    text = item.emojiBadge
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    gravity = Gravity.CENTER
                }
                card.addView(badge)
            }
        } else {
            val badge = TextView(context).apply {
                text = item.emojiBadge
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                gravity = Gravity.CENTER
            }
            card.addView(badge)
        }

        val label = TextView(context).apply {
            text = item.title
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
        }
        card.addView(label)

        return card
    }

    private fun setupBottomBar() {
        bottomBar.removeAllViews()

        // 1. Return to T9 Keypad
        val t9Btn = TextView(context).apply {
            text = "⌨ T9"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.kb_accent))
                cornerRadius = dpToPx(12f).toFloat()
            }
            setOnClickListener {
                performHaptic()
                listener?.onCloseEmojiPanel()
            }
        }
        bottomBar.addView(t9Btn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f).apply {
            marginEnd = dpToPx(6f)
        })

        // 2. Space key
        val spaceBtn = TextView(context).apply {
            text = "ESPACIO"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
            gravity = Gravity.CENTER
            background = createKeyBackground()
            setOnClickListener {
                performHaptic()
                listener?.onSpace()
            }
        }
        bottomBar.addView(spaceBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.5f).apply {
            marginEnd = dpToPx(6f)
        })

        // 3. Backspace key
        val backspaceBtn = TextView(context).apply {
            text = "⌫"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            gravity = Gravity.CENTER
            background = createKeyBackground()
            setOnClickListener {
                performHaptic()
                listener?.onBackspace()
            }
        }
        bottomBar.addView(backspaceBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f))
    }

    private fun createKeyBackground(): StateListDrawable {
        val pressedBg = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.kb_key_pressed))
            cornerRadius = dpToPx(10f).toFloat()
        }
        val normalBg = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.kb_key_bg))
            cornerRadius = dpToPx(10f).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
            addState(intArrayOf(), normalBg)
        }
    }

    private fun getRecentEmojis(): List<String> {
        val raw = sharedPrefs.getString("recents", null) ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    private fun addRecentEmoji(item: String) {
        val current = getRecentEmojis().toMutableList()
        current.remove(item)
        current.add(0, item)
        val trimmed = current.take(28)
        sharedPrefs.edit().putString("recents", trimmed.joinToString(",")).apply()
    }

    private fun performHaptic() {
        performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    private fun marginEndParams(marginEndDp: Int): LayoutParams {
        return LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dpToPx(marginEndDp.toFloat())
        }
    }
}
