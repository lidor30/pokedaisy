package com.pokedaisy.app.companion.data

/**
 * Super Mariomon's guide: NEXT BOSS for the eight STARS (its badges: Emerald's FLAG_BADGE01_GET
 * 0x867.. 0x86E, the STARS on its continue screen and card), HERE from its wild tables
 * (GUIDE_TABLES_MARIOMON_*). Each leader is the trainer whose battle script sets the star's flag
 * (trainerbattle / setflag bytecode), the place the map that script belongs to (event and map
 * script pointers walked back to their map header): PEACH 102, DAISY 121 (103 is a second copy
 * with the same team), WARIO 104, WALUIGI 105, DK 149, PAULINE 166, ROSALINA 175, BOWSER 201. The
 * same ids and flags in every release. The teams are the default ones: the game swaps in the
 * record's other parties under flags 0x29 / 0x885 (CreateNPCTrainerParty), which no script sets.
 * Its League rooms at MUSHROOM HILL (FLAG_DEFEATED_ELITE_4_* 0x4FB-0x4FE, set out of room order)
 * aren't listed yet.
 */
private const val FLAG_BADGE01_GET = 0x867

private val BOSSES_MARIOMON = listOf(
    Triple("PEACH", "PEACH'S CASTLE", 102), Triple("DAISY", "DAISY'S CRUISER", 121),
    Triple("WARIO", "DIAMOND CITY", 104), Triple("WALUIGI", "MAIN STREET", 105),
    Triple("DK", "JUNGLE VILLAGE", 149), Triple("PAULINE", "NEW DONK CITY", 166),
    Triple("ROSALINA", "COMET OBSERVATORY", 175), Triple("BOWSER", "BOWSER'S CASTLE", 201),
).mapIndexed { i, (name, where, id) -> Boss("STAR ${i + 1} $name", where, FLAG_BADGE01_GET + i) { id } }

internal val GUIDE_MARIOMON = GameGuide(verified = false, bosses = BOSSES_MARIOMON, pages = emptyList())
