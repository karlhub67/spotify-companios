package com.example.spotifycompanion

import android.content.Context

/**
 * Saves only the PC address and port. The pairing code is deliberately NOT
 * saved: the PC makes a new one every time its server starts.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var port: Int
        get() = sp.getInt("port", 8765)
        set(v) = sp.edit().putInt("port", v).apply()
}
