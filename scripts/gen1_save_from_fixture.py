#!/usr/bin/env python3
"""A Red / Blue save (32 KB SRAM) from a fixture's wram.bin / hram.bin, written the way SaveSAVtoSRAM writes one
(pret/pokered's pokered.sym): wPlayerName, the main data, the sprite data, the party and the current box into
sGameData, its checksum, every other PC box empty (with their bank checksums). Every build that keeps English Red's
RAM layout loads it (the German Red / Blue's saves were made this way from the `red` fixture: their RAM is English's
+ 5 bytes from wOnSGB up, the save image is the same bytes).

usage: gen1_save_from_fixture.py <fixture dir> <out.sav>"""
import sys

fx, out = sys.argv[1], sys.argv[2]
wram = open(fx + "/wram.bin", "rb").read()   # C000-DFFF
hram = open(fx + "/hram.bin", "rb").read()   # FF80-FFFE


def w(lo, hi):
    return wram[lo - 0xC000:hi - 0xC000]


sram = bytearray(0x8000)


def put(bank, addr, data):
    o = bank * 0x2000 + addr - 0xA000
    sram[o:o + len(data)] = data


def checksum(data):
    return ~sum(data) & 0xFF


# pokered.sym: sGameData 1:A598 = sPlayerName, sMainData, sSpriteData, sPartyData, sCurBoxData, sTileAnimations;
# sMainDataCheckSum 1:B523 over sGameData .. sGameDataEnd.
game = (w(0xD158, 0xD163)           # wPlayerName (NAME_LENGTH 11)
        + w(0xD2F7, 0xDA80)         # wMainDataStart .. wMainDataEnd
        + w(0xC100, 0xC300)         # wSpriteDataStart .. wSpriteDataEnd
        + w(0xD163, 0xD2F7)         # wPartyDataStart .. wPartyDataEnd
        + w(0xDA80, 0xDEE2)         # wBoxDataStart .. wBoxDataEnd
        + bytes([hram[0xFFD7 - 0xFF80]]))   # hTileAnimations -> sTileAnimations
assert 0xA598 + len(game) == 0xB523, hex(0xA598 + len(game))
put(1, 0xA598, game)
put(1, 0xB523, bytes([checksum(game)]))

# Boxes 1-12 empty (count 0, the species list's terminator), each bank's all-boxes and per-box checksums.
box = 0xDEE2 - 0xDA80
for bank in (2, 3):
    for i in range(6):
        put(bank, 0xA000 + i * box, b"\x00\xff")
    put(bank, 0xBA4C, bytes([checksum(sram[bank * 0x2000:bank * 0x2000 + 6 * box])]))
    put(bank, 0xBA4D, bytes(checksum(sram[bank * 0x2000 + i * box:bank * 0x2000 + (i + 1) * box]) for i in range(6)))
open(out, "wb").write(sram)
print("ok", len(sram))
