package com.example.spotifycompanion

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Result of one Note job. */
enum class Outcome {
    VERIFIED,               // composer closed and the new text is visible in Messenger
    ALREADY_CURRENT,        // the editor already contained exactly this text; nothing was published
    FILLED_NOT_PUBLISHED,   // fill-only test: text typed and checked, Share left for you to tap
    PUBLISHED_UNVERIFIED,   // Share was tapped and the composer closed, but the text could not be seen afterwards
    FAILED
}

data class RunResult(
    val outcome: Outcome,
    val code: String,
    val detail: String,
    /** true = Messenger's screen does not look the way we expect (stop automation); false = temporary (locked phone, slow app). */
    val structural: Boolean = false
) {
    val ok: Boolean get() = outcome != Outcome.FAILED
}

private class FlowAbort(val code: String, val detail: String, val structural: Boolean) : Exception(detail)

/** A captured Messenger screen: flat description + the live nodes (same indexes) to act on. */
private class Screen(val nodes: List<NodeView>, val refs: List<AccessibilityNodeInfo>)

/**
 * Accessibility service that only ever looks at / acts on Messenger (com.facebook.orca).
 *  - Events are filtered to that package by res/xml/messenger_accessibility_config.xml.
 *  - Every capture and every action re-checks that the active window really is Messenger.
 *  - It never taps coordinates, never uses gestures, never reads other apps.
 *  - You must switch it on yourself in Android Settings > Accessibility.
 */
class MessengerNotesService : AccessibilityService() {

    companion object {
        const val MESSENGER = "com.facebook.orca"
        @Volatile var instance: MessengerNotesService? = null
        private const val MAX_NODES = 700
        private const val MAX_DEPTH = 45
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastWindowClass = ""
    private var lastReportHash = 0
    private val diagRunnable = Runnable { diagnosticSnapshot("window changed") }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        NoteState.update { it.copy(serviceConnected = true, accessibilityEnabled = true) }
        AppState.log("[Notes] Accessibility service connected (Messenger only)")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        NoteState.update { it.copy(serviceConnected = false, accessibilityEnabled = false) }
        NoteAutomation.onServiceLost()
        AppState.log("[Notes] Accessibility service disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() { /* nothing to interrupt */ }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != MESSENGER) return
        if (!NoteState.ui.value.diagnosticOn) return          // do nothing at all unless diagnostics were requested
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString().orEmpty()
            if (cls != lastWindowClass) {
                lastWindowClass = cls
                NoteState.addDiag(listOf("${AppState.clock()}  Messenger is in the foreground, window: ${cls.substringAfterLast('.')}"))
            }
        }
        handler.removeCallbacks(diagRunnable)
        handler.postDelayed(diagRunnable, 700)                // wait for the screen to settle
    }

    // ------------------------------------------------------------------ capture

    private fun activeIsMessenger(): Boolean = rootInActiveWindow?.packageName?.toString() == MESSENGER

    private fun capture(): Screen? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != MESSENGER) return null
        val nodes = ArrayList<NodeView>()
        val refs = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, parentTarget: Int, depth: Int) {
            if (nodes.size >= MAX_NODES || depth > MAX_DEPTH) return
            val idx = nodes.size
            val target = if (n.isClickable) idx else parentTarget
            nodes += NodeView(
                index = idx,
                cls = n.className?.toString().orEmpty(),
                viewId = n.viewIdResourceName.orEmpty(),
                text = n.text?.toString().orEmpty(),
                desc = n.contentDescription?.toString().orEmpty(),
                hint = n.hintText?.toString().orEmpty(),
                clickable = n.isClickable,
                editable = n.isEditable,
                enabled = n.isEnabled,
                visible = n.isVisibleToUser,
                showingHint = n.isShowingHintText,
                maxTextLength = n.maxTextLength,
                clickTarget = target
            )
            refs += n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, target, depth + 1)
            }
        }
        walk(root, -1, 0)
        return Screen(nodes, refs)
    }

    /** Diagnostic mode: privacy-filtered report of the current Messenger screen. */
    fun diagnosticSnapshot(reason: String) {
        val screen = capture()
        if (screen == null) {
            NoteState.addDiag(listOf("${AppState.clock()}  Messenger is NOT the active window - nothing recorded"))
            return
        }
        val labels = NoteSettings(this).labelSet()
        val lines = DiagnosticReport.build("${AppState.clock()} $reason", screen.nodes, labels)
        val h = lines.drop(1).joinToString("\n").hashCode()
        if (h == lastReportHash) return                        // identical screen, don't repeat
        lastReportHash = h
        NoteState.addDiag(lines)
    }

    // ------------------------------------------------------------------ safe actions

    private fun mustBeMessenger() {
        if (!activeIsMessenger()) throw FlowAbort("LEFT_MESSENGER", "Messenger is no longer the active window; stopped without doing anything else", false)
    }

    private fun click(screen: Screen, index: Int): Boolean {
        mustBeMessenger()
        if (index < 0 || index >= screen.refs.size) return false
        return screen.refs[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Polls until [pick] returns non-null or [timeoutMs] passes. */
    private suspend fun <T> waitFor(timeoutMs: Long, pick: (Screen, Classified) -> T?, labels: LabelSet): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            currentCoroutineContext().ensureActive()
            val s = capture()
            if (s != null) {
                val v = pick(s, ScreenClassifier.classify(s.nodes, labels))
                if (v != null) return v
            }
            if (System.currentTimeMillis() >= end) return null
            delay(250)
        }
    }

    private fun failure(e: FlowAbort) = RunResult(Outcome.FAILED, e.code, e.detail, e.structural)

    // ------------------------------------------------------------------ the Note job

    /**
     * Updates the Note. Never types anything unless the screen was classified as the Notes composer
     * (editable field + Share control + Notes marker, and NO chat controls). Never taps anything outside Messenger.
     *
     * [textFor] receives the effective character limit and returns the exact text to put in the Note.
     * [publish] = false stops after the text is typed and checked (you tap Share yourself).
     */
    suspend fun runNoteUpdate(
        textFor: (Int) -> String,
        publish: Boolean,
        settings: NoteSettings,
        log: (String) -> Unit
    ): RunResult {
        val labels = settings.labelSet()
        try {
            val km = getSystemService(KeyguardManager::class.java)
            if (km != null && km.isKeyguardLocked()) throw FlowAbort("PHONE_LOCKED", "The phone is locked. Unlock it; accessibility cannot operate Messenger on a locked screen.", false)
            if (packageManager.getLaunchIntentForPackage(MESSENGER) == null) throw FlowAbort("MESSENGER_NOT_INSTALLED", "Messenger ($MESSENGER) is not installed or not visible to this app.", true)

            // 1) Messenger in front
            if (!activeIsMessenger()) {
                log("Opening Messenger...")
                val launch = packageManager.getLaunchIntentForPackage(MESSENGER)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(launch) } catch (t: Throwable) {
                    throw FlowAbort("MESSENGER_LAUNCH_BLOCKED", "Android refused to open Messenger from the background (${t.javaClass.simpleName}). Open Messenger yourself and retry.", false)
                }
                val up = waitFor(9000, { _, _ -> true }, labels)
                if (up == null) throw FlowAbort("MESSENGER_NOT_FOREGROUND", "Messenger did not come to the foreground within 9 s (Android may block background app launches). Open Messenger yourself and retry.", false)
            }

            // 2) get to the Notes composer
            var backs = 0
            var steps = 0
            var menuClicks = 0
            var justClickedEntry = false
            var cls: Classified
            var screen: Screen
            while (true) {
                currentCoroutineContext().ensureActive()
                steps++
                if (steps > 12) throw FlowAbort("TOO_MANY_STEPS", "Could not reach the Notes editor after 12 steps; stopped.", true)
                val got = waitFor(6000, { s, c -> if (c.kind != ScreenKind.EMPTY) s to c else null }, labels)
                    ?: throw FlowAbort("NO_SCREEN", "Messenger exposes no readable content (screen empty or protected).", true)
                screen = got.first; cls = got.second
                log("Screen: ${cls.kind} (${cls.why})")
                if (cls.kind == ScreenKind.UNKNOWN) {
                    // Record what this screen exposes (privacy-filtered) so the labels can be fixed.
                    NoteState.addDiag(DiagnosticReport.build("${AppState.clock()} unrecognised screen during job", screen.nodes, labels))
                }
                when (cls.kind) {
                    ScreenKind.NOTE_COMPOSER -> break
                    ScreenKind.NOTES_ENTRY -> {
                        if (!click(screen, cls.entryIndex)) throw FlowAbort("ENTRY_CLICK_FAILED", "Messenger did not accept a click on the Notes entry.", true)
                        justClickedEntry = true
                        // The screen must change; clicking the same entry twice could toggle something back.
                        waitFor(3500, { _, c -> if (c.kind != ScreenKind.NOTES_ENTRY && c.kind != ScreenKind.EMPTY) true else null }, labels)
                            ?: throw FlowAbort("ENTRY_CLICK_NO_EFFECT", "Clicked the Notes entry but the screen did not change. Stopped (not clicking twice).", true)
                    }
                    ScreenKind.NOTE_MENU -> {
                        if (++menuClicks > 2) throw FlowAbort("NOTE_MENU_LOOP", "The Note menu keeps reappearing; stopped.", true)
                        if (settings.deleteFirst && cls.deleteIndex >= 0) {
                            log("Deleting the existing Note first (experimental setting)")
                            if (!click(screen, cls.deleteIndex)) throw FlowAbort("DELETE_CLICK_FAILED", "Could not click Delete.", true)
                            delay(700)
                            val dialog = capture()
                            val confirmIdx = dialog?.nodes?.firstOrNull { it.visible && labels.isDeleteConfirm(it) && it.clickTarget >= 0 && it.index != cls.deleteIndex }?.clickTarget
                            if (dialog != null && confirmIdx != null) { click(dialog, confirmIdx); delay(900) }
                        } else if (cls.replaceIndex >= 0) {
                            if (!click(screen, cls.replaceIndex)) throw FlowAbort("REPLACE_CLICK_FAILED", "Could not click the replace/new-note item.", true)
                            delay(700)
                        } else throw FlowAbort("NOTE_MENU_UNUSABLE", "A Note menu is open but no replace item was recognised.", true)
                    }
                    ScreenKind.CHAT_OR_MESSAGE_BOX, ScreenKind.UNKNOWN -> {
                        if (justClickedEntry && cls.kind == ScreenKind.UNKNOWN) {
                            // We just opened something from the Notes entry and do not recognise it: do not wander, report it.
                            throw FlowAbort("COMPOSER_NOT_RECOGNISED",
                                "Opened the Notes entry but the next screen is not recognised as the Notes editor (${cls.why}). Nothing was typed. The screen's controls are in the diagnostic box.", true)
                        }
                        if (settings.allowBack && backs < 3 && activeIsMessenger()) {
                            backs++
                            log("Not on the Notes screen (${cls.kind}); pressing Back ($backs/3)")
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            delay(800)
                        } else {
                            val why = if (cls.kind == ScreenKind.CHAT_OR_MESSAGE_BOX) "CHAT_SCREEN_DETECTED" else "NOTES_ENTRY_NOT_FOUND"
                            throw FlowAbort(why,
                                if (cls.kind == ScreenKind.CHAT_OR_MESSAGE_BOX)
                                    "Messenger is showing a chat/message box (${cls.why}). Nothing was typed. Open Messenger's main chats screen and retry."
                                else "Could not find the Notes entry on this screen (${cls.why}). Run diagnostic mode and adjust the labels.",
                                true)
                        }
                    }
                    ScreenKind.EMPTY -> {}
                }
            }

            // 3) composer: verify, limit, type, verify
            val editNode = screen.nodes[cls.editIndex]
            val liveLimit = if (editNode.maxTextLength > 0) minOf(settings.charLimit, editNode.maxTextLength) else settings.charLimit
            val text = textFor(liveLimit)
            val expected = NoteFormat.normalizeForCompare(text)
            if (NoteFormat.cp(text) > liveLimit) throw FlowAbort("TEXT_TOO_LONG", "Note text is ${NoteFormat.cp(text)} characters; the limit in use is $liveLimit.", false)
            log("Composer found. Limit in use: $liveLimit" + if (editNode.maxTextLength > 0) " (reported by Messenger's field)" else " (your setting; the field reports none)")

            val existing = if (editNode.showingHint) "" else NoteFormat.normalizeForCompare(editNode.text)
            if (existing == expected && publish) {
                return RunResult(Outcome.ALREADY_CURRENT, "ALREADY_CURRENT", "The editor already holds exactly this text; nothing was published.")
            }

            currentCoroutineContext().ensureActive()
            mustBeMessenger()
            val edit = screen.refs[cls.editIndex]
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                throw FlowAbort("SET_TEXT_REFUSED", "Messenger's text field refused programmatic text entry.", true)
            delay(400)

            // 4) verify what is in the field
            val after = waitFor(3000, { s, c -> if (c.kind == ScreenKind.NOTE_COMPOSER) s to c else null }, labels)
                ?: throw FlowAbort("COMPOSER_LOST", "After typing, the Notes editor was no longer recognised; nothing was shared.", true)
            val typed = after.first.nodes[after.second.editIndex]
            val got = if (typed.showingHint) "" else NoteFormat.normalizeForCompare(typed.text)
            if (got != expected) throw FlowAbort("TEXT_NOT_ACCEPTED", "The field contains ${got.length} characters but ${expected.length} were expected (the editor may have trimmed or rejected the text). Nothing was shared.", true)
            log("Typed text verified in the field (${NoteFormat.cp(text)} characters)")

            if (!publish) return RunResult(Outcome.FILLED_NOT_PUBLISHED, "FILLED_NOT_PUBLISHED", "Text typed and verified. Tap Share in Messenger yourself if it looks right.")

            // 5) last check immediately before Share (no suspension between check and click)
            currentCoroutineContext().ensureActive()
            val lastPair = waitFor(3500, { sc, c ->
                if (c.kind == ScreenKind.NOTE_COMPOSER && sc.nodes[c.shareIndex].enabled) sc to c else null
            }, labels) ?: throw FlowAbort("SHARE_DISABLED", "The Share control is missing or stayed disabled after typing; Messenger may not have accepted the text. Nothing was shared.", true)
            val last = lastPair.first
            val lastCls = lastPair.second
            val lastEdit = last.nodes[lastCls.editIndex]
            if (NoteFormat.normalizeForCompare(lastEdit.text) != expected || lastEdit.showingHint)
                throw FlowAbort("TEXT_CHANGED", "The field text changed before Share; nothing was shared.", true)
            if (!click(last, lastCls.shareIndex)) throw FlowAbort("SHARE_CLICK_FAILED", "Messenger did not accept a click on Share.", true)
            log("Share tapped; waiting to confirm...")

            // 6) confirm
            val probe = expected.lines().first().let { l -> l.substring(0, l.offsetByCodePoints(0, minOf(NoteFormat.cp(l), 25))) }
            val end = System.currentTimeMillis() + 9000
            var closedAt = 0L
            while (System.currentTimeMillis() < end) {
                currentCoroutineContext().ensureActive()
                delay(500)
                val s = capture() ?: continue
                val c = ScreenClassifier.classify(s.nodes, labels)
                if (c.kind != ScreenKind.NOTE_COMPOSER) {
                    if (closedAt == 0L) closedAt = System.currentTimeMillis()
                    val seen = s.nodes.any { n -> n.visible && !n.editable && n.labels.any { NoteFormat.normalizeForCompare(it).contains(probe) } }
                    if (seen) return RunResult(Outcome.VERIFIED, "VERIFIED", "Editor closed and the new Note text is visible in Messenger.")
                    if (System.currentTimeMillis() - closedAt > 3000)
                        return RunResult(Outcome.PUBLISHED_UNVERIFIED, "UNVERIFIED", "Share was tapped and the editor closed, but the new text could not be found on screen. It may still have worked - please look at Messenger.")
                }
            }
            return RunResult(Outcome.FAILED, "SHARE_NOT_CONFIRMED", "Share was tapped but the editor never closed. The Note was probably NOT updated.", true)
        } catch (e: FlowAbort) {
            log("STOPPED ${e.code}: ${e.detail}")
            capture()?.let { s -> NoteState.addDiag(DiagnosticReport.build("${AppState.clock()} failure ${e.code}", s.nodes, labels)) }
            return failure(e)
        }
    }
}
