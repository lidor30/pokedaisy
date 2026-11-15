import sys, struct
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from romfs import RomFS
def garc_files(blob):
    assert blob[:4] == b'CRAG', blob[:4]
    hdr = struct.unpack_from('<I', blob, 4)[0]
    data_off = struct.unpack_from('<I', blob, 0x10)[0]
    o = hdr
    assert blob[o:o+4] == b'OTAF'
    size = struct.unpack_from('<I', blob, o + 4)[0]; n = struct.unpack_from('<H', blob, o + 8)[0]
    o += size
    assert blob[o:o+4] == b'BTAF', blob[o:o+4]
    bsize = struct.unpack_from('<I', blob, o + 4)[0]; count = struct.unpack_from('<I', blob, o + 8)[0]
    p = o + 12; files = []
    for i in range(count):
        bits = struct.unpack_from('<I', blob, p)[0]; p += 4
        sub = []
        for b in range(32):
            if bits >> b & 1:
                s, e, l = struct.unpack_from('<3I', blob, p); p += 12
                sub.append((s, e, l))
        files.append(sub)
    return [blob[data_off + sub[0][0]: data_off + sub[0][1]] if sub else b'' for sub in files]
def text_lines(f):
    sections, lines, total, key0, sec_off = struct.unpack_from('<HHIII', f, 0)
    base = sec_off
    out = []
    key = 0x7C89
    for i in range(lines):
        off, length = struct.unpack_from('<IH', f, base + 4 + i * 8)
        raw = f[base + off: base + off + length * 2]
        k = key; chars = []
        for j in range(length):
            c = struct.unpack_from('<H', raw, j * 2)[0] ^ k
            k = ((k << 3) | (k >> 13)) & 0xFFFF
            chars.append(c)
        key = (key + 0x2983) & 0xFFFF
        s = ''; j = 0
        while j < len(chars):
            c = chars[j]
            if c == 0: break
            if c == 0x10:  # a variable: count, then the words it takes
                n = chars[j + 1]; s += '{var}'; j += 2 + n; continue
            s += chr(c); j += 1
        out.append(s)
    return out
if __name__ == '__main__':
    r = RomFS(sys.argv[1]); garc = sys.argv[2]
    off, size = r.find(garc); blob = r.read(off, size)
    files = garc_files(blob)
    print(len(files), 'files')
    for want in sys.argv[3:]:
        for i, f in enumerate(files):
            try: lines = text_lines(f)
            except Exception: continue
            if any(want == l for l in lines): print(want, 'in file', i, 'lines', len(lines), lines[:5])
