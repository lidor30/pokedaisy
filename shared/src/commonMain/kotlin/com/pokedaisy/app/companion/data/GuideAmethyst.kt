package com.pokedaisy.app.companion.data

/**
 * Pokémon Amethyst's guide: WHERE IS from its area data (gen_guide_areas_rom.py),
 * NEXT BOSS for the gyms whose scripts battle a fixed trainer (FireRed's badge
 * flags, 0x820..). Every team comes in four versions - the game's trainer
 * loader picks a table by HARD MODE and the DIVERGENT Pokémon set (see
 * [GUIDE_TABLES_AMETHYST]) - so all four are listed. The third and fourth
 * gyms can be taken in either order: RAINE's script picks her team by the
 * badges you hold (2, 3, 4: trainers 105, 256, 257), CHANCE's by 3 or fewer
 * vs 4 (154, 258), counted by a native routine (0x088FB3D9) the scripts also
 * use for the level cap. CASEY's two ids differ only by the player's gender (same
 * team). The eighth gym and the League load their trainer from a var at run
 * time, so they aren't listed. Places are the region map's names.
 */
private const val FLAG_BADGE01_GET = 0x820

private val MODES = listOf("STANDARD", "HARD", "DIVERGENT", "DIVERGENT · HARD")

/** [id] in each of the four tables. */
private fun modes(id: Int) = MODES.mapIndexed { k, label -> label to (id or (k shl TRAINER_TABLE_SHIFT)) }

private fun badges(p: SaveProgress) = (0 until 8).count { p.flag(FLAG_BADGE01_GET + it) }

private fun gym(n: Int, title: String, where: String, id: Int) =
    Boss(title, where, FLAG_BADGE01_GET + n - 1, variants = modes(id)) { id }

private fun byBadges(n: Int, title: String, where: String, pick: (Int) -> Int) =
    Boss(title, where, FLAG_BADGE01_GET + n - 1, variantsFor = { p -> modes(pick(badges(p))) }) { p -> pick(badges(p)) }

private val BOSSES_AMETHYST = listOf(
    gym(1, "LEADER TERRENCE", "RHODANZI CITY GYM", 14),
    gym(2, "LEADER STELLA", "FERROX VILLAGE GYM", 64),
    byBadges(3, "LEADER RAINE", "HELEO CITY GYM") { b -> if (b <= 2) 105 else if (b == 3) 256 else 257 },
    byBadges(4, "LEADER CHANCE", "DAIMYN CITY GYM") { b -> if (b <= 3) 154 else 258 },
    gym(5, "LEADER CASEY", "LAPLAZ TOWN GYM", 233),
    gym(6, "LEADER ABBY", "BRUCCIE VILLAGE GYM", 279),
    gym(7, "LEADER IRIS", "TSARVOSA CITY GYM", 453),
)

internal val GUIDE_AMETHYST = GameGuide(
    verified = false,
    bosses = BOSSES_AMETHYST,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Why four teams per leader?",
                    "Each leader's team depends on two choices from the start of the game: HARD MODE and the DIVERGENT Pokémon selection. " +
                        "NEXT BOSS lists all four.",
                ),
                entry(
                    "Does the order of the middle gyms matter?",
                    "Yes - RAINE and CHANCE bring stronger teams the more badges you hold when you challenge them. NEXT BOSS shows the " +
                        "teams for the badges you have now.",
                ),
                entry(
                    "Why does NEXT BOSS stop at the seventh gym?",
                    "The last gym and the POKéMON LEAGUE pick their trainers while the game runs, in a way the guide can't read.",
                ),
            ),
        ),
    ),
)
