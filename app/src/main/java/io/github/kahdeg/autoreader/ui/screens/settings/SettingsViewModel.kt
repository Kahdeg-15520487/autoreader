package io.github.kahdeg.autoreader.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppSettings(
    val apiBaseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelName: String = "gpt-4o-mini",
    val lookAheadLimit: Int = 5
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val prefs = context.getSharedPreferences("autoreader_settings", Context.MODE_PRIVATE)
    
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    
    private fun loadSettings(): AppSettings {
        return AppSettings(
            apiBaseUrl = prefs.getString("api_base_url", "https://api.openai.com/v1") ?: "",
            apiKey = prefs.getString("api_key", "") ?: "",
            modelName = prefs.getString("model_name", "gpt-4o-mini") ?: "",
            lookAheadLimit = prefs.getInt("look_ahead_limit", 5)
        )
    }
    
    private fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString("api_base_url", settings.apiBaseUrl)
            .putString("api_key", settings.apiKey)
            .putString("model_name", settings.modelName)
            .putInt("look_ahead_limit", settings.lookAheadLimit)
            .apply()
    }
    
    fun onApiBaseUrlChange(value: String) {
        _settings.update { it.copy(apiBaseUrl = value) }
        viewModelScope.launch { saveSettings(_settings.value) }
    }
    
    fun onApiKeyChange(value: String) {
        _settings.update { it.copy(apiKey = value) }
        viewModelScope.launch { saveSettings(_settings.value) }
    }
    
    fun onModelNameChange(value: String) {
        _settings.update { it.copy(modelName = value) }
        viewModelScope.launch { saveSettings(_settings.value) }
    }
    
    fun onLookAheadChange(value: Int) {
        _settings.update { it.copy(lookAheadLimit = value.coerceIn(1, 10)) }
        viewModelScope.launch { saveSettings(_settings.value) }
    }
}
