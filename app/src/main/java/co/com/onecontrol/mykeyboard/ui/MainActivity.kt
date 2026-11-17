package co.com.onecontrol.mykeyboard.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import co.com.onecontrol.mykeyboard.R
import co.com.onecontrol.mykeyboard.databinding.ActivityMainBinding
import co.com.onecontrol.mykeyboard.dictionary.TextShortcutStore
import co.com.onecontrol.mykeyboard.ime.KeyboardAlignment
import co.com.onecontrol.mykeyboard.ime.KeyboardConfig
import co.com.onecontrol.mykeyboard.ime.KeyboardPreferences
import co.com.onecontrol.mykeyboard.ime.T9InputMethodService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: KeyboardPreferences
    private lateinit var shortcutStore: TextShortcutStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = KeyboardPreferences(applicationContext)
        shortcutStore = TextShortcutStore(applicationContext)

        binding.btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        binding.btnSelectIme.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        binding.sliderHaptic.addOnChangeListener { _, value, fromUser ->
            val strength = value.toInt()
            binding.tvHapticLabel.text = "Vibración háptica: $strength%"
            if (fromUser) {
                lifecycleScope.launch {
                    preferences.updateHapticStrength(strength)
                }
            }
        }

        binding.sliderTimeout.addOnChangeListener { _, value, fromUser ->
            val ms = value.toLong()
            binding.tvTimeoutLabel.text = "Temporizador Multi-tap (ABC): $ms ms"
            if (fromUser) {
                lifecycleScope.launch {
                    preferences.updateMultiTapTimeout(ms)
                }
            }
        }

        binding.btnResetDimensions.setOnClickListener {
            lifecycleScope.launch {
                preferences.updateDimensions(
                    widthPercent = KeyboardConfig.DEFAULT_WIDTH_PERCENT,
                    keyHeightDp = KeyboardConfig.DEFAULT_KEY_HEIGHT_DP,
                    alignment = KeyboardAlignment.RIGHT,
                    bottomMarginDp = KeyboardConfig.DEFAULT_BOTTOM_MARGIN_DP
                )
            }
        }

        binding.btnAddShortcut.setOnClickListener {
            showAddShortcutDialog()
        }

        renderShortcutsList()

        lifecycleScope.launch {
            preferences.configFlow.collectLatest { cfg ->
                binding.sliderHaptic.value = cfg.hapticStrength.toFloat()
                binding.tvHapticLabel.text = "Vibración háptica: ${cfg.hapticStrength}%"
                binding.sliderTimeout.value = cfg.multiTapTimeoutMs.toFloat()
                binding.tvTimeoutLabel.text = "Temporizador Multi-tap (ABC): ${cfg.multiTapTimeoutMs} ms"
                binding.tvDimensionsSummary.text =
                    "Dimensiones: ${cfg.widthPercent}% ancho · ${cfg.keyHeightDp}dp tecla · ${cfg.alignment.label} · +${cfg.bottomMarginDp}dp base"
            }
        }
    }

    private fun renderShortcutsList() {
        val container = binding.layoutShortcutsList
        container.removeAllViews()

        val shortcuts = shortcutStore.getAllShortcuts()
        if (shortcuts.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No tienes atajos guardados aún. Toca el botón para agregar uno."
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 16, 0, 16)
            }
            container.addView(tvEmpty)
            return
        }

        shortcuts.forEach { sc ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
                setBackgroundColor(ContextCompat.getColor(context, R.color.kb_surface))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(8f)
                }
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvShortcut = TextView(this).apply {
                text = "⚡ ${sc.shortcut}"
                setTextColor(ContextCompat.getColor(context, R.color.kb_accent))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val tvExpansion = TextView(this).apply {
                text = sc.expansion
                setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dpToPx(2f), 0, 0)
            }
            textLayout.addView(tvShortcut)
            textLayout.addView(tvExpansion)
            row.addView(textLayout)

            val btnDelete = TextView(this).apply {
                text = "🗑"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dpToPx(12f), dpToPx(6f), dpToPx(12f), dpToPx(6f))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    shortcutStore.deleteShortcut(sc.shortcut)
                    renderShortcutsList()
                    Toast.makeText(this@MainActivity, "Atajo eliminado", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(btnDelete)

            container.addView(row)
        }
    }

    private fun showAddShortcutDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20f), dpToPx(12f), dpToPx(20f), dpToPx(4f))
        }

        val etShortcut = EditText(this).apply {
            hint = "Atajo (ej: micorreo, micel, dir)"
            isSingleLine = true
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
        }
        val etExpansion = EditText(this).apply {
            hint = "Texto expandido (ej: correo@gmail.com)"
            setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.kb_text_secondary))
        }

        layout.addView(etShortcut)
        layout.addView(etExpansion)

        MaterialAlertDialogBuilder(this)
            .setTitle("Nuevo atajo de texto")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val shortcut = etShortcut.text.toString().trim()
                val expansion = etExpansion.text.toString().trim()
                if (shortcut.isNotEmpty() && expansion.isNotEmpty()) {
                    shortcutStore.saveShortcut(shortcut, expansion)
                    renderShortcutsList()
                    Toast.makeText(this, "Atajo guardado: ⚡ $shortcut", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Por favor llena ambos campos", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    override fun onResume() {
        super.onResume()
        updateImeStatus()
        renderShortcutsList()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateImeStatus()
        }
    }

    private fun updateImeStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val ourComponentShort = ComponentName(this, T9InputMethodService::class.java).flattenToShortString()
        val ourComponentFull = ComponentName(this, T9InputMethodService::class.java).flattenToString()
        val isEnabled = imm.enabledInputMethodList.any {
            it.packageName == packageName || it.id == ourComponentShort || it.id == ourComponentFull || it.serviceInfo.packageName == packageName
        }
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val isSelected = defaultIme.contains(packageName) || defaultIme == ourComponentShort || defaultIme == ourComponentFull

        binding.tvStatusBadge.text = when {
            isEnabled && isSelected -> "Estado: ✓ Habilitado y seleccionado como teclado activo"
            isEnabled -> "Estado: ✓ Habilitado (toca el paso 2 para seleccionarlo)"
            else -> "Estado: Pendiente de habilitar en Ajustes (paso 1)"
        }
    }
}
