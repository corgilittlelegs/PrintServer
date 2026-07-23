package dev.jaspreet.printserver.scan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScanToneSettings(
    val brightness: Int = ScanTone.DEFAULT,
    val contrast: Int = ScanTone.DEFAULT,
)

object ScanToneSettingsState {
    private val _settings = MutableStateFlow(ScanToneSettings())
    val settings: StateFlow<ScanToneSettings> = _settings.asStateFlow()

    fun update(brightness: Int, contrast: Int) {
        _settings.value = ScanToneSettings(
            brightness = ScanTone.resolve(brightness),
            contrast = ScanTone.resolve(contrast),
        )
    }
}
