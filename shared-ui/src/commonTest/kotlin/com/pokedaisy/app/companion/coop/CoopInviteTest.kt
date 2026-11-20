package com.pokedaisy.app.companion.coop

import kotlin.test.*

/** [CoopInvite]: a link or a bare ticket in, the ticket out; anything else is refused. */
class CoopInviteTest {
    private val t = "pdco" + "a".repeat(56)

    @Test fun linkAndBareTicketParse() {
        assertEquals("pokedaisy://coop/$t", CoopInvite.link(t))
        assertEquals(t, CoopInvite.parse(CoopInvite.link(t)))
        assertEquals(t, CoopInvite.parse("  $t\n"))
        assertEquals(t, CoopInvite.parse("POKEDAISY://coop/$t"))
        assertEquals(t, CoopInvite.parse("\tpokedaisy://coop/$t  "))
        val real = CoopTicket.peerOnly(CoopPeerId.of(ByteArray(32) { 9 }))
        assertEquals(real, CoopInvite.parse(CoopInvite.link(real)))
    }

    @Test fun ticketLengthBounds() {
        assertEquals("pdco" + "a".repeat(56), CoopInvite.parse("pdco" + "a".repeat(56)))
        assertNull(CoopInvite.parse("pdco" + "a".repeat(55)))
        assertEquals("pdco" + "a".repeat(205), CoopInvite.parse("pdco" + "a".repeat(205)))
        assertNull(CoopInvite.parse("pdco" + "a".repeat(206)))
    }

    @Test fun anOlderTicketFormIsRefused() {
        assertNull(CoopInvite.parse("endpoint" + "a".repeat(40)))
        assertNull(CoopInvite.parse("pokedaisy://coop/endpoint" + "a".repeat(40)))
    }

    @Test fun hostileInvitesRefused() {
        listOf(
            "https://coop/pdcoaaaaaaaaaaaaaaaaaaaa",
            "pokedaisy://coop/$t/x",
            "pokedaisy://coop/pdcoAAAA" + "a".repeat(52),
            "pdco" + "1".repeat(56),
            "a".repeat(1_000_000),
            "",
            "pokedaisy://coop/",
            "pokedaisy://COOP/$t",
            "pokedaisy://other/$t",
            "pokedaisy://coop/pokedaisy://coop/$t",
            "pdco" + "a".repeat(28) + " " + "a".repeat(28),
            "PDCO" + "a".repeat(56),
            " ".repeat(4_097) + t,
        ).forEach { assertNull(CoopInvite.parse(it), it.take(40)) }
    }

    @Test fun linkRefusesABadTicket() {
        assertFailsWith<IllegalArgumentException> { CoopInvite.link("nope") }
    }
}
