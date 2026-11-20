"""pokeemerald-expansion's "smol" graphics compression, for the generators -
the Python twin of shared/.../companion/data/Smol.kt (same bytes out).

    decode(src) -> (decoded bytes, raw length consumed)

Modes 1-6 are tile data (tANS-coded instructions + symbols, see Smol.kt);
mode 8 is a tilemap: raw u16 symbols and instruction bytes, then a running
sum over the u16 tile entries (expansion's SmolDecompressTilemap).
"""
import struct


def u32(b, o):
    return struct.unpack_from('<I', b, o)[0]


def u16(b, o):
    return struct.unpack_from('<H', b, o)[0]


def _instructions(lo, sym, outn):
    """(length, offset) pairs over u16 symbols -> outn bytes (see Smol.kt)."""
    out = [0] * (outn // 2 + 1)
    n = i = si = 0
    while i < len(lo) and n < len(out):
        if lo[i] & 0x80:
            length = (lo[i] & 0x7F) | (lo[i + 1] << 7)
            off = lo[i + 2] & 0x7F
            if lo[i + 2] & 0x80:
                off |= lo[i + 3] << 7
                i += 4
            else:
                i += 3
        else:
            length = lo[i] & 0x7F
            off = lo[i + 1] & 0x7F
            if lo[i + 1] & 0x80:
                off |= lo[i + 2] << 7
                i += 3
            else:
                i += 2
        if length:
            out[n] = sym[si]
            n += 1
            si += 1
            for _ in range(length):
                out[n] = out[n - off]
                n += 1
        else:
            for _ in range(off):
                out[n] = sym[si]
                n += 1
                si += 1
    return out, n


def tilemap(src):
    h0, lo_size = u32(src, 0), u32(src, 4)
    size, sym_size = (h0 >> 4) & 0x3FFF, (h0 >> 18) & 0x3FFF
    sym = [u16(src, 8 + 2 * i) for i in range(sym_size)]
    lo_at = 8 + sym_size * 2 + 2 * (sym_size % 2)
    out, _ = _instructions(src[lo_at:lo_at + lo_size], sym, size)
    b = bytearray(size)
    acc = 0
    for w in range(size // 2):
        acc = (acc + out[w]) & 0xFFFF
        struct.pack_into('<H', b, 2 * w, acc)
    return bytes(b), lo_at + lo_size
def data(src):
    h0=u32(src,0); h1=u32(src,4)
    mode=h0&0xF; assert 1<=mode<=6, mode
    imageSize=(h0>>4)&0x3FFF; symSize=(h0>>18)&0x3FFF
    bitstreamSize=(h1>>6)&0x1FFF; loSize=(h1>>19)&0x1FFF
    data=8
    loEnc=mode in (4,5,6); symEnc=mode in (2,3,5,6); symDelta=mode in (3,6)
    loF=symF=sw=0
    if mode==4: loF=data; sw=3
    elif mode in (2,3): symF=data; sw=3
    elif mode in (5,6): loF=data; symF=data+12; sw=6
    st={'word':sw,'state':h1&0x3F,'bi':0}
    st['bits']=u32(src,data+4*sw)
    def table(off):
        freqs=[0]*16
        for i in range(3):
            w=u32(src,off+4*i)
            for j in range(5): freqs[i*5+j]=(w>>(6*j))&0x3F
            freqs[15]+=(w&0xC0000000)>>(30-2*i)
        t=[]
        for s in range(16):
            for j in range(freqs[s],2*freqs[s]):
                k=0
                while (j<<k)<64: k+=1
                t.append((s,k,(j<<k)-64,(1<<k)-1))
        assert len(t)==64
        return t
    def nxt(t):
        s,k,y,m=t[st['state']]
        st['state']=y+((st['bits']>>st['bi'])&m)
        st['bi']+=k
        if st['bi']>=32:
            st['word']+=1; st['bits']=u32(src,data+4*st['word']); st['bi']-=32
            if st['bi']: st['state']+=(st['bits']&((1<<st['bi'])-1))<<(k-st['bi'])
        return s
    left=data; lo=None; sym=None
    if loEnc:
        t=table(loF); lo=bytearray()
        for i in range(loSize):
            a=nxt(t); b=nxt(t); lo.append(a|(b<<4))
        left+=12
    if symEnc:
        t=table(symF); cur=0; sym=[]
        for i in range(symSize):
            v=0
            for n in range(4):
                x=nxt(t)
                if symDelta: cur=(cur+x)&0xF; x=cur
                v|=x<<(4*n)
            sym.append(v)
        left+=12
    if loEnc or symEnc: left+=4*bitstreamSize
    if not symEnc:
        sym=[u16(src,left+2*i) for i in range(symSize)]; left+=symSize*2
    if not loEnc: lo=src[left:left+loSize]
    outn = imageSize * 4
    out, n = _instructions(lo, sym, outn)
    b = bytearray(outn)
    for w in range(min(n, outn // 2)):
        b[2 * w] = out[w] & 0xFF
        b[2 * w + 1] = out[w] >> 8
    return bytes(b), (left + loSize if not loEnc else left)


def decode(src):
    mode = src[0] & 0xF
    if mode == 8:
        return tilemap(src)
    if 1 <= mode <= 6:
        return data(src)
    raise ValueError(f"not smol (mode {mode})")
