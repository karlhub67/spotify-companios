package com.example.spotifycompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

/**
 * Owns the WebSocket to the PC server (ws://<ip>:<port>/ws).
 *
 * Protocol (taken from the existing server.py, unchanged):
 *   1. Phone opens /ws and, within 10 s, sends {"type":"pair","code":"123456"}.
 *   2. Server replies {"type":"paired"}, then {"type":"snapshot", ...}.
 *   3. Later: {"type":"track_changed"|"playback_changed"|"no_source", "title",
 *      "artist","is_playing","timestamp"}.
 *   4. Refusals: {"type":"error","message":...,"fatal":true} then the server closes.
 *
 * Runs as a foreground service so Android is much less likely to stop it
 * while the screen is off. This is NOT a guarantee - see README.
 */
class ConnectionService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.example.spotifycompanion.CONNECT"
        const val ACTION_DISCONNECT = "com.example.spotifycompanion.DISCONNECT"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
        private const val CHANNEL_ID = "connection"
        private const val NOTIF_ID = 1
        private const val MAX_MESSAGE_CHARS = 4096
    }

    private data class Key(val none: Boolean, val title: String, val artist: String, val playing: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // pingInterval: detects a silently dead Wi-Fi link and triggers a reconnect.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var host = ""
    private var port = 0
    private var code = ""            // kept in memory only
    private var socket: WebSocket? = null
    private var generation = 0       // callbacks from an older socket are ignored
    private var retry = 0
    private var reconnectJob: Job? = null
    private var paired = false
    private var fatal = false
    private var lastKey: Key? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Connection status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        registerFutureModules()
    }

    /** Future Messenger Notes module registers here: TrackEvents.register(MyModule()). */
    private fun registerFutureModules() {
        // Stage 3: Messenger Notes automation. It stays OFF until you approve a manual test in the app.
        NoteAutomation.init(applicationContext)
        TrackEvents.register(NoteAutomation)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // Must call startForeground quickly after startForegroundService().
                enterForeground("Connecting...")
                val h = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val p = intent.getIntExtra(EXTRA_PORT, 0)
                val c = intent.getStringExtra(EXTRA_CODE).orEmpty()
                if (!Net.isPrivateIpv4(h) || !Net.isValidPort(p) || !Net.isValidCode(c)) {
                    AppState.error("Invalid connection settings; not connecting")
                    shutdown("Not connecting")
                    return START_NOT_STICKY
                }
                host = h; port = p; code = c
                retry = 0
                lastKey = null
                AppState.update { it.copy(active = true, lastError = "-", retryInfo = "") }
                AppState.log("Connecting to ws://$host:$port/ws")
                openSocket()
            }
            ACTION_DISCONNECT -> shutdown("Disconnected by user")
            else -> stopSelf()
        }
        // Not sticky: after a system kill we have no pairing code, so do not auto-restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        closeSocket()
        scope.cancel()
        if (AppState.ui.value.active) {
            AppState.update { it.copy(conn = ConnState.DISCONNECTED, active = false, retryInfo = "") }
            AppState.log("Disconnected")
        }
        code = ""
        super.onDestroy()
    }

    // ---------------- connection handling ----------------

    private fun openSocket() {
        reconnectJob?.cancel()
        closeSocket()
        generation++
        val gen = generation
        paired = false
        fatal = false
        AppState.update { it.copy(conn = ConnState.CONNECTING, retryInfo = "") }

        val request = Request.Builder().url("http://$host:$port/ws").build()
        val pairCode = code
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "pair").put("code", pairCode).toString())
                scope.launch { if (gen == generation) AppState.log("Socket open; pairing code sent") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (gen == generation) handleMessage(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { onSocketEnded(gen, "closed by PC (code $code ${reason.ifEmpty { "no reason" }})") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
                scope.launch { onSocketEnded(gen, why) }
            }
        })
    }

    private fun closeSocket() {
        generation++                       // makes every old callback a no-op
        try { socket?.close(1000, "bye") } catch (_: Exception) {}
        socket = null
    }

    private fun onSocketEnded(gen: Int, reason: String) {
        if (gen != generation) return
        socket = null
        val wasPaired = paired
        paired = false

        if (fatal) {                        // wrong code / blocked / timed out: do NOT retry
            AppState.log("PC refused the pairing - not retrying")
            shutdown("Stopped")
            return
        }
        val wait = minOf(15, 1 shl minOf(retry, 4))     // 1,2,4,8,15,15... seconds
        retry++
        AppState.error((if (wasPaired) "Connection lost: " else "Could not connect: ") + reason)
        AppState.update { it.copy(conn = ConnState.DISCONNECTED, retryInfo = "Retrying in $wait s...") }
        updateNotification("Disconnected - retrying in $wait s")
        reconnectJob = scope.launch {
            delay(wait * 1000L)
            openSocket()
        }
    }

    private fun shutdown(logText: String) {
        reconnectJob?.cancel()
        closeSocket()
        code = ""
        AppState.update { it.copy(conn = ConnState.DISCONNECTED, active = false, retryInfo = "") }
        AppState.log(logText)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- messages ----------------

    private fun handleMessage(text: String) {
        if (text.length > MAX_MESSAGE_CHARS) {
            AppState.log("Ignored oversized message"); return
        }
        val m = try { JSONObject(text) } catch (e: JSONException) {
            AppState.log("Ignored invalid message (not JSON)"); return
        }
        when (val type = m.optString("type", "")) {
            "paired" -> {
                paired = true
                retry = 0
                AppState.update { it.copy(conn = ConnState.CONNECTED, retryInfo = "") }
                AppState.log("Paired and connected")
                updateNotification("Connected to $host:$port")
            }
            "snapshot", "track_changed", "playback_changed", "no_source" -> {
                if (!paired) { AppState.log("Ignored $type before pairing"); return }
                handleTrack(type, m)
            }
            "error" -> {
                val msg = m.optString("message", "Error from PC").take(200)
                if (m.optBoolean("fatal", false)) fatal = true
                AppState.error("PC says: $msg")
            }
            "pong" -> { /* not used */ }
            else -> AppState.log("Ignored unknown message type '${type.take(40)}'")
        }
    }

    private fun handleTrack(type: String, m: JSONObject) {
        val none = type == "no_source"
        val title = if (none) "" else m.optString("title", "").trim().take(300)
        val artist = if (none) "" else m.optString("artist", "").trim().take(300)
        val playing = !none && m.optBoolean("is_playing", false)
        val ts = m.optString("timestamp", "").take(40)
        val key = Key(title.isEmpty(), title, artist, playing)
        val summary = if (title.isEmpty()) "no Spotify source"
        else "$title - $artist (${if (playing) "playing" else "paused"})"

        if (key == lastKey) {
            AppState.update { it.copy(lastMessage = "${AppState.clock()}  $type (duplicate ignored)") }
            AppState.log("Duplicate $type ignored")
            return
        }
        lastKey = key
        AppState.update {
            it.copy(
                track = TrackInfo(title, artist, playing, ts),
                lastMessage = "${AppState.clock()}  $type: $summary"
            )
        }
        AppState.log("$type: $summary")
        updateNotification(if (title.isEmpty()) "Connected - nothing playing" else "Connected - $title - $artist")

        if (type != "snapshot") {
            TrackEvents.dispatch(TrackEvent(type, title, artist, playing, ts))
        } else {
            TrackEvents.dispatchSnapshot(TrackEvent(type, title, artist, playing, ts))
        }
    }

    // ---------------- notification ----------------

    private fun enterForeground(text: String) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Spotify Companion")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
