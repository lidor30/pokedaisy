"""Build an original, logo-free NDS test cart: ARM9 fills VRAM green, ARM7 loops.

No game, firmware, BIOS or Nintendo header logo is copied. ARM instructions
are emitted directly so this diagnostic does not need a devkitARM install.
"""
from pathlib import Path
import struct
import sys


def diagnostic():
    words, literals = [], []

    def load(register, value):
        literals.append((len(words), register, value))
        words.append(0)

    load(0, 0x04000304)
    load(1, 0x8203)
    words.append(0xe1c010b0)  # strh r1, [r0]: power LCD + main engine
    load(0, 0x04000000)
    load(1, 0x00020000)
    words.append(0xe5801000)  # str r1, [r0]: VRAM display mode
    load(0, 0x04000240)
    words += [0xe3a01080, 0xe5c01000]  # mov r1,#0x80; strb: VRAM A LCDC
    load(0, 0x06800000)
    load(1, 0x83e0)  # opaque green RGB555
    load(2, 256 * 192)
    words += [0xe0c010b2, 0xe2522001, 0x1afffffc, 0xeafffffe]
    for index, register, value in literals:
        distance = (len(words) - index) * 4 - 8
        words[index] = 0xe59f0000 | register << 12 | distance
        words.append(value)
    arm9 = struct.pack('<' + 'I' * len(words), *words)
    rom = bytearray(0x10000)
    rom[:12] = b'PD DS TEST  '
    rom[12:16] = b'DSPT'
    struct.pack_into('<8I', rom, 0x20, 0x200, 0x02000000, 0x02000000,
                     len(arm9), 0x400, 0x03800000, 0x03800000, 4)
    struct.pack_into('<II', rom, 0x80, len(rom), 0x200)
    rom[0x200:0x200 + len(arm9)] = arm9
    struct.pack_into('<I', rom, 0x400, 0xeafffffe)
    return rom


if __name__ == '__main__':
    Path(sys.argv[1]).write_bytes(diagnostic())
