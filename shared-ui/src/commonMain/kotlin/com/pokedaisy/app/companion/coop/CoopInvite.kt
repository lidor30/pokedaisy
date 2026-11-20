package com.pokedaisy.app.companion.coop

/**
 * Invite links: `pokedaisy://coop/<ticket>`, the ticket being a [CoopTicket] string
 * ("pdco" + lowercase unpadded base32). Pasted text, a scanned QR code or a tapped link
 * all come through [parse], which takes the link or a bare ticket and refuses anything else.
 */
object CoopInvite {
    const val SCHEME_PREFIX = "pokedaisy://coop/"

    /** The longest text [parse] looks at, before trimming. */
    private const val MAX_INPUT = 4_096

    private const val SCHEME = "pokedaisy://"
    private const val PATH = "coop/"
    private val TICKET = Regex("^pdco[a-z2-7]{56,205}$")

    /** True for a string in our ticket form (what the HELLO also requires). */
    fun isTicket(s: String): Boolean = TICKET.matches(s)

    fun link(ticket: String): String {
        require(isTicket(ticket)) { "not a co-op ticket" }
        return SCHEME_PREFIX + ticket
    }

    /** The ticket in [text] (a link, case-insensitive scheme, or a bare ticket), or null. */
    fun parse(text: String): String? {
        if (text.length > MAX_INPUT) return null
        val t = text.trim()
        val ticket = if (t.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
            if (!t.startsWith(PATH, startIndex = SCHEME.length)) return null
            t.substring(SCHEME.length + PATH.length)
        } else {
            t
        }
        return ticket.takeIf { isTicket(it) }
    }
}
