package com.example.spotifycompanion

/**
 * Pure Kotlin (no Android classes) so it can be unit-tested on a PC.
 *
 * Turns a template + title + artist into the exact Note text:
 *  - removes control / invisible / bidi-override characters (they can garble or spoof text)
 *  - collapses whitespace inside title and artist
 *  - fits the result into [limit] Unicode code points (so an emoji counts once, not twice)
 *    by shortening the title first, then the artist, then the whole text.
 */
object NoteFormat {
    const val DEFAULT_SINGLE = "🎧 Listening — {title} by {artist}"
    const val DEFAULT_MULTI = "🎧 Listening\n{title} — {artist}"

    /** Assumption only, NOT verified against Messenger. The live text field's own limit (if it reports one) wins. */
    const val DEFAULT_LIMIT = 60
    const val MAX_TEMPLATE_CHARS = 200
    private const val ELLIPSIS = "…"
    private const val MIN_PART = 3

    fun cp(s: String): Int = s.codePointCount(0, s.length)

    /** Returns null if the template is fine, otherwise a readable problem. */
    fun validateTemplate(t: String): String? {
        if (t.isBlank()) return "Template is empty"
        if (t.length > MAX_TEMPLATE_CHARS) return "Template is longer than $MAX_TEMPLATE_CHARS characters"
        if (!t.contains("{title}") && !t.contains("{artist}")) return "Template must contain {title} and/or {artist}"
        return null
    }

    private fun isBad(c: Int): Boolean = when {
        c == '\n'.code || c == '\r'.code || c == '\t'.code -> false   // whitespace, handled separately
        Character.isISOControl(c) -> true
        c in 0x200B..0x200F -> true      // zero-width + LRM/RLM
        c in 0x202A..0x202E -> true      // bidi embeddings / overrides
        c in 0x2066..0x2069 -> true      // bidi isolates
        c == 0xFEFF || c == 0x2028 || c == 0x2029 -> true
        c in 0xD800..0xDFFF -> true      // lone surrogate
        else -> false
    }

    /** Checks a hand-typed Note text (manual test). Returns null if fine. */
    fun validateText(t: String): String? {
        if (t.isBlank()) return "Note text is empty"
        var i = 0
        while (i < t.length) {
            val c = t.codePointAt(i)
            if (isBad(c)) return "Note text contains an invisible/control character (U+%04X)".format(c)
            i += Character.charCount(c)
        }
        return null
    }

    fun sanitize(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw.codePointAt(i)
            if (!isBad(c)) sb.appendCodePoint(c) else sb.append(' ')
            i += Character.charCount(c)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun shorten(s: String, maxCp: Int): String {
        if (cp(s) <= maxCp) return s
        val keep = (maxCp - 1).coerceAtLeast(0)
        val end = s.offsetByCodePoints(0, keep.coerceAtMost(cp(s)))
        return s.substring(0, end).trimEnd() + ELLIPSIS
    }

    private val placeholder = Regex("\\{(title|artist)\\}")

    /** One pass, so a title that literally contains "{artist}" is not expanded a second time. */
    private fun fill(template: String, title: String, artist: String) =
        placeholder.replace(template) { m -> if (m.groupValues[1] == "title") title else artist }

    /** Normalises line endings only; the template's own line breaks are kept. */
    private fun cleanTemplate(t: String) = t.replace("\r\n", "\n").replace('\r', '\n')

    fun render(template: String, title: String, artist: String, limit: Int): String {
        val tpl = cleanTemplate(template)
        var t = sanitize(title)
        var a = sanitize(artist)
        if (t.isEmpty()) t = "Unknown title"
        if (a.isEmpty()) a = "Unknown artist"
        val lim = limit.coerceAtLeast(10)

        var out = fill(tpl, t, a)
        var over = cp(out) - lim
        if (over <= 0) return out

        // 1) shorten the title (keep at least MIN_PART characters)
        val tLen = cp(t)
        if (tLen > MIN_PART) {
            val target = (tLen - over).coerceAtLeast(MIN_PART)
            t = shorten(t, target)
            out = fill(tpl, t, a)
            over = cp(out) - lim
            if (over <= 0) return out
        }
        // 2) shorten the artist
        val aLen = cp(a)
        if (aLen > MIN_PART) {
            val target = (aLen - over).coerceAtLeast(MIN_PART)
            a = shorten(a, target)
            out = fill(tpl, t, a)
            over = cp(out) - lim
            if (over <= 0) return out
        }
        // 3) last resort: cut the whole text
        return shorten(out, lim)
    }

    /** Identity used to compare "is this the same Note text" (ignores trailing space / line-ending style). */
    fun normalizeForCompare(s: String): String =
        s.replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { it.trim() }.trim()
}
