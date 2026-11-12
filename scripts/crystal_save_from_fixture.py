#!/usr/bin/env python3
"""A Crystal save (32 KB SRAM) from a fixture's wram.bin / wramx.bin, written the way _SaveGameData writes one
(pret/pokecrystal's pokecrystal11.sym): the options, wPlayerData, wCurMapData and wPokemonData into sGameData and its
backup copy, both checksums and check values, empty PC boxes. Every Crystal build that keeps English's RAM loads it
(German Crystal's save was made this way from the crystal fixture).

usage: crystal_save_from_fixture.py <fixture dir> <out.sav> [box stride, hex: 450]"""
import sys
fx, out = sys.argv[1], sys.argv[2]
stride = int(sys.argv[3], 16) if len(sys.argv) > 3 else 0x450
wram = open(fx + "/wram.bin", "rb").read()       # C000-DFFF
wx = open(fx + "/wramx.bin", "rb").read()        # 8 banks x 0x1000 (D000-DFFF)
def w(addr, n):
    if addr < 0xD000: return wram[addr - 0xC000: addr - 0xC000 + n]
    return wx[0x1000 + addr - 0xD000: 0x1000 + addr - 0xD000 + n]
sram = bytearray(b"\x00" * 0x8000)
def s(bank, addr): return bank * 0x2000 + addr - 0xA000
def put(bank, addr, data): sram[s(bank, addr): s(bank, addr) + len(data)] = data
opts = bytearray(w(0xCFCC, 8)); opts[0] &= ~(1 << 6) & 0xFF
pd, cm, pk = w(0xD47B, 0xDCA5 - 0xD47B), w(0xDCA5, 0xDCD7 - 0xDCA5), w(0xDCD7, 0xDFF5 - 0xDCD7)
for bank, base, cv1, cv2, ck, end in ((1, 0xA000, 0xA008, 0xAD0F, 0xAD0D, 0xAB83), (0, 0xB200, 0xB208, 0xBF0F, 0xBF0D, 0xBD83)):
    put(bank, base, opts); put(bank, cv1, bytes([99])); put(bank, cv2, bytes([127]))
    g = base + 9
    put(bank, g, pd); put(bank, g + len(pd), cm); put(bank, g + len(pd) + len(cm), pk)
    c = sum(sram[s(bank, g): s(bank, end)]) & 0xFFFF
    put(bank, ck, bytes([c & 0xFF, c >> 8]))
# empty boxes: the current box (1:AD10) and boxes 1-14 (banks 2 / 3)
put(1, 0xAD10, b"\x00\xff")
for bank in (2, 3):
    for i in range(7): put(bank, 0xA000 + i * stride, b"\x00\xff")
put(0, 0xAC60, b"\x00")
open(out, "wb").write(sram)
print("ok", len(sram))
