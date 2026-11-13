package com.pokedaisy.app.companion

/**
 * WHAT'S NEW: the few lines a player cares about in a release, which the Library shows once after an update
 * ([com.pokedaisy.app.companion.ui.WhatsNewWindow]). Written with each release, beside `make bump` (docs/RELEASING.md):
 * short, the highlights only - RELEASE PAGE opens the GitHub release for the rest. Every line starts with what it is
 * about and a colon ("SHINY ODDS: ..."), which the window draws red. English, like the manual; the window's own words
 * (title, section titles, buttons) are translated. WhatsNewTest fails while the app's version has no notes, or a line
 * has no lead-in.
 */
object WhatsNew {
    class Section(
        /** Drawn through tr(): NEW or FIXES. */
        val title: String,
        val items: List<String>,
    )

    class Notes(val version: String, val sections: List<Section>)

    const val NEW = "NEW"
    const val FIXES = "FIXES"

    /**
     * A hotfix (a fourth version part: 1.5.0.1) is its release's notes again, its own [line] first under FIXES (the
     * user's call: the same WHAT'S NEW, saying it's a hotfix). A player who saw the release's notes isn't shown them
     * again ([due]).
     */
    private fun hotfix(version: String, of: Notes, line: String): Notes {
        val fixes = of.sections.firstOrNull { it.title == FIXES }
        val sections = if (fixes == null) of.sections + Section(FIXES, listOf(line))
        else of.sections.map { if (it === fixes) Section(FIXES, listOf(line) + it.items) else it }
        return Notes(version, sections)
    }

    /** A hotfix's release (1.5.0.1 -> 1.5.0); any other version itself. */
    fun releaseOf(version: String): String = version.split('.').let { if (it.size > 3) it.take(3).joinToString(".") else version }

    /** Newest first. */
    val RELEASES: List<Notes> by lazy {
        listOf(
            NOTES_1_5_1,
            hotfix("1.5.0.1", NOTES_1_5_0, "1.5.0.1 HOTFIX: turning a Samsung Galaxy sideways no longer closes the game."),
        ) + OLDER
    }

    private val NOTES_1_5_1 = Notes(
        "1.5.1",
        listOf(
            Section(
                NEW,
                listOf(
                    "FREE FLIGHT: take off from the MAP tab or the quick menu and fly over the map yourself.",
                    "Scenic FLY: FLY takes you there through the sky. FLY STYLE brings back the classic way.",
                    "FLY ON and RIDE ON: fly in a balloon, a helicopter, a zeppelin or a plane, and ride a motorcycle or a scooter instead of the bike.",
                    "New games: Blaze Black 2 and Volt White 2 Redux, Team Rocket Edition 2026 v3.1, and German Crystal, Gold and Silver.",
                    "FLY in more hacks: Seaglass, Too Many Types 2, R.O.W.E., Super Mariomon and Quetzal.",
                    "DS CORE: a second DS emulator, faster on weaker devices. AUTO picks it for you.",
                    "Polished Crystal: EXPAND widens the overworld.",
                    "Unbound: change your party's order from the companion.",
                    "Save states: press and hold a slot to delete it.",
                    "DS TOUCH SCREEN: STRETCH fills the second screen with the touch screen.",
                ),
            ),
            Section(
                FIXES,
                listOf(
                    "DS on weaker devices: smoother play, skipping a frame when a device can't keep up.",
                    "Smoother play: a game that turns choppy gets smooth again by itself.",
                    "DS battles on one screen: the companion's tab stays where you can tap it.",
                    "AUTO-RUN: no longer closes Recharged Yellow's START menu.",
                ),
            ),
        ),
    )

    private val NOTES_1_5_0 = Notes(
        "1.5.0",
        listOf(
            Section(
                NEW,
                listOf(
                    "New games: Monomyth v1.1, Yellow Legacy, Crystal Legacy, Renegade Platinum and Modern Emerald.",
                    "Korean: the whole app in Korean.",
                    "Fresh look: a refreshed HUD, quick menu and battle buttons, with new animations.",
                    "DS in more languages: HeartGold to White 2 in French, German, Spanish, Italian, Japanese and Korean, and Platinum in Spanish.",
                    "DS on one screen: menus load faster, behind a steady picture instead of the fades.",
                    "CHEATS on DS: Action Replay DS codes.",
                    "INSTANT TEXT: the GBA games' dialogue shows all at once.",
                    "BATTLE STYLE: a Gen 5 battle screen for the companion, beside the Gen 4 one.",
                    "Field moves: teach TMs from the bag, FLASH from the quick menu, and the whole PC menu anywhere.",
                    "Library: search your games, show SUPPORTED ONLY, and ADD TO HOME for a game's own icon.",
                    "FOLD LAYOUT: a half open foldable puts the game and the companion on either side of the hinge.",
                    "Touch controls: new layouts, looks and colours, X and Y buttons, and Delta skins.",
                ),
            ),
            Section(
                FIXES,
                listOf(
                    "SoulGold: switching Pokémon from the companion follows your PARTY MENU option.",
                    "Smoother play: steadier frames on the RG DS and other screens that tick unevenly.",
                    "Unbound: no more white box behind the text boxes with EXPAND on.",
                ),
            ),
        ),
    )

    private val OLDER: List<Notes> = listOf(
        NOTES_1_5_0,
        Notes(
            "1.4.0",
            listOf(
                Section(
                    NEW,
                    listOf(
                        "DS on one screen: hold your device sideways and the touch screen floats in when the game needs it.",
                        "New DS games: Pokémon White, Black 2, White 2, Origin HeartGold, and German HeartGold and SoulSilver.",
                        "New GBA games: Let's Go, Monomyth, Team Rocket Edition 2026 (Spanish), and randomized FireRed and Emerald.",
                        "BOXES: your PC's boxes in the companion. Move Pokémon between a box and your party.",
                        "THEME: colour the app and the companion, with eight new themes and a few for the holidays.",
                        "TRADE EVOLUTIONS: evolve a Pokémon that needs a trade, from its summary.",
                        "GAME VOLUME: a volume slider, MUTE GAME and a MUTE hotkey.",
                        "PATCH A ROM: turn a .bps patch into a game, from the library's TOOLS.",
                        "LINK PLAY: two to four GBA games joined by a link cable, to trade and battle between them.",
                        "SAVE EDITOR: change a game's boxes, bag and money in its save.",
                        "TRADE SAVES: move Pokémon between your games' saves, the way a trade would.",
                    ),
                ),
                Section(
                    FIXES,
                    listOf(
                        "Game Boy sound: no more crackle in Yellow, Crystal and the other Game Boy games.",
                        "Sound: no more wobble in the music's pitch while you play.",
                        "Black: a party of six no longer blanks the companion.",
                        "Black 2 and White 2: the battle's text box looks like the game's, and the bag shows the item you pick.",
                    ),
                ),
            ),
        ),
        Notes(
            "1.3.3",
            listOf(
                Section(
                    NEW,
                    listOf(
                        "New games: Pokémon Black, Wishes of Tomorrow, PureRed / PureBlue and Dreamstone Mysteries.",
                        "SHINY ODDS: pick how often wild Pokémon are shiny. Shiny ones get their own icons and marks.",
                        "NUZLOCKE: a new tab that keeps score of your run.",
                        "DS RESOLUTION: the DS games' 3D drawn sharper, up to 8x.",
                    ),
                ),
                Section(
                    FIXES,
                    listOf(
                        "Hotkeys: one with SELECT or START in it no longer swallows that button in the game.",
                        "WILD ENCOUNTERS: turning it back on always brings the wild Pokémon back.",
                        "Fast-forward and Game Boy sound: FF takes effect as soon as you turn it on, and loud Game Boy music no longer crackles.",
                    ),
                ),
            ),
        ),
        Notes(
            "1.3.2",
            listOf(
                Section(
                    NEW,
                    listOf(
                        "Phones held upright: the game on top, the companion in the middle and the touch controls at the bottom.",
                        "DS games: X and Y on the touch controls, and the buttons in the bottom corners when you hold one screen sideways.",
                        "COMPANION hotkey: opens the companion on one screen.",
                        "Nicknames: the party shows them in the Game Boy games, R.O.W.E. and Elite Redux too.",
                    ),
                ),
                Section(
                    FIXES,
                    listOf(
                        "GBA games: no longer crash when you close them.",
                        "Gold and Silver: listed under GBC.",
                        "SoulGold: shiny party icons, and NEXT BOSS follows the Elite Four.",
                        "Library: no longer freezes while it reads new games.",
                    ),
                ),
            ),
        ),
    )

    fun notesFor(version: String): Notes? = RELEASES.firstOrNull { it.version == version }

    /**
     * [current]'s notes when they're due: the app was updated (a fresh install has nothing to tell) to a version the
     * player hasn't seen them for ([lastSeen]: Prefs.whatsNewSeen), and that version has notes.
     */
    fun due(current: String, lastSeen: String?, updated: Boolean): Notes? =
        if (updated && lastSeen != current && lastSeen?.let(::releaseOf) != releaseOf(current)) notesFor(current) else null
}
