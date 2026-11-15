import sys, struct, zlib, os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lz11 import lz11
def morton(i):
    return (i & 1) | ((i >> 1) & 2) | ((i >> 2) & 4), ((i >> 1) & 1) | ((i >> 2) & 2) | ((i >> 3) & 4)
def c5551(v): return ((v >> 11 & 31) * 255 // 31, (v >> 6 & 31) * 255 // 31, (v >> 1 & 31) * 255 // 31)
def icon(f):
    d = lz11(f) if f[0] == 0x11 else f
    n = struct.unpack_from('<H', d, 2)[0]; pal = struct.unpack_from('<%dH' % n, d, 4); base = 4 + 2 * n
    im = [[None] * 40 for _ in range(32)]
    for t in range(32):
        tx, ty = (t % 8) * 8, (t // 8) * 8
        for i in range(64):
            wide = n > 16
            k = base + t * 64 + i if wide else base + t * 32 + i // 2
            b = d[k] if k < len(d) else 0
            idx = b if wide else (b & 0xF) if i & 1 else (b >> 4)
            x, y = morton(i)
            if tx + x < 40 and idx < n and pal[idx] & 1: im[ty + y][tx + x] = c5551(pal[idx])
    return im
def sheet(path, ims, cols=10, scale=3):
    rows = (len(ims) + cols - 1) // cols; W = cols * 42; H = rows * 34
    px = [[(255, 255, 255)] * W for _ in range(H)]
    for k, im in enumerate(ims):
        ox, oy = (k % cols) * 42, (k // cols) * 34
        for y in range(32):
            for x in range(40):
                if im[y][x]: px[oy + y][ox + x] = im[y][x]
    raw = b''
    for y in range(H * scale): raw += b'\0' + b''.join(bytes(px[y // scale][x // scale]) for x in range(W * scale))
    ch = lambda t, c: struct.pack('>I', len(c)) + t + c + struct.pack('>I', zlib.crc32(t + c) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', W * scale, H * scale, 8, 2, 0, 0, 0)) + ch(b'IDAT', zlib.compress(raw)) + ch(b'IEND', b''))
