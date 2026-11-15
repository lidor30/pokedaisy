import sys, os, struct, zlib, pickle
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
def png(path):
    d = open(path, 'rb').read(); i = 8; idat = b''
    while i < len(d):
        n = struct.unpack('>I', d[i:i+4])[0]; t = d[i+4:i+8]
        if t == b'IHDR': w, h, bd, ct = struct.unpack('>IIBB', d[i+8:i+18])
        if t == b'IDAT': idat += d[i+8:i+8+n]
        i += 12 + n
    raw = zlib.decompress(idat); bpp = 4 if ct == 6 else 3; stride = w * bpp
    rows = []; prev = bytearray(stride); p = 0
    for y in range(h):
        f = raw[p]; line = bytearray(raw[p+1:p+1+stride]); p += 1 + stride
        for x in range(stride):
            a = line[x-bpp] if x >= bpp else 0; b = prev[x]; c = prev[x-bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append([tuple(line[k*bpp:k*bpp+3]) for k in range(w)]); prev = line
    return rows
WORK = os.environ.get('N3DS_ICONS_WORK', '.')
IMS = pickle.load(open(os.path.join(WORK, 'ims.pkl'), 'rb'))  # icons6.icon() of every a/0/9/3 entry
OPAQUE = [[(y, x, c) for y in range(32) for x in range(40) for c in [im[y][x]] if c] for im in IMS]
def err(shot, ox, oy, k, cap=1e9):
    pts = OPAQUE[k]
    if not pts: return 1e9
    s = 0
    for y, x, c in pts:
        yy, xx = oy + y, ox + x
        if 0 <= yy < len(shot) and 0 <= xx < len(shot[0]):
            q = shot[yy][xx]; s += abs(q[0]-c[0]) + abs(q[1]-c[1]) + abs(q[2]-c[2])
        else: s += 300
        if s > cap: return 1e9
    return s / len(pts)
SLOTS = [(0, 0), (160, 12), (0, 64), (160, 76), (0, 128), (160, 140)]  # rough slot origins
def calibrate(shot, k=1):
    out = []
    for sx, sy in SLOTS:
        best = min(((err(shot, sx + dx, sy + dy, k), sx + dx, sy + dy) for dx in range(-5, 40) for dy in range(-12, 30)))
        out.append((best[1], best[2], round(best[0], 1)))
    return out
def identify(shot, offs, cands=None):
    res = []
    for ox, oy, _ in offs:
        ks = cands if cands is not None else range(len(IMS))
        best = (1e9, -1); second = (1e9, -1)
        for k in ks:
            e = err(shot, ox, oy, k, cap=second[0] * len(OPAQUE[k]) if second[0] < 1e9 else 1e9)
            if e < best[0]: second = best; best = (e, k)
            elif e < second[0]: second = (e, k)
        res.append((best[1], round(best[0], 1), round(second[0], 1), second[1]))
    return res
if __name__ == '__main__':
    shot = png(sys.argv[1]); offs = calibrate(shot)
    print(offs)
    pickle.dump(offs, open(os.path.join(WORK, 'offs.pkl'), 'wb'))
