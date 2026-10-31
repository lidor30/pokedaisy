"""Package a Linux executable and its existing license notices, never game data.

--portmaster lays it out as a PortMaster port instead: PokeDaisy.sh beside a pokedaisy/ folder."""
from pathlib import Path
import argparse, shutil, subprocess, zipfile
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('binary',type=Path);p.add_argument('output',type=Path)
p.add_argument('--portmaster',action='store_true',help='a PortMaster port: PokeDaisy.sh + pokedaisy/')
p.add_argument('--dsperate',action='store_true',help='label the opt-in DS experiment and include its notices')
a=p.parse_args();repo=Path(__file__).resolve().parents[1]
header=a.binary.read_bytes()[:20]
if header[:6] != b'\x7fELF\x02\x01':p.error('Expected a 64-bit little-endian Linux ELF')
arch=int.from_bytes(header[18:20],'little')
if arch not in (62,183):p.error('Supported package targets: x86_64 or AArch64')
name=('pokedaisy-portmaster-' if a.portmaster else 'pokedaisy-linux-')+('aarch64' if arch==183 else 'x86_64')
if a.dsperate:
    if b'DSperate %s' not in a.binary.read_bytes():p.error('Expected a POCKET_DSPERATE build')
    name+='-ds-experiment'
a.output.mkdir(parents=True,exist_ok=True);top=a.output/name
top.mkdir() # Refuse to overwrite an earlier package.
# A PortMaster port is its launcher in the ports folder beside the port's own folder.
dest=top/'pokedaisy' if a.portmaster else top
dest.mkdir(exist_ok=True)
launcher=(repo/'linux/portmaster/PokeDaisy.sh',top/'PokeDaisy.sh') if a.portmaster else (repo/'linux/launch.sh',dest/'launch.sh')
for source,target in [(a.binary,dest/'pokedaisy-linux'),launcher,
                      (repo/'linux/README.md',dest/'README.md'),
                      (repo/'LICENSE',dest/'LICENSE'),(repo/'NOTICE',dest/'NOTICE')]:
    shutil.copy2(source,target)
(dest/'pokedaisy-linux').chmod(0o755);launcher[1].chmod(0o755)
if not a.portmaster:
    # The TrimUI menu's app entry (Apps/<folder>/config.json): its name and its icon (linux/make_icon.py).
    shutil.copy2(repo/'linux/icon.png',dest/'icon.png')
    (dest/'config.json').write_text('{\n  "label": "PokeDaisy",\n  "icon": "icon.png",\n  "iconsel": "icon.png",\n'
                                    '  "launch": "launch.sh",\n  "description": "Pokemon companion and emulator"\n}\n')
licenses=dest/'licenses';licenses.mkdir()
for path,license_name in [('LICENSE','mGBA-MPL-2.0.txt'),('src/third-party/blip_buf/license.txt','blip_buf-LGPL-2.1.txt'),('src/third-party/inih/LICENSE.txt','inih-BSD.txt')]:
    shutil.copy2(repo/'third_party/mgba'/path,licenses/license_name)
(licenses/'PixelOperator.txt').write_text('Pixel Operator by Jayvee Enaguas. CC0. See NOTICE.\n')
if a.dsperate:
    ds=repo/'third_party/dsperate'
    for source,target in [('LICENSE','DSperate-GPL-3.0.txt'),
            ('src/core/bios/LICENSE.freebios','FreeBIOS-BSD.txt'),
            ('src/core/cart/miniz/LICENSE','miniz-MIT.txt'),
            ('src/core/io/dsi_font/LICENSE-WenQuanYi-MicroHei.txt','WenQuanYi-MicroHei.txt'),
            ('src/core/io/dsi_font/LICENSE-NotoSans-OFL-1.1.txt','NotoSans-OFL-1.1.txt')]:
        shutil.copy2(ds/source,licenses/target)
    shutil.copy2(repo/'linux/dsperate/README.md',dest/'DS-EXPERIMENT.md')
sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
core=subprocess.check_output(['git','rev-parse','HEAD:third_party/mgba'],cwd=repo,text=True).strip()
(dest/'SOURCE.txt').write_text(f'Source: https://github.com/lidor30/pokedaisy\nFork: https://github.com/keeganwhitacre/pokedaisy-brick\nCommit: {sha}\nmGBA: {core}\nBuild: linux/README.md at this commit\n')
if a.dsperate:
    ds_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo/'third_party/dsperate',text=True).strip()
    with (dest/'SOURCE.txt').open('a') as f:
        f.write(f'EXPERIMENTAL DS backend: https://github.com/beebono/DSperate\nDSperate: {ds_commit}\nBuild: linux/dsperate/README.md\n')
archive=a.output/(name+'.zip')
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for f in sorted(top.rglob('*')):
        if f.is_file():z.write(f,f.relative_to(a.output))
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    assert not any(n.lower().endswith(('.gb','.gbc','.gba','.sav','.ss','.srm','.bin')) for n in z.namelist())
print(archive)
