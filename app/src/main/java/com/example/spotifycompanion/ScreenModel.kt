package com.example.spotifycompanion

/**
 * Pure Kotlin description of what the Messenger window exposes through accessibility,
 * plus the rules that decide "which screen is this" and "what may be written to the diagnostic report".
 * No Android classes here, so these rules can be tested on a PC with made-up screens.
 */

/** One accessibility node, flattened. [clickTarget] = index of this node or its nearest clickable ancestor, or -1. */
data class NodeView(
    val index: Int,
    val cls: String,
    val viewId: String,
    val text: String,
    val desc: String,
    val hint: String,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val showingHint: Boolean,
    val maxTextLength: Int,
    val clickTarget: Int
) {
    /** Every label the node offers, non-empty. */
    val labels: List<String> get() = listOf(text, desc, hint).filter { it.isNotBlank() }
}

/**
 * Words used to recognise screens. ALL of these are best guesses and have NOT been verified against your
 * Messenger version; that is what diagnostic mode is for. They can be edited in the app (Label settings)
 * without rebuilding. Matching is case-insensitive and on the WHOLE label (regex fullmatch), so a chat
 * called "Notes for school" is not mistaken for the Notes button.
 */
data class LabelSet(
    val entry: List<String> = listOf(
        "your note", "add a note", "create a note", "share a note", "leave a note", "new note", "note",
        // seen on Messenger 582 (screen recording): the bubble above "Create story"
        "drop a thought", "post a note.*", "post a thought.*"
    ),
    val composerMarker: List<String> = listOf(
        "share a note", "new note", "your note", "note", "leave a note", "what's on your mind\\??",
        // seen on Messenger 582: editor title, hint, and footer text
        "share a thought.*", "friends and connections can see your note.*"
    ),
    val share: List<String> = listOf("share", "post"),
    /** If ANY of these is visible we are in a chat (or something with a normal message box): never type. */
    val chatGuardLabels: List<String> = listOf(
        "send", "message", "write a message", "aa", "voice call", "video call", "like", "choose a sticker",
        "press and hold to record", "open camera", "attach a file", "send a like", "send a quick reaction"
    ),
    /** Items of a menu for an existing Note (only used if present). */
    val replace: List<String> = listOf("leave a new note", "share a new note", "replace note", "edit note", "new note"),
    val delete: List<String> = listOf("delete note", "delete"),
    val deleteConfirm: List<String> = listOf("delete", "delete note", "remove")
) {
    private fun rx(words: List<String>) = words.map { Regex(it, RegexOption.IGNORE_CASE) }
    private fun matches(patterns: List<Regex>, label: String): Boolean {
        val l = label.trim()
        return patterns.any { it.matches(l) }
    }
    private val entryRx = rx(entry)
    private val markerRx = rx(composerMarker)
    private val shareRx = rx(share)
    private val guardRx = rx(chatGuardLabels)
    private val replaceRx = rx(replace)
    private val deleteRx = rx(delete)
    private val deleteConfirmRx = rx(deleteConfirm)

    fun isEntry(n: NodeView) = n.labels.any { matches(entryRx, it) }
    fun isMarker(n: NodeView) = n.labels.any { matches(markerRx, it) }
    fun isShare(n: NodeView) = n.labels.any { matches(shareRx, it) }
    fun isGuard(n: NodeView) = n.labels.any { matches(guardRx, it) }
    fun isReplace(n: NodeView) = n.labels.any { matches(replaceRx, it) }
    fun isDelete(n: NodeView) = n.labels.any { matches(deleteRx, it) }
    fun isDeleteConfirm(n: NodeView) = n.labels.any { matches(deleteConfirmRx, it) }

    /** True if [s] is plain UI vocabulary that may appear in the diagnostic report. */
    fun isVocabulary(s: String): Boolean {
        val l = s.trim()
        if (l.isEmpty() || l.length > 40) return false
        val all = entryRx + markerRx + shareRx + guardRx + replaceRx + deleteRx + deleteConfirmRx +
            listOf(Regex("cancel|done|back|close|save|edit|ok|next|discard|keep|\\d{1,3}\\s*/\\s*\\d{1,3}", RegexOption.IGNORE_CASE))
        return all.any { it.matches(l) }
    }

    companion object {
        /** Splits a user-typed comma/newline separated list into regex words (each entry is a case-insensitive full-match pattern). */
        fun parse(s: String): List<String> =
            s.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.take(30)
    }
}

enum class ScreenKind { EMPTY, CHAT_OR_MESSAGE_BOX, NOTE_COMPOSER, NOTE_MENU, NOTES_ENTRY, UNKNOWN }

data class Classified(
    val kind: ScreenKind,
    val editIndex: Int = -1,
    val shareIndex: Int = -1,
    val entryIndex: Int = -1,
    val replaceIndex: Int = -1,
    val deleteIndex: Int = -1,
    val why: String = ""
)

object ScreenClassifier {
    /**
     * Order matters for safety: anything that looks like a chat wins over everything else, because typing
     * into a chat box could send a normal message.
     */
    fun classify(nodes: List<NodeView>, labels: LabelSet): Classified {
        val vis = nodes.filter { it.visible }
        if (vis.isEmpty()) return Classified(ScreenKind.EMPTY, why = "no visible nodes")

        val guard = vis.firstOrNull { labels.isGuard(it) && (it.editable || it.clickable || it.clickTarget >= 0 || it.hint.isNotBlank()) }
        if (guard != null) {
            return Classified(ScreenKind.CHAT_OR_MESSAGE_BOX, why = "visible control looks like a chat control (${guard.cls} ${guard.viewId.ifEmpty { "-" }})")
        }

        val edit = vis.firstOrNull { it.editable && it.enabled }
        // Share is normally DISABLED while the field is empty (seen on Messenger 582), so enabled is checked later, at click time.
        val share = vis.firstOrNull { labels.isShare(it) && it.clickTarget >= 0 }
        val marker = vis.firstOrNull { labels.isMarker(it) && !it.editable }
        val markerOnEdit = edit != null && labels.isMarker(edit)
        if (edit != null && share != null && (marker != null || markerOnEdit)) {
            return Classified(ScreenKind.NOTE_COMPOSER, editIndex = edit.index, shareIndex = share.clickTarget,
                why = "editable field + Share control + Notes marker present")
        }

        val rep = vis.firstOrNull { labels.isReplace(it) && it.clickTarget >= 0 }
        val del = vis.firstOrNull { labels.isDelete(it) && it.clickTarget >= 0 }
        if (rep != null || del != null) {
            return Classified(ScreenKind.NOTE_MENU, replaceIndex = rep?.clickTarget ?: -1, deleteIndex = del?.clickTarget ?: -1,
                why = "menu items for an existing Note")
        }

        val entry = vis.firstOrNull { labels.isEntry(it) && it.clickTarget >= 0 }
        if (entry != null) return Classified(ScreenKind.NOTES_ENTRY, entryIndex = entry.clickTarget, why = "Notes entry control present")

        if (share != null && (marker != null) && edit == null)
            return Classified(ScreenKind.UNKNOWN, why = "Notes-editor-like screen (marker + Share) but NO editable text field exposed")
        return Classified(ScreenKind.UNKNOWN, why = "no Notes control recognised")
    }
}

/**
 * Builds the privacy-filtered diagnostic report.
 *  - Only nodes that are interactive (clickable/editable) or whose label is known UI vocabulary are listed.
 *  - Label text is printed ONLY if it is known vocabulary; everything else shows as <redacted N chars>.
 *    So chat names, message previews, passwords and typed text never appear.
 *  - Text inside editable fields is never printed, only its length (and whether it shows a hint).
 */
object DiagnosticReport {
    private fun show(labels: LabelSet, s: String): String =
        if (s.isBlank()) "" else if (labels.isVocabulary(s)) "\"${s.trim()}\"" else "<redacted ${s.length} chars>"

    fun build(title: String, nodes: List<NodeView>, labels: LabelSet): List<String> {
        val out = ArrayList<String>()
        val cls = ScreenClassifier.classify(nodes, labels)
        out += "== $title =="
        out += "nodes=${nodes.size} visible=${nodes.count { it.visible }} -> classified as ${cls.kind} (${cls.why})"
        var listed = 0
        for (n in nodes) {
            if (!n.visible) continue
            val vocab = n.labels.any { labels.isVocabulary(it) }
            if (!(n.clickable || n.editable || vocab)) continue
            listed++
            if (listed > 80) { out += "... more nodes omitted"; break }
            val flags = buildList {
                if (n.clickable) add("clickable")
                if (n.clickTarget >= 0 && !n.clickable) add("click-via=#${n.clickTarget}")
                if (n.editable) add("EDITABLE")
                if (!n.enabled) add("disabled")
                if (n.maxTextLength > 0) add("maxLen=${n.maxTextLength}")
            }.joinToString(",")
            val t = if (n.editable) {
                if (n.showingHint || n.text.isBlank()) "text=<empty>" else "text=<${n.text.length} chars, hidden>"
            } else show(labels, n.text).let { if (it.isEmpty()) "" else "text=$it" }
            val d = show(labels, n.desc).let { if (it.isEmpty()) "" else "desc=$it" }
            val h = show(labels, n.hint).let { if (it.isEmpty()) "" else "hint=$it" }
            val id = if (n.viewId.isNotEmpty()) "id=${n.viewId.substringAfter(":id/")}" else ""
            out += "#${n.index} ${n.cls.substringAfterLast('.')} [$flags] $id $t $d $h".replace(Regex(" +"), " ").trim()
        }
        if (listed == 0) out += "(no interactive or recognisable controls visible)"
        return out
    }
}
