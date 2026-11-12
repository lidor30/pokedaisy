#!/usr/bin/env python3
"""A Crystal or Gold / Silver save (32 KB SRAM) from a fixture's wram.bin / wramx.bin, written the way _SaveGameData
writes one (pret/pokecrystal's pokecrystal11.sym, pret/pokegold's pokegold.sym): the options, wPlayerData, wCurMapData
and wPokemonData into sGameData, the checksum and check values, empty PC boxes; Crystal's backup copy too (Gold's
backup is split up, and the game loads the main copy first). Every build that keeps English's RAM loads it (German
Crystal's and German Gold / Silver's saves were made this way from the crystal / gold fixtures).

usage: gen2_save_from_fixture.py <crystal|gold> <fixture dir> <out.sav>"""
import sys
# options, player / map / pokemon data, current box: WRAM, then SRAM (bank, address); copies: (bank, options,
# check value 1, check value 2, checksum, game data end).
LAYOUTS = {
    "crystal": dict(opts=0xCFCC, data=(0xD47B, 0xDCA5, 0xDCD7, 0xDFF5), cur_box=(1, 0xAD10),
                    copies=((1, 0xA000, 0xA008, 0xAD0F, 0xAD0D, 0xAB83), (0, 0xB200, 0xB208, 0xBF0F, 0xBF0D, 0xBD83))),
    "gold": dict(opts=0xD199, data=(0xD1A1, 0xD9EE, 0xDA22, 0xDF01), cur_box=(1, 0xAD6C),
                 copies=((1, 0xA000, 0xA008, 0xAD6B, 0xAD69, 0xAD69),)),
}
lay = LAYOUTS[sys.argv[1]]
fx, out = sys.argv[2], sys.argv[3]
wram = open(fx + "/wram.bin", "rb").read()       # C000-DFFF
wx = open(fx + "/wramx.bin", "rb").read()        # 8 banks x 0x1000 (D000-DFFF)
def w(addr, n):
    if addr < 0xD000: return wram[addr - 0xC000: addr - 0xC000 + n]
    return wx[0x1000 + addr - 0xD000: 0x1000 + addr - 0xD000 + n]
sram = bytearray(b"\x00" * 0x8000)
def s(bank, addr): return bank * 0x2000 + addr - 0xA000
def put(bank, addr, data): sram[s(bank, addr): s(bank, addr) + len(data)] = data
opts = bytearray(w(lay["opts"], 8)); opts[0] &= ~(1 << 6) & 0xFF
pl, cm, pk, end = lay["data"]
pd, cm, pk = w(pl, cm - pl), w(cm, pk - cm), w(pk, end - pk)
for bank, base, cv1, cv2, ck, end in lay["copies"]:
    put(bank, base, opts); put(bank, cv1, bytes([99])); put(bank, cv2, bytes([127]))
    g = base + 9
    put(bank, g, pd); put(bank, g + len(pd), cm); put(bank, g + len(pd) + len(cm), pk)
    c = sum(sram[s(bank, g): s(bank, end)]) & 0xFFFF
    put(bank, ck, bytes([c & 0xFF, c >> 8]))
# empty boxes: the current box and boxes 1-14 (banks 2 / 3, 7 a bank)
put(*lay["cur_box"], b"\x00\xff")
for bank in (2, 3):
    for i in range(7): put(bank, 0xA000 + i * 0x450, b"\x00\xff")
put(0, 0xAC60, b"\x00")
open(out, "wb").write(sram)
print("ok", len(sram))
