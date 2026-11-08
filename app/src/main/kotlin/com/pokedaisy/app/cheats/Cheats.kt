package com.pokedaisy.app.cheats

import com.pokedaisy.app.companion.i18n.tk
import java.io.File

/**
 * One cheat: its [name], its code [lines] as mGBA reads them ("82025838 FFFF"),
 * the [directive] mGBA settled on for them ("GSAv1", "PARv3", ... - how an
 * encrypted GameShark / Action Replay code is decrypted; "" for CodeBreaker and
 * VBA codes, which need none) and whether it's [enabled].
 */
data class Cheat(
    val name: String,
    val lines: List<String>,
    val directive: String = "",
    val enabled: Boolean = false,
)

/** ADD CODE's TYPE: what a typed code is. [native] = mGBA's GBACheatType;
 * [label] stays English (the selector matches by it). */
enum class CheatType(val label: String, val native: Int) {
    /** mGBA works it out from the code itself. */
    AUTO(tk("AUTO"), 0),
    /** GameShark, and Action Replay v1 / v2 (the same encryption). */
    GAMESHARK(tk("GAMESHARK"), 2),
    /** Action Replay v3. */
    ACTION_REPLAY(tk("ACTION REPLAY"), 3),
    CODEBREAKER(tk("CODEBREAKER"), 1),
}

/**
 * Cheat files: mGBA's own `.cheats` (also what [CheatStore] keeps) and
 * RetroArch's libretro `.cht`. Parsed here rather than by mGBA's parsers, which
 * stop at a Windows line ending and drop lines they can't read without a word;
 * every code line still goes through mGBA's own code parser ([CheatCheck]).
 */
object CheatFiles {
    private val LIBRETRO_COUNT = Regex("""^cheats\s*=""", RegexOption.IGNORE_CASE)
    private val LIBRETRO_KEY = Regex("""^cheat(\d+)_(\w+)\s*=\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val HEX = Regex("^[0-9A-Fa-f]+$")

    /** A file's cheats, whichever format it's in; a list of bare codes is one cheat named [fallbackName]. */
    fun parse(text: String, fallbackName: String): List<Cheat> {
        val lines = text.lines()
        return if (lines.any { LIBRETRO_COUNT.containsMatchIn(it.trim()) }) parseLibretro(lines) else parseMgba(lines, fallbackName)
    }

    /**
     * mGBA's `.cheats`, as mCheatParseFile reads it: `# NAME` starts a cheat, the
     * code lines follow; `!disabled` before it turns it off, other `!` lines are
     * directives that hold until `!reset` (the last GameShark / Action Replay one wins).
     */
    fun parseMgba(lines: List<String>, fallbackName: String): List<Cheat> {
        val out = mutableListOf<Cheat>()
        var name: String? = null
        var code = mutableListOf<String>()
        var enabled = true
        var nextDisabled = false
        var directive = ""
        var setDirective = ""
        var open = false
        fun close() {
            if (open && code.isNotEmpty()) out += Cheat(name?.ifBlank { null } ?: fallbackName, code, setDirective, enabled)
            code = mutableListOf()
        }
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#") -> {
                    close()
                    name = line.drop(1).trim()
                    enabled = !nextDisabled
                    nextDisabled = false
                    setDirective = directive
                    open = true
                }
                line.startsWith("!") -> when (val d = line.drop(1).trim()) {
                    "disabled" -> nextDisabled = true
                    "reset" -> directive = ""
                    in DIRECTIVES -> directive = d
                }
                else -> {
                    if (!open) {
                        // Codes before any name: one unnamed cheat, as mGBA does.
                        name = null
                        enabled = !nextDisabled
                        nextDisabled = false
                        setDirective = directive
                        open = true
                    }
                    code += CheatCodes.normalize(line)
                }
            }
        }
        close()
        return out
    }

    /**
     * RetroArch's `.cht`: `cheats = N`, then `cheatI_desc` / `cheatI_code` /
     * `cheatI_enable` (other keys, like the newer `cheatI_address`, are RetroArch's
     * own). A code's parts are joined by `+`, the way mGBA's libretro core splits them.
     */
    fun parseLibretro(lines: List<String>): List<Cheat> {
        val desc = sortedMapOf<Int, String>()
        val code = sortedMapOf<Int, String>()
        val on = mutableMapOf<Int, Boolean>()
        for (raw in lines) {
            val m = LIBRETRO_KEY.matchEntire(raw.trim()) ?: continue
            val i = m.groupValues[1].toInt()
            val value = unquote(m.groupValues[3])
            when (m.groupValues[2].lowercase()) {
                "desc" -> desc[i] = value
                "code" -> code[i] = value
                "enable" -> on[i] = value.equals("true", ignoreCase = true)
            }
        }
        return code.mapNotNull { (i, c) ->
            val parts = CheatCodes.normalize(c)
            if (parts.isEmpty()) null
            else Cheat(desc[i]?.ifBlank { null } ?: "CHEAT ${i + 1}", parts, "", on[i] ?: false)
        }
    }

    /** [cheats] as mGBA's `.cheats`, each standing alone (`!reset` first), so mGBA reads it back the same. */
    fun write(cheats: List<Cheat>): String = buildString {
        for (c in cheats) {
            append("!reset\n")
            if (!c.enabled) append("!disabled\n")
            if (c.directive.isNotEmpty()) append('!').append(c.directive).append('\n')
            append("# ").append(c.name.replace('\n', ' ').replace('\r', ' ').trim()).append('\n')
            c.lines.forEach { append(it).append('\n') }
        }
    }

    /** What the core gets: the enabled cheats only, so with none on the ROM is the file again. */
    fun coreText(cheats: List<Cheat>): String = write(cheats.filter { it.enabled })

    private fun unquote(v: String): String {
        val t = v.trim()
        return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1) else t
    }

    internal fun isHex(s: String) = HEX.matches(s)

    /** The directives mGBA's GBA cheat sets understand (GBACheatParseDirectives). */
    val DIRECTIVES = setOf("GSAv1", "GSAv1 raw", "PARv3", "PARv3 raw")
}

/** Typed or pasted code into mGBA's one-code-a-line form. */
object CheatCodes {
    /**
     * Splits [raw] into code lines the way mGBA's libretro core does: spaces and `+`
     * separate the parts, then an 8-digit part takes the 4- or 8-digit part after it
     * ("82025838 FFFF", "XXXXXXXX YYYYYYYY"); a 12 / 16 digit run is cut the same
     * way; VBA's "ADDRESS:VALUE" stays as it is. A line with anything else in it is
     * kept whole, for mGBA to turn down ([CheatCheck] reports it).
     */
    fun normalize(raw: String): List<String> = raw.lines().flatMap { line ->
        val tokens = line.split(Regex("""[\s+]+""")).filter { it.isNotEmpty() }
        if (tokens.all { ':' in it || CheatFiles.isHex(it) }) pair(tokens)
        else listOf(line.trim())
    }

    private fun pair(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            when {
                ':' in t -> { out += t.uppercase(); i++ }
                t.length == 12 || t.length == 16 -> { out += "${t.take(8)} ${t.drop(8)}".uppercase(); i++ }
                t.length == 8 && next != null && CheatFiles.isHex(next) && (next.length == 4 || next.length == 8) -> {
                    out += "$t $next".uppercase(); i += 2
                }
                else -> { out += t.uppercase(); i++ }
            }
        }
        return out
    }
}

/**
 * Checks code lines with mGBA's own parser ([CheatsNative], no core needed).
 * [Result.cheat] holds the lines mGBA read, with the directive it settled on;
 * [Result.bad] the ones it couldn't. Null [Result.cheat] = none were readable.
 */
object CheatCheck {
    class Result(val cheat: Cheat?, val bad: List<String>)

    private val DS_LINE = Regex("^[0-9A-F]{8} [0-9A-F]{8}$")

    /** [check] for a DS game ([ds]): Action Replay DS codes, two 8-digit words a line ([DsCheats]), checked here. */
    fun check(cheat: Cheat, type: CheatType = CheatType.AUTO, ds: Boolean): Result =
        if (!ds) check(cheat, type) else checkDs(cheat)

    /** An Action Replay DS code: every line two 8-digit hex words (melonDS's AREngine runs them). No directive. */
    fun checkDs(cheat: Cheat): Result {
        if (cheat.lines.isEmpty()) return Result(null, emptyList())
        val bad = cheat.lines.filterNot { DS_LINE.matches(it) }
        return Result(if (bad.isEmpty()) cheat.copy(directive = "") else null, bad)
    }

    fun check(cheat: Cheat, type: CheatType = CheatType.AUTO): Result {
        if (cheat.lines.isEmpty()) return Result(null, emptyList())
        val answer = CheatsNative.check(cheat.lines.joinToString("\n"), type.native, cheat.directive)
            ?: return Result(null, cheat.lines)
        val directive = answer.substringBefore('\n')
        val flags = answer.substringAfter('\n', "")
        val good = cheat.lines.filterIndexed { i, _ -> flags.getOrNull(i) == '1' }
        val bad = cheat.lines.filterIndexed { i, _ -> flags.getOrNull(i) != '1' }
        // A good line after a bad one may have been read in the wrong context
        // (multi-line codes): only trust a cheat whose every line was read.
        val ok = good.isNotEmpty() && bad.isEmpty()
        return Result(if (ok) cheat.copy(lines = good, directive = directive) else null, bad)
    }
}

/**
 * One ROM's cheats, kept as `filesDir/cheats/<ROM CRC32>.cheats` in mGBA's own
 * format (enabled flags included), keyed by CRC like the other per-ROM caches so a
 * renamed or zipped ROM keeps its cheats. Not a save: never backed up, never in the
 * saves folder. [Prefs.cheatsEnabled][com.pokedaisy.app.Prefs.cheatsEnabled] is the
 * master switch over all of them.
 */
class CheatStore(val file: File) {
    fun load(): List<Cheat> =
        runCatching { if (file.isFile) CheatFiles.parseMgba(file.readLines(), "CHEAT") else emptyList() }.getOrDefault(emptyList())

    fun save(cheats: List<Cheat>) {
        file.parentFile?.mkdirs()
        if (cheats.isEmpty()) { file.delete(); return }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(CheatFiles.write(cheats))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** What imported: [added] cheats (all OFF), [badLines] code lines mGBA couldn't
     * read across [badCheats] cheats left out, [duplicates] already in the list. */
    class Import(val added: Int, val badCheats: Int, val badLines: Int, val duplicates: Int)

    /** Adds [text]'s cheats (a `.cht` / `.cheats` file), checked line by line ([ds]: as Action Replay DS codes), after the
     * ones here. */
    fun import(text: String, fallbackName: String, ds: Boolean = false): Import {
        val have = load()
        var badCheats = 0
        var badLines = 0
        var duplicates = 0
        val added = mutableListOf<Cheat>()
        for (c in CheatFiles.parse(text, fallbackName)) {
            val r = CheatCheck.check(c, ds = ds)
            val ok = r.cheat
            if (ok == null) { badCheats++; badLines += r.bad.size; continue }
            if ((have + added).any { it.name == ok.name && it.lines == ok.lines }) { duplicates++; continue }
            // Imported cheats start off: the player picks which to turn on.
            added += ok.copy(enabled = false)
        }
        if (added.isNotEmpty()) save(have + added)
        return Import(added.size, badCheats, badLines, duplicates)
    }

    companion object {
        fun dir(filesDir: File) = File(filesDir, "cheats")
        fun forCrc(filesDir: File, crc: String) = CheatStore(File(dir(filesDir), "$crc.cheats"))
    }
}

/**
 * Action Replay DS codes for melonDS's own engine (AREngine, run on the ARM7's VBlank): each enabled cheat's lines as
 * 32-bit words, two a line, and how many words each cheat has. Kept in the same `.cheats` file as a GBA game's, with no
 * directive.
 */
object DsCheats {
    class Words(val words: IntArray, val lengths: IntArray)

    fun words(cheats: List<Cheat>): Words {
        val words = mutableListOf<Int>()
        val lengths = mutableListOf<Int>()
        for (c in cheats) {
            if (!c.enabled) continue
            val w = c.lines.flatMap { line -> line.split(' ').filter { it.isNotEmpty() } }
                .mapNotNull { it.toLongOrNull(16)?.takeIf { v -> v <= 0xFFFFFFFFL }?.toInt() }
            // A code is whole lines (an even count); anything else never reached the store (CheatCheck.checkDs).
            if (w.isEmpty() || w.size % 2 != 0) continue
            words += w
            lengths += w.size
        }
        return Words(words.toIntArray(), lengths.toIntArray())
    }

    /** [words] from the engine's text (CheatFiles.coreText: the enabled cheats as `.cheats`). */
    fun fromText(text: String): Words = words(CheatFiles.parseMgba(text.lines(), "CHEAT"))
}
