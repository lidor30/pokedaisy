package com.pokedaisy.app

import android.util.Log
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.isGen1
import com.pokedaisy.app.companion.data.isGen2

/**
 * PokeDaisy — Running Shoes for Generation 1 & Generation 2.
 *
 * Implements holding B to run on overworld at bicycle speed (2x) via in-memory
 * patching of the emulated ROM buffer (pkRomWrite).
 * The user's ROM file on disk is never modified.
 */
object RunningShoes {
    private const val TAG = "RunningShoes"

    // --- Profil Pokémon Yellow (Retail & Gen II Graphics Patched V1.1) ---
    // 1. Bank 0 player bike-speed hook:
    // In Bank 0, DoBikeSpeedup starts at 0x049D:
    // Original 5 bytes: FA FF D6 3D C0 (ld a, [wWalkBikeSurfState]; dec a; ret nz)
    private const val YELLOW_HOOK_ADDR = 0x049DL
    private val YELLOW_ORIGINAL = byteArrayOf(
        0xFA.toByte(), 0xFF.toByte(), 0xD6.toByte(), 0x3D.toByte(), 0xC0.toByte()
    )
    private val YELLOW_HOOK = byteArrayOf(
        0xC3.toByte(), 0xEF.toByte(), 0x3F.toByte(), // jp $3FEF
        0x00.toByte(), 0x00.toByte()                  // nop, nop
    )

    // Trampoline at 0x3FEF (Bank 0 free space 0x3FEF-0x3FFF = 17 bytes)
    private const val YELLOW_TRAMPOLINE_ADDR = 0x3FEFL
    // 2x Speed (Bicycle Speed - 14 bytes)
    private val YELLOW_TRAMPOLINE_2X = byteArrayOf(
        0xF0.toByte(), 0xB4.toByte(),             // ldh a, [$B4] (hJoyHeld)
        0xE6.toByte(), 0x02.toByte(),             // and 2 (B_BUTTON)
        0x20.toByte(), 0x05.toByte(),             // jr nz, +5 (to jp $04A2)
        0xFA.toByte(), 0xFF.toByte(), 0xD6.toByte(), // ld a, [wWalkBikeSurfState = $D6FF]
        0x3D.toByte(),                           // dec a
        0xC0.toByte(),                           // ret nz
        0xC3.toByte(), 0xA2.toByte(), 0x04.toByte()  // jp $04A2 (ledge check -> DoBikeSpeedup)
    )

    // 2. Bank 0x3F Pikachu follow synchronization hook (Yellow Legacy solution):
    // In Bank 0x3F, PikachuMovementState_ProcessCommand checks whether to use FastPikachuFollow.
    // At linear offset 0xFC7DA (CPU address 0x47DA in Bank 3F):
    // Original 9 bytes: CD 17 4D DA DF 49 C3 B4 49
    // (call $4D17; jp c, $49DF; jp $49B4)
    private const val YELLOW_PIKA_HOOK_ADDR = 0xFC7DAL
    private val YELLOW_PIKA_ORIGINAL = byteArrayOf(
        0xCD.toByte(), 0x17.toByte(), 0x4D.toByte(),
        0xDA.toByte(), 0xDF.toByte(), 0x49.toByte(),
        0xC3.toByte(), 0xB4.toByte(), 0x49.toByte()
    )
    private val YELLOW_PIKA_HOOK = byteArrayOf(
        0xC3.toByte(), 0x70.toByte(), 0x72.toByte(), // jp $7270 (Bank 3F free space)
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // nop, nop, nop
        0x00.toByte(), 0x00.toByte(), 0x00.toByte()  // nop, nop, nop
    )

    // Trampoline at linear 0xFF270 (CPU $7270 in Bank 3F free space):
    // Checks B button: if held, immediately triggers FastPikachuFollow ($49DF) from step 1!
    // If B not held, executes the original buffer check. 16 bytes.
    private const val YELLOW_PIKA_TRAMPOLINE_ADDR = 0xFF270L
    private val YELLOW_PIKA_TRAMPOLINE = byteArrayOf(
        0xF0.toByte(), 0xB4.toByte(),             // ldh a, [$B4] (hJoyHeld)
        0xE6.toByte(), 0x02.toByte(),             // and 2 (B_BUTTON)
        0xC2.toByte(), 0xDF.toByte(), 0x49.toByte(), // jp nz, $49DF (FastPikachuFollow)
        0xCD.toByte(), 0x17.toByte(), 0x4D.toByte(), // call $4D17 (AreThereAtLeastTwoSteps)
        0xDA.toByte(), 0xDF.toByte(), 0x49.toByte(), // jp c, $49DF (FastPikachuFollow)
        0xC3.toByte(), 0xB4.toByte(), 0x49.toByte()  // jp $49B4 (NormalPikachuFollow)
    )

    // 3. Bank 1 player running animation cadence hook (Yellow Legacy / Shin Red solution):
    // In Bank 1, UpdatePlayerSprite increments tick counter and wraps at 4 ticks ($5274).
    // At linear offset 0x527B (CPU address $527B in Bank 1):
    // Original 6 bytes: 7E 3C E6 03 77 C0 (ld a, [hl]; inc a; and 3; ld [hl], a; ret nz)
    private const val YELLOW_SPRITE_HOOK_ADDR = 0x527BL
    private val YELLOW_SPRITE_ORIGINAL = byteArrayOf(
        0x7E.toByte(), 0x3C.toByte(), 0xE6.toByte(), 0x03.toByte(), 0x77.toByte(), 0xC0.toByte()
    )
    private val YELLOW_SPRITE_HOOK = byteArrayOf(
        0xC3.toByte(), 0x30.toByte(), 0x7B.toByte(), // jp $7B30 (Bank 1 free space)
        0x00.toByte(), 0x00.toByte(), 0x00.toByte()  // nop, nop, nop
    )

    // Trampoline at linear 0x7B30 (CPU $7B30 in Bank 1 free space 0x7B28-0x7FFF = 1240 bytes):
    // Checks B button: if held, wraps at 2 ticks (and 1) -> 2x footstep animation speed!
    // If B not held, wraps at 4 ticks (and 3) -> normal walking animation. 24 bytes.
    // ponytail: mirrors Yellow Legacy/ShinRed animation cadence speedup
    private const val YELLOW_SPRITE_TRAMPOLINE_ADDR = 0x7B30L
    private val YELLOW_SPRITE_TRAMPOLINE = byteArrayOf(
        0xF0.toByte(), 0xB4.toByte(),             // ldh a, [$B4] (hJoyHeld)
        0xE6.toByte(), 0x02.toByte(),             // and 2 (B_BUTTON)
        0x20.toByte(), 0x09.toByte(),             // jr nz, +9 (to .running at $7B3F)
        0x7E.toByte(),                           // ld a, [hl]
        0x3C.toByte(),                           // inc a
        0xE6.toByte(), 0x03.toByte(),             // and 3
        0x77.toByte(),                           // ld [hl], a
        0xC0.toByte(),                           // ret nz
        0xC3.toByte(), 0x81.toByte(), 0x52.toByte(), // jp $5281
        // .running ($7B3F):
        0x7E.toByte(),                           // ld a, [hl]
        0x3C.toByte(),                           // inc a
        0xE6.toByte(), 0x01.toByte(),             // and 1 (2x animation cadence)
        0x77.toByte(),                           // ld [hl], a
        0xC0.toByte(),                           // ret nz
        0xC3.toByte(), 0x81.toByte(), 0x52.toByte()  // jp $5281
    )

    // --- Profil Pokémon Gold / Silver (Retail — identical bytes in both) ---
    // DoPlayerMovement determines step speed at CPU $418D (call $43CA; jr nz, $41AE).
    // Subroutine $43CA checks if player is on bike (state==1) or slip (state==2).
    // If Z=1 -> engine executes step vector 2 (bike speed 2x: 4 px/tick, 8 frames/tile, double cadence).
    // If NZ  -> engine executes step vector 1 (walking speed 1x: 2 px/tick, 16 frames/tile, normal cadence).
    // Hook at 0x0103CA (Bank 4, CPU $43CA): FA 82 D6 -> JP $50D0
    private const val GS_HOOK_ADDR = 0x0103CAL
    private val GS_ORIGINAL = byteArrayOf(0xFA.toByte(), 0x82.toByte(), 0xD6.toByte())
    private val GS_HOOK = byteArrayOf(0xC3.toByte(), 0xD0.toByte(), 0x50.toByte()) // jp $50D0

    // Trampoline at 0x0110D0 (Bank 4, CPU $50D0 free space — 161 bytes of 0xFF).
    // 16 bytes. If bike or slip -> Z=1. If walking: reads hJoyDown ($FFAA), if B held -> Z=1, else NZ.
    // Sprite stays walking sprite because wPlayerState remains 0. Cadence is automatic 2x.
    private const val GS_TRAMPOLINE_ADDR = 0x0110D0L
    private val GS_TRAMPOLINE = byteArrayOf(
        0xFA.toByte(), 0x82.toByte(), 0xD6.toByte(), // ld a, [wPlayerState=$D682] [0-2]
        0xFE.toByte(), 0x01.toByte(),                // cp 1 (PLAYER_BIKE)        [3-4]
        0xC8.toByte(),                               // ret z (if bike, Z=1)      [5]
        0xFE.toByte(), 0x02.toByte(),                // cp 2 (PLAYER_SLIP)        [6-7]
        0xC8.toByte(),                               // ret z (if slip, Z=1)      [8]
        0xF0.toByte(), 0xAA.toByte(),                // ldh a, [$AA] (hJoyDown=$FFAA) [9-10]
        0xE6.toByte(), 0x02.toByte(),                // and 2 (B_BUTTON)          [11-12]
        0xEE.toByte(), 0x02.toByte(),                // xor 2 (B held -> Z=1)     [13-14]
        0xC9.toByte()                                // ret                       [15]
    )

    // --- Profil Pokémon Crystal ---
    // DoPlayerMovement determines step speed at CPU $418D (call $43CA; jr nz, $41AE).
    // Subroutine $43CA checks if player is on bike (state==1) or slip (state==2).
    // If Z=1 -> engine executes step vector 2 (bike speed 2x: 4 px/tick, 8 frames/tile, double cadence).
    // If NZ  -> engine executes step vector 1 (walking speed 1x: 2 px/tick, 16 frames/tile, normal cadence).
    // Hook at 0x0803CA (Bank 32, CPU $43CA): FA 5D D9 -> JP $63C8
    private const val CRYSTAL_HOOK_ADDR = 0x0803CAL
    private val CRYSTAL_ORIGINAL = byteArrayOf(0xFA.toByte(), 0x5D.toByte(), 0xD9.toByte())
    private val CRYSTAL_HOOK = byteArrayOf(0xC3.toByte(), 0xC8.toByte(), 0x63.toByte()) // jp $63C8

    // Trampoline at 0x0823C8 (Bank 32, CPU $63C8 free space — 7224 bytes of 0x00).
    // 16 bytes. If bike or slip -> Z=1. If walking: reads hJoyDown ($FFA8), if B held -> Z=1, else NZ.
    // Sprite stays walking sprite because wPlayerState remains 0. Cadence is automatic 2x.
    private const val CRYSTAL_TRAMPOLINE_ADDR = 0x0823C8L
    private val CRYSTAL_TRAMPOLINE = byteArrayOf(
        0xFA.toByte(), 0x5D.toByte(), 0xD9.toByte(), // ld a, [wPlayerState=$D95D] [0-2]
        0xFE.toByte(), 0x01.toByte(),                // cp 1 (PLAYER_BIKE)        [3-4]
        0xC8.toByte(),                               // ret z (if bike, Z=1)      [5]
        0xFE.toByte(), 0x02.toByte(),                // cp 2 (PLAYER_SLIP)        [6-7]
        0xC8.toByte(),                               // ret z (if slip, Z=1)      [8]
        0xF0.toByte(), 0xA8.toByte(),                // ldh a, [$A8] (hJoyDown=$FFA8) [9-10]
        0xE6.toByte(), 0x02.toByte(),                // and 2 (B_BUTTON)          [11-12]
        0xEE.toByte(), 0x02.toByte(),                // xor 2 (B held -> Z=1)     [13-14]
        0xC9.toByte()                                // ret                       [15]
    )

    // Bank 1 running sprite cadence hook for Crystal.
    // Hook at 0x0044CA (Bank 1, CPU $44CA): 21 0C 00 (ld hl, 12) -> jp $7500
    private const val CRYSTAL_SPRITE_HOOK_ADDR = 0x0044CAL
    private val CRYSTAL_SPRITE_ORIGINAL = byteArrayOf(0x21.toByte(), 0x0C.toByte(), 0x00.toByte())
    private val CRYSTAL_SPRITE_HOOK = byteArrayOf(0xC3.toByte(), 0x00.toByte(), 0x75.toByte()) // jp $7500

    // Trampoline at 0x007500 (Bank 1, CPU $7500 free space — 2949 bytes of 0x00). 21 bytes.
    // Restores `ld hl, 12; add hl, bc`. If B held -> `add a, 2` (2x cadence). Else -> `inc a` (1x cadence).
    // Resumes at CPU $44D0 (and $0F; ld [hl], a; rrca; rrca; and 3...).
    private const val CRYSTAL_SPRITE_TRAMPOLINE_ADDR = 0x007500L
    private val CRYSTAL_SPRITE_TRAMPOLINE = byteArrayOf(
        0x21.toByte(), 0x0C.toByte(), 0x00.toByte(), // ld hl, 12
        0x09.toByte(),                               // add hl, bc
        0xF0.toByte(), 0xA8.toByte(),                // ldh a, [$A8] (hJoyDown=$FFA8 Crystal)
        0xE6.toByte(), 0x02.toByte(),                // and 2 (B_BUTTON)
        0x20.toByte(), 0x05.toByte(),                // jr nz, +5 (.running)
        0x7E.toByte(),                               // ld a, [hl]
        0x3C.toByte(),                               // inc a (1x cadence)
        0xC3.toByte(), 0xD0.toByte(), 0x44.toByte(), // jp $44D0
        // .running:
        0x7E.toByte(),                               // ld a, [hl]
        0xC6.toByte(), 0x02.toByte(),                // add a, 2 (2x cadence!)
        0xC3.toByte(), 0xD0.toByte(), 0x44.toByte()  // jp $44D0
    )

    // Bank 1 running sprite cadence hook for Gold/Silver.
    // Hook at 0x0044C7 (Bank 1, CPU $44C7): 21 0C 00 (ld hl, 12) -> jp $7600
    private const val GS_SPRITE_HOOK_ADDR = 0x0044C7L
    private val GS_SPRITE_ORIGINAL = byteArrayOf(0x21.toByte(), 0x0C.toByte(), 0x00.toByte())
    private val GS_SPRITE_HOOK = byteArrayOf(0xC3.toByte(), 0x00.toByte(), 0x76.toByte()) // jp $7600

    // Trampoline at 0x007600 (Bank 1, CPU $7600 free space — 2738 bytes of 0x00). 21 bytes.
    // Restores `ld hl, 12; add hl, bc`. If B held -> `add a, 2` (2x cadence). Else -> `inc a` (1x cadence).
    // Resumes at CPU $44CD (and $0F; ld [hl], a; rrca; rrca; and 3...).
    private const val GS_SPRITE_TRAMPOLINE_ADDR = 0x007600L
    private val GS_SPRITE_TRAMPOLINE = byteArrayOf(
        0x21.toByte(), 0x0C.toByte(), 0x00.toByte(), // ld hl, 12
        0x09.toByte(),                               // add hl, bc
        0xF0.toByte(), 0xAA.toByte(),                // ldh a, [$AA] (hJoyDown=$FFAA Gold/Silver)
        0xE6.toByte(), 0x02.toByte(),                // and 2 (B_BUTTON)
        0x20.toByte(), 0x05.toByte(),                // jr nz, +5 (.running)
        0x7E.toByte(),                               // ld a, [hl]
        0x3C.toByte(),                               // inc a (1x cadence)
        0xC3.toByte(), 0xCD.toByte(), 0x44.toByte(), // jp $44CD
        // .running:
        0x7E.toByte(),                               // ld a, [hl]
        0xC6.toByte(), 0x02.toByte(),                // add a, 2 (2x cadence!)
        0xC3.toByte(), 0xCD.toByte(), 0x44.toByte()  // jp $44CD
    )

    // --- Profil Pokémon Red / Blue (Retail & Red Gen II Graphics Patched V2.0) ---
    // In Bank 0, .moveAhead2 bike check starts at 0x05CC:
    // Original 6 bytes: FA 00 D7 3D 20 0A (ld a, [wWalkBikeSurfState]; dec a; jr nz, +10)
    private const val RED_BLUE_HOOK_ADDR = 0x05CCL
    private val RED_BLUE_ORIGINAL = byteArrayOf(
        0xFA.toByte(), 0x00.toByte(), 0xD7.toByte(),
        0x3D.toByte(), 0x20.toByte(), 0x0A.toByte()
    )
    private val RED_BLUE_HOOK = byteArrayOf(
        0xC3.toByte(), 0xA6.toByte(), 0x3F.toByte(), // jp $3FA6
        0x00.toByte(), 0x00.toByte(), 0x00.toByte()  // nop, nop, nop
    )

    // Trampoline at 0x3FA6 (Bank 0 free space 0x3FA6-0x3FFF = 90 bytes)
    private const val RED_BLUE_TRAMPOLINE_ADDR = 0x3FA6L
    // 2x Speed (Bicycle Speed - 18 bytes)
    private val RED_BLUE_TRAMPOLINE_2X = byteArrayOf(
        0xF0.toByte(), 0xB4.toByte(),             // ldh a, [$B4] (hJoyHeld)
        0xE6.toByte(), 0x02.toByte(),             // and 2 (B_BUTTON)
        0x20.toByte(), 0x05.toByte(),             // jr nz, +5 (to jp $05D2)
        0xFA.toByte(), 0x00.toByte(), 0xD7.toByte(), // ld a, [wWalkBikeSurfState = $D700]
        0x3D.toByte(),                           // dec a
        0x20.toByte(), 0x03.toByte(),             // jr nz, +3 (to jp $05DC)
        0xC3.toByte(), 0xD2.toByte(), 0x05.toByte(), // jp $05D2 (ledge check -> DoBikeSpeedup)
        0xC3.toByte(), 0xDC.toByte(), 0x05.toByte()  // jp $05DC (AdvancePlayerSprite)
    )

    // 2. Bank 1 player running animation cadence hook (Shin Red solution):
    // In Bank 1, UpdatePlayerSprite increments tick counter and compares with 4 ($4E9F).
    // At linear offset 0x4E9F (CPU address $4E9F in Bank 1):
    // Original 4 bytes: FE 04 20 08 (cp 4; jr nz, +8)
    private const val RED_BLUE_SPRITE_HOOK_ADDR = 0x4E9FL
    private val RED_BLUE_SPRITE_ORIGINAL = byteArrayOf(
        0xFE.toByte(), 0x04.toByte(), 0x20.toByte(), 0x08.toByte()
    )
    private val RED_BLUE_SPRITE_HOOK = byteArrayOf(
        0xC3.toByte(), 0x70.toByte(), 0x7C.toByte(), // jp $7C70 (Bank 1 free space)
        0x00.toByte()                                // nop
    )

    // Trampoline at linear 0x7C70 (CPU $7C70 in Bank 1 free space 0x7C62-0x7FFF = 926 bytes):
    // Checks B button: if held, compares with 2 (advance if >= 2) -> 2x footstep animation speed!
    // If B not held, compares with 4 -> normal walking animation. 24 bytes.
    // ponytail: mirrors ShinRed animation cadence speedup
    private const val RED_BLUE_SPRITE_TRAMPOLINE_ADDR = 0x7C70L
    private val RED_BLUE_SPRITE_TRAMPOLINE = byteArrayOf(
        0xF0.toByte(), 0xB4.toByte(),             // ldh a, [$B4] (hJoyHeld)
        0xE6.toByte(), 0x02.toByte(),             // and 2 (B_BUTTON)
        0x20.toByte(), 0x09.toByte(),             // jr nz, +9 (to .running at $7C7F)
        0x7E.toByte(),                           // ld a, [hl]
        0xFE.toByte(), 0x04.toByte(),             // cp 4
        0xCA.toByte(), 0xA3.toByte(), 0x4E.toByte(), // jp z, $4EA3
        0xC3.toByte(), 0xAB.toByte(), 0x4E.toByte(), // jp $4EAB
        // .running ($7C7F):
        0x7E.toByte(),                           // ld a, [hl]
        0xFE.toByte(), 0x02.toByte(),             // cp 2
        0xD2.toByte(), 0xA3.toByte(), 0x4E.toByte(), // jp nc, $4EA3
        0xC3.toByte(), 0xAB.toByte(), 0x4E.toByte()  // jp $4EAB
    )

    /**
     * Whether Running Shoes is supported for this game.
     * Supports Gen 1 (Red, Blue, Yellow, PureRed, PureBlue, Yellow Legacy, Gen II Graphics)
     * and Gen 2 (Gold, Silver, Crystal).
     */
    fun isSupported(game: GameKind?): Boolean = game.isGen1 || game.isGen2

    // State tracking
    @Volatile private var currentlyPatchedFor: GameKind? = null

    /** Reset state tracker when a new ROM is loaded. */
    fun resetState() {
        currentlyPatchedFor = null
    }

    /**
     * Applies or restores the in-memory Running Shoes patch.
     * Emu thread or companion settings change.
     */
    fun applyPatch(game: GameKind?, on: Boolean) {
        if (!isSupported(game) || !on) {
            restore()
            return
        }

        if (currentlyPatchedFor == game) {
            return
        }

        val isYellow = game == GameKind.YELLOW || game == GameKind.YELLOW_LEGACY
        val isCrystal = game == GameKind.CRYSTAL
        when {
            isYellow -> {
                val ok1 = MgbaCore.pkRomWrite(YELLOW_TRAMPOLINE_ADDR, YELLOW_TRAMPOLINE_2X)
                val ok2 = MgbaCore.pkRomWrite(YELLOW_HOOK_ADDR, YELLOW_HOOK)
                val ok3 = MgbaCore.pkRomWrite(YELLOW_PIKA_TRAMPOLINE_ADDR, YELLOW_PIKA_TRAMPOLINE)
                val ok4 = MgbaCore.pkRomWrite(YELLOW_PIKA_HOOK_ADDR, YELLOW_PIKA_HOOK)
                val ok5 = MgbaCore.pkRomWrite(YELLOW_SPRITE_TRAMPOLINE_ADDR, YELLOW_SPRITE_TRAMPOLINE)
                val ok6 = MgbaCore.pkRomWrite(YELLOW_SPRITE_HOOK_ADDR, YELLOW_SPRITE_HOOK)
                if (ok1 && ok2 && ok3 && ok4 && ok5 && ok6) {
                    currentlyPatchedFor = game
                    Log.i(TAG, "Running shoes patch applied for Yellow (Player + Pikachu sync + Running sprite)")
                } else {
                    Log.e(TAG, "Failed to apply running shoes patch for Yellow (tramp=$ok1, hook=$ok2, pikaTramp=$ok3, pikaHook=$ok4, spTramp=$ok5, spHook=$ok6)")
                }
            }
            isCrystal -> {
                val ok1 = MgbaCore.pkRomWrite(CRYSTAL_TRAMPOLINE_ADDR, CRYSTAL_TRAMPOLINE)
                val ok2 = MgbaCore.pkRomWrite(CRYSTAL_HOOK_ADDR, CRYSTAL_HOOK)
                val ok3 = MgbaCore.pkRomWrite(CRYSTAL_SPRITE_TRAMPOLINE_ADDR, CRYSTAL_SPRITE_TRAMPOLINE)
                val ok4 = MgbaCore.pkRomWrite(CRYSTAL_SPRITE_HOOK_ADDR, CRYSTAL_SPRITE_HOOK)
                if (ok1 && ok2 && ok3 && ok4) {
                    currentlyPatchedFor = game
                    Log.i(TAG, "Running shoes patch applied for Crystal (2x speed + running sprite cadence)")
                } else {
                    Log.e(TAG, "Failed to apply running shoes patch for Crystal (tramp=$ok1, hook=$ok2, spTramp=$ok3, spHook=$ok4)")
                }
            }
            game.isGen2 -> {
                // Gold / Silver (identical addresses)
                val ok1 = MgbaCore.pkRomWrite(GS_TRAMPOLINE_ADDR, GS_TRAMPOLINE)
                val ok2 = MgbaCore.pkRomWrite(GS_HOOK_ADDR, GS_HOOK)
                val ok3 = MgbaCore.pkRomWrite(GS_SPRITE_TRAMPOLINE_ADDR, GS_SPRITE_TRAMPOLINE)
                val ok4 = MgbaCore.pkRomWrite(GS_SPRITE_HOOK_ADDR, GS_SPRITE_HOOK)
                if (ok1 && ok2 && ok3 && ok4) {
                    currentlyPatchedFor = game
                    Log.i(TAG, "Running shoes patch applied for Gold/Silver (2x speed + running sprite cadence)")
                } else {
                    Log.e(TAG, "Failed to apply running shoes patch for Gold/Silver (tramp=$ok1, hook=$ok2, spTramp=$ok3, spHook=$ok4)")
                }
            }
            else -> {
                // Red / Blue (Retail + Gen II Graphics)
                val ok1 = MgbaCore.pkRomWrite(RED_BLUE_TRAMPOLINE_ADDR, RED_BLUE_TRAMPOLINE_2X)
                val ok2 = MgbaCore.pkRomWrite(RED_BLUE_HOOK_ADDR, RED_BLUE_HOOK)
                val ok3 = MgbaCore.pkRomWrite(RED_BLUE_SPRITE_TRAMPOLINE_ADDR, RED_BLUE_SPRITE_TRAMPOLINE)
                val ok4 = MgbaCore.pkRomWrite(RED_BLUE_SPRITE_HOOK_ADDR, RED_BLUE_SPRITE_HOOK)
                if (ok1 && ok2 && ok3 && ok4) {
                    currentlyPatchedFor = game
                    Log.i(TAG, "Running shoes patch applied for Red/Blue (2x bike speed + Running sprite)")
                } else {
                    Log.e(TAG, "Failed to apply running shoes patch for Red/Blue (tramp=$ok1, hook=$ok2, spTramp=$ok3, spHook=$ok4)")
                }
            }
        }
    }

    /**
     * Restores original game bytes in emulated ROM buffer.
     */
    fun restore() {
        val game = currentlyPatchedFor ?: return
        val isYellow = game == GameKind.YELLOW || game == GameKind.YELLOW_LEGACY
        val isCrystal = game == GameKind.CRYSTAL
        when {
            isYellow -> {
                MgbaCore.pkRomWrite(YELLOW_HOOK_ADDR, YELLOW_ORIGINAL)
                MgbaCore.pkRomWrite(YELLOW_TRAMPOLINE_ADDR, ByteArray(YELLOW_TRAMPOLINE_2X.size))
                MgbaCore.pkRomWrite(YELLOW_PIKA_HOOK_ADDR, YELLOW_PIKA_ORIGINAL)
                MgbaCore.pkRomWrite(YELLOW_PIKA_TRAMPOLINE_ADDR, ByteArray(YELLOW_PIKA_TRAMPOLINE.size))
                MgbaCore.pkRomWrite(YELLOW_SPRITE_HOOK_ADDR, YELLOW_SPRITE_ORIGINAL)
                MgbaCore.pkRomWrite(YELLOW_SPRITE_TRAMPOLINE_ADDR, ByteArray(YELLOW_SPRITE_TRAMPOLINE.size))
                Log.i(TAG, "Running shoes patch restored to original for Yellow")
            }
            isCrystal -> {
                MgbaCore.pkRomWrite(CRYSTAL_SPRITE_HOOK_ADDR, CRYSTAL_SPRITE_ORIGINAL)
                MgbaCore.pkRomWrite(CRYSTAL_SPRITE_TRAMPOLINE_ADDR, ByteArray(CRYSTAL_SPRITE_TRAMPOLINE.size))
                MgbaCore.pkRomWrite(CRYSTAL_HOOK_ADDR, CRYSTAL_ORIGINAL)
                MgbaCore.pkRomWrite(CRYSTAL_TRAMPOLINE_ADDR, ByteArray(CRYSTAL_TRAMPOLINE.size))
                Log.i(TAG, "Running shoes patch restored to original for Crystal")
            }
            game.isGen2 -> {
                MgbaCore.pkRomWrite(GS_SPRITE_HOOK_ADDR, GS_SPRITE_ORIGINAL)
                MgbaCore.pkRomWrite(GS_SPRITE_TRAMPOLINE_ADDR, ByteArray(GS_SPRITE_TRAMPOLINE.size))
                MgbaCore.pkRomWrite(GS_HOOK_ADDR, GS_ORIGINAL)
                MgbaCore.pkRomWrite(GS_TRAMPOLINE_ADDR, ByteArray(GS_TRAMPOLINE.size) { 0xFF.toByte() })
                Log.i(TAG, "Running shoes patch restored to original for Gold/Silver")
            }
            else -> {
                MgbaCore.pkRomWrite(RED_BLUE_HOOK_ADDR, RED_BLUE_ORIGINAL)
                MgbaCore.pkRomWrite(RED_BLUE_TRAMPOLINE_ADDR, ByteArray(RED_BLUE_TRAMPOLINE_2X.size))
                MgbaCore.pkRomWrite(RED_BLUE_SPRITE_HOOK_ADDR, RED_BLUE_SPRITE_ORIGINAL)
                MgbaCore.pkRomWrite(RED_BLUE_SPRITE_TRAMPOLINE_ADDR, ByteArray(RED_BLUE_SPRITE_TRAMPOLINE.size))
                Log.i(TAG, "Running shoes patch restored to original for Red/Blue")
            }
        }
        currentlyPatchedFor = null
    }
}
