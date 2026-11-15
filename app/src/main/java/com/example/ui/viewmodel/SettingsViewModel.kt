package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ProcessingMode
import com.example.data.remote.BackendApiClient
import com.example.data.repository.AppSettings
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface BackendHealthState {
  data object Idle : BackendHealthState
  data object Checking : BackendHealthState
  data object Reachable : BackendHealthState
  data class Unreachable(val reason: String) : BackendHealthState
}

class SettingsViewModel(
  private val settingsRepository: SettingsRepository,
  private val backendApiClient: BackendApiClient = BackendApiClient(),
) : ViewModel() {

  val settingsState: StateFlow<AppSettings> =
    settingsRepository.settingsFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000L),
      initialValue = AppSettings(),
    )

  private val _healthState = MutableStateFlow<BackendHealthState>(BackendHealthState.Idle)
  val healthState: StateFlow<BackendHealthState> = _healthState.asStateFlow()

  fun setProcessingMode(mode: ProcessingMode) {
    viewModelScope.launch { settingsRepository.setProcessingMode(mode) }
  }

  fun setBackendBaseUrl(url: String) {
    _healthState.value = BackendHealthState.Idle
    viewModelScope.launch { settingsRepository.setBackendBaseUrl(url) }
  }

  fun setSubtitleFontSize(sizeSp: Float) {
    viewModelScope.launch { settingsRepository.setSubtitleFontSize(sizeSp) }
  }

  fun setShowEnglishDualLine(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setShowEnglishDualLine(enabled) }
  }

  fun testBackendConnection(url: String) {
    viewModelScope.launch {
      _healthState.value = BackendHealthState.Checking
      val trimmed = url.trim().trimEnd('/')
      if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        _healthState.value =
          BackendHealthState.Unreachable("URL must start with https:// or http://")
        return@launch
      }
      settingsRepository.setBackendBaseUrl(trimmed)
      val ok = backendApiClient.checkHealth(trimmed)
      _healthState.value =
        if (ok) {
          BackendHealthState.Reachable
        } else {
          BackendHealthState.Unreachable("Could not reach $trimmed/health")
        }
    }
  }
}
