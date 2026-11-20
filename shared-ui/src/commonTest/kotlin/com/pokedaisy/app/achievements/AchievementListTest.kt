package com.pokedaisy.app.achievements

import kotlin.test.*

/** [parseAchievementList] reads what pokedaisy_ra.c's raAchievementList writes. */
class AchievementListTest {
    private fun record(vararg f: String) = f.joinToString("\u001F")

    @Test
    fun parsesRecords() {
        val text = listOf(
            record("7", "Almost There", "42", "Gotta Catch 'Em", "Register 50 Pokémon", "10", "0",
                "https://media.retroachievements.org/Badge/1.png", "https://media.retroachievements.org/Badge/1_lock.png",
                "38/50", "76.000", "12.500", "0"),
            record("2", "Unlocked", "43", "Boulder Badge 🏅", "Defeat Brock\nin Pewter", "5", "1", "", "", "", "0.000", "80.000", "2"),
            "",
        ).joinToString("\u001E")
        val list = parseAchievementList(text.encodeToByteArray())
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals(42, a.id)
        assertEquals("ALMOST THERE", a.section)
        assertEquals("Register 50 Pokémon", a.description)
        assertFalse(a.unlocked)
        assertEquals("38/50", a.progress)
        assertEquals(76f, a.percent, 0.001f)
        assertEquals("https://media.retroachievements.org/Badge/1_lock.png", a.lockedBadgeUrl)
        val b = list[1]
        assertTrue(b.unlocked)
        assertEquals("Boulder Badge 🏅", b.title)
        assertEquals("Defeat Brock\nin Pewter", b.description) // a newline doesn't split a record
        assertEquals(null, b.badgeUrl)
    }

    @Test
    fun skipsBrokenRecords() {
        assertEquals(0, parseAchievementList(record("1", "Locked", "x").encodeToByteArray()).size)
        assertEquals(0, parseAchievementList(ByteArray(0)).size)
    }

    @Test
    fun parsesLeaderboards() {
        val boards = parseLeaderboardList(
            (record("Pokémon FireRed Version", "11", "Elite Four", "Fastest League run", "1") + "\u001E" +
                record("Bonus", "12", "Safari Haul", "Most caught", "0") + "\u001E").encodeToByteArray(),
        )
        assertEquals(listOf(11, 12), boards.map { it.id })
        assertEquals("POKÉMON FIRERED VERSION", boards[0].section)
        assertTrue(boards[0].lowerIsBetter)
        assertFalse(boards[1].lowerIsBetter)
    }

    @Test
    fun marksThePlayersEntry() {
        val entries = parseLeaderboardEntries(
            (record("1", "Speedy", "12:04.33") + "\u001E" + record("17", "Lidor", "15:30.02") + "\u001E").encodeToByteArray(),
            me = "lidor",
        )
        assertEquals(listOf(1, 17), entries.map { it.rank })
        assertEquals(listOf(false, true), entries.map { it.isMe })
        assertEquals("15:30.02", entries[1].score)
    }
}
