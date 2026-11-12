package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Money (the top-screen status bar), per fixture save. */
class MoneyTest {
    private fun money(key: String, cfg: NativeConfig) = readMoney(FixtureMemoryReader.load(key), cfg)

    @Test
    fun `retail saves`() {
        assertEquals(224300L, money("firered_vanilla", NATIVE_FIRERED_REV1))
        assertEquals(6098L, money("leafgreen_rev1", NATIVE_LEAFGREEN_REV1))
        assertEquals(185092L, money("emerald_vanilla", NATIVE_EMERALD_RETAIL))
        // Ruby / Sapphire don't XOR it.
        assertEquals(4961L, money("ruby_rev2", NATIVE_RUBY))
        assertEquals(398179L, money("sapphire_rev2", NATIVE_SAPPHIRE))
    }

    @Test
    fun `hacks keep their base game's offset`() {
        assertEquals(3848L, money("unbound", NATIVE_UNBOUND))
        assertEquals(2999L, money("celia", NATIVE_CELIA))
        // Heart and Soul's SaveBlock1 has 4 extra bytes up front.
        assertEquals(3000L, money("heart_and_soul", NATIVE_HEART_AND_SOUL))
        assertEquals(3000L, money("lazarus", NATIVE_LAZARUS))
        // Rogue's SaveBlock1 grew (104-byte party mons): money at +0x4A8; this save has none.
        assertEquals(0L, money("emerald_rogue", NATIVE_EMERALD_ROGUE))
        assertEquals(3000L, money("glazed", NATIVE_GLAZED))
        // Emerald Legacy: its SaveBlock1 grew past the bag, money is still at +0x490.
        assertEquals(3000L, money("emerald_legacy", NATIVE_EMERALD_LEGACY))
        assertEquals(3000L, money("imperium", NATIVE_IMPERIUM))
        // Quetzal's SaveBlock1 is its own: money at +0x918, the key at SB2+0x2C.
        assertEquals(7450L, money("quetzal", NATIVE_QUETZAL))
        assertEquals(48937L, money("quetzal_johto", NATIVE_QUETZAL))
        // Quetzal's money goes past 999,999 (its START menu shows 1048458).
        assertEquals(1_048_458L, money("quetzal_es", NATIVE_QUETZAL_ES))
        // Alpha 9 v1 keeps v0's SaveBlock1: the v0 saves, the same money.
        assertEquals(7450L, money("quetzal_v1", NATIVE_QUETZAL_V1))
        assertEquals(1_048_458L, money("quetzal_es_v1", NATIVE_QUETZAL_ES_V1))
        // Quetzal 0.8.4 keeps Alpha 9's SaveBlock1: the same save, the same money, every build.
        assertEquals(1_048_458L, money("quetzal_084", NATIVE_QUETZAL_V084))
        assertEquals(1_048_458L, money("quetzal_es_084", NATIVE_QUETZAL_V084_ES))
        assertEquals(1_048_458L, money("quetzal_moemon_084", NATIVE_QUETZAL_MOEMON_V084))
        assertEquals(1_048_458L, money("quetzal_moemon_es_084", NATIVE_QUETZAL_MOEMON_V084_ES))
        assertEquals(1748L, money("unbound_fr", NATIVE_UNBOUND_FR))
        assertEquals(2000L, money("orange_islands", NATIVE_ORANGE_ISLANDS))
        assertEquals(3080L, money("ultra_violet", NATIVE_ULTRA_VIOLET))
        // Retail FireRed's save, booted in FireRed Rocket Edition and Ash Gray.
        assertEquals(246468L, money("rocket_edition", NATIVE_ROCKET_EDITION))
        assertEquals(246468L, money("ash_gray", NATIVE_ASH_GRAY))
        assertEquals(246468L, money("ash_gray_fork", NATIVE_ASH_GRAY))
        // Elite Redux's save in both builds: plain (it zeroes the encryption key), its trainer card shows BP instead.
        assertEquals(999_900L, money("elite_redux", NATIVE_ELITE_REDUX_V2_65_4_1B))
        assertEquals(999_900L, money("elite_redux_v2653", NATIVE_ELITE_REDUX_V2_65_3B))
        // Monomyth's (the user's save): SaveBlock1 +0x494 XOR the key at SaveBlock2 +0x40.
        assertEquals(1968L, money("monomyth", NATIVE_MONOMYTH))
        assertEquals(1968L, money("monomyth_v1_1", NATIVE_MONOMYTH_V1_1))
        // Team Rocket Edition: Dragonsden's save: past 999,999 (its AddMoney caps at 99,999,999).
        assertEquals(3_000_000L, money("team_rocket", NATIVE_TEAM_ROCKET))
        // Team Rocket Edition 2026 v3.1's new game: SaveBlock1 +0x490 XOR the key at SaveBlock2 +0x44.
        assertEquals(3000L, money("team_rocket_v3_1", NATIVE_TEAM_ROCKET_V3_1))
        // Wishes of Tomorrow's new game (its START menu: ₽3000), the randomizer one too.
        assertEquals(3000L, money("wishes", NATIVE_WISHES))
        assertEquals(3000L, money("wishes_random", NATIVE_WISHES))
        // Dreamstone Mysteries' new game (its save screen and START menu): SaveBlock1 +0x490 XOR SaveBlock2 +0xAC.
        assertEquals(3000L, money("dreamstone", NATIVE_DREAMSTONE))
        // Ghost Grey's 1.0.3 save in each build (its trainer card: ₽163300).
        assertEquals(163300L, money("ghost_grey", NATIVE_GHOST_GREY))
        assertEquals(163300L, money("ghost_grey_v102", NATIVE_GHOST_GREY))
        assertEquals(163300L, money("ghost_grey_v101", NATIVE_GHOST_GREY_V1_0_1))
        // Let's Go's new game (its START menu's 3000 + a script's 3000).
        assertEquals(6000L, money("lets_go_pikachu", NATIVE_LETS_GO_PIKACHU))
        assertEquals(6000L, money("lets_go_eevee", NATIVE_LETS_GO_EEVEE))
        assertEquals(5000L, money("sword_shield_up", NATIVE_SWORD_SHIELD))
        assertEquals(13000L, money("gs_chronicles", NATIVE_GS_CHRONICLES))
        assertEquals(13000L, money("gs_chronicles_swapped", NATIVE_GS_CHRONICLES))
        // Recharged Yellow keeps no key: SaveBlock1+0x29C plain, every release (its trainer card says P4804).
        assertEquals(4804L, money("recharged_yellow_v180", NATIVE_RECHARGED_YELLOW_V1_8_0))
        assertEquals(4804L, money("recharged_yellow", NATIVE_RECHARGED_YELLOW_V1_9_7))
        // Recharged Emerald: retail Emerald's save converted - its money, under its key (Rebalanced's 4 bytes later).
        assertEquals(185092L, money("recharged_emerald_v225", NATIVE_RECHARGED_EMERALD_V2_2_5))
        assertEquals(185092L, money("recharged_emerald", NATIVE_RECHARGED_EMERALD_V2_2_8))
        assertEquals(185092L, money("recharged_emerald_rebalanced", NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8))
        // Modern Emerald: SaveBlock1+0x490 under SaveBlock2's key at +0xBC (retail +0xAC); a new game's 3000.
        assertEquals(3000L, money("modern_emerald", NATIVE_MODERN_EMERALD))
        // Super Mariomon: vanilla's offsets in every release (its card says P3100).
        for ((key, cfg) in listOf("mariomon_v1_5_2" to NATIVE_MARIOMON_V1_5_2, "mariomon_v1_5_1" to NATIVE_MARIOMON_V1_5_1,
                "mariomon_v1_3" to NATIVE_MARIOMON_V1_3, "mariomon_v1_2_2" to NATIVE_MARIOMON_V1_2_2, "mariomon_v1_2_1" to NATIVE_MARIOMON_V1_2_1)) {
            assertEquals(key, 3100L, money(key, cfg))
        }
    }
}
