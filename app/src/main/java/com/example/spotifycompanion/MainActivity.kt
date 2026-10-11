package com.example.spotifycompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.spotifycompanion.databinding.ActivityMainBinding
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var pending: Triple<String, Int, String>? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                AppState.log("Notification permission denied. The app still works, but the status notification may be hidden.")
            }
            pending?.let { startConnection(it.first, it.second, it.third) }
            pending = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        b.hostInput.setText(prefs.host)
        b.portInput.setText(prefs.port.toString())

        b.testButton.setOnClickListener { testConnection() }
        b.connectButton.setOnClickListener { onConnect() }
        b.disconnectButton.setOnClickListener {
            stopService(Intent(this, ConnectionService::class.java))
        }
        setupNotes()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AppState.ui.collect { render(it) } }
                launch { NoteState.ui.collect { renderNotes(it) } }
                launch { NoteState.diag.collect { renderDiag(it) } }
                launch { while (true) { NoteAutomation.refreshStatus(); delay(1500) } }
                launch {
                    AppState.log.collect { lines ->
                        b.logText.text = lines.joinToString("\n")
                        b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    private fun render(s: UiState) {
        when (s.conn) {
            ConnState.CONNECTED -> { b.statusText.text = "CONNECTED"; b.statusText.setTextColor(Color.rgb(46, 160, 67)) }
            ConnState.CONNECTING -> { b.statusText.text = "CONNECTING..."; b.statusText.setTextColor(Color.rgb(210, 153, 34)) }
            ConnState.DISCONNECTED -> { b.statusText.text = "DISCONNECTED"; b.statusText.setTextColor(Color.rgb(220, 60, 50)) }
        }
        b.retryText.text = s.retryInfo
        b.retryText.visibility = if (s.retryInfo.isEmpty()) View.GONE else View.VISIBLE

        val t = s.track
        val has = t != null && t.hasSource
        b.titleText.text = if (t == null) "-" else if (has) t.title else "Nothing playing"
        b.artistText.text = if (t != null && has) t.artist else "-"
        b.playbackText.text = if (t == null || !has) "-" else if (t.isPlaying) "▶ Playing" else "⏸ Paused"
        b.lastMessageText.text = s.lastMessage
        b.lastErrorText.text = s.lastError

        b.connectButton.isEnabled = !s.active
        b.disconnectButton.isEnabled = s.active
        b.hostInput.isEnabled = !s.active
        b.portInput.isEnabled = !s.active
        b.codeInput.isEnabled = !s.active
    }

    private fun readHostPort(): Pair<String, Int>? {
        val host = b.hostInput.text.toString().trim()
        val port = b.portInput.text.toString().trim().toIntOrNull()
        if (!Net.isPrivateIpv4(host)) {
            AppState.error("Enter your PC's private IPv4 address (like 192.168.x.x or 10.x.x.x)")
            return null
        }
        if (port == null || !Net.isValidPort(port)) {
            AppState.error("Port must be a number between 1024 and 65535")
            return null
        }
        return host to port
    }

    private fun onConnect() {
        val (host, port) = readHostPort() ?: return
        val code = b.codeInput.text.toString().trim()
        if (!Net.isValidCode(code)) {
            AppState.error("The pairing code is exactly 6 digits (shown in the PC app)")
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = Triple(host, port, code)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startConnection(host, port, code)
        }
    }

    private fun startConnection(host: String, port: Int, code: String) {
        prefs.host = host
        prefs.port = port
        val intent = Intent(this, ConnectionService::class.java)
            .setAction(ConnectionService.ACTION_CONNECT)
            .putExtra(ConnectionService.EXTRA_HOST, host)
            .putExtra(ConnectionService.EXTRA_PORT, port)
            .putExtra(ConnectionService.EXTRA_CODE, code)
        ContextCompat.startForegroundService(this, intent)
    }

    /** Plain HTTP GET of the server's start page. Does not use or test the pairing code. */
    private fun testConnection() {
        val (host, port) = readHostPort() ?: return
        prefs.host = host
        prefs.port = port
        b.testButton.isEnabled = false
        AppState.log("Testing http://$host:$port/ ...")
        lifecycleScope.launch {
            val result: Pair<Boolean, String> = withContext(Dispatchers.IO) {
                try {
                    http.newCall(Request.Builder().url("http://$host:$port/").build()).execute().use { r ->
                        when (r.code) {
                            200 -> true to "Server reachable (HTTP 200). Now enter the pairing code and tap Connect."
                            403 -> false to "Server answered but refused this phone (it only accepts private-network addresses)."
                            404 -> false to "A server answered, but dashboard.html is missing next to server.py. The WebSocket may still work."
                            else -> false to "Unexpected HTTP ${r.code} from $host:$port."
                        }
                    }
                } catch (e: IOException) {
                    false to "Cannot reach $host:$port (${e.message ?: e.javaClass.simpleName}). Check: same Wi-Fi, phone server started, firewall rule, correct IP/port."
                }
            }
            if (result.first) AppState.log("TEST OK: ${result.second}") else AppState.error("TEST FAILED: ${result.second}")
            b.testButton.isEnabled = true
        }
    }

    // ======================================================================
    // Stage 3: Messenger Notes section (everything below is new)
    // ======================================================================

    private val labelFields = linkedMapOf(
        "l_entry" to "Notes entry labels (one per line, case-insensitive, whole-label regex)",
        "l_marker" to "Notes editor marker labels",
        "l_share" to "Share button labels",
        "l_guard" to "Chat-control labels (if visible: never type)",
        "l_replace" to "Replace / new-note menu labels",
        "l_delete" to "Delete menu labels"
    )
    private val labelInputs = HashMap<String, EditText>()

    private fun setupNotes() {
        NoteAutomation.init(this)
        val ns = NoteSettings(this)

        b.singleTemplateInput.setText(ns.singleTemplate)
        b.multiTemplateInput.setText(ns.multiTemplate)
        b.multiSwitch.isChecked = ns.useMulti
        b.delayInput.setText(ns.delaySec.toString())
        b.limitInput.setText(ns.charLimit.toString())
        b.pausedSwitch.isChecked = ns.updateWhenPaused
        b.backSwitch.isChecked = ns.allowBack
        b.deleteSwitch.isChecked = ns.deleteFirst

        for ((key, title) in labelFields) {
            val tv = android.widget.TextView(this).apply { text = title; setPadding(0, 12, 0, 0) }
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                hint = "(empty = built-in guesses)"
                setText(ns.rawLabels(key))
            }
            labelInputs[key] = et
            b.labelsContainer.addView(tv); b.labelsContainer.addView(et)
        }
        b.labelsToggleButton.setOnClickListener {
            b.labelsContainer.visibility = if (b.labelsContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        b.saveNoteSettingsButton.setOnClickListener { saveNoteSettings() }
        b.openMessengerButton.setOnClickListener { openMessenger() }
        b.openA11yButton.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.diagSwitch.setOnClickListener { NoteAutomation.setDiagnostic(b.diagSwitch.isChecked) }
        b.captureButton.setOnClickListener {
            AppState.log("[Notes] Capturing in 8 s: switch to Messenger and open the screen you want recorded")
            NoteAutomation.captureAfter(8)
        }
        b.copyDiagButton.setOnClickListener {
            val text = NoteState.diag.value.joinToString("\n")
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diagnostics", text))
            Toast.makeText(this, "Diagnostics copied (${text.length} chars)", Toast.LENGTH_SHORT).show()
        }
        b.clearDiagButton.setOnClickListener { NoteState.clearDiag() }
        b.testNoteButton.setOnClickListener { onTestNote() }
        b.approveButton.setOnClickListener { NoteAutomation.approveTest() }
        b.autoSwitch.setOnClickListener {
            if (b.autoSwitch.isChecked) { if (!NoteAutomation.enableAuto()) b.autoSwitch.isChecked = false }
            else NoteAutomation.stopNow("switched off by you")
        }
        b.stopAutoButton.setOnClickListener {
            NoteAutomation.stopNow("Stop Automation pressed")
            b.autoSwitch.isChecked = false
        }
    }

    private fun saveNoteSettings() {
        val ns = NoteSettings(this)
        val single = b.singleTemplateInput.text.toString()
        val multi = b.multiTemplateInput.text.toString()
        NoteFormat.validateTemplate(single)?.let { AppState.error("Single-line template: $it"); return }
        NoteFormat.validateTemplate(multi)?.let { AppState.error("Multiline template: $it"); return }
        val delay = b.delayInput.text.toString().toIntOrNull()
        val limit = b.limitInput.text.toString().toIntOrNull()
        if (delay == null || delay !in 0..120) { AppState.error("Delay must be 0-120 seconds"); return }
        if (limit == null || limit !in 10..500) { AppState.error("Limit must be 10-500 characters"); return }
        ns.singleTemplate = single
        ns.multiTemplate = multi
        ns.useMulti = b.multiSwitch.isChecked
        ns.delaySec = delay
        ns.charLimit = limit
        ns.updateWhenPaused = b.pausedSwitch.isChecked
        ns.allowBack = b.backSwitch.isChecked
        ns.deleteFirst = b.deleteSwitch.isChecked
        for ((key, et) in labelInputs) ns.setRawLabels(key, et.text.toString())
        AppState.log("[Notes] Note settings saved")
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun openMessenger() {
        val i = packageManager.getLaunchIntentForPackage(MessengerNotesService.MESSENGER)
        if (i == null) { AppState.error("Messenger (com.facebook.orca) not detected on this phone"); return }
        startActivity(i)
    }

    private fun onTestNote() {
        NoteAutomation.refreshStatus()
        val ui = NoteState.ui.value
        if (!ui.messengerInstalled) { AppState.error("Messenger not detected"); return }
        if (!ui.serviceConnected) {
            AppState.error("Turn the accessibility service ON first (Open Android Accessibility settings > Spotify Companion)")
            return
        }
        val ns = NoteSettings(this)
        val t = AppState.ui.value.track
        val sample = if (t != null && t.hasSource) NoteFormat.render(ns.activeTemplate, t.title, t.artist, ns.charLimit)
        else NoteFormat.render(ns.activeTemplate, "Test song", "Test artist", ns.charLimit)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setText(sample)
        }
        fun go(publish: Boolean) {
            val text = input.text.toString()
            val n = NoteFormat.cp(text)
            if (n > ns.charLimit) { AppState.error("Test text is $n characters; your limit setting is ${ns.charLimit}"); return }
            AppState.log("[Notes] Manual test started (${if (publish) "fill and share" else "fill only"}), $n characters")
            NoteAutomation.runManualTest(text, publish)
        }
        AlertDialog.Builder(this)
            .setTitle("Confirm the exact Note text")
            .setMessage("This exact text will be typed into YOUR Messenger Note (limit assumed: ${ns.charLimit} characters).\n\n" +
                "Fill only = types it and stops; you tap Share in Messenger yourself. Recommended for the first test.\n" +
                "Fill and Share = also taps Share after checking the text.")
            .setView(input)
            .setPositiveButton("Fill and Share") { _, _ -> go(true) }
            .setNeutralButton("Fill only") { _, _ -> go(false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderDiag(lines: List<String>) {
        b.diagText.text = if (lines.isEmpty()) "(diagnostic reports appear here)" else lines.joinToString("\n")
    }

    private fun renderNotes(s: NoteUi) {
        b.accStatusText.text = when {
            s.serviceConnected -> "ENABLED (running)"
            s.accessibilityEnabled -> "ENABLED (not started yet)"
            else -> "DISABLED"
        }
        b.accStatusText.setTextColor(if (s.serviceConnected) Color.rgb(46, 160, 67) else Color.rgb(220, 60, 50))
        b.messengerStatusText.text = if (s.messengerInstalled) "DETECTED (version ${s.messengerVersion})" else "NOT DETECTED"
        b.messengerStatusText.setTextColor(if (s.messengerInstalled) Color.rgb(46, 160, 67) else Color.rgb(220, 60, 50))
        b.openMessengerButton.isEnabled = s.messengerInstalled
        if (b.diagSwitch.isChecked != s.diagnosticOn) b.diagSwitch.isChecked = s.diagnosticOn
        b.testNoteButton.isEnabled = s.serviceConnected && s.messengerInstalled && !s.busy
        b.approveButton.isEnabled = s.testRan && !s.approved
        b.approveButton.text = if (s.approved) "Test approved for Messenger ${s.messengerVersion}" else "I checked Messenger: the test Note is correct - approve"
        b.autoSwitch.isEnabled = s.approved && s.serviceConnected
        if (b.autoSwitch.isChecked != s.autoOn) b.autoSwitch.isChecked = s.autoOn
        b.stopAutoButton.isEnabled = s.autoOn || s.busy
        b.lastResultText.text = s.lastResult
        b.lastSuccessText.text = s.lastSuccess
        b.pendingText.text = if (s.busy) "Working on a Note update..." else s.pendingInfo
    }
}
