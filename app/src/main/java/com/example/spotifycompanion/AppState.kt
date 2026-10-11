package com.example.spotifycompanion

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide state shared by the service (writer) and the screen (reader).
 * Thread-safe. It lives only in memory, so nothing sensitive is written to disk.
 */
object AppState {
    private const val MAX_LOG_LINES = 300

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun clock(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    fun update(block: (UiState) -> UiState) = _ui.update(block)

    fun log(text: String) {
        val line = "${clock()}  $text"
        _log.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    fun error(text: String) {
        update { it.copy(lastError = "${clock()}  $text") }
        log("ERROR: $text")
    }
}
