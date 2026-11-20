package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals

/**
 * Shared checks for the LeafGreen / Ruby / Sapphire decode tests. Every
 * expected value was read off the real game's own screens for the same save
 * (headless mgba_dump `shot`s of the party menu, each bag pocket and the
 * POKéDEX, 2026-09-29) - see those tests' comments.
 */
/** Where the ROM-reading tests look for the player's own ROMs (`-DromDir=` overrides); tests skip a missing one. */
val RETAIL_ROM_DIR: String = System.getProperty("romDir")?.let { if (it.endsWith("/")) it else "$it/" }
    ?: "${System.getProperty("user.home")}/Downloads/Game ROMs & Emulation/gba/"

/** Decodes [fixture] the way the app does: RAM from the capture, cartridge
 * reads (R/S keep gBagPockets in ROM) from the ROM file when it's here. */
fun decodeRetail(fixture: String, cfg: NativeConfig, romFile: String): Pair<Telemetry, RomFileReader?> {
    resetNativeBagCache()
    val ram = FixtureMemoryReader.load(fixture)
    val rom = RomFileReader.load(RETAIL_ROM_DIR + romFile)
    return readNativeTelemetry(rom?.withRam(ram) ?: ram, cfg) to rom
}

/** "RAYQUAZA 100 326/339" per party slot: species (upper-cased), level, HP. */
fun assertParty(t: Telemetry, expected: List<String>) {
    assertEquals(expected, t.party.map { "${speciesNames[it.species]?.uppercase()} ${it.level} ${it.hp}/${it.maxHp}" })
}

/** One pocket in bag order as "NAME xN", named from [names] - the game's own
 * item strings (ItemTextGame.kt), i.e. what its bag screen prints. */
fun pocket(t: Telemetry, p: Int, names: Map<Int, String>): List<String> =
    t.items.filter { it.pocket == p }.map { "${names[it.itemId]} x${it.quantity}" }
