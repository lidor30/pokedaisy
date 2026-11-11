"""The real pokedaisy-linux on the diagnostic ROMs (make_diagnostic.py), headless, over both cores:
test_host.py BINARY CORES_DIR DIAGNOSTICS_DIR. The GB ROM runs on pokedaisy_gbc and writes a battery-save marker,
which has to reach DATA/saves; the GBA ROM runs on pokedaisy_gba (armGBA) and fills its screen red, which has to
reach the window. A second run backs the save up, and the game list draws with no ROM given."""
from pathlib import Path
import os,shutil,struct,subprocess,sys,tempfile,zlib

binary,cores,diag=(Path(a).resolve() for a in sys.argv[1:4])

def png_pixels(path):
    data=path.read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n',path
    pos,idat=8,b''
    while pos<len(data):
        n,kind=struct.unpack('>I4s',data[pos:pos+8]);body=data[pos+8:pos+8+n];pos+=12+n
        if kind==b'IHDR':w,h,depth,color=struct.unpack('>IIBB',body[:10])
        if kind==b'IDAT':idat+=body
    assert depth==8 and color in (2,6),(depth,color)
    bpp=3 if color==2 else 4;raw=zlib.decompress(idat);stride=w*bpp;rows=[];prev=bytearray(stride)
    for y in range(h):
        f=raw[y*(stride+1)];line=bytearray(raw[y*(stride+1)+1:(y+1)*(stride+1)])
        for i in range(stride):
            a=line[i-bpp] if i>=bpp else 0;b=prev[i];c=prev[i-bpp] if i>=bpp else 0
            if f==1:line[i]=(line[i]+a)&255
            elif f==2:line[i]=(line[i]+b)&255
            elif f==3:line[i]=(line[i]+(a+b)//2)&255
            elif f==4:
                p=a+b-c;pa,pb,pc=abs(p-a),abs(p-b),abs(p-c)
                line[i]=(line[i]+(a if pa<=pb and pa<=pc else b if pb<=pc else c))&255
        rows.append(bytes(line));prev=line
    return w,h,bpp,rows

env=dict(os.environ,POCKET_CORE_DIR=str(cores),SDL_AUDIODRIVER='dummy')
env.setdefault('SDL_VIDEODRIVER','dummy');env.setdefault('SDL_RENDER_DRIVER','software')
def run(data,*args):
    r=subprocess.run([str(binary),'--data',str(data),*args],env=env,capture_output=True,text=True,timeout=60)
    assert r.returncode==0,(args,r.returncode,r.stderr[-2000:])
    return r.stderr

with tempfile.TemporaryDirectory(prefix='pd-host-') as d:
    d=Path(d);(d/'roms').mkdir()
    for rom in ('diagnostic.gb','diagnostic.gba'):shutil.copy(diag/rom,d/'roms'/rom)
    # The GB ROM on the mGBA core: its save marker written to DATA/saves.
    run(d,str(d/'roms/diagnostic.gb'),'--frames','120','--shot',str(d/'gb.png'))
    sav=d/'saves/diagnostic.gb.sav';assert sav.exists() and sav.read_bytes()[:1]==b'\x5a',sav
    w,h,bpp,rows=png_pixels(d/'gb.png');assert len({r for r in rows})>1,'the GB frame is one colour'
    # Run again: the save is backed up first.
    run(d,str(d/'roms/diagnostic.gb'),'--frames','10')
    backups=list((d/'saves/pokedaisy-backups').glob('diagnostic.gb.sav.*.sav'));assert len(backups)==1,backups
    # A Game Boy cartridge with a clock (MBC3, as Crystal's): the clock is kept after the save, mGBA's layout.
    rtc=bytearray((diag/'diagnostic.gb').read_bytes());rtc[0x147]=0x10;rtc[0x14d]=(-sum(rtc[0x134:0x14d])-25)&255
    (d/'roms/clock.gb').write_bytes(rtc)
    run(d,str(d/'roms/clock.gb'),'--frames','120')
    clock=(d/'saves/clock.gb.sav').read_bytes();assert len(clock)==8192+48 and clock[:1]==b'\x5a',len(clock)
    assert any(clock[8192:]),'the clock bytes are empty'
    # The GBA ROM on the armGBA core: its red reaches the window.
    log=run(d,str(d/'roms/diagnostic.gba'),'--frames','120','--shot',str(d/'gba.png'))
    w,h,bpp,rows=png_pixels(d/'gba.png')
    mid=rows[h//2][(w//2)*bpp:(w//2)*bpp+3];assert mid[0]>200 and mid[1]<60 and mid[2]<60,(mid,log[-500:])
    # The host menu (Escape) with the keys scripted: DOWN to SAVE STATE, A writes the state file.
    env['POCKET_KEYS']='20:menu,30:down,40:a'
    run(d,str(d/'roms/diagnostic.gb'),'--frames','80')
    del env['POCKET_KEYS']
    assert (d/'states/diagnostic.gb.ss').exists(),'SAVE STATE from the host menu wrote nothing'
    # A/B BUTTONS (seven rows down from RESUME) is saved with the host's settings.
    env['POCKET_KEYS']='20:menu,'+','.join(f'{30+10*i}:down' for i in range(7))+',110:a'
    run(d,str(d/'roms/diagnostic.gb'),'--frames','140')
    del env['POCKET_KEYS']
    assert 'swap_ab=1' in (d/'host.ini').read_text(),(d/'host.ini').read_text()
    # HOTKEYS (three rows down): REWIND takes L3; SAVE STATE takes R2, which FAST FORWARD then loses.
    ini=(d/'host.ini').read_text();assert 'hk_ff=r2' in ini and 'hk_rewind=l2' in ini,ini
    steps=['menu']+['down']*3+['a','down','a','l3','down','a','r2']
    env['POCKET_KEYS']=','.join(f'{20+10*i}:{k}' for i,k in enumerate(steps))
    run(d,str(d/'roms/diagnostic.gb'),'--frames',str(40+10*len(steps)))
    del env['POCKET_KEYS']
    ini=(d/'host.ini').read_text()
    assert 'hk_rewind=l3' in ini and 'hk_savestate=r2' in ini and 'hk_ff=off' in ini and 'hk_loadstate=off' in ini,ini
    # The hotkey works in the game: R2 now saves the state.
    (d/'states/diagnostic.gb.ss').unlink()
    env['POCKET_KEYS']='30:r2'
    run(d,str(d/'roms/diagnostic.gb'),'--frames','60')
    del env['POCKET_KEYS']
    assert (d/'states/diagnostic.gb.ss').exists(),'the SAVE STATE hotkey wrote nothing'
    # No ROM: the game list.
    run(d,'--frames','5','--shot',str(d/'list.png'));png_pixels(d/'list.png')
    # A ROM no core plays is refused, not crashed on.
    (d/'roms/bad.txt').write_text('x')
    r=subprocess.run([str(binary),'--data',str(d),str(d/'roms/bad.txt'),'--frames','5'],env=env,capture_output=True,text=True,timeout=60)
    assert r.returncode==1 and 'Unsupported ROM' in r.stderr,r.stderr
print('host ok')
