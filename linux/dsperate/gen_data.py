"""Extract the experimental DS reader's data from the existing Android sources.

No ROM offsets are maintained separately. Fail rather than silently generate
different data when the upstream declarations change.
"""
from pathlib import Path
import argparse, json, re

root = Path(__file__).resolve().parents[2]
data = root / 'app/src/main/kotlin/com/pokedaisy/app/companion/data'
reader = (data / 'Gen4Reader.kt').read_text()
tables = (data / 'Gen4TablesPlatinum.kt').read_text()
config = re.search(r'val GEN4_PLATINUM_US = Gen4Config\((.*?)\n\)', reader, re.S)[1]
def number(text, name):
    return int(re.search(r'\b' + name + r'(?:\s*:\s*\w+)?\s*=\s*(0x[0-9a-fA-F]+|\d+)', text)[1], 0)
fields = {name: number(config, name) for name in
          ['saveDataPtr', 'trainerOff', 'partyOff', 'bagOff', 'locationOff', 'pokedexOff', 'nationalDexOff']}
dex = (data / 'Gen4Dex.kt').read_text()
fields['dexSize'] = int(re.search(r'val d = r.readCoreMemory\(body \+ off, (0x[0-9A-Fa-f]+)\)', dex)[1], 0)
fields['dexMagic'] = int(re.search(r'if \(magic != (0x[0-9A-Fa-f]+)L\)', dex)[1], 0)
fields['dexMax'] = int(re.search(r'val seen = \(1\.\.(\d+)\)', dex)[1])
assert 'bit(4 + 64, it)' in dex and 'bit(4, it)' in dex
fields['dexCaughtBase'] = 4
fields['dexSeenBase'] = 4 + 64
fields['bodyOff'] = number(reader, 'bodyOff')
fields['monSize'] = number(reader, 'GEN4_MON_SIZE')
fields['boxSize'] = number(reader, 'GEN4_BOX_SIZE')
orders = re.findall(r'"([ABCD]{4})"', re.search(r'GEN4_BLOCK_ORDERS = arrayOf\((.*?)\n\)', reader, re.S)[1])
assert len(orders) == 24
pockets = re.search(r'val pocketSizes: IntArray = intArrayOf\(([^)]*)\)', reader)[1]
pockets = [int(x.strip()) for x in pockets.split(',')]
def string_map(name, move=False, source=tables):
    body = re.search(r'val '+name+r':[^\n]*= mapOf\((.*?)\n\)', source, re.S)[1]
    pattern = r'(\d+) to '+(r'MoveInfo\(' if move else '')+r'("(?:\\.|[^"\\])*")'
    entries = {int(i): json.loads(s) for i, s in re.findall(pattern, body)}
    assert entries
    return entries
maps = {'species': string_map('speciesNamesPlatinum'),
        'moves': string_map('moveDataPlatinum', True),
        'items': string_map('itemNamesPlatinum'),
        'places': string_map('mapPlacesPlatinum')}
town = (data / 'Gen4TownMapPlatinum.kt').read_text()
art = (data / 'NdsArt.kt').read_text()
model = (data / 'RegionMapModel.kt').read_text().split('private val platinumTownMap:')[1].split('\n}')[0]
map_cfg = re.search(r'data class Gen4TownMap\((.*?)\n\)', reader, re.S)[1]
fields['fieldSystemPtr'] = number(config.split('townMap = Gen4TownMap(')[1], 'fieldSystemPtr')
for key in ['offLocation','offPlayerAvatar','offAvatarMapObject','offObjectX','offObjectZ','offExitLocation','varsOff','numVars','firstArrivalFlag']:
    fields['map_'+key] = number(map_cfg,key)
fields['mapTile'] = number(model,'tile')
crop = [int(n) for n in re.search(r'PLATINUM_MAP_CROP = intArrayOf\(([^)]*)\)',art)[1].split(',')]
fields.update(mapLeft=crop[0],mapTop=crop[1],mapWidth=crop[2]-crop[0],mapHeight=crop[3]-crop[1])
fields['mapOriginX'] = int(re.search(r'originX = (\d+) - crop',model)[1])-crop[0]
fields['mapOriginY'] = int(re.search(r'originY = (-?\d+) - crop',model)[1])-crop[1]
# Signed origins are emitted separately below.
map_grid = bytes.fromhex(''.join(re.findall(r'"([A-F0-9]+)"',town.split('const val TOWN_MAP_GRID_PLATINUM =')[1].split('/**')[0])))
assert len(map_grid)==1800
map_headers = [int(n) for n in re.findall(r'\d+',re.search(r'mainMatrixHeadersPlatinum: Set<Int> = setOf\((.*?)\n\)',town,re.S)[1])]
marks = re.findall(r'TownMapMark\((\d+), (\d+), (\d+), (\d+), (\d+), (true|false)\)',town)
assert len(marks)==20
maps['mapSections'] = string_map('placeNamesPlatinum',source=town)
map_art_section=art.split('private fun townMap(narc:')[1].split('private fun townMapBlack')[0]
asset_members=[int(n) for n in re.findall(r'narc.member\((\d+)\)',map_art_section.split('/**')[0])]
assert asset_members==[0,2,19,16,17,10,11]
screens=[int(n) for n in re.search(r'for \(screen in intArrayOf\(([^)]*)\)',map_art_section)[1].split(',')]
map_path=re.search(r'file\(f, header, "([^"]*tmap_gra.narc)"\)',art)[1]
icon_path = re.search(r'val iconPath = if \(platinum\) "([^"]+)"', art)[1]
icon_base = int(re.search(r'icon\(narc.member\((\d+) \+ species\)', art)[1])
icon_egg = int(re.search(r'private const val EGG = (\d+)', art)[1])
icon_palettes = [int(n) for n in re.findall(r'\d+', re.search(r'val iconPalettesPlatinum: ByteArray = byteArrayOf\((.*?)\n\)', tables, re.S)[1])]
assert len(icon_palettes) > icon_egg and max(icon_palettes) < 3
stats = (data / 'MonStats.kt').read_text()
natures = re.findall(r'"([^"\n]+)"', re.search(r'private val natureNames = listOf\((.*?)\n\)', stats, re.S)[1])
assert len(natures) == 25
maps['natures'] = dict(enumerate(natures))
charmap = (data / 'Gen4Charmap.kt').read_text()
def literal(name):
    return json.loads('"'+re.search(name+r'\s*=\s*"((?:\\.|[^"\\])*)"', charmap)[1]+'"')
codes, values = literal('GEN4_CHAR_CODES'), literal('GEN4_CHAR_VALUES').split('\0')
chars = {ord(c): v for c, v in zip(codes, values) if v and v.isascii() and v.isprintable()}
identity = (root / 'app/src/main/kotlin/com/pokedaisy/app/GameTitles.kt').read_text()
sha = re.search(r'"([a-f0-9]{40})" to "Pokémon Platinum"', identity)[1]
out = ['// Generated by linux/dsperate/gen_data.py from Android app-owned data. GPL-3.0.',
       '#pragma once', '#include <cstdint>',
       'namespace pd_ds_data {', f'inline constexpr char sha1[] = "{sha}";']
out += [f'inline constexpr {"int32_t" if value < 0 else "uint32_t"} {name} = {value};' for name, value in fields.items()]
out += ['inline constexpr int pockets[] = {'+', '.join(map(str, pockets))+'};',
        'inline constexpr char orders[][5] = {'+', '.join(json.dumps(x) for x in orders)+'};']
for name, entries in maps.items():
    out += [f'inline constexpr const char* {name}[] = {{'+', '.join(json.dumps(entries.get(i, ''), ensure_ascii=True) for i in range(max(entries)+1))+'};']
out += ['inline constexpr char iconPath[] = '+json.dumps(icon_path)+';',
        f'inline constexpr int iconBase = {icon_base}, iconEgg = {icon_egg};',
        'inline constexpr uint8_t iconPalettes[] = {'+', '.join(map(str,icon_palettes))+'};']
out += ['inline constexpr char mapPath[] = '+json.dumps(map_path)+';',
        'inline constexpr int mapAssets[] = {'+', '.join(map(str,asset_members))+'};',
        'inline constexpr int mapScreens[] = {'+', '.join(map(str,screens))+'};',
        'inline constexpr uint16_t mapGrid[] = {'+', '.join(str(int.from_bytes(map_grid[i:i+2],'little')) for i in range(0,len(map_grid),2))+'};',
        'inline constexpr int mainHeaders[] = {'+', '.join(map(str,map_headers))+'};',
        'struct MapMark {int x,y,shape,palette,arrival;bool hidden;};',
        'inline constexpr MapMark mapMarks[] = {'+', '.join('{'+','.join(m)+'}' for m in marks)+'};']
out += ['struct Char { uint16_t code; const char* text; };',
        'inline constexpr Char chars[] = {'+', '.join('{'+str(k)+', '+json.dumps(v)+'}' for k,v in chars.items())+'};', '}']
generated = '\n'.join(out)+'\n'
parser = argparse.ArgumentParser(); parser.add_argument('--check', action='store_true'); args=parser.parse_args()
dest = Path(__file__).with_name('data_gen.h')
if args.check:
    assert dest.read_text() == generated, 'Run python3 linux/dsperate/gen_data.py'
else:
    dest.write_text(generated)
print('PASS DS data matches Android source declarations')
