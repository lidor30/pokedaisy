package com.pokedaisy.app.companion.data

/**
 * GAMEPLAY's ROM options on the Game Boy games (2026-11-18): INSTANT TEXT, REUSABLE TMS, FIELD POISON, EXP SHARE and LOW HP BEEP, each one small edit of
 * the cart's own code, found by its shape (pret's pokered / pokeyellow / pokegold / pokecrystal), every one unique in the cart
 * or none. Patched like the GBA's (`pk_cheats_patch`): only where the cart holds the shape, the cart's bytes kept for the
 * Poller's hash; addresses are [TAG] + the cart offset (bank * 0x4000 + the address in its bank).
 *
 * - INSTANT TEXT: PrintLetterDelay (its two flag checks first, `ld a, [nn]; bit n, a; ret nz` twice) returns at once: a `ret`
 *   on its first byte. Every letter goes down without a frame's wait; the A / B press still closes a page.
 * - REUSABLE TMS, Gen 1: ItemUseTMHM's end, `ld a, [wCurItem]; call IsItemHM; ret c; jp RemoveUsedItem`, then BootedUpTMText:
 *   the `ret c` made a `ret`. Gen 2: TeachTMHM's `ld a, [wCurItem]; call IsHM; ret c; ld c, ..; callfar ChangeHappiness;
 *   call ConsumeTM; jr`: the `call ConsumeTM` three `nop`s (the happiness the move brings stays).
 * - FIELD POISON off: Gen 1's ApplyOutOfBattlePoisonDamage skips its damage (the day care's EXP and Pikachu's mood still run),
 *   Gen 2's DoPoisonStep takes its "no party" way out.
 * - EXP SHARE: Gen 1's DivideExpDataByNumMonsGainingExp counts wPartyGainExpFlags' bits, Gen 2's GiveExperiencePoints
 *   .EvenlyDivideExpAmongParticipants wBattleParticipantsNotFainted's (`ld a, [nn]; ld b, a; ... srl b; adc d; ... cp 2; ret c`),
 *   and divides the win by them; each Pokémon with its bit set (and HP) gets its share. The byte's address comes from that load
 *   ([Found.exp]: SPLIT sets every bit each frame, like Gen 3's gSentPokesToOpponent); FULL makes the `cp 2` a `ret`, no division.
 * - LOW HP BEEP off: the HUD's low-health check sets the alarm's bit (`ld hl, wLowHealthAlarm; set 7, [hl]`), Gen 1's
 *   DrawPlayerHUDAndHPBar.setLowHealthAlarm, Gen 2's CheckDanger.danger: the `set` two `nop`s. The music then never hears it.
 * - RUNNING SHOES: holding B while walking moves at the bike's speed ([run]). Red / Blue: .moveAhead2's bike check
 *   (`ld a, [wWalkBikeSurfState]; dec a; jr nz, .normalPlayerSpriteAdvancement`, before the ledge check and
 *   `call DoBikeSpeedup`) jumps to a trampoline; Yellow: DoBikeSpeedup's own first check (`...; dec a; ret nz`); Gold /
 *   Silver / Crystal: DoPlayerMovement's .BikeCheck (`ld a, [wPlayerState]; cp PLAYER_BIKE; ret z; cp PLAYER_SKATE; ret`).
 *   Each trampoline keeps the game's own answer for the bike (and Gen 2's skates), says "walking" for any other state
 *   (surfing above all), and says "bike" for walking only while the joypad byte (Gen 1's hJoyHeld from DoBikeSpeedup's
 *   Cycling Road check, Gen 2's hJoyDown from .GetDPad) has B. It goes at the very end of the hook's bank (bank 0 for
 *   Gen 1: its caller is home code), in the cart's padding, which its write checks is still there.
 *
 * Found in Red, Blue, Yellow (every language), Gold, Silver, Crystal (rev 0, rev 1, Australia) and the hacks on that code
 * (Brown, Yellow Legacy, Crystal Clear, Crystal Legacy); PureRed / PureBlue's text check is three places, so INSTANT TEXT
 * isn't offered there. Played headless (mgba_dump `rompatch`).
 */
object GbGamePatches {
    const val TAG = 0x10000000L

    class Found(val tms: List<ReusableTms.Site>, val text: InstantText.Patch?, val beep: InstantText.Patch?, val poison: InstantText.Patch? = null,
                val exp: ExpShare.Found? = null, val run: InstantText.Patch? = null)

    private const val ANY = -1
    private val TEXT = sig("fa????cb??c0fa????cb??c8")
    private val TM1 = sig("fa????cd????d8c3????17??????50")
    private val TM2 = sig("fa????cd????d80e??21????3e??cfcd????18")
    private val BEEP1 = sig("21????cb7e3600c8afea????c921????cbfec9")
    private val BEEP2 = sig("21????cbbe18??21????cbfec9")
    private val POISON1 = sig("fa????e603c2????ea????21????")
    private val POISON2 = sig("fa????a728??af0e??21????")
    private val EXP1 = sig("fa????47af0e081600afcb388a570d20f8fe02d8")
    private val EXP2 = sig("fa????470e061600afcb388a570d20f8fe02d8")
    // RUNNING SHOES. Red / Blue's .moveAhead2 check, and DoBikeSpeedup for hJoyHeld (`cp ROUTE_17; jr nz; ldh a, [hJoyHeld]`).
    private val RUN_RED = sig("fa????3d200afa????cb??2003cd????")
    private val RUN_RED_SPEEDUP = sig("fa????a7c0fa????fe1c2005f0??e670c0c3????")
    // Yellow's DoBikeSpeedup, its own check first and hJoyHeld in its Cycling Road check.
    private val RUN_YELLOW = sig("fa????3dc0fa????cb??c0fa????a7c0fa????fe1c2005f0??e670c0cd????c9")
    // Gen 2's .BikeCheck and .GetDPad (`ldh a, [hJoyDown]; ld [wCurInput], a`, then the downhill bike's own code).
    private val RUN_GS = sig("fa????fe01c8fe02c9")
    private val RUN_GS_DPAD = sig("f0??ea????21????cb??c84fe6f0c079f680ea????c9")

    /** What [rom] (a whole Game Boy cart) has; null for anything that isn't one. */
    fun find(rom: ByteArray): Found? {
        if (rom.size < 0x8000 || rom.size > 0x800000) return null
        fun one(s: IntArray): Int? = hits(rom, s).singleOrNull()
        // Each write checks the cart's bytes from its own address: the whole shape for the text, the `set 7, [hl]; ret` for the beep.
        val text = one(TEXT)?.let { at ->
            InstantText.Patch(listOf(InstantText.Write(TAG + at, rom.copyOfRange(at, at + TEXT.size), byteArrayOf(0xC9.toByte()))))
        }
        val tms = (one(TM1)?.let { at -> Triple(at, TM1, 6 to byteArrayOf(0xC9.toByte())) }
            ?: one(TM2)?.let { at -> Triple(at, TM2, 15 to byteArrayOf(0, 0, 0)) })
            ?.let { (at, s, p) -> listOf(ReusableTms.Site(TAG + at, rom.copyOfRange(at, at + s.size), TAG + at + p.first, p.second)) }
            .orEmpty()
        val beep = (one(BEEP1)?.let { it + 16 } ?: one(BEEP2)?.let { it + 10 })?.let { at ->
            InstantText.Patch(listOf(InstantText.Write(TAG + at, rom.copyOfRange(at, at + 3), byteArrayOf(0, 0))))
        }
        // FIELD POISON off: Gen 1's `jp nz, .skipPoisonEffectAndSound` (every fourth step) made a `jp` - the day care's EXP and
        // Pikachu's mood before it still run; Gen 2's DoPoisonStep's `jr z, .no_faint` (no party) made a `jr`.
        val poison = one(POISON1)?.let { at -> Triple(at, POISON1, 5 to 0xC3) } ?: one(POISON2)?.let { at -> Triple(at, POISON2, 4 to 0x18) }
        val poisonPatch = poison?.let { (at, s, p) ->
            InstantText.Patch(listOf(InstantText.Write(TAG + at + p.first, rom.copyOfRange(at + p.first, at + s.size), byteArrayOf(p.second.toByte()))))
        }
        // EXP SHARE: the participants byte's load and the `cp 2; ret c` that skips the division for one Pokémon.
        val exp = (one(EXP1)?.let { it to EXP1 } ?: one(EXP2)?.let { it to EXP2 })?.let { (at, s) ->
            val flags = (rom[at + 1].toInt() and 0xFF) or ((rom[at + 2].toInt() and 0xFF) shl 8)
            val cp = at + s.size - 3
            // C000-CFFF is plain WRAM; D000-DFFF read in WRAM bank 1 (a Color cart may page it), as the readers do.
            val addr = if (flags >= 0xD000) 0x10000L or flags.toLong() else flags.toLong()
            // Gen 1 (EXP1: DivideExpDataByNumMonsGainingExp) keeps wIsInBattle right before wPartyGainExpFlags in every
            // build of pokered's WRAM (GbExpShareGateTest checks it against each config); Gen 2's battle byte isn't next to
            // its participants', so the host hands it to [ExpShare.mark] from the game's config.
            val battle = if (s === EXP1) addr - 1 else 0L
            if (flags !in 0xC000..0xDFFF) null
            else ExpShare.Found(addr, listOf(InstantText.Write(TAG + cp, rom.copyOfRange(cp, cp + 2), byteArrayOf(0xC9.toByte(), 0))), width = 1, battle = battle)
        }
        return Found(tms, text, beep, poisonPatch, exp, runPatch(rom, ::one))
    }

    /**
     * RUNNING SHOES ([Found.run]): the hook and its trampoline, or null. The trampoline sits at the end of the hook's bank,
     * in a run of the cart's padding (one byte, 00 or FF, to the bank's end) longer than itself: its write expects that
     * padding, so a cart that uses the space is refused there.
     */
    private fun runPatch(rom: ByteArray, one: (IntArray) -> Int?): InstantText.Patch? {
        fun u8(i: Int) = rom[i].toInt() and 0xFF
        fun place(bank: Int, size: Int): Int? {
            val end = (bank + 1) * 0x4000
            if (end > rom.size) return null
            val fill = rom[end - 1]
            if (u8(end - 1) != 0 && u8(end - 1) != 0xFF) return null
            var start = end
            while (start > bank * 0x4000 && rom[start - 1] == fill) start--
            return if (end - start > size) end - size else null
        }
        // A cart offset's CPU address while its bank is mapped (bank 0 at 0000-3FFF, the others at 4000-7FFF).
        fun cpu(off: Int) = if (off < 0x4000) off else 0x4000 + off % 0x4000
        fun jp(to: Int) = byteArrayOf(0xC3.toByte(), (to and 0xFF).toByte(), (to shr 8).toByte())
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        // A Game Boy cart's patch is written 4 bytes at most at a time (pk_cheats_patch), each piece checking its own bytes:
        // the trampoline first, then the jump to it, so the jump never lands on a half-written trampoline.
        fun pieces(off: Int, value: ByteArray) = (value.indices step 4).map { i ->
            val n = minOf(4, value.size - i)
            InstantText.Write(TAG + off + i, rom.copyOfRange(off + i, off + i + n), value.copyOfRange(i, i + n))
        }
        fun patch(hook: Int, hookSize: Int, trampoline: ByteArray, at: Int) = InstantText.Patch(
            pieces(at, trampoline) + pieces(hook, jp(cpu(at)) + ByteArray(hookSize - 3)))
        one(RUN_RED)?.let { hook ->
            if (hook >= 0x4000) return null
            val joy = one(RUN_RED_SPEEDUP)?.let { u8(it + 13) } ?: return null
            val at = place(0, 21) ?: return null
            // ld a, [wWalkBikeSurfState]; dec a; jr z, .bike; inc a; jr nz, .normal; ldh a, [hJoyHeld]; and B; jr z, .normal
            // .bike: jp the ledge check; .normal: jp .normalPlayerSpriteAdvancement
            val t = b(0xFA, u8(hook + 1), u8(hook + 2), 0x3D, 0x28, 9, 0x3C, 0x20, 9, 0xF0, joy, 0xE6, 0x02, 0x28, 3) +
                jp(cpu(hook + 6)) + jp(cpu(hook + 16))
            return patch(hook, 6, t, at)
        }
        one(RUN_YELLOW)?.let { hook ->
            if (hook >= 0x4000) return null
            val at = place(0, 16) ?: return null
            // ld a, [wWalkBikeSurfState]; dec a; jr z, .bike; inc a; ret nz; ldh a, [hJoyHeld]; and B; ret z
            // .bike: jp DoBikeSpeedup's ledge check
            val t = b(0xFA, u8(hook + 1), u8(hook + 2), 0x3D, 0x28, 7, 0x3C, 0xC0, 0xF0, u8(hook + 24), 0xE6, 0x02, 0xC8) +
                jp(cpu(hook + 5))
            return patch(hook, 5, t, at)
        }
        one(RUN_GS)?.let { hook ->
            val bank = hook / 0x4000
            val joy = hits(rom, RUN_GS_DPAD).singleOrNull { it / 0x4000 == bank }?.let { u8(it + 1) } ?: return null
            // Gen 2 carts keep 64 bytes or more of padding there; a hack with less is left alone (Prism: 27).
            val at = place(bank, 18)?.takeIf { (bank + 1) * 0x4000 - it >= 18 && run(rom, bank) >= 64 } ?: return null
            // ld a, [wPlayerState]; cp BIKE; ret z; cp SKATE; ret z; and a; ret nz; ldh a, [hJoyDown]; and B; xor B; ret
            val t = b(0xFA, u8(hook + 1), u8(hook + 2), 0xFE, 0x01, 0xC8, 0xFE, 0x02, 0xC8, 0xA7, 0xC0, 0xF0, joy, 0xE6, 0x02, 0xEE, 0x02, 0xC9)
            return patch(hook, 3, t, at)
        }
        return null
    }

    /** The padding run at the end of [bank] (one byte, 00 or FF): its length. */
    private fun run(rom: ByteArray, bank: Int): Int {
        val end = (bank + 1) * 0x4000
        if (end > rom.size) return 0
        val fill = rom[end - 1]
        var start = end
        while (start > bank * 0x4000 && rom[start - 1] == fill) start--
        return end - start
    }

    /** A Game Boy cart file: its header's logo check (0x104) and size. */
    fun find(path: okio.Path): Found? = runCatching {
        val bytes = okio.FileSystem.SYSTEM.read(path) { readByteArray() }
        if (!isGameBoyRom(bytes)) null else find(bytes)
    }.getOrNull()

    private fun isGameBoyRom(b: ByteArray) = b.size >= 0x150 && (b[0x104].toInt() and 0xFF) == 0xCE && (b[0x105].toInt() and 0xFF) == 0xED

    private fun hits(rom: ByteArray, s: IntArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + s.size <= rom.size) {
            var ok = true
            for (k in s.indices) if (s[k] != ANY && (rom[i + k].toInt() and 0xFF) != s[k]) { ok = false; break }
            if (ok) out += i
            i++
        }
        return out
    }

    private fun sig(hex: String) = IntArray(hex.length / 2) { i ->
        val p = hex.substring(i * 2, i * 2 + 2)
        if (p == "??") ANY else p.toInt(16)
    }
}
