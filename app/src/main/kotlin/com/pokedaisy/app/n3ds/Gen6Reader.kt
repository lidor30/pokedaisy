package com.pokedaisy.app.n3ds

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pokemon X / Y's party and bag, read from the game's memory (docs/3DS_GAMES_PLAN.md, found headless with n3ds_dump on
 * the player's save and checked on two boots). Addresses are the game process's.
 *
 * - The live party: six slots of [Config.partyStride] bytes. Each starts with a Gen 6 Pokemon's 0xE8 bytes (PK6:
 *   encrypted by an LCRNG seeded with its encryption constant, its four 0x38-byte blocks shuffled by that constant), and
 *   its 0x1C bytes of party stats (status, level, HP, stats) sit at [Config.partyStatsAt], encrypted by the same cipher
 *   started again.
 * - The bag: the save's pockets as the game keeps them (u16 item, u16 count), from [Config.bag].
 * - MyStatus (the trainer): its language at +0x2D (1 Japanese, 2 English, 3 French, 4 Italian, 5 German, 7 Spanish,
 *   8 Korean), which picks the text the names come from.
 */
object Gen6Reader {
    class Config(
        val party: Int,
        val partyStride: Int,
        val partyStatsAt: Int,
        val myStatus: Int,
        val bag: Int,
        /** The current zone (u16; the save's situation, the player's coordinates as floats after it): [Gen6Zones]. */
        val zone: Int,
        /** Each pocket: its offset from [bag] and how many entries it holds, with the companion's pocket. */
        val pockets: List<Pocket>,
        /** The static module's (CRS) header: the loaded modules' chain starts there ([N3dsNative.modules]). */
        val staticModule: Int = 0,
        /** The field's X menu ([sceneOf]): at [fieldMenuRoot] the field's object, and in it, +[fieldMenuOffset], a
         * pointer set while the menu is open, with at +0x24 a byte that's 1 then; both 0 on the field, in dialogue,
         * through doors. The object moves with the heap (it sat at 0x086036D4, then after a Save at 0x08602794: a fixed
         * 0x08603714 went blind), its root doesn't. Mapped while the field module is loaded. Found on the RP6 and
         * headless (two saves), the root checked against ten dumps on two devices (2026-11-15). */
        val fieldMenuRoot: Int = 0,
        val fieldMenuOffset: Int = 0x40,
        /** A u32 whose bit 1 ([FIELD_HELD]) is set while the game holds the player on the field: talking, a scripted
         * scene, a door (0x3D1 free, 0x3D3 held; the same in the player's save on the RP6 and a test save headless,
         * 2026-11-15). The HUD's bottom row steps away meanwhile (the game's text box is there). */
        val fieldHold: Int = 0,
        /** The battle's touch-screen controller ([battleInput]); its command panel points at the battlers' records
         * ([battleMon]): the player's at +[panelPlayer], the foe's at +[panelFoe]. */
        val battleUi: Int = 0,
        val panelPlayer: Int = 0xDC,
        val panelFoe: Int = 0xE4,
        /** Sun / Moon's battle instead of [battleUi] ([battleInputSm]): a byte for the menu up ([battleMenu]: 5 the
         * command menu, 2 the moves, 1 the bag or the party, 3 a turn playing, 0 out of battle), a byte telling the
         * bag (3) from the party (2) ([battleSubmenu]), and words pointing at the player's and the foe's battler
         * records ([battlerPlayer] / [battlerFoe]). */
        val battleMenu: Int = 0,
        val battleSubmenu: Int = 0,
        /** [battleMenu] / [battleSubmenu] as roots with these paths ([chained]), where the bytes move with the heap. */
        val battleMenuPath: IntArray? = null,
        val battleSubmenuPath: IntArray? = null,
        val battlerPlayer: Int = 0,
        val battlerFoe: Int = 0,
        /** Sun / Moon: the player's live world position (floats x, y, z), for the Town Map's pin ([SmTownMap]). */
        val playerPos: Int = 0,
        /** Where a battler's four moves start in its record ([battleMon]): X / ORAS +0x116, Sun / Moon +0x1FC. */
        val battleMovesAt: Int = 0x116,
        /** The field camera ([Camera]); null where it isn't mapped (no ZOOM or free camera there). */
        val camera: Camera? = null,
        /** MyStatus' language byte (X / ORAS +0x2D, Sun / Moon +0x35). */
        val languageAt: Int = 0x2D,
        /** The bag's entries: Gen 6's u16 item + u16 count, or Gen 7's u32 (item 10 bits, count the next 10). */
        val bagGen7: Boolean = false,
        /** The field's and the battle's modules ([sceneOf]): X / ORAS "DllField" / "DllBattle", Sun / Moon "FieldRo" /
         * "Battle". */
        val fieldModule: String = "DllField",
        val battleModule: String = "DllBattle",
        /** The title's / start's modules: no screen of the game's own. */
        val notScreens: Set<String> = NOT_SCREENS,
        /** Field modules that wait on the touch screen alone ([FIELD_TOUCH]). */
        val fieldTouch: Set<String> = FIELD_TOUCH,
        /** Sun / Moon: a byte that's non-zero while the X menu is open (instead of [fieldMenuRoot]'s object). It's set
         * under a text box too, so it counts only while [fieldHold] isn't. */
        val fieldMenuWord: Int = 0,
        /** [fieldHold]'s bits that mean held (X / ORAS [FIELD_HELD]; Sun / Moon bit 0). */
        val fieldHoldMask: Int = FIELD_HELD,
        /** Sun / Moon: [fieldMenuWord] and [fieldHold] are roots, not the bytes ([chained]): their objects move with the
         * heap from save to save, so the pointers are followed each read. */
        val fieldMenuPath: IntArray? = null,
        val fieldHoldPath: IntArray? = null,
    )

    class Pocket(val offset: Int, val capacity: Int, val companionPocket: Int)

    /** The address [path] leads to from [root] ([readWord] reads the game's memory): the word at [root], then for each
     * offset but the last the word that far into the last object, and the last offset added; [root] itself without a
     * path. Null where a pointer isn't one into the heap. */
    fun chained(root: Int, path: IntArray?, readWord: (Int) -> Int?): Int? {
        if (path == null || path.isEmpty()) return root
        var a = readWord(root) ?: return null
        for (off in path.dropLast(1)) {
            if (!inHeap(a)) return null
            a = readWord(a + off) ?: return null
        }
        return if (inHeap(a)) a + path.last() else null
    }

    /**
     * Where a game keeps its field camera's settings and how: the word at [root] is an object, then each of [path]
     * is the next pointer's offset in the last, and the settings sit [setupAt] past the last object. In them the
     * pitch (negative = looking down), the yaw and the distance at their offsets, in degrees or [radians]. Written
     * there, a value sticks and the camera follows. [turnsPad]: the game walks the tile grid as it was (X / Y,
     * Omega Ruby / Alpha Sapphire), so the circle pad is turned with the camera; Sun / Moon turn it themselves.
     */
    class Camera(
        val root: Int,
        val path: IntArray,
        val setupAt: Int,
        val pitch: Int,
        val yaw: Int,
        val distance: Int,
        val radians: Boolean = false,
        val turnsPad: Boolean = true,
    )

    /** The modules that are the title and the start menu, and the field's: a screen is any other. */
    val NOT_SCREENS = setOf("DllTitle", "DllBackGround", "DllSequence", "DllStartMenu")

    /** Field modules that wait on the touch screen alone, the top screen dimmed: MENU too. Save's "save the game?" with
     * its Yes / No (the X menu's marker is cleared meanwhile), and START's PSS menu and its pages (headless, 2026-11-15:
     * START loads DllPssMainMenu beside the field; the cart's other DllPss* are its pages). Not DllPssMessageWindow:
     * the PSS's notes ride along on the plain field. */
    val FIELD_TOUCH = setOf(
        "DllReport", "DllPssMainMenu", "DllPssCharaMenu", "DllPssConfig", "DllPssFavorite", "DllPssIconSelect",
        "DllPssPlaySelect", "DllPssRuleSelect", "DllPssAssistPower",
    )


    /** Pokemon X (USA). */
    val POKEMON_X_USA = Config(
        party = 0x08CE1CE8,
        partyStride = 0x1E4,
        partyStatsAt = 0x158,
        myStatus = 0x08C79C2C,
        bag = 0x08C67154,
        zone = 0x08C6709E,
        pockets = listOf(
            Pocket(0x400, 400, com.pokedaisy.app.companion.data.POCKET_ITEMS),
            Pocket(0xA40, 96, com.pokedaisy.app.companion.data.POCKET_KEY_ITEMS),
            Pocket(0xBC0, 106, com.pokedaisy.app.companion.data.POCKET_TM_HM),
            Pocket(0xD68, 64, com.pokedaisy.app.companion.data.POCKET_MEDICINE),
            Pocket(0xE68, 64, com.pokedaisy.app.companion.data.POCKET_BERRIES),
        ),
        staticModule = 0x012B6000,
        fieldMenuRoot = 0x08C54044,
        fieldHold = 0x08D03400,
        battleUi = 0x08515980,
        camera = Camera(root = 0x08C54044, path = intArrayOf(0xD8, 0x21C, 0xB0C), setupAt = CAMERA_SETUP, pitch = 0x4, yaw = 0x8, distance = CAMERA_DISTANCE),
    )

    /**
     * Omega Ruby / Alpha Sapphire (USA, Rev 2 carts): X's structures at their own addresses, the same in both (headless,
     * 2026-11-17, n3ds_dump on the players' saves: an Omega Ruby one in Slateport's Pokemon Center, an Alpha Sapphire one
     * in Littleroot). The live party (Azelf Lv100 219 / 300 HP decoded with its stats at +0x158, as X's), the bag's five
     * pockets at X's offsets from the bag, MyStatus (the name at +0x48), the save's situation (the zone at +2, then the
     * coordinates). The X menu: the field object's root at 0x08C57660, its menu pointer at +0x4C (X's +0x40) with the
     * byte at +0x24 that's 1 while it's open (menu open / closed dumps). The hold word reads 0x3D1 on the field and 0x3D3
     * with the menu up, as X's.
     * The zone: the save's situation (0x08C6B89E) keeps the place of the last save; the live one is 0x08C6A7B2 (Alpha
     * Sapphire walked out of a Littleroot house, 224, into the town, 6, and up Route 101, 23).
     * The battle's controller is X's at 0x0852FCF0 (Alpha Sapphire, a wild battle on Route 101: +0x17 1 at the command
     * menu, 2 at the moves, the bag's 1 at +0x10; the foe's record Zigzagoon Lv2 14 / 14 with Tackle / Growl, as X's).
     * The camera: the field object +0xE8, +0x5C, +0xAF8 = X's controller, its setup at +0x64 (pitch -40.74, FOV 30;
     * a distance and a yaw written there moved the camera).
     */
    val POKEMON_ORAS_USA = Config(
        party = 0x08CF727C,
        partyStride = 0x1E4,
        partyStatsAt = 0x158,
        myStatus = 0x08C7D350,
        bag = 0x08C6A880,
        zone = 0x08C6A7B2,
        pockets = POKEMON_X_USA.pockets,
        staticModule = 0x01307000,
        fieldMenuRoot = 0x08C57660,
        fieldMenuOffset = 0x4C,
        fieldHold = 0x08D1E3FC,
        battleUi = 0x0852FCF0,
        camera = Camera(
            root = 0x08C57660, path = intArrayOf(0xE8, 0x5C, 0xAF8), setupAt = CAMERA_SETUP,
            pitch = 0x4, yaw = 0x8, distance = CAMERA_DISTANCE,
        ),
    )

    /**
     * Sun / Moon (USA): Gen 7's game keeps its heap at 0x30000000 up (headless, 2026-11-17, n3ds_dump on a Moon save in
     * Seafolk Village's Pokemon Center; Sun's saves crashed the Mac's software renderer as the field loaded). The live
     * party is X's shape (0x1E4 a slot, the stats at +0x158: Mimikyu Lv100 251 / 251 decoded); the save's blocks packed
     * as X's: the bag at the save's own offset 0 (six pockets of u32 entries, PKHeX's MyItem7SM: items 430, key items
     * 184, TMs 108, medicine 64, berries 72, Z-Crystals 30), MyStatus (the language at +0x35, the name at +0x38), the
     * situation (the zone at +2, 263 = Seafolk Village). The X menu: 0x083FE170 holds a pointer while it's open, 0 on
     * the field (open / closed dumps). The modules have no "Dll": FieldRo the field, Battle the battle.
     */
    val POKEMON_SM_USA = Config(
        party = 0x34195E10,
        partyStride = 0x1E4,
        partyStatsAt = 0x158,
        myStatus = 0x330D67D0,
        bag = 0x330D5934,
        // The live zone (the save's situation at 0x330D6736 keeps the last save's place): 263 in the Moon save's dumps,
        // 77 / 134 walking out of / into Heahea City's Pokemon Center in Sun's.
        zone = 0x300019AC,
        pockets = listOf(
            Pocket(0x000, 430, com.pokedaisy.app.companion.data.POCKET_ITEMS),
            Pocket(0x6B8, 184, com.pokedaisy.app.companion.data.POCKET_KEY_ITEMS),
            Pocket(0x998, 108, com.pokedaisy.app.companion.data.POCKET_TM_HM),
            Pocket(0xB48, 64, com.pokedaisy.app.companion.data.POCKET_MEDICINE),
            Pocket(0xC48, 72, com.pokedaisy.app.companion.data.POCKET_BERRIES),
            Pocket(0xD68, 30, com.pokedaisy.app.companion.data.POCKET_KEY_ITEMS),
        ),
        staticModule = 0x00869000,
        // The X menu's byte: in the field object the camera's in ([0x300002A0] +0x2A8, then +0x235): 1 while the menu
        // or Save's question is up, 0 on the field. First found at a fixed 0x30004D1D in the Moon save; Sun's own save
        // has it elsewhere (0x300064AD), so the chain, which reads the same on every dump of both saves.
        fieldMenuWord = 0x300002A0,
        fieldMenuPath = intArrayOf(0x2A8, 0x235),
        // The hold byte ([0x30001A9C] +0x7C, then +0x14C; the Moon save's 0x300046C8): 2 on the field and in the X menu,
        // 3 with a text box up (Sun, the Ride Pokemon's "can't call it here" box twice, field / box / field / box
        // dumps); the menu byte above is 1 under a text box as well.
        fieldHold = 0x30001A9C,
        fieldHoldPath = intArrayOf(0x7C, 0x14C),
        fieldHoldMask = 1,
        languageAt = 0x35,
        bagGen7 = true,
        fieldModule = "FieldRo",
        battleModule = "Battle",
        // The field camera (Sun, headless, 2026-11-17: Heahea City at night and in its Pokemon Center; the same chain on
        // every Moon dump): its controller (outdoors 0x300065EC, indoors 0x30004E5C) holds the pitch (radians) at
        // +0x10, the yaw at +0x14 and the distance at +0x20 (756.8 = the camera's distance from its target, -0.613 =
        // its -35.1 degrees); written there, the camera pulled back, turned and tilted. The game walks relative to
        // the yaw by itself (turned 0.8, DOWN walked diagonally).
        camera = Camera(
            root = 0x300002A0, path = intArrayOf(0x2A8), setupAt = 0x374,
            pitch = 0x10, yaw = 0x14, distance = 0x20, radians = true, turnsPad = false,
        ),
        notScreens = setOf("Title", "TitleMenu", "Background", "SequencePlayer", "LangSelect", "HeroSelect", "Object", "FieldMenu"),
        fieldTouch = emptySet(),
        // The battle (Sun, headless, two wild battles on Route 4 - a Grubbin, an Alolan Rattata - each at the command
        // menu, the moves, back, the cursor moved, the bag, the party and mid-turn): the same bytes in both.
        // The bytes sat at 0x3003D508 / 0x302E13E0 in both battles; read through the heap's fixed start
        // ([0x30000380] +0x1D0, +0xC08 and +0x1B8, +0x170: the same in every dump of both) since the X menu's moved
        // between saves.
        battleMenu = 0x30000380,
        battleMenuPath = intArrayOf(0x1D0, 0xC08),
        battleSubmenu = 0x30000380,
        battleSubmenuPath = intArrayOf(0x1B8, 0x170),
        battlerPlayer = 0x30000404,
        battlerFoe = 0x30000420,
        battleMovesAt = 0x1FC,
        // Rewritten every frame from the player's object outdoors (Sun: walking moved it, written by 0x725E9C); indoors
        // it holds something else, so it's read only on the island's main world.
        playerPos = 0x30001B2C,
    )

    /** What the game's two screens show, for where the touch screen goes ([sceneOf]). */
    enum class Scene {
        /** The field, the touch screen its own (PSS): nothing to touch there that buttons can't do. */
        FIELD,
        /** The field's X menu: on the touch screen alone, the top screen still the field. */
        MENU,
        /** A screen of its own (party, bag...): both screens matter. */
        SCREEN,
        /** A battle: full size, the app's strip for its commands, the game's bag / party floating in. */
        BATTLE,
    }

    /**
     * The field camera's setup, from the field object ([Config.fieldMenuRoot]): +0xD8, then +0x21C (the camera system),
     * then +0xB0C (its controller), and +[CAMERA_SETUP] in that the 68 bytes the game copies into the drawn camera every
     * frame (DllField's 0x7242B4): floats target height, pitch (degrees), yaw (degrees), 0, near, far, FOV (degrees),
     * distance (+[CAMERA_DISTANCE]), ... Written there, a value sticks and the camera follows it (found headless with
     * n3ds_dump's watchw, checked live on the RP6, 2026-11-15: the controller at 0x084C0AB0 / 0x084C0D90).
     */
    val CAMERA_PATH = intArrayOf(0xD8, 0x21C, 0xB0C)
    const val CAMERA_SETUP = 0x64
    const val CAMERA_DISTANCE = 0x1C

    /** [Config.fieldHold]'s bit while the game holds the player. */
    const val FIELD_HELD = 2

    /** What [sceneOf] reads at the field object's [Config.fieldMenuOffset]. */
    const val FIELD_MENU_BYTES = 0x28

    /**
     * The scene from the loaded modules ([N3dsNative.modules], one name a line) and the field menu's ([Config.fieldMenuRoot])
     * [FIELD_MENU_BYTES]; null while it can't tell (between two screens no module is loaded; the title): the last
     * one stands. (The field's lower-screen object's flags, tried first, also read as the menu in dialogue and on
     * every door.)
     */
    fun sceneOf(modules: String, fieldMenu: ByteArray?, c: Config = POKEMON_X_USA): Scene? {
        val names = modules.split('\n').filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        if (c.fieldModule in names) {
            if (names.any { it in c.fieldTouch }) return Scene.MENU
            if (c.fieldMenuWord != 0) {
                // Sun / Moon: one word, set while the menu is open.
                // [fieldMenu] here: the menu byte, then the hold byte (a text box sets the menu byte too).
                val open = fieldMenu != null && fieldMenu.isNotEmpty() && fieldMenu[0].toInt() != 0 &&
                    !(fieldMenu.size >= 2 && fieldMenu[1].toInt() and c.fieldHoldMask != 0)
                return if (open) Scene.MENU else Scene.FIELD
            }
            if (fieldMenu == null || fieldMenu.size < FIELD_MENU_BYTES) return Scene.FIELD
            val open = ByteBuffer.wrap(fieldMenu).order(ByteOrder.LITTLE_ENDIAN).getInt(0) != 0 && fieldMenu[0x24].toInt() == 1
            return if (open) Scene.MENU else Scene.FIELD
        }
        // A battle whose controller isn't mapped ([Config.battleUi] 0: Omega Ruby / Alpha Sapphire, Sun / Moon so far) has
        // no battle buttons to stand for its touch screen: both screens side by side, as a screen of the game's own.
        if (c.battleModule in names) return if (c.battleUi != 0 || c.battleMenu != 0) Scene.BATTLE else Scene.SCREEN
        return if (names.all { it in c.notScreens }) null else Scene.SCREEN
    }

    // ---- battles (found on the RP6 with heap dumps, 2026-11-15: a wild battle at its command menu, its move list,
    // mid-turn, its bag and party) ----------------------------------------------------------------------------------

    /** What [battleInput] reads at [Config.battleUi]. */
    const val BATTLE_UI_BYTES = 0x18
    /** What [battleMon] reads of a battler's record (to its last move). */
    const val BATTLE_MON_BYTES = 0x150

    /**
     * The battle's state from its touch-screen controller: +0 / +4 the command and move panels, +0xC the one up, +0x10
     * the bag (1) or the party (2) while those are open (the panels zero then; left as it was once they close), +0x17
     * 1 at the command menu, 2 at the move list, 0 otherwise. As the companion's BATTLE_INPUT_* values.
     */
    fun battleInput(ui: ByteArray?): Int {
        if (ui == null || ui.size < BATTLE_UI_BYTES) return com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
        val b = ByteBuffer.wrap(ui).order(ByteOrder.LITTLE_ENDIAN)
        return when (ui[0x17].toInt()) {
            1 -> com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
            2 -> com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
            else -> if (b.getInt(0) == 0 && b.getInt(4) == 0 && b.getInt(0xC) == 0) when (b.getInt(0x10)) {
                1 -> com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
                2 -> com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
                else -> com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
            } else com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
        }
    }

    /** Sun / Moon's battle state from [Config.battleMenu]'s and [Config.battleSubmenu]'s bytes. */
    fun battleInputSm(menu: Int, submenu: Int): Int = when (menu) {
        5 -> com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
        2 -> com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
        1 -> when (submenu) {
            3 -> com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
            2 -> com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
            else -> com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
        }
        else -> com.pokedaisy.app.companion.data.BATTLE_INPUT_NONE
    }

    /** What [battleMon] reads of a battler's record in [c]'s game. */
    fun battleMonBytes(c: Config): Int = c.battleMovesAt + 4 * 14

    /** The command panel's address in [ui] (0 = none), where the battlers' record pointers are. */
    fun commandPanel(ui: ByteArray?): Int =
        if (ui == null || ui.size < 4) 0 else ByteBuffer.wrap(ui).order(ByteOrder.LITTLE_ENDIAN).getInt(0).takeIf { inHeap(it) } ?: 0

    /** A pointer into the game's heap: Gen 6's at 0x08000000, Sun / Moon's at 0x30000000. */
    fun inHeap(a: Int): Boolean = a in 0x08000000 until 0x10000000 || a in 0x30000000 until 0x40000000

    data class BattleMove(val id: Int, val pp: Int, val maxPp: Int)
    data class BattleMon(val species: Int, val level: Int, val hp: Int, val maxHp: Int, val moves: List<BattleMove>)
    data class Battle(val input: Int, val player: BattleMon?, val foe: BattleMon?)

    /** A battler's record: +0xC species, +0xE max HP, +0x10 HP, +0x18 level, from +0x116 four 14-byte moves (id, PP,
     * max PP, then the move as it was). Null for an empty or broken one. */
    fun battleMon(rec: ByteArray?, movesAt: Int = 0x116): BattleMon? {
        if (rec == null || rec.size < movesAt + 4 * 14) return null
        val b = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        val species = b.getShort(0xC).toInt() and 0xFFFF
        if (species == 0 || species > 809) return null
        val moves = (0 until 4).mapNotNull { i ->
            val o = movesAt + i * 14
            val id = b.getShort(o).toInt() and 0xFFFF
            if (id == 0 || id > 1000) null else BattleMove(id, rec[o + 2].toInt() and 0xFF, rec[o + 3].toInt() and 0xFF)
        }
        return BattleMon(species, rec[0x18].toInt() and 0xFF, b.getShort(0x10).toInt() and 0xFFFF, b.getShort(0xE).toInt() and 0xFFFF, moves)
    }

    fun configFor(titleId: Long): Config? = when (titleId) {
        // Y: X's code and memory, every address X's (headless on one save, 2026-11-15).
        N3dsCart.POKEMON_X_USA, N3dsCart.POKEMON_Y_USA -> POKEMON_X_USA
        N3dsCart.POKEMON_OR_USA, N3dsCart.POKEMON_AS_USA -> POKEMON_ORAS_USA
        // Moon's addresses: Sun reads the Moon save at every one of them (headless, 2026-11-17).
        N3dsCart.POKEMON_SUN_USA, N3dsCart.POKEMON_MOON_USA -> POKEMON_SM_USA
        else -> null
    }

    /** One party Pokemon, decoded. [species] 0 = an empty slot (never returned). */
    data class Mon(
        val encryptionConstant: Long,
        val species: Int,
        val form: Int,
        /** The record's gender bits (+0x1D: bit 1 female, bit 2 genderless). */
        val female: Boolean,
        val heldItem: Int,
        val ability: Int,
        val nature: Int,
        val exp: Long,
        val nickname: String,
        val moves: IntArray,
        val pp: IntArray,
        val isEgg: Boolean,
        val shiny: Boolean,
        val level: Int,
        val hp: Int,
        val maxHp: Int,
        val status: Int,
        /** Attack, Defense, Speed, Sp. Atk, Sp. Def. */
        val stats: IntArray,
        /** The record's gender bit 2: no gender. */
        val genderless: Boolean = false,
    )

    data class Item(val id: Int, val count: Int, val pocket: Int)

    /** The memory [read] needs for [c], as (address, length) pairs: the party, MyStatus, the bag and the zone. */
    fun ranges(c: Config): IntArray = intArrayOf(
        c.party, c.partyStride * 6,
        c.myStatus, 0x170,
        c.bag + bagStart(c), bagEnd(c) - bagStart(c),
        c.zone, 2,
    )

    /** The bag's pockets from the first to the end of the last, from [Config.bag]. */
    private fun bagStart(c: Config) = c.pockets.minOf { it.offset }
    private fun bagEnd(c: Config) = c.pockets.maxOf { it.offset + it.capacity * 4 }

    class Sample(val party: List<Mon>, val language: Int, val bag: List<Item>, val zone: Int = -1)

    /** [bytes] is [ranges]' memory, one after another. */
    fun read(c: Config, bytes: ByteArray): Sample? {
        val partyLen = c.partyStride * 6
        val zoneAt = partyLen + 0x170 + (bagEnd(c) - bagStart(c))
        if (bytes.size < zoneAt) return null
        val party = (0 until 6).mapNotNull { slot ->
            val at = slot * c.partyStride
            val raw = ByteArray(PARTY_SIZE)
            System.arraycopy(bytes, at, raw, 0, STORED_SIZE)
            System.arraycopy(bytes, at + c.partyStatsAt, raw, STORED_SIZE, PARTY_SIZE - STORED_SIZE)
            decode(raw)
        }
        val language = bytes[partyLen + c.languageAt].toInt() and 0xFF
        val bagAt = partyLen + 0x170
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val bag = c.pockets.flatMap { p ->
            val out = ArrayList<Item>()
            for (i in 0 until p.capacity) {
                val o = bagAt + (p.offset - bagStart(c)) + i * 4
                val id: Int
                val count: Int
                if (c.bagGen7) {
                    val v = b.getInt(o)
                    id = v and 0x3FF
                    count = (v ushr 10) and 0x3FF
                } else {
                    id = b.getShort(o).toInt() and 0xFFFF
                    count = b.getShort(o + 2).toInt() and 0xFFFF
                }
                if (id == 0) break
                out += Item(id, count, p.companionPocket)
            }
            out
        }
        val zone = if (bytes.size >= zoneAt + 2) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(zoneAt).toInt() and 0xFFFF else -1
        return Sample(party, language, bag, zone)
    }

    const val STORED_SIZE = 0xE8
    const val PARTY_SIZE = 0x104

    private val BLOCK_ORDERS = arrayOf(
        "ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB", "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
        "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA", "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA",
    )

    /** A PK6 (0x104 bytes, party stats included) decrypted and unshuffled; null when empty or its checksum is off. */
    fun decrypt(raw: ByteArray): ByteArray? {
        if (raw.size < PARTY_SIZE) return null
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val ec = b.getInt(0).toLong() and 0xFFFFFFFFL
        val out = raw.copyOf(PARTY_SIZE)
        val o = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        var seed = ec
        var i = 8
        while (i < PARTY_SIZE) {
            if (i == STORED_SIZE) seed = ec // the party stats: the cipher started again
            seed = (seed * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
            o.putShort(i, ((b.getShort(i).toInt() xor (seed ushr 16).toInt()) and 0xFFFF).toShort())
            i += 2
        }
        val order = BLOCK_ORDERS[(((ec ushr 13) and 31) % 24).toInt()]
        val plain = out.copyOf()
        for (k in 0 until 4) {
            val dest = "ABCD".indexOf(order[k])
            System.arraycopy(out, 8 + 56 * k, plain, 8 + 56 * dest, 56)
        }
        val p = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        var j = 8
        while (j < STORED_SIZE) { sum = (sum + (p.getShort(j).toInt() and 0xFFFF)) and 0xFFFF; j += 2 }
        if (sum != (p.getShort(6).toInt() and 0xFFFF)) return null
        return plain
    }

    fun decode(raw: ByteArray): Mon? {
        val p = decrypt(raw) ?: return null
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val species = b.getShort(0x08).toInt() and 0xFFFF
        if (species == 0) return null
        val nick = String(p, 0x40, 24, Charsets.UTF_16LE).substringBefore('\u0000')
        val pid = b.getInt(0x18).toLong() and 0xFFFFFFFFL
        val tid = b.getShort(0x0C).toInt() and 0xFFFF
        val sid = b.getShort(0x0E).toInt() and 0xFFFF
        val shiny = ((tid xor sid xor (pid ushr 16).toInt() xor (pid and 0xFFFF).toInt()) and 0xFFFF) < 16
        return Mon(
            encryptionConstant = b.getInt(0).toLong() and 0xFFFFFFFFL,
            species = species,
            form = (p[0x1D].toInt() and 0xFF) ushr 3,
            female = p[0x1D].toInt() and 2 != 0,
            heldItem = b.getShort(0x0A).toInt() and 0xFFFF,
            ability = p[0x14].toInt() and 0xFF,
            nature = p[0x1C].toInt() and 0xFF,
            exp = b.getInt(0x10).toLong() and 0xFFFFFFFFL,
            nickname = nick,
            moves = IntArray(4) { b.getShort(0x5A + it * 2).toInt() and 0xFFFF },
            pp = IntArray(4) { p[0x62 + it].toInt() and 0xFF },
            isEgg = (b.getInt(0x74) ushr 30) and 1 == 1,
            shiny = shiny,
            status = b.getInt(0xE8),
            level = p[0xEC].toInt() and 0xFF,
            hp = b.getShort(0xF0).toInt() and 0xFFFF,
            maxHp = b.getShort(0xF2).toInt() and 0xFFFF,
            stats = IntArray(5) { b.getShort(0xF4 + it * 2).toInt() and 0xFFFF },
            genderless = p[0x1D].toInt() and 4 != 0,
        )
    }
}
