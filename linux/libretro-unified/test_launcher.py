from pathlib import Path
import os,subprocess,tempfile,unittest,shutil
HERE=Path(__file__).resolve().parent
class Dispatch(unittest.TestCase):
 def test_platform_dispatch_and_original_paths(self):
  with tempfile.TemporaryDirectory(prefix='PokeDaisy test ') as d:
   root=Path(d);pak=root/'Emus/tg5040/PDAI.pak';pak.mkdir(parents=True)
   shutil.copyfile(HERE/'launch.sh',pak/'launch.sh');(pak/'launch.sh').chmod(0o755)
   for core in ['pokedaisy_gba_libretro.so','pokedaisy_gbc_libretro.so','pokedaisy_ds_libretro.so']:(pak/core).touch()
   bindir=root/'bin';bindir.mkdir();fake=bindir/'minarch.elf';fake.write_text('#!/bin/sh\nprintf "%s\\n%s\\n" "$1" "$2"\n');fake.chmod(0o755)
   env=dict(os.environ,USERDATA_PATH=str(root),LOGS_PATH=str(root),PATH=str(bindir)+':'+os.environ['PATH'])
   collection=root/'Roms/PokeDaisy (PDAI)';collection.mkdir(parents=True)
   for ext in ['gb','GBC','gBa','NDS','Nintendo DS/legacy.nds']:
    rom=collection/('Pokemon with spaces.'+ext if '/' not in ext else ext)
    rom.parent.mkdir(exist_ok=True);rom.touch()
    subprocess.run(['sh',str(pak/'launch.sh'),str(rom)],env=env,check=True)
    lines=(root/'pokedaisy-unified.txt').read_text().splitlines()
    core={'.nds':'pokedaisy_ds_libretro.so','.gba':'pokedaisy_gba_libretro.so'}.get(rom.suffix.lower(),'pokedaisy_gbc_libretro.so')
    self.assertEqual(lines,[str(pak/core),str(rom)])
   bad=root/'archive.zip';bad.touch()
   self.assertEqual(subprocess.run(['sh',str(pak/'launch.sh'),str(bad)],env=env,capture_output=True).returncode,2)
   self.assertEqual(subprocess.run(['sh',str(pak/'launch.sh'),str(root/'missing.nds')],env=env,capture_output=True).returncode,1)
if __name__=='__main__':unittest.main()
