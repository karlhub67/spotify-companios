package com.example.spotifycompanion

import java.util.concurrent.CopyOnWriteArrayList

/**
 * ===== EXTENSION POINT FOR THE FUTURE MESSENGER NOTES STAGE =====
 *
 * A real change reported by the PC (track_changed, playback_changed,
 * no_source). Duplicates are already removed. "snapshot" messages (sent once
 * right after pairing) update the screen but are NEVER dispatched here, as the
 * server README requires: a snapshot must not update a Messenger Note by itself.
 */
data class TrackEvent(
    val type: String,          // "track_changed" | "playback_changed" | "no_source"
    val title: String,         // "" for no_source
    val artist: String,
    val isPlaying: Boolean,
    val timestamp: String      // ISO-8601 string produced by the PC
)

interface TrackEventListener {
    /** Called on the main thread. Keep it fast; hand slow work to a coroutine. */
    fun onTrackEvent(event: TrackEvent)

    /**
     * The snapshot sent right after (re)pairing. Default: ignore. It is NOT a track change, so by default
     * modules must not act on it (see NotePlanner.onSnapshot for the one safe use: resync after Wi-Fi loss).
     */
    fun onSnapshot(event: TrackEvent) {}
}

object TrackEvents {
    private val listeners = CopyOnWriteArrayList<TrackEventListener>()

    fun register(l: TrackEventListener) { listeners.addIfAbsent(l) }
    fun unregister(l: TrackEventListener) { listeners.remove(l) }

    internal fun dispatchSnapshot(event: TrackEvent) {
        for (l in listeners) {
            try {
                l.onSnapshot(event)
            } catch (t: Throwable) {
                AppState.log("Listener failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    internal fun dispatch(event: TrackEvent) {
        for (l in listeners) {
            try {
                l.onTrackEvent(event)
            } catch (t: Throwable) {          // one bad module must not break the receiver
                AppState.log("Listener failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
