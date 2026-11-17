package co.com.onecontrol.mykeyboard.ime

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import co.com.onecontrol.mykeyboard.engine.InputMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.keyboardDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "t9_keyboard_prefs"
)

enum class KeyboardAlignment(val label: String) {
    LEFT("Izq"),
    CENTER("Centro"),
    RIGHT("Der")
}

data class KeyboardConfig(
    val widthPercent: Int = DEFAULT_WIDTH_PERCENT,
    val keyHeightDp: Int = DEFAULT_KEY_HEIGHT_DP,
    val alignment: KeyboardAlignment = KeyboardAlignment.RIGHT,
    val bottomMarginDp: Int = DEFAULT_BOTTOM_MARGIN_DP,
    val hapticStrength: Int = DEFAULT_HAPTIC_STRENGTH,
    val multiTapTimeoutMs: Long = DEFAULT_MULTITAP_TIMEOUT_MS,
    val inputMode: InputMode = InputMode.T9_ES
) {
    companion object {
        const val MIN_WIDTH_PERCENT = 55
        const val MAX_WIDTH_PERCENT = 100
        const val DEFAULT_WIDTH_PERCENT = 72

        const val MIN_KEY_HEIGHT_DP = 38
        const val MAX_KEY_HEIGHT_DP = 76
        const val DEFAULT_KEY_HEIGHT_DP = 52

        const val MIN_BOTTOM_MARGIN_DP = 0
        const val MAX_BOTTOM_MARGIN_DP = 64
        const val DEFAULT_BOTTOM_MARGIN_DP = 14

        const val DEFAULT_HAPTIC_STRENGTH = 60
        const val DEFAULT_MULTITAP_TIMEOUT_MS = 650L
    }
}

class KeyboardPreferences(private val context: Context) {

    private object Keys {
        val WIDTH_PERCENT = intPreferencesKey("width_percent")
        val KEY_HEIGHT_DP = intPreferencesKey("key_height_dp")
        val ALIGNMENT = stringPreferencesKey("alignment")
        val BOTTOM_MARGIN_DP = intPreferencesKey("bottom_margin_dp")
        val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        val MULTITAP_TIMEOUT_MS = longPreferencesKey("multitap_timeout_ms")
        val INPUT_MODE = stringPreferencesKey("input_mode")
    }

    val configFlow: Flow<KeyboardConfig> = context.keyboardDataStore.data.map { prefs ->
        val alignName = prefs[Keys.ALIGNMENT] ?: KeyboardAlignment.RIGHT.name
        val alignment = runCatching { KeyboardAlignment.valueOf(alignName) }
            .getOrDefault(KeyboardAlignment.RIGHT)

        val modeName = prefs[Keys.INPUT_MODE] ?: InputMode.T9_ES.name
        val inputMode = runCatching { InputMode.valueOf(modeName) }
            .getOrDefault(InputMode.T9_ES)

        KeyboardConfig(
            widthPercent = (prefs[Keys.WIDTH_PERCENT] ?: KeyboardConfig.DEFAULT_WIDTH_PERCENT)
                .coerceIn(KeyboardConfig.MIN_WIDTH_PERCENT, KeyboardConfig.MAX_WIDTH_PERCENT),
            keyHeightDp = (prefs[Keys.KEY_HEIGHT_DP] ?: KeyboardConfig.DEFAULT_KEY_HEIGHT_DP)
                .coerceIn(KeyboardConfig.MIN_KEY_HEIGHT_DP, KeyboardConfig.MAX_KEY_HEIGHT_DP),
            alignment = alignment,
            bottomMarginDp = (prefs[Keys.BOTTOM_MARGIN_DP] ?: KeyboardConfig.DEFAULT_BOTTOM_MARGIN_DP)
                .coerceIn(KeyboardConfig.MIN_BOTTOM_MARGIN_DP, KeyboardConfig.MAX_BOTTOM_MARGIN_DP),
            hapticStrength = (prefs[Keys.HAPTIC_STRENGTH] ?: KeyboardConfig.DEFAULT_HAPTIC_STRENGTH)
                .coerceIn(0, 100),
            multiTapTimeoutMs = (prefs[Keys.MULTITAP_TIMEOUT_MS] ?: KeyboardConfig.DEFAULT_MULTITAP_TIMEOUT_MS)
                .coerceIn(250L, 1500L),
            inputMode = inputMode
        )
    }

    suspend fun updateDimensions(
        widthPercent: Int,
        keyHeightDp: Int,
        alignment: KeyboardAlignment,
        bottomMarginDp: Int
    ) {
        context.keyboardDataStore.edit { prefs ->
            prefs[Keys.WIDTH_PERCENT] = widthPercent.coerceIn(
                KeyboardConfig.MIN_WIDTH_PERCENT,
                KeyboardConfig.MAX_WIDTH_PERCENT
            )
            prefs[Keys.KEY_HEIGHT_DP] = keyHeightDp.coerceIn(
                KeyboardConfig.MIN_KEY_HEIGHT_DP,
                KeyboardConfig.MAX_KEY_HEIGHT_DP
            )
            prefs[Keys.ALIGNMENT] = alignment.name
            prefs[Keys.BOTTOM_MARGIN_DP] = bottomMarginDp.coerceIn(
                KeyboardConfig.MIN_BOTTOM_MARGIN_DP,
                KeyboardConfig.MAX_BOTTOM_MARGIN_DP
            )
        }
    }

    suspend fun updateHapticStrength(strength: Int) {
        context.keyboardDataStore.edit { prefs ->
            prefs[Keys.HAPTIC_STRENGTH] = strength.coerceIn(0, 100)
        }
    }

    suspend fun updateMultiTapTimeout(timeoutMs: Long) {
        context.keyboardDataStore.edit { prefs ->
            prefs[Keys.MULTITAP_TIMEOUT_MS] = timeoutMs.coerceIn(250L, 1500L)
        }
    }

    suspend fun updateInputMode(mode: InputMode) {
        context.keyboardDataStore.edit { prefs ->
            prefs[Keys.INPUT_MODE] = mode.name
        }
    }
}
