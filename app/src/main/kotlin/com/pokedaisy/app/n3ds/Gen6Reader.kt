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
        /** The field's X menu ([sceneOf]): a pointer, set while the menu is open, and at +0x24 a byte that's 1 then;
         * both 0 on the field, in dialogue, through doors. Mapped while the field module is loaded. Found on the RP6
         * and headless, two saves (2026-11-15). */
        val fieldMenu: Int = 0,
        /** The battle's touch-screen controller ([battleInput]); its command panel points at the battlers' records
         * ([battleMon]): the player's at +[panelPlayer], the foe's at +[panelFoe]. */
        val battleUi: Int = 0,
        val panelPlayer: Int = 0xDC,
        val panelFoe: Int = 0xE4,
    )

    class Pocket(val offset: Int, val capacity: Int, val companionPocket: Int)

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
        fieldMenu = 0x08603714,
        battleUi = 0x08515980,
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

    /** The modules that are the title and the start menu, and the field's: a screen is any other. */
    private val NOT_SCREENS = setOf("DllTitle", "DllBackGround", "DllSequence", "DllStartMenu")

    /** What [sceneOf] reads at [Config.fieldMenu]. */
    const val FIELD_MENU_BYTES = 0x28

    /**
     * The scene from the loaded modules ([N3dsNative.modules], one name a line) and [Config.fieldMenu]'s
     * [FIELD_MENU_BYTES]; null while it can't tell (between two screens no module is loaded; the title): the last
     * one stands. (The field's lower-screen object's flags, tried first, also read as the menu in dialogue and on
     * every door.)
     */
    fun sceneOf(modules: String, fieldMenu: ByteArray?): Scene? {
        val names = modules.split('\n').filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        if ("DllField" in names) {
            if (fieldMenu == null || fieldMenu.size < FIELD_MENU_BYTES) return Scene.FIELD
            val open = ByteBuffer.wrap(fieldMenu).order(ByteOrder.LITTLE_ENDIAN).getInt(0) != 0 && fieldMenu[0x24].toInt() == 1
            return if (open) Scene.MENU else Scene.FIELD
        }
        if ("DllBattle" in names) return Scene.BATTLE
        return if (names.all { it in NOT_SCREENS }) null else Scene.SCREEN
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

    /** The command panel's address in [ui] (0 = none), where the battlers' record pointers are. */
    fun commandPanel(ui: ByteArray?): Int =
        if (ui == null || ui.size < 4) 0 else ByteBuffer.wrap(ui).order(ByteOrder.LITTLE_ENDIAN).getInt(0).takeIf { inHeap(it) } ?: 0

    fun inHeap(a: Int): Boolean = a in 0x08000000 until 0x10000000

    data class BattleMove(val id: Int, val pp: Int, val maxPp: Int)
    data class BattleMon(val species: Int, val level: Int, val hp: Int, val maxHp: Int, val moves: List<BattleMove>)
    data class Battle(val input: Int, val player: BattleMon?, val foe: BattleMon?)

    /** A battler's record: +0xC species, +0xE max HP, +0x10 HP, +0x18 level, from +0x116 four 14-byte moves (id, PP,
     * max PP, then the move as it was). Null for an empty or broken one. */
    fun battleMon(rec: ByteArray?): BattleMon? {
        if (rec == null || rec.size < BATTLE_MON_BYTES) return null
        val b = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        val species = b.getShort(0xC).toInt() and 0xFFFF
        if (species == 0 || species > 721) return null
        val moves = (0 until 4).mapNotNull { i ->
            val o = 0x116 + i * 14
            val id = b.getShort(o).toInt() and 0xFFFF
            if (id == 0 || id > 1000) null else BattleMove(id, rec[o + 2].toInt() and 0xFF, rec[o + 3].toInt() and 0xFF)
        }
        return BattleMon(species, rec[0x18].toInt() and 0xFF, b.getShort(0x10).toInt() and 0xFFFF, b.getShort(0xE).toInt() and 0xFFFF, moves)
    }

    fun configFor(titleId: Long): Config? = when (titleId) {
        N3dsCart.POKEMON_X_USA -> POKEMON_X_USA
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
    )

    data class Item(val id: Int, val count: Int, val pocket: Int)

    /** The memory [read] needs for [c], as (address, length) pairs: the party, MyStatus, the bag and the zone. */
    fun ranges(c: Config): IntArray = intArrayOf(
        c.party, c.partyStride * 6,
        c.myStatus, 0x170,
        c.bag + 0x400, 0xF68 - 0x400,
        c.zone, 2,
    )

    class Sample(val party: List<Mon>, val language: Int, val bag: List<Item>, val zone: Int = -1)

    /** [bytes] is [ranges]' memory, one after another. */
    fun read(c: Config, bytes: ByteArray): Sample? {
        val partyLen = c.partyStride * 6
        val zoneAt = partyLen + 0x170 + (0xF68 - 0x400)
        if (bytes.size < zoneAt) return null
        val party = (0 until 6).mapNotNull { slot ->
            val at = slot * c.partyStride
            val raw = ByteArray(PARTY_SIZE)
            System.arraycopy(bytes, at, raw, 0, STORED_SIZE)
            System.arraycopy(bytes, at + c.partyStatsAt, raw, STORED_SIZE, PARTY_SIZE - STORED_SIZE)
            decode(raw)
        }
        val language = bytes[partyLen + 0x2D].toInt() and 0xFF
        val bagAt = partyLen + 0x170
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val bag = c.pockets.flatMap { p ->
            val out = ArrayList<Item>()
            for (i in 0 until p.capacity) {
                val o = bagAt + (p.offset - 0x400) + i * 4
                val id = b.getShort(o).toInt() and 0xFFFF
                val count = b.getShort(o + 2).toInt() and 0xFFFF
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
        )
    }
}
