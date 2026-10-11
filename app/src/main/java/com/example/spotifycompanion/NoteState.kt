package com.example.spotifycompanion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the Messenger Notes section of the screen shows. Separate from AppState so the working receiver state is untouched. */
data class NoteUi(
    val accessibilityEnabled: Boolean = false,   // switched on in Android Settings
    val serviceConnected: Boolean = false,       // Android actually started our service
    val messengerInstalled: Boolean = false,
    val messengerVersion: String = "",
    val diagnosticOn: Boolean = false,
    val busy: Boolean = false,                   // a Note job is running right now
    val testRan: Boolean = false,                // a manual test finished in this app session
    val approved: Boolean = false,               // you approved the test for THIS Messenger version
    val autoOn: Boolean = false,
    val lastResult: String = "-",
    val lastSuccess: String = "-",
    val pendingInfo: String = ""
)

object NoteState {
    private const val MAX_DIAG_LINES = 400

    private val _ui = MutableStateFlow(NoteUi())
    val ui: StateFlow<NoteUi> = _ui.asStateFlow()

    private val _diag = MutableStateFlow<List<String>>(emptyList())
    val diag: StateFlow<List<String>> = _diag.asStateFlow()

    fun update(block: (NoteUi) -> NoteUi) = _ui.update(block)

    fun addDiag(lines: List<String>) {
        _diag.update { (it + lines).takeLast(MAX_DIAG_LINES) }
    }

    fun clearDiag() { _diag.value = emptyList() }
}
