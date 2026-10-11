package com.example.spotifycompanion

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Connects the existing track receiver (TrackEvents) to the Messenger Notes service.
 * All decisions (dedupe, delay, coalescing, one job at a time, retries) live in [NotePlanner];
 * this object only drives timers and runs the job. Everything runs on the main thread.
 *
 * Automation is OFF until: the accessibility service is connected AND you approved a manual test
 * for the Messenger version that is currently installed. It is never saved as "on" across app restarts.
 */
object NoteAutomation : TrackEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val planner = NotePlanner()
    private var timer: Job? = null
    private var job: Job? = null
    private val manualBusy = AtomicBoolean(false)
    private lateinit var appCtx: Context
    private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        appCtx = context.applicationContext
        initialised = true
        refreshStatus()
    }

    private fun settings() = NoteSettings(appCtx)
    private fun now() = System.currentTimeMillis()
    private fun note(msg: String) = AppState.log("[Notes] $msg")

    // ------------------------------------------------------------ status

    fun refreshStatus() {
        if (!initialised) return
        val version: String
        try {
            val pi = appCtx.packageManager.getPackageInfo(MessengerNotesService.MESSENGER, 0)
            version = pi.versionName ?: pi.longVersionCode.toString()
        } catch (_: Exception) {
            NoteState.update { it.copy(messengerInstalled = false, messengerVersion = "") }
            updateAccessibilityFlags()
            return
        }
        val approved = settings().approvedMessengerVersion.let { it.isNotEmpty() && it == version }
        NoteState.update { it.copy(messengerInstalled = true, messengerVersion = version, approved = approved) }
        updateAccessibilityFlags()
    }

    private fun updateAccessibilityFlags() {
        val me = ComponentName(appCtx, MessengerNotesService::class.java)
        val enabledList = Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val enabled = enabledList.split(':').any {
            ComponentName.unflattenFromString(it) == me
        }
        NoteState.update { it.copy(accessibilityEnabled = enabled, serviceConnected = MessengerNotesService.instance != null) }
    }

    /** Called by the service if Android disconnects it (you switched it off). */
    fun onServiceLost() {
        if (initialised && NoteState.ui.value.autoOn) stopNow("Accessibility service was switched off")
    }

    // ------------------------------------------------------------ approval / toggle / stop

    /** You looked at Messenger and the test Note was right. */
    fun approveTest() {
        refreshStatus()
        val v = NoteState.ui.value
        if (!v.testRan) { note("Run a test first"); return }
        if (v.messengerVersion.isEmpty()) { note("Messenger version unknown; cannot approve"); return }
        settings().approvedMessengerVersion = v.messengerVersion
        NoteState.update { it.copy(approved = true) }
        note("Test approved for Messenger ${v.messengerVersion}. You can now enable automatic updates.")
    }

    fun revokeApproval() {
        settings().approvedMessengerVersion = ""
        NoteState.update { it.copy(approved = false) }
        stopNow("Approval revoked")
    }

    fun enableAuto(): Boolean {
        refreshStatus()
        val v = NoteState.ui.value
        val problem = when {
            !v.approved -> "Approve a manual test first (for Messenger ${v.messengerVersion.ifEmpty { "?" }})"
            !v.serviceConnected -> "Turn on the accessibility service in Android Settings first"
            else -> null
        }
        if (problem != null) { note("Cannot enable automatic updates: $problem"); return false }
        planner.config = settings().plannerConfig()
        planner.enable()
        NoteState.update { it.copy(autoOn = true, pendingInfo = "") }
        note("Automatic Note updates ON. The next real track change will update the Note.")
        return true
    }

    /** Immediate stop: clears the queue, cancels the running job. */
    fun stopNow(reason: String) {
        planner.stop()
        timer?.cancel(); timer = null
        job?.cancel(); job = null
        NoteState.update { it.copy(autoOn = false, busy = false, pendingInfo = "") }
        note("Automation stopped: $reason")
    }

    // ------------------------------------------------------------ track events (called by ConnectionService)

    override fun onTrackEvent(event: TrackEvent) {
        if (!initialised) return
        val v = planner.onTrack(event.type, event.title, event.artist, event.isPlaying, now())
        afterVerdict(event.title, event.artist, v)
    }

    override fun onSnapshot(event: TrackEvent) {
        if (!initialised) return
        val v = planner.onSnapshot(event.title, event.artist, event.isPlaying, now())
        // quiet for the normal first snapshot
        if (v is NotePlanner.Verdict.Queued) afterVerdict(event.title, event.artist, v)
    }

    private fun afterVerdict(title: String, artist: String, v: NotePlanner.Verdict) {
        when (v) {
            is NotePlanner.Verdict.Ignored -> if (planner.enabled) note("Track not queued: ${v.why}")
            is NotePlanner.Verdict.Queued -> {
                note((if (v.replaced) "Newer track replaces the pending one: " else "Queued: ") + "$title - $artist (waiting ${settings().delaySec}s)")
                NoteState.update { it.copy(pendingInfo = "Pending: $title - $artist") }
            }
        }
        scheduleTimer()
    }

    private fun scheduleTimer() {
        timer?.cancel()
        val ms = planner.msUntilDue(now()) ?: return
        timer = scope.launch { delay(ms + 20); tick() }
    }

    private fun tick() {
        if (manualBusy.get()) { timer = scope.launch { delay(2000); tick() }; return }
        val t = planner.takeDue(now()) ?: run { scheduleTimer(); return }
        NoteState.update { it.copy(busy = true, pendingInfo = "") }
        job = scope.launch { execute(t) }
    }

    private suspend fun execute(t: NotePlanner.Track) {
        var failure = NotePlanner.Failure.TRANSIENT
        try {
            val svc = MessengerNotesService.instance
            val s = settings()
            val result = if (svc == null)
                RunResult(Outcome.FAILED, "SERVICE_NOT_CONNECTED", "Accessibility service is not running.", true)
            else svc.runNoteUpdate(
                textFor = { lim -> NoteFormat.render(s.activeTemplate, t.title, t.artist, lim) },
                publish = true, settings = s, log = { note(it) })
            record(result, "${t.title} - ${t.artist}")
            failure = when {
                result.ok -> NotePlanner.Failure.NONE
                result.structural -> NotePlanner.Failure.STRUCTURAL
                else -> NotePlanner.Failure.TRANSIENT
            }
            if (failure == NotePlanner.Failure.STRUCTURAL) note("Automation stopped because Messenger's screen is not as expected: ${result.detail}")
        } catch (e: CancellationException) {
            throw e
        } finally {
            planner.finished(t, failure, now())
            NoteState.update { it.copy(busy = false, autoOn = planner.enabled) }
            job = null
            scheduleTimer()
        }
    }

    // ------------------------------------------------------------ manual test

    /** Runs ONE Note job with exactly [text]. Never starts if another job is running. */
    fun runManualTest(text: String, publish: Boolean) {
        refreshStatus()
        val svc = MessengerNotesService.instance
        if (svc == null) { note("Test not started: accessibility service is not connected"); recordRaw("NOT STARTED - turn on the accessibility service"); return }
        if (planner.running || manualBusy.get()) { note("Test not started: a Note job is already running"); return }
        val err = NoteFormat.validateText(text)
        if (err != null) { note("Test not started: $err"); recordRaw("NOT STARTED - $err"); return }
        manualBusy.set(true)
        NoteState.update { it.copy(busy = true) }
        job = scope.launch {
            try {
                val s = settings()
                val result = svc.runNoteUpdate(textFor = { text }, publish = publish, settings = s, log = { note(it) })
                record(result, "test: " + text.replace("\n", " / "))
                NoteState.update { it.copy(testRan = result.ok) }   // only a successful test can be approved
            } catch (e: CancellationException) {
                recordRaw("CANCELLED")
                throw e
            } finally {
                manualBusy.set(false)
                NoteState.update { it.copy(busy = false) }
                job = null
            }
        }
    }

    /** Gives you time to switch to Messenger (our own app would otherwise be the active window). */
    fun captureAfter(seconds: Int) {
        scope.launch { delay(seconds * 1000L); captureNow() }
    }

    fun captureNow() {
        val svc = MessengerNotesService.instance
        if (svc == null) { NoteState.addDiag(listOf("${AppState.clock()}  Accessibility service is not connected")); return }
        svc.diagnosticSnapshot("manual capture")
    }

    fun setDiagnostic(on: Boolean) {
        NoteState.update { it.copy(diagnosticOn = on) }
        note(if (on) "Diagnostic mode ON (only Messenger screens; texts redacted)" else "Diagnostic mode OFF")
    }

    // ------------------------------------------------------------ results

    private fun record(r: RunResult, what: String) {
        val stamp = AppState.clock()
        val line = "$stamp  ${r.code}: ${r.detail}  [$what]"
        NoteState.update {
            it.copy(
                lastResult = line,
                lastSuccess = if (r.outcome == Outcome.VERIFIED || r.outcome == Outcome.PUBLISHED_UNVERIFIED) "$stamp  ${r.code}  [$what]" else it.lastSuccess
            )
        }
        note("Result ${r.code}: ${r.detail}")
    }

    private fun recordRaw(s: String) = NoteState.update { it.copy(lastResult = "${AppState.clock()}  $s") }
}
