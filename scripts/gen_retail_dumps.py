#!/usr/bin/env python3
"""Retail FireRed / Emerald dumps as collections ship them -> RetailDumpsGen.kt.

Collection dumps of retail FireRed / Emerald are often not the cart byte for byte: the empty padding at the
end is trimmed off, and the save code is patched so the game saves without the cart's flash chip (the
"flash to SRAM" patch, often with the save warnings reworded: "The internal battery has run dry"). Their
RAM and game code are retail's, so the companion reads them as retail - but their SHA-1 isn't retail's.

For each retail ROM this writes:
  - `end`: the ROM's data end (one past its last byte that isn't 0x00 / 0xFF padding);
  - `ranges`: the cart's GPIO bytes, AgbMain's wait-state value, the flash chips' descriptors, retail's empty
    space (64 KB+ of 0xFF: nothing reads it), the save code (pret's agb_flash*.o, siirtc.o on Emerald, plus the 16 bytes before it - the
    patch's search lands there) and the save warnings, which such a patch may rewrite;
  - `sha1`: the SHA-1 of bytes [0, end) with those ranges zeroed.
A ROM of the same game code and revision whose bytes [0, end), ranges zeroed, hash to that is that retail
game (RetailDumps.kt; the website's src/lib/compat.js does the same in JavaScript). Only offsets and a hash
are written - no ROM bytes.

Emerald's ranges come from pret's map (its build is byte-identical to retail). FireRed's are found by content:
the flash code is the same library (Emerald's bytes, searched for), the warnings by their text - the
pokefirered map in $DECOMPS isn't a byte-identical build, its code sits 0x93C off retail rev 1's.

    python3 scripts/gen_retail_dumps.py <FireRed rev 0 .gba> <FireRed rev 1 .gba> <Emerald .gba>
(needs $DECOMPS for the maps; `make` exports it).
"""
import hashlib
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data/RetailDumpsGen.kt"
RETAIL_SHA1 = {
    ("BPRE", 0): "41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc",
    ("BPRE", 1): "dd5945db9b930750cb39d00c84da8571feebf417",
    ("BPEE", 0): "f3ae088181bf583e55daf962a92bb46f4f1d07b7",
}


def symbols(map_path):
    """(address, name) of every ROM symbol, and each object's .text and .rodata start / size."""
    syms, texts, ro = [], {}, {}
    obj_text = re.compile(r"\s*\.(text|rodata)\s+0x([0-9a-f]+)\s+0x([0-9a-f]+)\s+(\S+)")
    sym = re.compile(r"\s+0x([0-9a-f]{8,16})\s+([A-Za-z_]\w*)\s*$")
    for line in open(map_path, errors="ignore"):
        m = obj_text.match(line)
        if m:
            (texts if m.group(1) == "text" else ro)[m.group(4)] = (int(m.group(2), 16), int(m.group(3), 16))
            continue
        m = sym.match(line)
        if m:
            a = int(m.group(1), 16)
            if 0x08000000 <= a < 0x0A000000:
                syms.append((a - 0x08000000, m.group(2)))
    syms.sort()
    return syms, texts, ro


def after(syms, name):
    """The offset of the symbol following [name] (where its data ends)."""
    i = next(i for i, (_, n) in enumerate(syms) if n == name)
    return syms[i + 1][0]


def at(syms, name):
    return next(a for a, n in syms if n == name)


def text_range(texts, objs):
    spans = [texts[o] for o in objs if o in texts and texts[o][1] > 0]
    return min(a for a, _ in spans) - 0x08000000, max(a + s for a, s in spans) - 0x08000000


def data_end(rom):
    i = len(rom)
    while i > 0 and rom[i - 1] in (0x00, 0xFF):
        i -= 1
    return i


def normalized_sha1(rom, end, ranges):
    b = bytearray(rom[:end])
    for a, z in ranges:
        b[a:z] = bytes(z - a)
    return hashlib.sha1(b).hexdigest()


def encode(text):
    """English text in the games' charset (letters, digits, space, full stop)."""
    out = bytearray()
    for ch in text:
        if ch == " ":
            out.append(0x00)
        elif ch == ".":
            out.append(0xAD)
        elif "A" <= ch <= "Z":
            out.append(0xBB + ord(ch) - 65)
        elif "a" <= ch <= "z":
            out.append(0xD5 + ord(ch) - 97)
        else:
            out.append(0xA1 + ord(ch) - 48)
    return bytes(out)


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    fr0, fr1, em = (Path(p).expanduser().read_bytes() for p in sys.argv[1:])
    decomps = Path(os.environ.get("DECOMPS", "")).expanduser()
    em_syms, em_texts, em_ro = symbols(decomps / "pokeemerald/pokeemerald.map")

    flash = ["src/agb_flash.o", "src/agb_flash_1m.o", "src/agb_flash_mx.o", "src/agb_flash_le.o"]
    fa, fz = text_range(em_texts, flash)
    a, z = text_range(em_texts, flash + ["src/siirtc.o"])
    em_ranges = [(a - 0x10, z), (at(em_syms, "gText_SaveFileUpdated"), after(em_syms, "gText_BatteryRunDry"))]

    def fr_ranges(rom):
        # The same flash library as Emerald's, found by its first 32 bytes; then the save warnings by their text.
        head = em[fa:fa + 32]
        hits = [m.start() for m in re.finditer(re.escape(head), rom)]
        assert len(hits) == 1, f"{len(hits)} places for the flash code"
        start = rom.find(encode("The save file is corrupted."))
        last = encode("not installed.")
        end = rom.find(last, start) + len(last) + 1   # its 0xFF terminator
        assert start > 0 and end > start and end - start < 0x100
        return [(hits[0] - 0x10, hits[0] + (fz - fa)), (start, end)]

    fr0_ranges, fr1_ranges = fr_ranges(fr0), fr_ranges(fr1)

    # The flash chips' descriptors (agb_flash*.o's rodata: timings, chip IDs, function pointers; Emerald's siirtc
    # after them): a patch for another chip rewrites the ID. Emerald's from the map; FireRed's (a slightly different
    # layout) from its FLASH1M_V103 string, as far as the last chip ID (Sanyo's 0x1362).
    ro_a, ro_z = text_range(em_ro, flash)
    anchor = em.find(b"FLASH1M_V103")
    assert ro_a < anchor < ro_z and em.count(b"FLASH1M_V103") == 1
    em_ranges.append((ro_a, text_range(em_ro, flash + ["src/siirtc.o"])[1]))
    for rom, rs in ((fr0, fr0_ranges), (fr1, fr1_ranges)):
        assert rom.count(b"FLASH1M_V103") == 1
        i = rom.find(b"FLASH1M_V103")
        z = rom.find(bytes.fromhex("62130000"), i) + 4
        assert 0 < z - i < 0x200
        rs.append((i - (anchor - ro_a), z))

    # AgbMain's WAITCNT value (the cartridge's wait states, 0x4014): flash-cart patches change it.
    waitcnt = bytes.fromhex("0402000414400000")
    for rom, rs in ((em, em_ranges), (fr0, fr0_ranges), (fr1, fr1_ranges)):
        i = rom.find(waitcnt)
        assert 0 < i < 0x1000, f"WAITCNT at {i:#x}"
        rs.append((i + 4, i + 8))

    # Retail's empty space inside its data (64 KB+ of 0xFF): nothing in the game reads it, and collections put
    # their own things there (a repack's leftovers). A change that uses it needs code changed elsewhere, which counts.
    for rom, rs in ((em, em_ranges), (fr0, fr0_ranges), (fr1, fr1_ranges)):
        end = data_end(rom)
        rs.extend((m.start(), m.end()) for m in re.finditer(rb"\xff{65536,}", rom[:end]))

    # The cart's GPIO registers (0xC4-0xC9: zero in every retail file) read as live values on the bus, so the app's
    # in-game check zeroes them (Poller's GPIO hole): so does this.
    gpio = (0xC4, 0xCA)
    em_ranges, fr0_ranges, fr1_ranges = ([gpio] + r for r in (em_ranges, fr0_ranges, fr1_ranges))

    entries = []
    for (code, rev), rom, ranges in [(("BPRE", 0), fr0, fr0_ranges), (("BPRE", 1), fr1, fr1_ranges), (("BPEE", 0), em, em_ranges)]:
        assert hashlib.sha1(rom).hexdigest() == RETAIL_SHA1[(code, rev)], f"not retail {code} rev {rev}"
        assert rom[0xAC:0xB0].decode() == code and rom[0xBC] == rev
        end = data_end(rom)
        ranges = sorted(ranges)
        entries.append((code, rev, end, ranges, normalized_sha1(rom, end, ranges)))
        print(f"{code} rev {rev}: end {end:#x}, ranges {[(hex(a), hex(z)) for a, z in ranges]}")

    lines = [
        "package com.pokedaisy.app.companion.data",
        "",
        "// Generated by scripts/gen_retail_dumps.py from the retail ROMs and pret's maps - don't edit.",
        "// Offsets and a hash only: no ROM bytes.",
        "",
        "val RETAIL_DUMPS = listOf(",
    ]
    for code, rev, end, ranges, sha1 in entries:
        rs = ", ".join(f"0x{a:X} until 0x{z:X}" for a, z in ranges)
        lines.append(f'    RetailDump("{code}", {rev}, 0x{end:X}, listOf({rs}), "{sha1}", "{RETAIL_SHA1[(code, rev)]}"),')
    lines += [")", ""]
    OUT.write_text("\n".join(lines))
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
