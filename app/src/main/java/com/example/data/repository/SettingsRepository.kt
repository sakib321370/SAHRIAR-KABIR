package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.data.model.ProcessingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "banglasub_settings")

data class AppSettings(
  val processingMode: ProcessingMode =
    if (BuildConfig.DEFAULT_BACKEND_URL.isNotBlank()) ProcessingMode.FFMPEG_BACKEND
    else ProcessingMode.CLOUD_AI,
  val backendBaseUrl: String = BuildConfig.DEFAULT_BACKEND_URL.trim().trimEnd('/'),
  val subtitleFontSizeSp: Float = 20f,
  val showEnglishDualLine: Boolean = false,
)

class SettingsRepository(private val context: Context) {
  private val dataStore = context.applicationContext.appSettingsDataStore

  val settingsFlow: Flow<AppSettings> =
    dataStore.data.map { prefs ->
      val configuredDefaultUrl = BuildConfig.DEFAULT_BACKEND_URL.trim().trimEnd('/')
      val savedUrl = prefs[KEY_BACKEND_URL].orEmpty().trim().trimEnd('/')
      val effectiveUrl = savedUrl.ifBlank { configuredDefaultUrl }
      val defaultMode =
        if (effectiveUrl.isNotBlank()) ProcessingMode.FFMPEG_BACKEND else ProcessingMode.CLOUD_AI
      AppSettings(
        processingMode =
          ProcessingMode.fromWire(prefs[KEY_PROCESSING_MODE] ?: defaultMode.wireValue),
        backendBaseUrl = effectiveUrl,
        subtitleFontSizeSp = (prefs[KEY_FONT_SIZE] ?: 20f).coerceIn(14f, 34f),
        showEnglishDualLine = prefs[KEY_DUAL_LINE] ?: false,
      )
    }

  suspend fun setProcessingMode(mode: ProcessingMode) {
    dataStore.edit { it[KEY_PROCESSING_MODE] = mode.wireValue }
  }

  suspend fun setBackendBaseUrl(url: String) {
    dataStore.edit { it[KEY_BACKEND_URL] = url.trim().trimEnd('/') }
  }

  suspend fun setSubtitleFontSize(sizeSp: Float) {
    dataStore.edit { it[KEY_FONT_SIZE] = sizeSp.coerceIn(14f, 34f) }
  }

  suspend fun setShowEnglishDualLine(enabled: Boolean) {
    dataStore.edit { it[KEY_DUAL_LINE] = enabled }
  }

  private companion object {
    val KEY_PROCESSING_MODE = stringPreferencesKey("processing_mode")
    val KEY_BACKEND_URL = stringPreferencesKey("backend_base_url")
    val KEY_FONT_SIZE = floatPreferencesKey("subtitle_font_size_sp")
    val KEY_DUAL_LINE = booleanPreferencesKey("show_english_dual_line")
  }
}
