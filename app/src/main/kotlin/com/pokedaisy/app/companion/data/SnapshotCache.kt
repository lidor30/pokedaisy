package com.pokedaisy.app.companion.data

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import java.io.File

/**
 * The companion's data saved beside a savestate (`<state>.companion`), so a launch that resumes
 * from it shows the party, bag, map and status bar at once instead of LOADING while the game is
 * detected; the live data replaces it in place ([com.pokedaisy.app.companion.TelemetryStore.showCached]).
 *
 * Only what's already plain data: party (moves, matchups, EXP, IVs / EVs), bag, location, money and
 * the map fields. The DEX / GUIDE / CARD tabs need the ROM's tables, so only their presence is kept
 * ([SnapshotView.cachedTabs]). Battles aren't kept: a state made mid-battle shows the party until the
 * battle is read. A few KB; a file that doesn't read back (another version) is simply ignored.
 */
object SnapshotCache {
    private const val MAGIC = 0x50445343   // "PDSC"
    // 2: move ids (the move advice reads them). 4: the names' game code and language, nicknames.
    // 5: the individual's ability slot.
    private const val VERSION = 5
    private const val MAX_BYTES = 256 * 1024

    fun fileFor(state: File) = File(state.path + ".companion")

    /** The tabs [v] has data for that the cache can't carry. */
    fun dataTabs(v: SnapshotView): Set<String> = buildSet {
        // A copy saved again before the live data came in (a quick close) keeps its own.
        v.cachedTabs?.let { addAll(it) }
        if (v.pokedex != null) add("DEX")
        val tables = v.pokedex?.tables?.takeIf { it.evolutions != 0L }
        if (tables != null || v.guideTables != null || gameGuide(guideId(v.game, v.guideTables)) != null) add("GUIDE")
        if (v.trainerCard != null) add("CARD")
        if (v.pcBoxes != null) add("BOXES")
    }

    /** Writes [v] beside [state], or removes a stale one when [v] has no data worth showing. */
    fun write(state: File, v: SnapshotView?) {
        val f = fileFor(state)
        val bytes = v?.takeIf { it.hasData && !it.unsupported }?.let { encode(it) }
        runCatching {
            if (bytes == null) {
                f.delete()
                return
            }
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun read(state: File): SnapshotView? {
        val f = fileFor(state)
        if (!f.isFile || f.length() > MAX_BYTES) return null
        return runCatching { decode(f.readBytes()) }.getOrNull()
    }

    /** Copies [from]'s cache to [to]'s (or clears [to]'s when [from] has none). */
    fun copy(from: File, to: File) {
        runCatching {
            val src = fileFor(from)
            if (src.isFile) src.copyTo(fileFor(to), overwrite = true) else fileFor(to).delete()
        }
    }

    fun encode(v: SnapshotView): ByteArray {
        val bytes = Buffer()
        bytes.let { o ->
            o.writeInt(MAGIC)
            o.writeInt(VERSION)
            o.writeModifiedUtf8(v.game!!.name)
            o.writeModifiedUtf8(v.textCode)
            o.writeChar(v.textLanguage.code)
            o.writeLong(v.frameCounter)
            o.writeModifiedUtf8(v.facingDirection)
            o.writeInt(v.x); o.writeInt(v.y)
            o.writeInt(v.mapGroup); o.writeInt(v.mapNum)
            o.writeInt(v.regionMapSectionId)
            o.writeInt(v.mapWidth); o.writeInt(v.mapHeight); o.writeInt(v.mapType)
            o.writeInt(v.playerGender)
            o.writeBoolean(v.money != null); o.writeLong(v.money ?: 0)
            v.location.let {
                o.writeModifiedUtf8(it.mapSecName); o.nullableUTF(it.regionMapAsset)
                o.writeInt(it.highlightX); o.writeInt(it.highlightY); o.writeInt(it.highlightW); o.writeInt(it.highlightH)
            }
            o.writeInt(v.party.size)
            v.party.forEach { o.mon(it) }
            o.writeInt(v.items.size)
            v.items.forEach {
                o.writeInt(it.itemId); o.writeModifiedUtf8(it.name); o.writeInt(it.quantity); o.nullableUTF(it.iconAsset)
                o.writeInt(it.pocket); o.writeModifiedUtf8(it.description)
            }
            val tabs = dataTabs(v)
            o.writeInt(tabs.size)
            tabs.forEach { o.writeModifiedUtf8(it) }
        }
        return bytes.readByteArray()
    }

    fun decode(bytes: ByteArray): SnapshotView? = Buffer().write(bytes).let { i ->
        if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
        val game = GameKind.valueOf(i.readModifiedUtf8())
        val textCode = i.readModifiedUtf8()
        val textLanguage = i.readChar()
        val frameCounter = i.readLong()
        val facing = i.readModifiedUtf8()
        val x = i.readInt(); val y = i.readInt()
        val mapGroup = i.readInt(); val mapNum = i.readInt()
        val mapSec = i.readInt()
        val mapWidth = i.readInt(); val mapHeight = i.readInt(); val mapType = i.readInt()
        val gender = i.readInt()
        val money = i.readBoolean().let { has -> i.readLong().takeIf { has } }
        val location = LocationView(i.readModifiedUtf8(), i.nullableUTF(), i.readInt(), i.readInt(), i.readInt(), i.readInt())
        val party = List(i.count(PARTY_SIZE)) { i.mon() }
        val items = List(i.count(4096)) {
            ItemView(i.readInt(), i.readModifiedUtf8(), i.readInt(), i.nullableUTF(), i.readInt(), i.readModifiedUtf8())
        }
        val tabs = List(i.count(16)) { i.readModifiedUtf8() }.toSet()
        SnapshotView(
            connected = true, game = game, textCode = textCode, textLanguage = textLanguage, frameCounter = frameCounter, facingDirection = facing,
            x = x, y = y, mapGroup = mapGroup, mapNum = mapNum, regionMapSectionId = mapSec,
            mapWidth = mapWidth, mapHeight = mapHeight, mapType = mapType, playerGender = gender, money = money,
            location = location, party = party, items = items, cachedTabs = tabs,
        )
    }

    private fun BufferedSink.nullableUTF(s: String?) {
        writeBoolean(s != null)
        if (s != null) writeModifiedUtf8(s)
    }

    private fun BufferedSource.nullableUTF(): String? = if (readBoolean()) readModifiedUtf8() else null

    private fun BufferedSource.count(max: Int): Int = readInt().also { require(it in 0..max) }

    private fun BufferedSink.ints(l: List<Int>) { writeInt(l.size); l.forEach { writeInt(it) } }
    private fun BufferedSource.ints(): List<Int> = List(count(16)) { readInt() }

    private fun BufferedSink.matchups(l: List<TypeMatchup>) {
        writeInt(l.size)
        l.forEach { writeModifiedUtf8(it.type); writeModifiedUtf8(it.label); writeInt(it.pct) }
    }
    private fun BufferedSource.matchups(): List<TypeMatchup> = List(count(64)) { TypeMatchup(readModifiedUtf8(), readModifiedUtf8(), readInt()) }

    private fun BufferedSink.mon(m: MonView) {
        writeInt(m.species); writeModifiedUtf8(m.name); writeInt(m.level); writeInt(m.hp); writeInt(m.maxHp)
        writeModifiedUtf8(m.status)
        writeInt(m.types.size); m.types.forEach { writeModifiedUtf8(it) }
        nullableUTF(m.iconAsset)
        writeInt(m.genderSymbol); writeBoolean(m.isEgg)
        writeInt(m.moves.size)
        m.moves.forEach { writeModifiedUtf8(it.name); writeModifiedUtf8(it.type); writeInt(it.pp); writeInt(it.power); writeInt(it.id) }
        matchups(m.weaknesses); matchups(m.resistances); matchups(m.immunities)
        writeBoolean(m.exp != null)
        m.exp?.let { writeLong(it.total); writeLong(it.levelStart); writeLong(it.nextLevel) }
        writeLong(m.personality)
        writeBoolean(m.stats != null)
        m.stats?.let { ints(it.ivs); ints(it.evs); writeInt(it.nature); ints(it.stats); writeInt(it.abilityNum ?: -1) }
        writeInt(m.heldItemId)
        nullableUTF(m.nickname)
    }

    private fun BufferedSource.mon(): MonView {
        val species = readInt(); val name = readModifiedUtf8(); val level = readInt(); val hp = readInt(); val maxHp = readInt()
        val status = readModifiedUtf8()
        val types = List(count(4)) { readModifiedUtf8() }
        val icon = nullableUTF()
        val gender = readInt(); val egg = readBoolean()
        val moves = List(count(8)) { MoveView(readModifiedUtf8(), readModifiedUtf8(), readInt(), readInt(), id = readInt()) }
        val weak = matchups(); val resist = matchups(); val immune = matchups()
        val exp = if (readBoolean()) ExpProgress(readLong(), readLong(), readLong()) else null
        val personality = readLong()
        val stats = if (readBoolean()) MonStats(ints(), ints(), readInt(), ints(), readInt().takeIf { it >= 0 }) else null
        val held = readInt()
        val nickname = nullableUTF()
        return MonView(
            species, name, level, hp, maxHp, status, types, icon, gender, egg, moves,
            weak, resist, immune, exp, personality, stats, heldItemId = held, nickname = nickname,
        )
    }

    // DataOutputStream's encodings, so a file from before still reads.
    private fun BufferedSink.writeBoolean(b: Boolean) = writeByte(if (b) 1 else 0)
    private fun BufferedSource.readBoolean(): Boolean = readByte() != 0.toByte()
    private fun BufferedSink.writeChar(c: Int) = writeShort(c)
    private fun BufferedSource.readChar(): Char = readShort().toInt().toChar()
}
