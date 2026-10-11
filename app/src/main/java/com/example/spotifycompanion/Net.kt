package com.example.spotifycompanion

object Net {
    /**
     * Accepts only canonical private/loopback/link-local IPv4 literals:
     * 10.x.x.x, 172.16-31.x.x, 192.168.x.x, 127.x.x.x, 169.254.x.x.
     * This mirrors the PC server's own private-address filter, and it is how
     * the app guarantees it never talks to a public internet address.
     */
    fun isPrivateIpv4(host: String): Boolean {
        val parts = host.trim().split(".")
        if (parts.size != 4) return false
        val n = parts.map { p ->
            val v = p.toIntOrNull() ?: return false
            if (v !in 0..255 || p != v.toString()) return false   // rejects "01", "+1", etc.
            v
        }
        return n[0] == 10 ||
            (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 192 && n[1] == 168) ||
            n[0] == 127 ||
            (n[0] == 169 && n[1] == 254)
    }

    fun isValidPort(p: Int?): Boolean = p != null && p in 1024..65535

    private val codeRegex = Regex("\\d{6}")
    fun isValidCode(c: String): Boolean = codeRegex.matches(c)
}
