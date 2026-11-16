import struct
ORDERS = ["ABCD","ABDC","ACBD","ACDB","ADBC","ADCB","BACD","BADC","BCAD","BCDA","BDAC","BDCA",
          "CABD","CADB","CBAD","CBDA","CDAB","CDBA","DABC","DACB","DBAC","DBCA","DCAB","DCBA"]
def crypt(buf, ec, start, end, seed=None):
    seed = ec if seed is None else seed; out = bytearray(buf)
    for i in range(start, end, 2):
        seed = (seed * 0x41C64E6D + 0x6073) & 0xFFFFFFFF
        out[i:i+2] = struct.pack('<H', struct.unpack_from('<H', buf, i)[0] ^ (seed >> 16))
    return out
def decrypt(stored):  # 0xE8 bytes
    ec = struct.unpack_from('<I', stored, 0)[0]
    out = crypt(stored, ec, 8, 0xE8)
    order = ORDERS[((ec >> 13) & 31) % 24]
    blocks = {order[k]: out[8 + 56*k: 8 + 56*(k+1)] for k in range(4)}
    return bytearray(out[:8] + blocks['A'] + blocks['B'] + blocks['C'] + blocks['D'])
def encrypt(plain):
    plain = bytearray(plain)
    chk = sum(struct.unpack_from('<H', plain, i)[0] for i in range(8, 0xE8, 2)) & 0xFFFF
    struct.pack_into('<H', plain, 6, chk)
    ec = struct.unpack_from('<I', plain, 0)[0]
    order = ORDERS[((ec >> 13) & 31) % 24]
    pb = {c: plain[8 + 56*i: 8 + 56*(i+1)] for i, c in enumerate('ABCD')}
    stored = plain[:8] + b''.join(pb[order[k]] for k in range(4))
    return crypt(stored, ec, 8, 0xE8)
def make(template_stored, species, form=0, female=False, genderless=False):
    p = decrypt(template_stored)
    struct.pack_into('<H', p, 8, species)
    p[0x1D] = (p[0x1D] & 1) | (2 if female else 0) | (4 if genderless else 0) | (form << 3)
    return encrypt(p)
