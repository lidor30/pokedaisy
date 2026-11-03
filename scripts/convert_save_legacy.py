#!/usr/bin/env python3
"""Rewrites a retail save for the Legacy hacks, which keep the save's size but not its layout:

    scripts/convert_save_legacy.py yellow  <retail Yellow .sav>  <out .sav> <Yellow Legacy.gb>
    scripts/convert_save_legacy.py crystal <retail Crystal .sav> <out .sav>

Pokemon Yellow Legacy v1.0.10 reads a retail save with everything past the bag out of place (0 badges, then a
garbled map): its bag grew from 20 to 59 slots out of the 78 bytes of padding at the end of wGameProgressFlags, so
wPlayerMoney .. the last script index sit 78 bytes later; wDifficulty and wPlayerGender took the padding after that
(0 = normal, boy). The save also keeps the current map's header, script and text pointers included, and CONTINUE sets
wCurMapTileset's bit 7 and keeps them, so retail's would run as code (an illegal opcode on the first frame of the
field), and so its tileset's, whose collision pointer would leave the player unable to walk: the map's header, its
connections and its tileset's header are rewritten from the hack's own cart.

Pokemon Crystal Legacy v1.3.1 loads a retail save but reads its pockets wrongly: ITEMS grew from 20 to 84, KEY ITEMS
follow them, and BALLS and the PC's items moved into padding further on (wNumBalls D9CC, wNumPCItems DA19; the PC
now holds 49). Both the main and the backup copy are rewritten, each with its checksum.

Addresses are the hacks' own .sym files (cRz-Shadows' repositories, tags V1.0.10 and V1.3.1, built byte for byte).
"""
import sys

SRAM_BANK = 0x2000


def yellow(s: bytearray, rom: bytes) -> bytearray:
    main = SRAM_BANK + 0x05A3                      # sMainData = wMainDataStart (D2F6)
    at = lambda addr: main + addr - 0xD2F6
    out = bytearray(s)
    # wNumBagItems stays; the 20 pairs + 0xFF end stay at wBagItems, the rest of the longer list is cleared.
    n = s[at(0xD31C)]
    if n > 20:
        raise SystemExit(f"bag count {n}: not a retail Yellow save")
    out[at(0xD31D + 2 * n + 1):at(0xD394)] = bytes(at(0xD394) - at(0xD31D + 2 * n + 1))
    # wPlayerMoney .. wRoute18Gate1FCurScript.
    out[at(0xD394):at(0xD6B7)] = s[at(0xD346):at(0xD669)]
    # wCurMapHeader (10 bytes: tileset .. connections), then a connection header (11 bytes) per direction it has:
    # the cart's MapHeaderBanks / MapHeaderPointers, by wCurMap.
    linear = lambda bank, addr: bank * 0x4000 + addr - 0x4000
    cur = out[at(0xD3AB)]
    bank = rom[linear(0x3F, 0x43E4) + cur]
    ptr = int.from_bytes(rom[linear(0x3F, 0x41F2) + 2 * cur:][:2], "little")
    header = linear(bank, ptr)
    out[at(0xD3B4):at(0xD3BE)] = rom[header:header + 10]
    # wTilesetBank .. wGrassTile, skipped on CONTINUE too: 11 bytes of the 12-byte Tilesets entry. A stale wTilesetCollisionPtr
    # leaves the player unable to take a step, and the map drawn as if nothing were wrong.
    tileset = linear(0x03, 0x45C8) + 12 * (out[at(0xD3B4)] & 0x7F)
    out[at(0xD578):at(0xD583)] = rom[tileset:tileset + 11]
    connections = rom[header + 9]
    o = header + 10
    for i, bit in enumerate((3, 2, 1, 0)):         # north, south, west, east
        if connections >> bit & 1:
            out[at(0xD3BE + 11 * i):at(0xD3BE + 11 * i + 11)] = rom[o:o + 11]
            o += 11
    # CalcCheckSum over sGameData (A598 .. B522): the bytes' sum, inverted.
    first, last = SRAM_BANK + 0x0598, SRAM_BANK + 0x1523
    out[last] = ~sum(out[first:last]) & 0xFF
    return out


def crystal(s: bytearray) -> bytearray:
    out = bytearray(s)
    # (sGameData, sChecksum): the main copy in SRAM bank 1, the backup in bank 0.
    for data, checksum in ((SRAM_BANK + 0x0009, SRAM_BANK + 0x0D0D), (0x1209, 0x1F0D)):
        old = lambda addr: data + addr - 0xD47B    # wPlayerData's place, retail
        new = lambda addr: data + addr - 0xD486    # and the hack's
        pockets = {
            # retail count address, entry size, entries, the hack's count address, its capacity
            "items": (0xD892, 2, 20, 0xD89D, 84),
            "key": (0xD8BC, 1, 25, 0xD947, 25),
            "balls": (0xD8D7, 2, 12, 0xD9CC, 12),
            "pc": (0xD8F1, 2, 50, 0xDA19, 49),
        }
        for name, (src, size, cap, dst, new_cap) in pockets.items():
            n = s[old(src)]
            if n > min(cap, new_cap):
                raise SystemExit(f"{name}: {n} entries: not a retail Crystal save, or too many for the hack")
        # Blank every pocket first: the retail pockets' bytes overlap the hack's.
        for name, (src, size, cap, dst, new_cap) in pockets.items():
            out[new(dst):new(dst) + 2 + new_cap * size] = bytes(2 + new_cap * size)
        for name, (src, size, cap, dst, new_cap) in pockets.items():
            n = s[old(src)]
            body = s[old(src) + 1:old(src) + 1 + n * size]
            out[new(dst)] = n
            out[new(dst) + 1:new(dst) + 1 + len(body)] = body
            out[new(dst) + 1 + len(body)] = 0xFF
        # Checksum: the 16-bit sum of sGameData .. sGameDataEnd (0xB7A bytes), little-endian.
        total = sum(out[data:data + 0xB7A]) & 0xFFFF
        out[checksum], out[checksum + 1] = total & 0xFF, total >> 8
    return out


def main() -> None:
    if sys.argv[1:2] == ["yellow"] and len(sys.argv) == 5:
        out = yellow(bytearray(open(sys.argv[2], "rb").read()), open(sys.argv[4], "rb").read())
    elif sys.argv[1:2] == ["crystal"] and len(sys.argv) == 4:
        out = crystal(bytearray(open(sys.argv[2], "rb").read()))
    else:
        raise SystemExit(__doc__)
    open(sys.argv[3], "wb").write(out)


if __name__ == "__main__":
    main()
