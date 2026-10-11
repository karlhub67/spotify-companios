package com.example.spotifycompanion

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

/** What the screen shows about the current song. */
data class TrackInfo(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val timestamp: String
) {
    /** false when the PC reports "no Spotify source". */
    val hasSource: Boolean get() = title.isNotEmpty()
}

/** Everything the main screen displays (except the log). */
data class UiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    /** true while the connection service is running (including retry waits). */
    val active: Boolean = false,
    val track: TrackInfo? = null,
    val lastMessage: String = "-",
    val lastError: String = "-",
    val retryInfo: String = ""
)
