"""One PokeDaisy collection, two native libretro cores, separate settings namespaces."""
from pathlib import Path
import argparse,shutil,subprocess,tempfile,zipfile
p=argparse.ArgumentParser()
p.add_argument('gb_core',type=Path);p.add_argument('ds_core',type=Path);p.add_argument('output',type=Path)
p.add_argument('--strip',required=True);p.add_argument('--source-ref',required=True)
a=p.parse_args();here=Path(__file__).resolve().parent;root=here.parents[1]
platforms=['Game Boy','Game Boy Color','Game Boy Advance','Nintendo DS']
with tempfile.TemporaryDirectory() as temp:
 stage=Path(temp);base=stage/'Emus/tg5040';pak=base/'PDAI.pak';pak.mkdir(parents=True)
 for source,name in [(a.gb_core,'pokedaisy_libretro.so'),(a.ds_core,'pokedaisy_ds_libretro.so')]:
  subprocess.run([a.strip,'--strip-unneeded','-o',str(pak/name),str(source.resolve())],check=True)
 shutil.copyfile(here/'launch.sh',pak/'launch.sh');(pak/'launch.sh').chmod(0o755)
 shutil.copyfile(here/'default.cfg',pak/'default.cfg')
 for label in platforms:
  folder=stage/'Roms/PokeDaisy (PDAI)'/label;folder.mkdir(parents=True)
  (folder/'.keep').touch()
 shutil.copyfile(here/'README.md',stage/'README.md')
 notices=pak/'licenses';notices.mkdir()
 shutil.copyfile(root/'LICENSE',notices/'PokeDaisy-GPL-3.0.txt');shutil.copyfile(root/'NOTICE',notices/'PokeDaisy-NOTICE.txt')
 for module in ['dsperate-lr','mgba']:
  tree=root/'third_party'/module
  for f in sorted(tree.rglob('*')):
   if f.is_file() and f.name.lower().startswith(('license','copying','notice')):
    dest=notices/module/f.relative_to(tree);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,dest)
 (pak/'SOURCE.txt').write_text('Integration: https://github.com/thwonp/pokedaisy-lr\nRef: '+a.source_ref+'\n'
  'GB/GBC/GBA: libretro/ and third_party/mgba.\n'
  'DS: third_party/dsperate-lr pinned v3.3.0-lr.1, initialized recursively.\nBuild: linux/libretro-unified/README.md\n')
 a.output.parent.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED) as z:
  for f in sorted(stage.rglob('*')):
   if f.is_file():z.write(f,f.relative_to(stage))
print(a.output)
