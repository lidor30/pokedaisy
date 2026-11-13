"""One PokeDaisy collection for NXRedux (Emus/tg5040/PDAI.pak + Roms/PokeDaisy (PDAI)/), three native libretro cores,
separate settings namespaces. --cores-only: the three cores and their notices alone, in a folder named after the zip
(the release's pokedaisy-libretro-<arch>.zip, for other frontends)."""
from pathlib import Path
import argparse,shutil,subprocess,tempfile,zipfile
p=argparse.ArgumentParser()
p.add_argument('gbc_core',type=Path);p.add_argument('gba_core',type=Path);p.add_argument('ds_core',type=Path);p.add_argument('output',type=Path)
p.add_argument('--strip',required=True);p.add_argument('--source-ref',required=True)
p.add_argument('--cores-only',action='store_true',help='no NXRedux launcher or ROM folder: the cores and notices in one folder')
a=p.parse_args();here=Path(__file__).resolve().parent;root=here.parents[1]
with tempfile.TemporaryDirectory() as temp:
 stage=Path(temp);pak=stage/a.output.stem if a.cores_only else stage/'Emus/tg5040/PDAI.pak';pak.mkdir(parents=True)
 for source,name in [(a.gbc_core,'pokedaisy_gbc_libretro.so'),(a.gba_core,'pokedaisy_gba_libretro.so'),(a.ds_core,'pokedaisy_ds_libretro.so')]:
  subprocess.run([a.strip,'--strip-unneeded','-o',str(pak/name),str(source.resolve())],check=True)
 if not a.cores_only:
  shutil.copyfile(here/'launch.sh',pak/'launch.sh');(pak/'launch.sh').chmod(0o755)
  shutil.copyfile(here/'default.cfg',pak/'default.cfg')
  folder=stage/'Roms/PokeDaisy (PDAI)';folder.mkdir(parents=True)
  (folder/'.keep').touch()
  shutil.copyfile(here/'README.md',stage/'README.md')
 notices=pak/'licenses';notices.mkdir()
 shutil.copyfile(root/'LICENSE',notices/'PokeDaisy-GPL-3.0.txt');shutil.copyfile(root/'NOTICE',notices/'PokeDaisy-NOTICE.txt')
 for module in ['dsperate-lr','mgba','armgba','zlib']:
  tree=root/'third_party'/module
  for f in sorted(tree.rglob('*')):
   if f.is_file() and (f.name.lower().startswith(('license','copying','notice')) or f.parent.name=='LICENSES'):
    dest=notices/module/f.relative_to(tree);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,dest)
 (pak/'SOURCE.txt').write_text('Source: https://github.com/lidor30/pokedaisy\nRef: '+a.source_ref+'\n'
  'GB/GBC: libretro/ and third_party/mgba. GBA: libretro/armgba/, third_party/armgba and third_party/zlib.\n'
  'DS: third_party/dsperate-lr pinned v3.3.0-lr.1, initialized recursively.\nBuild: linux/libretro-unified/README.md\n')
 a.output.parent.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED) as z:
  for f in sorted(stage.rglob('*')):
   if f.is_file():z.write(f,f.relative_to(stage))
print(a.output)
