package com.example.spotifycompanion

// Plain-Kotlin checks for the Android-free logic (NoteFormat, NotePlanner, ScreenModel).
// Run on a PC with kotlinc - see ../MESSENGER_NOTES_README.md. The screens below are SYNTHETIC, not real Messenger screens.

var failures = 0
var checks = 0
fun check(name: String, cond: Boolean, extra: String = "") {
    checks++
    if (!cond) { failures++; println("FAIL: $name $extra") }
}
fun eq(name: String, actual: Any?, expected: Any?) = check(name, actual == expected, "expected=<$expected> actual=<$actual>")

fun node(i: Int, text: String = "", desc: String = "", hint: String = "", cls: String = "android.widget.TextView",
         clickable: Boolean = false, editable: Boolean = false, target: Int = -1, enabled: Boolean = true,
         showingHint: Boolean = false, max: Int = -1) =
    NodeView(i, cls, "", text, desc, hint, clickable, editable, enabled, true, showingHint, max, if (clickable) i else target)

fun formatTests() {
    val t = NoteFormat.DEFAULT_SINGLE
    eq("basic", NoteFormat.render(t, "Don't", "Bryson Tiller", 60), "🎧 Listening — Don't by Bryson Tiller")
    eq("multiline", NoteFormat.render(NoteFormat.DEFAULT_MULTI, "Don't", "Bryson Tiller", 60), "🎧 Listening\nDon't — Bryson Tiller")
    val long = "A".repeat(100)
    val r = NoteFormat.render(t, long, "Bryson Tiller", 60)
    check("long title fits", NoteFormat.cp(r) <= 60, "len=${NoteFormat.cp(r)}")
    check("long title keeps artist", r.contains("Bryson Tiller"))
    check("long title ellipsis", r.contains("…"))
    val r2 = NoteFormat.render(t, long, long, 60)
    check("both long fits", NoteFormat.cp(r2) <= 60, "len=${NoteFormat.cp(r2)}")
    val r3 = NoteFormat.render("{title}{artist}", "x", "y", 10)
    eq("tiny", r3, "xy")
    // emoji counted as one code point, never split
    val emo = "😀".repeat(40)
    val r4 = NoteFormat.render("{title}", emo, "a", 20)
    check("emoji no split surrogate", r4.all { !Character.isSurrogate(it) } || r4.codePointCount(0, r4.length) == r4.length - r4.count { Character.isHighSurrogate(it) })
    check("emoji fits", NoteFormat.cp(r4) <= 20, "cp=${NoteFormat.cp(r4)}")
    var i = 0; var ok = true
    while (i < r4.length) { val c = r4[i]; if (Character.isHighSurrogate(c)) { if (i + 1 >= r4.length || !Character.isLowSurrogate(r4[i + 1])) ok = false; i += 2 } else { if (Character.isLowSurrogate(c)) ok = false; i++ } }
    check("emoji pairs intact", ok)
    // invisible / bidi characters removed
    eq("bidi stripped", NoteFormat.sanitize("a‮b​c\u0000d"), "a b c d")
    eq("whitespace collapsed", NoteFormat.sanitize("  a \n\t b  "), "a b")
    eq("missing", NoteFormat.render(t, "", "", 80), "🎧 Listening — Unknown title by Unknown artist")
    check("template ok", NoteFormat.validateTemplate(t) == null)
    check("template without placeholder rejected", NoteFormat.validateTemplate("hello") != null)
    check("empty template rejected", NoteFormat.validateTemplate("  ") != null)
    check("validateText ok", NoteFormat.validateText("Hi\nthere") == null)
    check("validateText bidi rejected", NoteFormat.validateText("a‮b") != null)
    check("validateText blank rejected", NoteFormat.validateText(" \n ") != null)
    // placeholders inside a title must not be re-expanded
    eq("placeholder in title", NoteFormat.render(t, "{artist}", "X", 80), "🎧 Listening — {artist} by X")
    eq("compare normalise", NoteFormat.normalizeForCompare("a \r\n b  "), "a\nb")
}

fun plannerTests() {
    val cfg = NotePlanner.Config(delayMs = 3000, updateWhenPaused = false, minGapMs = 2000, retryBackoffMs = 20000, maxAttempts = 3)
    fun p() = NotePlanner(cfg).also { it.enable() }

    // off by default
    check("off ignores", NotePlanner(cfg).onTrack("track_changed", "A", "B", true, 0) is NotePlanner.Verdict.Ignored)

    // queue + delay
    var pl = p()
    check("queued", pl.onTrack("track_changed", "A", "B", true, 0) is NotePlanner.Verdict.Queued)
    check("not due early", pl.takeDue(2999) == null)
    val t = pl.takeDue(3000)
    eq("due", t?.title, "A")
    check("running", pl.running)
    pl.finished(t!!, NotePlanner.Failure.NONE, 5000)
    check("not running", !pl.running)
    // duplicate / unchanged
    check("unchanged ignored", pl.onTrack("playback_changed", "A", "B", true, 6000) is NotePlanner.Verdict.Ignored)
    check("same different case ignored", pl.onTrack("track_changed", " a ", "b", true, 6000) is NotePlanner.Verdict.Ignored)
    check("nothing pending", pl.pending == null)

    // coalescing: rapid skips -> only newest, one publication
    pl = p()
    pl.onTrack("track_changed", "S1", "X", true, 0)
    pl.onTrack("track_changed", "S2", "X", true, 500)
    val v3 = pl.onTrack("track_changed", "S3", "X", true, 1000)
    check("replaced", v3 is NotePlanner.Verdict.Queued && v3.replaced)
    check("window restarted", pl.takeDue(3500) == null)
    val got = pl.takeDue(4000)
    eq("only newest", got?.title, "S3")
    check("no second pending", pl.takeDue(99999) == null)
    // duplicate of pending does NOT extend the delay
    pl = p()
    pl.onTrack("track_changed", "A", "B", true, 0)
    check("dup pending ignored", pl.onTrack("track_changed", "A", "B", true, 2000) is NotePlanner.Verdict.Ignored)
    check("delay unchanged", pl.takeDue(3000) != null)

    // never overlap; newest-only while running
    pl = p()
    pl.onTrack("track_changed", "A", "B", true, 0)
    val ja = pl.takeDue(3000)!!
    pl.onTrack("track_changed", "N1", "B", true, 3500)
    pl.onTrack("track_changed", "N2", "B", true, 3600)
    check("no overlap while running", pl.takeDue(60000) == null)
    check("msUntilDue null while running", pl.msUntilDue(60000) == null)
    pl.finished(ja, NotePlanner.Failure.NONE, 9000)
    eq("newest waits for it", pl.takeDue(9001)?.title, null)
    eq("newest after min gap", pl.takeDue(11000)?.title, "N2")

    // paused
    pl = p()
    check("paused ignored", pl.onTrack("playback_changed", "A", "B", false, 0) is NotePlanner.Verdict.Ignored)
    check("no_source ignored", pl.onTrack("no_source", "", "", false, 0) is NotePlanner.Verdict.Ignored)
    val pp = NotePlanner(cfg.copy(updateWhenPaused = true)).also { it.enable() }
    check("paused allowed by setting", pp.onTrack("playback_changed", "A", "B", false, 0) is NotePlanner.Verdict.Queued)

    // skip back to the published track cancels the pending one
    pl = p()
    pl.onTrack("track_changed", "A", "B", true, 0)
    pl.finished(pl.takeDue(3000)!!, NotePlanner.Failure.NONE, 3500)
    pl.onTrack("track_changed", "C", "D", true, 4000)
    check("back to published drops pending", pl.onTrack("track_changed", "A", "B", true, 4500) is NotePlanner.Verdict.Ignored && pl.pending == null)

    // pause then resume the same track -> no new Note
    pl = p()
    pl.onTrack("track_changed", "A", "B", true, 0)
    pl.finished(pl.takeDue(3000)!!, NotePlanner.Failure.NONE, 3500)
    pl.onTrack("playback_changed", "A", "B", false, 5000)
    check("resume same track ignored", pl.onTrack("playback_changed", "A", "B", true, 9000) is NotePlanner.Verdict.Ignored)

    // transient failure: retry, then give up
    pl = p()
    pl.onTrack("track_changed", "A", "B", true, 0)
    var j = pl.takeDue(3000)!!
    pl.finished(j, NotePlanner.Failure.TRANSIENT, 4000)
    check("retry scheduled", pl.pending != null && pl.dueAt == 24000L, "due=${pl.dueAt}")
    j = pl.takeDue(24000)!!
    pl.finished(j, NotePlanner.Failure.TRANSIENT, 25000)
    j = pl.takeDue(45000)!!
    pl.finished(j, NotePlanner.Failure.TRANSIENT, 46000)
    check("gives up after maxAttempts", pl.pending == null && pl.enabled)
    // newer track beats a transient retry
    pl = p()
    pl.onTrack("track_changed", "A", "B", true, 0)
    j = pl.takeDue(3000)!!
    pl.onTrack("track_changed", "NEW", "B", true, 3500)
    pl.finished(j, NotePlanner.Failure.TRANSIENT, 4000)
    eq("newer kept over retry", pl.pending?.title, "NEW")

    // structural failure stops automation
    pl = p()
    pl.onTrack("track_changed", "A", "B", true, 0)
    j = pl.takeDue(3000)!!
    pl.onTrack("track_changed", "Z", "B", true, 3100)
    pl.finished(j, NotePlanner.Failure.STRUCTURAL, 4000)
    check("structural stops + clears", !pl.enabled && pl.pending == null)
    check("ignored after stop", pl.onTrack("track_changed", "Q", "B", true, 5000) is NotePlanner.Verdict.Ignored)

    // stop() is immediate
    pl = p()
    pl.onTrack("track_changed", "A", "B", true, 0)
    pl.stop()
    check("stop clears", pl.takeDue(99999) == null && pl.pending == null && !pl.enabled)

    // snapshot rule
    pl = p()
    check("first snapshot ignored", pl.onSnapshot("A", "B", true, 0) is NotePlanner.Verdict.Ignored)
    pl.onTrack("track_changed", "A", "B", true, 0)
    pl.finished(pl.takeDue(3000)!!, NotePlanner.Failure.NONE, 3500)
    check("snapshot same ignored", pl.onSnapshot("A", "B", true, 9000) is NotePlanner.Verdict.Ignored)
    check("snapshot differing after reconnect queued", pl.onSnapshot("C", "D", true, 9000) is NotePlanner.Verdict.Queued)
}

fun screenTests() {
    val labels = LabelSet()
    // synthetic home screen with a chat called "Notes for school" and a real-looking entry
    val home = listOf(
        node(0, cls = "android.widget.FrameLayout"),
        node(1, text = "Your note", clickable = true),
        node(2, text = "Notes for school", clickable = true),   // a CHAT NAME: must not count as the entry
        node(3, text = "Maria Santos", clickable = true),
        node(4, text = "See you at 5, bring the keys", target = 3)
    )
    val c = ScreenClassifier.classify(home, labels)
    eq("home -> entry", c.kind, ScreenKind.NOTES_ENTRY)
    eq("entry is 'Your note', not the chat", c.entryIndex, 1)

    val noEntry = listOf(node(0), node(1, text = "Notes for school", clickable = true), node(2, text = "Maria Santos", clickable = true))
    eq("chat named like Notes is not an entry", ScreenClassifier.classify(noEntry, labels).kind, ScreenKind.UNKNOWN)

    val composer = listOf(
        node(0), node(1, text = "Share a note"),
        node(2, text = "", hint = "Share what's on your mind", cls = "android.widget.EditText", editable = true, showingHint = true, max = 60),
        node(3, text = "Share", clickable = true)
    )
    val cc = ScreenClassifier.classify(composer, labels)
    eq("composer", cc.kind, ScreenKind.NOTE_COMPOSER)
    eq("composer edit idx", cc.editIndex, 2); eq("composer share idx", cc.shareIndex, 3)

    // chat screen: has a message box and Send -> must never be a composer
    val chat = listOf(
        node(0), node(1, text = "Share a note"),
        node(2, text = "", hint = "Message", cls = "android.widget.EditText", editable = true, showingHint = true),
        node(3, desc = "Send", clickable = true), node(4, text = "Share", clickable = true)
    )
    eq("chat wins over composer", ScreenClassifier.classify(chat, labels).kind, ScreenKind.CHAT_OR_MESSAGE_BOX)

    // editable + Share but no Notes marker -> not a composer
    val other = listOf(node(0), node(1, text = "", cls = "android.widget.EditText", editable = true, hint = "Write a caption"), node(2, text = "Share", clickable = true))
    check("no marker -> not composer", ScreenClassifier.classify(other, labels).kind != ScreenKind.NOTE_COMPOSER)

    // disabled Share -> not a composer
    val dis = listOf(node(0), node(1, text = "Share a note"), node(2, cls = "android.widget.EditText", editable = true), node(3, text = "Share", clickable = true, enabled = false))
    // Share is disabled while the field is empty (seen on Messenger 582): still the composer; enabled is checked at click time
    eq("disabled empty-state share still composer", ScreenClassifier.classify(dis, labels).kind, ScreenKind.NOTE_COMPOSER)

    // Layout seen in the user's screen recording (Messenger 582): close X, "New note", disabled Share, "Share a thought..." field, footer
    val seen582 = listOf(
        node(0), node(1, desc = "Close", clickable = true), node(2, text = "New note"),
        node(3, text = "Share", clickable = true, enabled = false),
        node(4, text = "", hint = "Share a thought…", cls = "android.widget.EditText", editable = true, showingHint = true),
        node(5, desc = "Music", clickable = true), node(6, desc = "GIF", clickable = true),
        node(7, text = "Friends and connections can see your note on Messenger and Facebook for 24 hours.")
    )
    eq("Messenger 582 editor recognised", ScreenClassifier.classify(seen582, labels).kind, ScreenKind.NOTE_COMPOSER)
    val noMarkerNoise = listOf(node(0), node(1, text = "Share", clickable = true, enabled = false), node(2, text = "New note"),
        node(3, text = "Share a thought…"))   // looks like the editor but no editable field exposed
    val nf = ScreenClassifier.classify(noMarkerNoise, labels)
    check("no editable field -> not composer, explains why", nf.kind == ScreenKind.UNKNOWN && nf.why.contains("NO editable"), nf.why)
    // home screen bubbles seen on Messenger 582
    eq("'Drop a thought' bubble is the entry", ScreenClassifier.classify(listOf(node(0, text = "Drop a thought", clickable = true), node(1, text = "Maria", clickable = true)), labels).kind, ScreenKind.NOTES_ENTRY)
    eq("'Post a note…' bubble is the entry", ScreenClassifier.classify(listOf(node(0, text = "Post a note…", clickable = true)), labels).kind, ScreenKind.NOTES_ENTRY)

    // menu
    val menu = listOf(node(0), node(1, text = "Leave a new note", clickable = true), node(2, text = "Delete note", clickable = true))
    val mc = ScreenClassifier.classify(menu, labels)
    eq("menu", mc.kind, ScreenKind.NOTE_MENU); eq("menu replace", mc.replaceIndex, 1); eq("menu delete", mc.deleteIndex, 2)

    // invisible nodes ignored
    val hidden = listOf(node(0).copy(visible = false), node(1, text = "Your note", clickable = true).copy(visible = false))
    eq("hidden -> empty", ScreenClassifier.classify(hidden, labels).kind, ScreenKind.EMPTY)

    // privacy: report never contains chat names, message previews, or typed text
    val screen = listOf(
        node(0, text = "Your note", clickable = true),
        node(1, text = "Maria Santos", clickable = true),
        node(2, text = "Maria: my password is hunter2", target = 1),
        node(3, text = "SECRETTYPED", cls = "android.widget.EditText", editable = true, hint = "Message"),
        node(4, text = "Share", clickable = true),
        node(5, text = "12/60")
    )
    val rep = DiagnosticReport.build("t", screen, labels).joinToString("\n")
    check("report keeps vocabulary", rep.contains("\"Your note\"") && rep.contains("\"Share\"") && rep.contains("\"12/60\""))
    check("report hides names", !rep.contains("Maria"))
    check("report hides previews", !rep.contains("hunter2") && !rep.contains("password"))
    check("report hides typed text", !rep.contains("SECRETTYPED"))
    check("report redaction marker", rep.contains("redacted"))

    // user-edited labels
    val custom = LabelSet(entry = LabelSet.parse("add to your story\nmy note"))
    eq("custom entry label", ScreenClassifier.classify(listOf(node(0, text = "My Note", clickable = true)), custom).kind, ScreenKind.NOTES_ENTRY)
}

fun main() {
    formatTests(); plannerTests(); screenTests()
    println(if (failures == 0) "ALL $checks CHECKS PASSED" else "$failures of $checks CHECKS FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
