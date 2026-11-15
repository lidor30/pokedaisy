def lz11(src):
    assert src[0] == 0x11, hex(src[0])
    size = src[1] | src[2] << 8 | src[3] << 16
    p = 4
    if size == 0:
        size = int.from_bytes(src[4:8], 'little'); p = 8
    out = bytearray()
    while len(out) < size:
        flags = src[p]; p += 1
        for bit in range(8):
            if len(out) >= size: break
            if flags & (0x80 >> bit):
                b = src[p]; ind = b >> 4
                if ind == 0:
                    n = ((b & 0xF) << 4 | src[p + 1] >> 4) + 0x11
                    disp = ((src[p + 1] & 0xF) << 8 | src[p + 2]) + 1; p += 3
                elif ind == 1:
                    n = ((b & 0xF) << 12 | src[p + 1] << 4 | src[p + 2] >> 4) + 0x111
                    disp = ((src[p + 2] & 0xF) << 8 | src[p + 3]) + 1; p += 4
                else:
                    n = ind + 1
                    disp = ((b & 0xF) << 8 | src[p + 1]) + 1; p += 2
                for _ in range(n): out.append(out[-disp])
            else:
                out.append(src[p]); p += 1
    return bytes(out)
