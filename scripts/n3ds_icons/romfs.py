import sys, struct
MU = 0x200
class RomFS:
    def __init__(self, path):
        self.f = open(path, 'rb')
        h = self.read(0, 0x200)
        assert h[0x100:0x104] == b'NCSD'
        part0 = struct.unpack_from('<I', h, 0x120)[0] * MU
        n = self.read(part0, 0x200)
        assert n[0x100:0x104] == b'NCCH'
        romfs = part0 + struct.unpack_from('<I', n, 0x1B0)[0] * MU
        iv = self.read(romfs, 0x60)
        assert iv[:4] == b'IVFC', iv[:4]
        master = struct.unpack_from('<I', iv, 8)[0]
        l3_block = struct.unpack_from('<I', iv, 0x4C)[0]   # level 3's block size log2
        start = 0x60 + master
        bs = 1 << l3_block
        self.l3 = romfs + ((start + bs - 1) // bs) * bs
        l3h = self.read(self.l3, 0x28)
        (hl, dho, dhl, dmo, dml, fho, fhl, fmo, fml, fdo) = struct.unpack('<10I', l3h)
        self.dirs = self.read(self.l3 + dmo, dml)
        self.files = self.read(self.l3 + fmo, fml)
        self.data = self.l3 + fdo
    def read(self, off, n):
        self.f.seek(off); return self.f.read(n)
    def dir_entry(self, o):
        parent, sibling, child, first, nh, nl = struct.unpack_from('<6I', self.dirs, o)
        return sibling, child, first, self.dirs[o + 0x18:o + 0x18 + nl].decode('utf-16le')
    def file_entry(self, o):
        parent, sibling = struct.unpack_from('<2I', self.files, o)
        off, size = struct.unpack_from('<2Q', self.files, o + 8)
        nh, nl = struct.unpack_from('<2I', self.files, o + 0x18)
        return sibling, off, size, self.files[o + 0x20:o + 0x20 + nl].decode('utf-16le')
    def find(self, path):
        d = 0
        parts = path.strip('/').split('/')
        for i, name in enumerate(parts):
            sib, child, first, _ = self.dir_entry(d)
            if i == len(parts) - 1:
                f = first
                while f != 0xFFFFFFFF:
                    s, off, size, n = self.file_entry(f)
                    if n == name: return self.data + off, size
                    f = s
            c = child; found = None
            while c != 0xFFFFFFFF:
                s, ch, fi, n = self.dir_entry(c)
                if n == name: found = c; break
                c = s
            if found is None: return None
            d = found
        return None
    def listdir(self, path):
        d = 0
        for name in [p for p in path.strip('/').split('/') if p]:
            c = self.dir_entry(d)[1]
            while c != 0xFFFFFFFF and self.dir_entry(c)[3] != name: c = self.dir_entry(c)[0]
            d = c
        out = []
        c = self.dir_entry(d)[1]
        while c != 0xFFFFFFFF: out.append(self.dir_entry(c)[3] + '/'); c = self.dir_entry(c)[0]
        f = self.dir_entry(d)[2]
        while f != 0xFFFFFFFF:
            s, off, size, n = self.file_entry(f); out.append('%s %d' % (n, size)); f = s
        return out
if __name__ == '__main__':
    r = RomFS(sys.argv[1])
    print(r.listdir(sys.argv[2] if len(sys.argv) > 2 else ''))
