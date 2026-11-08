#!/usr/bin/env python3
"""gb_addr_map.py BASE_ROM HACK_ROM ADDR...: where a Game Boy hack keeps the RAM a known game's config reads.

Both ROMs are swept as LR35902 code per 16 KB bank and reduced to their opcode sequences (operands dropped, so a hack's
shifted addresses next to a reference don't break the match). Each instruction in BASE that loads / stores / points at
ADDR (ld a,[nn]; ld [nn],a; ld hl|de|bc,nn) is looked up as a +-7 opcode window in HACK; a single match gives the
address HACK uses there. Prints, per ADDR, the references found and the votes (address, count). Used for Yume Blue,
Crystal Legacy and Ambrosia (docs/PLORPOS_V0.1.md): unanimous or near-unanimous votes are trusted, few votes are not.
Example: gb_addr_map.py 'Pokemon - Blue Version.gb' 'Pokemon Yume - Blue Version (v1.0).gb' D163 D16B D347
"""
import sys,collections
L2={0x06,0x0E,0x16,0x1E,0x26,0x2E,0x36,0x3E,0x18,0x20,0x28,0x30,0x38,0xE0,0xF0,0xC6,0xCE,0xD6,0xDE,0xE6,0xEE,0xF6,0xFE,0xE8,0xF8,0x10,0xCB}
L3={0x01,0x11,0x21,0x31,0x08,0xC2,0xC3,0xC4,0xCA,0xCC,0xCD,0xD2,0xD4,0xDA,0xDC,0xEA,0xFA}
ABS={0xFA,0xEA,0x21,0x11,0x01}
def sweep(b):
    ops=bytearray(); offs=[]
    for bank in range(0,len(b),0x4000):
        i=bank; end=min(bank+0x4000,len(b))
        while i<end:
            o=b[i]; offs.append(i)
            if o==0xCB: ops.append(0xCB); i+=2; continue
            ops.append(o); i+= 3 if o in L3 else 2 if o in L2 else 1
    return bytes(ops),offs
def run(base,hack,addrs,W=7):
    bo,boffs=sweep(base); ho,hoffs=sweep(hack)
    res={}
    for a in addrs:
        lo,hi=a&0xFF,a>>8; votes=collections.Counter(); refs=0
        for k,off in enumerate(boffs):
            if base[off] in ABS and base[off+1]==lo and base[off+2]==hi and k>=W and k+W<len(bo):
                refs+=1
                pat=bo[k-W:k+W+1]
                hits=[]; j=ho.find(pat)
                while j!=-1 and len(hits)<3: hits.append(j); j=ho.find(pat,j+1)
                if len(hits)==1:
                    h=hoffs[hits[0]+W]
                    if hack[h] in ABS: votes[hack[h+1]|(hack[h+2]<<8)]+=1
        res[a]=(refs,votes.most_common(3))
    return res
if __name__=='__main__':
    base=open(sys.argv[1],'rb').read(); hack=open(sys.argv[2],'rb').read()
    for a,(n,v) in run(base,hack,[int(x,16) for x in sys.argv[3:]]).items(): print(hex(a),n,[(hex(k),c) for k,c in v])
