package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * WILD ENCOUNTERS' switch, found in each ROM by StandardWildEncounter's code ([WildEncounters.find]). The retail
 * addresses are the pret decomps' sWildEncountersDisabled / gWildEncountersDisabled; the hacks' were checked by hand
 * (Glazed / Kaizo keep Emerald's code, the CFRU hacks hook StandardWildEncounter into their own, which reads
 * FireRed's; the gcc builds' were walked: warped onto grass (or water) with the `warp` script command, OFF gave 180
 * steps without a battle and NORMAL one within 80). OFF and 50% were walked headless in Route 101's / Route 1's grass
 * (mgba_dump `wild`). Skips a ROM that isn't here.
 */
class WildEncountersTest {
    private val cases = listOf(
        "Pokemon - Emerald Version (USA, Europe).gba" to 0x02038C00L,
        "Pokemon - FireRed Version (USA).gba" to 0x020386DCL,
        "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba" to 0x020386DCL,
        "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba" to 0x020386DCL,
        "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba" to 0x0202FF7CL,
        "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba" to 0x0202FF7CL,
        "../Pokemon Multi Language/Pocket Monsters - Emerald (Japan)/Pocket Monsters - Emerald (Japan).gba" to 0x020388A0L,
        "../Pokemon Multi Language/Pokemon - Smaragd-Edition (Germany)/Pokemon - Smaragd-Edition (Germany).gba" to 0x02038C00L,
        "Pokemon Glazed (v9.2.0).gba" to 0x02038C00L,
        "Pokemon Emerald Kaizo (v2.0).gba" to 0x02038C00L,
        // Emerald Legacy: its own agbcc build of Emerald's code (its ELF's sWildEncountersDisabled).
        "Pokemon Emerald Legacy (v1.1.4).gba" to 0x02038D8CL,
        "Pokemon Gaia (v3.2).gba" to 0x020386DCL,
        "Pokemon Ultra Violet (v1.22).gba" to 0x020386DCL,
        "Pokemon Celia's Stupid Romhack (v1.1.4).gba" to 0x020388D8L,
        "Pokemon Unbound (v2.1.1.1).gba" to 0x020386DCL,
        "Pokemon Radical Red (v4.1).gba" to 0x020386DCL,
        "Pokemon Amethyst (v1.4.1).gba" to 0x020386DCL,
        "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba" to 0x020386DCL,
        // gcc builds (pokeemerald-expansion, Recharged Yellow's pokefirered, R.O.W.E.'s fork): the looser shape.
        "Pokemon Lazarus (v2.0).gba" to 0x0201F5C0L,
        "Pokemon SoulGold (v1.1.4).gba" to 0x0203E42CL,
        "Pokemon SoulGold (v1.2).gba" to 0x0203E434L,
        "Pokemon SoulGold (v1.2) (Alt 1).gba" to 0x0203E434L,
        "Pokemon SoulGold (v1.2) (Alt 2).gba" to 0x0203E434L,
        "Pokemon SoulGold (v1.2.1).gba" to 0x0203E35CL,
        "Pokemon SoulGold (v1.2.1) (Alt 1).gba" to 0x0203E35CL,
        "Pokemon Recharged Yellow (v1.8.0).gba" to 0x0203E374L,
        "Pokemon Recharged Yellow (v1.9.0).gba" to 0x0203E39CL,
        "Pokemon Recharged Yellow (v1.9.4).gba" to 0x0203E3A8L,
        "Pokemon Recharged Yellow (v1.9.7).gba" to 0x0203E3A8L,
        "Pokemon Emerald Seaglass (v3.0).gba" to 0x0201D804L,
        "Pokemon Emerald Imperium (v1.3.1).gba" to 0x0203F94CL,
        "Pokemon Too Many Types 2 (v1.5.2).gba" to 0x020388C0L,
        "Pokemon Heart and Soul (v2.0.6).gba" to 0x0203C794L,
        "Pokemon R.O.W.E. (v2.1.9.1 Experimental).gba" to 0x02039B68L,
        "Super Mariomon (v1.2.1).gba" to 0x02039CC4L,
        "Super Mariomon (v1.3).gba" to 0x02039CC4L,
        "Super Mariomon (v1.5.2 Anniversary).gba" to 0x0203A6ACL,
        // Elite Redux (its own gcc pokeemerald): 2.65.4.1b reads the player's coordinates before the header lookup's call.
        "Pokemon Elite Redux (2.65.3b).gba" to 0x0203D214L,
        "Pokemon Elite Redux (2.65.4.1b).gba" to 0x0203D21CL,
        // Wishes of Tomorrow (expansion 1.15.3): +0xAC from an expansion 1.15.3 build's, like the globals around it. Not
        // walked: without field reads (no WIDE rows) the app doesn't roll it yet (ROM_FEATURES' note 21).
        "Pokemon Wishes of Tomorrow (v1.2.6).gba" to 0x0203788CL,
        // Dreamstone Mysteries (expansion 1.11.2): checked headless - set, 160 steps in ROUTE 1's grass met nothing; left
        // alone, a PIDOVE came. Not offered yet: the switch is rolled by the field read, which doesn't know this game.
        "Pokemon Dreamstone Mysteries.gba" to 0x02037F88L,
        // Not found: no WILD ENCOUNTERS there.
        "Pokemon Emerald Rogue (v2.2.1-EX).gba" to 0L,
        "Pokemon Quetzal (Alpha 9.0) (English).gba" to 0L,
    )

    @Test
    fun findsEachGamesSwitch() {
        for ((name, want) in cases) {
            val rom = File(RETAIL_ROM_DIR + name)
            if (!rom.isFile) continue
            assertEquals(name, "%08X".format(want), "%08X".format(WildEncounters.find(rom)))
        }
    }
}
