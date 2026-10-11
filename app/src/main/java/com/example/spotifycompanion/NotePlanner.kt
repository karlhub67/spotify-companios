package com.example.spotifycompanion

/**
 * Pure Kotlin decision logic for automatic Note updates (no Android, no coroutines, time is passed in),
 * so the dedupe / coalescing / never-overlap rules can be tested on a PC.
 *
 * Rules implemented:
 *  - only genuinely new title+artist pairs are queued (duplicates / unchanged are ignored)
 *  - paused tracks are ignored unless [Config.updateWhenPaused]
 *  - every newer track REPLACES the pending one and restarts the delay (coalescing; newest only)
 *  - at most one job runs at a time; a track arriving during a job waits for it
 *  - a minimum gap between two publications
 *  - transient failures are retried a few times; structural failures stop automation
 */
class NotePlanner(var config: Config = Config()) {

    data class Config(
        val delayMs: Long = 3000,
        val updateWhenPaused: Boolean = false,
        val minGapMs: Long = 2000,
        val retryBackoffMs: Long = 20000,
        val maxAttempts: Int = 3
    )

    data class Track(val title: String, val artist: String) {
        val key: String get() = title.trim().lowercase() + "\u0000" + artist.trim().lowercase()
    }

    sealed class Verdict {
        data class Ignored(val why: String) : Verdict()
        data class Queued(val replaced: Boolean) : Verdict()
    }

    var enabled = false
        private set
    var running = false
        private set
    var pending: Track? = null
        private set
    var dueAt = 0L
        private set
    var lastPublished: Track? = null
        private set
    private var attempts = 0
    private var lastFinishAt = Long.MIN_VALUE / 2

    fun enable() { enabled = true }

    /** Immediate stop: nothing pending, nothing will start. A running job must be cancelled by the caller. */
    fun stop() { enabled = false; pending = null }

    fun onTrack(type: String, title: String, artist: String, isPlaying: Boolean, now: Long): Verdict {
        if (!enabled) return Verdict.Ignored("automation is off")
        if (type == "no_source") return Verdict.Ignored("Spotify has no source")
        val t = Track(title.trim(), artist.trim())
        if (t.title.isEmpty()) return Verdict.Ignored("empty title")
        if (!isPlaying && !config.updateWhenPaused) return Verdict.Ignored("paused (setting is off)")

        val published = lastPublished
        if (published != null && published.key == t.key) {
            if (pending != null) { pending = null; return Verdict.Ignored("went back to the track already shown in the Note") }
            return Verdict.Ignored("unchanged track")
        }
        val p = pending
        if (p != null && p.key == t.key) return Verdict.Ignored("duplicate of the pending track")

        pending = t
        dueAt = maxOf(now + config.delayMs, lastFinishAt + config.minGapMs)
        attempts = 0
        return Verdict.Queued(replaced = p != null)
    }

    /**
     * Snapshot after (re)connecting. Normally ignored (server README: a snapshot must not update a Note
     * by itself). Exception: if this process already published a Note, a snapshot that differs from it
     * means a track changed while Wi-Fi was down, so it is treated like a real change.
     */
    fun onSnapshot(title: String, artist: String, isPlaying: Boolean, now: Long): Verdict {
        if (lastPublished == null) return Verdict.Ignored("snapshot ignored (nothing published yet)")
        return onTrack("snapshot", title, artist, isPlaying, now)
    }

    /** Returns the track to publish if one is due and nothing is running; marks the job as running. */
    fun takeDue(now: Long): Track? {
        val p = pending
        if (!enabled || running || p == null || now < dueAt) return null
        running = true
        pending = null
        return p
    }

    /** Milliseconds until [takeDue] could return something, or null if nothing is waiting. */
    fun msUntilDue(now: Long): Long? =
        if (!enabled || running || pending == null) null else (dueAt - now).coerceAtLeast(0)

    enum class Failure { NONE, TRANSIENT, STRUCTURAL }

    fun finished(track: Track, failure: Failure, now: Long) {
        running = false
        lastFinishAt = now
        when (failure) {
            Failure.NONE -> { lastPublished = track; attempts = 0 }
            Failure.STRUCTURAL -> { enabled = false; pending = null }
            Failure.TRANSIENT -> {
                if (pending == null && enabled && attempts + 1 < config.maxAttempts) {
                    attempts++
                    pending = track
                    dueAt = now + config.retryBackoffMs
                    return
                }
            }
        }
        // a newer track arrived while the job ran: it waits at least minGap after this job
        if (pending != null) dueAt = maxOf(dueAt, now + config.minGapMs)
    }
}
