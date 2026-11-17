package co.com.onecontrol.mykeyboard.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import co.com.onecontrol.mykeyboard.databinding.ActivityMainBinding
import co.com.onecontrol.mykeyboard.ime.KeyboardAlignment
import co.com.onecontrol.mykeyboard.ime.KeyboardConfig
import co.com.onecontrol.mykeyboard.ime.KeyboardPreferences
import co.com.onecontrol.mykeyboard.ime.T9InputMethodService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: KeyboardPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = KeyboardPreferences(applicationContext)

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

    override fun onResume() {
        super.onResume()
        updateImeStatus()
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
