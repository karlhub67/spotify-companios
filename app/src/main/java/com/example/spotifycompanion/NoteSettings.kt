package com.example.spotifycompanion

import android.content.Context

/**
 * Note-related settings. Stored on the phone only. No pairing code, no Note text history.
 * Label lists: empty = use the built-in guesses from LabelSet.
 */
class NoteSettings(context: Context) {
    private val sp = context.getSharedPreferences("note_settings", Context.MODE_PRIVATE)

    var singleTemplate: String
        get() = sp.getString("single", NoteFormat.DEFAULT_SINGLE) ?: NoteFormat.DEFAULT_SINGLE
        set(v) = sp.edit().putString("single", v).apply()

    var multiTemplate: String
        get() = sp.getString("multi", NoteFormat.DEFAULT_MULTI) ?: NoteFormat.DEFAULT_MULTI
        set(v) = sp.edit().putString("multi", v).apply()

    var useMulti: Boolean
        get() = sp.getBoolean("useMulti", false)
        set(v) = sp.edit().putBoolean("useMulti", v).apply()

    val activeTemplate: String get() = if (useMulti) multiTemplate else singleTemplate

    /** Seconds to wait after a track change before touching Messenger (coalescing window). */
    var delaySec: Int
        get() = sp.getInt("delaySec", 3)
        set(v) = sp.edit().putInt("delaySec", v.coerceIn(0, 120)).apply()

    var updateWhenPaused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) = sp.edit().putBoolean("paused", v).apply()

    /** Assumed Note length limit. The live text field's own limit (if it reports one) overrides it when smaller. */
    var charLimit: Int
        get() = sp.getInt("limit", NoteFormat.DEFAULT_LIMIT)
        set(v) = sp.edit().putInt("limit", v.coerceIn(10, 500)).apply()

    /** Experimental: delete the existing Note first. Off: the editor's text is simply replaced. */
    var deleteFirst: Boolean
        get() = sp.getBoolean("deleteFirst", false)
        set(v) = sp.edit().putBoolean("deleteFirst", v).apply()

    /** Press Back (max 3 times, only while Messenger is the active window) to leave a chat and reach the Notes screen. */
    var allowBack: Boolean
        get() = sp.getBoolean("allowBack", true)
        set(v) = sp.edit().putBoolean("allowBack", v).apply()

    /** Messenger version you approved the one-time test for. A different version resets the approval. */
    var approvedMessengerVersion: String
        get() = sp.getString("approvedVersion2", "") ?: ""
        set(v) = sp.edit().putString("approvedVersion2", v).apply()

    private fun list(key: String, default: List<String>): List<String> {
        val s = sp.getString(key, "") ?: ""
        val p = LabelSet.parse(s)
        return if (p.isEmpty()) default else p
    }

    fun rawLabels(key: String): String = sp.getString(key, "") ?: ""
    fun setRawLabels(key: String, value: String) = sp.edit().putString(key, value).apply()

    fun labelSet(): LabelSet {
        val d = LabelSet()
        return LabelSet(
            entry = list("l_entry", d.entry),
            composerMarker = list("l_marker", d.composerMarker),
            share = list("l_share", d.share),
            chatGuardLabels = list("l_guard", d.chatGuardLabels),
            replace = list("l_replace", d.replace),
            delete = list("l_delete", d.delete),
            deleteConfirm = list("l_deleteConfirm", d.deleteConfirm)
        )
    }

    fun plannerConfig() = NotePlanner.Config(
        delayMs = delaySec * 1000L,
        updateWhenPaused = updateWhenPaused
    )
}
