"""The real pokedaisy-linux on the diagnostic ROMs (make_diagnostic.py), headless, over all three cores:
test_host.py BINARY CORES_DIR DIAGNOSTICS_DIR. The GB ROM runs on pokedaisy_gbc and writes a battery-save marker,
which has to reach DATA/saves; the GBA ROM runs on pokedaisy_gba (armGBA) and fills its screen red, which has to
reach the window. The DS ROM fills its top screen green. A second run backs the save up, and the game list draws with no ROM given."""
from pathlib import Path
import os,shutil,struct,subprocess,sys,tempfile,time,zlib

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
    for rom in ('diagnostic.gb','diagnostic.gba','diagnostic.nds'):shutil.copy(diag/rom,d/'roms'/rom)
    # The GB ROM on the mGBA core: its save marker written to DATA/saves.
    run(d,str(d/'roms/diagnostic.gb'),'--frames','120','--shot',str(d/'gb.png'))
    sav=d/'saves/diagnostic.gb.sav';assert sav.exists() and sav.read_bytes()[:1]==b'\x5a',sav
    w,h,bpp,rows=png_pixels(d/'gb.png');assert len({r for r in rows})>1,'the GB frame is one colour'
    # Run again: the save is backed up first.
    run(d,str(d/'roms/diagnostic.gb'),'--frames','10')
    backups=list((d/'saves/pokedaisy-backups').glob('diagnostic.gb.backup-*.sav'));assert len(backups)==1,backups
    # A Game Boy cartridge with a clock (MBC3, as Crystal's): the clock is kept after the save, mGBA's layout.
    rtc=bytearray((diag/'diagnostic.gb').read_bytes());rtc[0x147]=0x10;rtc[0x14d]=(-sum(rtc[0x134:0x14d])-25)&255
    (d/'roms/clock.gb').write_bytes(rtc)
    run(d,str(d/'roms/clock.gb'),'--frames','120')
    clock=(d/'saves/clock.gb.sav').read_bytes();assert len(clock)==8192+48 and clock[:1]==b'\x5a',len(clock)
    assert any(clock[8192:]),'the clock bytes are empty'
    # Each run moves the clock behind the save: that alone makes no new backup (only the save data counts).
    run(d,str(d/'roms/clock.gb'),'--frames','10');time.sleep(1.1);run(d,str(d/'roms/clock.gb'),'--frames','10')
    kept=list((d/'saves/pokedaisy-backups').glob('clock.gb.backup-*.sav'));assert len(kept)==1,kept
    # The GBA ROM on the armGBA core: its grey / olive one-pixel stripes reach the window.
    log=run(d,str(d/'roms/diagnostic.gba'),'--frames','120','--shot',str(d/'gba.png'))
    w,h,bpp,rows=png_pixels(d/'gba.png')
    row=rows[h//2];seen={tuple(row[i:i+3]) for i in range(0,w*bpp,bpp)}
    assert any(r>120 and g>120 and b>120 for r,g,b in seen) and any(r>120 and g>120 and b<60 for r,g,b in seen),(seen,log[-500:])
    # DS maps the ROM from its path, so it also works above the host's 64 MiB buffer limit.
    with (d/'roms/diagnostic.nds').open('r+b') as nds:nds.truncate(65 << 20)
    # Select DS from the sorted list, save a state through the host menu, and return (QUIT asks: CANCEL, then QUIT).
    env['POCKET_KEYS']='10:down,20:down,30:down,40:a,70:menu,80:down,90:a,100:menu,110:up,120:a,130:down,140:a'
    run(d,'--frames','65')
    del env['POCKET_KEYS']
    assert (d/'states/diagnostic.nds.ss').exists(),'DS did not launch from the game list'
    run(d,str(d/'roms/diagnostic.nds'),'--frames','90','--shot',str(d/'ds.png'))
    w,h,bpp,rows=png_pixels(d/'ds.png')
    mid=rows[h//2][(w//2)*bpp:(w//2)*bpp+3]
    assert mid[0]<60 and mid[1]>200 and mid[2]<60,mid
    # L2 must reach DS Companion even though GB/GBA use that button for host rewind.
    env['POCKET_KEYS']='25:l2'
    run(d,str(d/'roms/diagnostic.nds'),'--frames','55','--shot',str(d/'ds-companion.png'))
    del env['POCKET_KEYS']
    _,_,_,companion=png_pixels(d/'ds-companion.png')
    assert companion!=rows,'L2 did not open the DS Companion'
    env['POCKET_KEYS']='25:r2'
    run(d,str(d/'roms/diagnostic.nds'),'--frames','55','--shot',str(d/'ds-bottom.png'))
    del env['POCKET_KEYS']
    w,h,bpp,bottom=png_pixels(d/'ds-bottom.png')
    assert min(bottom[h//2][(w//2)*bpp:(w//2)*bpp+3])>200,'R2 did not switch to the DS bottom screen'
    for chord in ('r2','l2'):
        env['POCKET_KEYS']=f'25:select:4,26:{chord}:12'
        run(d,str(d/'roms/diagnostic.nds'),'--frames','55','--shot',str(d/'ds-chord.png'))
        del env['POCKET_KEYS']
        _,_,_,chord_rows=png_pixels(d/'ds-chord.png')
        # The host can overlay a rewind-disabled notice. The DS game remains on its top screen.
        r,g,b=chord_rows[3*h//8][(w//2)*bpp:(w//2)*bpp+3]
        assert r<60 and g>100 and b<60,chord
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
    # The hotkey requires the modifier: Select + R2 now saves the state.
    (d/'states/diagnostic.gb.ss').unlink()
    env['POCKET_KEYS']='30:r2'
    run(d,str(d/'roms/diagnostic.gb'),'--frames','60')
    assert not (d/'states/diagnostic.gb.ss').exists(),'R2 alone saved a state'
    env['POCKET_KEYS']='25:select:12,30:r2'
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
