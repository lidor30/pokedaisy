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
          ['saveDataPtr', 'trainerOff', 'partyOff', 'bagOff', 'locationOff', 'pokedexOff', 'nationalDexOff', 'repelStepsOff']}
battle_defaults = reader.split('data class Gen4Battle(', 1)[1].split('\n)', 1)[0]
battle_config = config.split('battle = Gen4Battle(', 1)[1].split(')', 1)[0]
for name in ('fieldSystemPtr', 'battleTemplate'):
    fields['battle_' + name] = number(battle_config, name)
for name in ('offChild', 'offAppData', 'offBattleType', 'offBattleCtx', 'offBattleMons'):
    fields['battle_' + name] = number(battle_defaults, name)
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
unova = (data / 'Gen5TablesBlack.kt').read_text()
johto = (data / 'Gen4TablesHeartGold.kt').read_text()
unova_items = (data / 'Gen5ItemsBlack.kt').read_text()
sequels = (data / 'Gen5TablesBlack2.kt').read_text()
maps['unovaSpecies'] = string_map('speciesNamesBlack', source=unova)
maps['unovaMoves'] = string_map('moveDataBlack', True, unova)
maps['johtoItems'] = string_map('itemNamesHeartGold', source=johto)
maps['johtoPlaces'] = string_map('mapPlacesHeartGold', source=johto)
maps['unovaItems'] = string_map('itemNamesBlack', source=unova_items)
maps['sequelItems'] = string_map('itemNamesBlack2Added', source=sequels)

johto_config = reader.split(') = Gen4Config(', 1)[1].split('\n)', 1)[0]
gen5 = (data / 'Gen5Reader.kt').read_text()
for name in ('bodyOff', 'partyOff', 'trainerOff', 'bagOff', 'locationOff', 'pokedexOff', 'nationalDexOff', 'repelStepsOff'):
    fields['johto_' + name] = number(johto_config, name)
fields['johto_saveDataPtr'] = number(reader, 'HEARTGOLD_SAVE_DATA_PTR')
for name in ('partyOff', 'trainerOff', 'bagOff', 'locationOff', 'offBackupSystem', 'offImage', 'moneyOff', 'badgesOff', 'pokedexOff', 'repelStepsOff'):
    fields['unova_' + name] = number(gen5, name)
fields['unova_saveControlPtr'] = number(gen5, 'saveControlPtr')
fields['unova_monSize'] = number(gen5, 'GEN5_MON_SIZE')
out = ['// Generated by linux/dsperate/gen_data.py from Android app-owned data. GPL-3.0.',
       '#pragma once', '#include <cstdint>',
       'namespace pd_ds_data {', f'inline constexpr char sha1[] = "{sha}";']
for key, title in [('heartGold', 'HeartGold'), ('soulSilver', 'SoulSilver'), ('black', 'Black'),
                   ('white', 'White'), ('black2', 'Black 2'), ('white2', 'White 2')]:
    digest = re.search(r'"([a-f0-9]{40})" to "Pokémon '+title+r'"', identity)[1]
    out.append(f'inline constexpr char {key}Sha1[] = "{digest}";')
out += [f'inline constexpr {"int32_t" if value < 0 else "uint32_t"} {name} = {value};' for name, value in fields.items()]
out += ['inline constexpr int pockets[] = {'+', '.join(map(str, pockets))+'};',
        'inline constexpr char orders[][5] = {'+', '.join(json.dumps(x) for x in orders)+'};']
for key, src in [('johto', johto_config), ('unova', gen5)]:
    values = re.search(r'pocketSizes[^\n]*intArrayOf\(([^)]*)\)', src)[1]
    out.append(f'inline constexpr int {key}Pockets[] = {{{values}}};')
for key, src, var in [('johto', johto, 'HeartGold'), ('unova', unova_items, 'Black'),
                      ('sequel', sequels, 'Black2')]:
    values = re.search(r'val iconPalettes'+var+r': ByteArray = byteArrayOf\((.*?)\n\)', src, re.S)[1]
    out.append(f'inline constexpr uint8_t {key}IconPalettes[] = {{{values}}};')
for key, src, var in [('platinum', tables, 'Platinum'), ('johto', johto, 'HeartGold'),
                      ('unova', unova_items, 'Black'), ('sequel', sequels, 'Black2')]:
    pairs = re.search(r'val itemIcons'+var+r': Map<Int, Int> = mapOf\((.*?)\n\)', src, re.S)[1]
    names = maps[{'platinum': 'items', 'johto': 'johtoItems', 'unova': 'unovaItems', 'sequel': 'unovaItems'}[key]]
    balls = {i for i, name in names.items() if name.endswith(' Ball')
             and name not in ('Smoke Ball', 'Light Ball', 'Iron Ball')}
    members = [(int(i), value) for i, value in re.findall(r'(\d+) to (0x[0-9A-Fa-f]+|\d+)', pairs) if int(i) in balls or names.get(int(i)) == "Repel"]
    assert all(any(i == n for i, _ in members) for n in (1, 2, 3, 4))
    out.append('inline constexpr uint32_t '+key+'HudItemArt[][2] = {'+
               ','.join('{'+str(i)+','+value+'}' for i,value in members)+'};')
for key, src, suffix in [('unova', unova, 'BLACK'), ('sequel', sequels, 'BLACK2')]:
    bank = src.split('private const val MAP_PLACE_IDS_'+suffix+' =', 1)[1].split('\n\n', 1)[0]
    ids = ''.join(re.findall(r'"([\d,]+)"', bank)).split(',')
    places = string_map('placeNamesBlack'+('2' if key=='sequel' else ''), source=src)
    maps[key+'Places'] = {i:places.get(int(n),'') for i,n in enumerate(ids) if n}
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
johto_town = (data / 'Gen4TownMapHeartGold.kt').read_text()
pg = (data / 'PokegearMap.kt').read_text()
pg_fields = re.search(r'data class Gen4Pokegear\((.*?)\n\)', reader, re.S)[1]
for key in ('offPlayerAvatar','offAvatarMapObject','offObjectX','offObjectZ','offSpecialSpawn'):
    out.append(f'inline constexpr int pg_{key} = {number(pg_fields,key)};')
out.append(f"inline constexpr int pg_fieldSystemPtr = {number(johto_config.split('pokegear =')[1], 'fieldSystemPtr')};")
out.append(f"inline constexpr int pg_wordOff = {number(johto_config, 'pokegearWordOff')};")
out.append(f"inline constexpr int pg_flagsOff = {number(johto_config, 'varsFlagsOff') + 2*number(johto_config, 'numVars')};")
out.append(f"inline constexpr int pg_firstFlag = {number(pg,'FIRST_FLYPOINT_FLAG')};")
pg_grid = bytes.fromhex(''.join(re.findall(r'"([A-F0-9]+)"',johto_town.split('const val POKEGEAR_GRID_HEARTGOLD =')[1].split('/**')[0])))
assert len(pg_grid)==47*20*2
out.append('inline constexpr uint16_t pgGrid[] = {'+','.join(str(int.from_bytes(pg_grid[i:i+2],'little')) for i in range(0,len(pg_grid),2))+'};')
pg_headers = re.search(r'mainMatrixHeadersHeartGold: Set<Int> = setOf\((.*?)\n\)',johto_town,re.S)[1]
pg_headers = re.sub(r'//[^\n]*','',pg_headers)
out.append('inline constexpr int pgHeaders[] = {'+','.join(re.findall(r'\d+',pg_headers))+'};')
pg_world = re.findall(r'(\d+) to \((\d+) to (\d+)\)',johto_town.split('val worldMapHeartGold')[1].split('\n)')[0])
out.append('inline constexpr int pgWorld[][3] = {'+','.join('{'+','.join(row)+'}' for row in pg_world)+'};')
pg_covers = re.findall(r'intArrayOf\(([0-9, ]+)\)',johto_town.split('val pokegearCoversHeartGold')[1].split('\n)')[0])
out.append('inline constexpr int pgCovers[][8] = {'+','.join('{'+row+'}' for row in pg_covers)+'};')
pg_names = string_map('placeNamesHeartGold',source=johto_town)
out.append('inline constexpr const char* pgPlaces[] = {'+', '.join(json.dumps(pg_names.get(i,''),ensure_ascii=True) for i in range(max(pg_names)+1))+'};')
for suffix,key in [('Black','unova'),('Black2','sequel')]:
    src = (data / f'Gen5TownMap{suffix}.kt').read_text()
    points = re.findall(r'Gen5TownMapPlace\((-?\d+), (-?\d+), (\d+), (\d+), (\d+), (-?\d+)(?:, shownBy = (0x[0-9a-fA-F]+|-?\d+))?\)',src)
    assert len(points)==src.count('    Gen5TownMapPlace(')
    zones = ''.join(re.findall(r'"([-0-9,]+)"',src.split('private const val TOWN_MAP_ZONES_')[1]))
    assert zones
    out.append('inline constexpr int '+key+'MapPoints[][7] = {'+','.join('{'+','.join((*row[:-1],row[-1] or '-1'))+'}' for row in points)+'};')
    out.append('inline constexpr int '+key+'MapZones[] = {'+zones+'};')
out += ['struct Char { uint16_t code; const char* text; };',
        'inline constexpr Char chars[] = {'+', '.join('{'+str(k)+', '+json.dumps(v)+'}' for k,v in chars.items())+'};', '}']
# Summary metadata comes from the same app-owned Platinum tables as names.
move_meta = {int(i):(int(t),int(p)) for i,t,p in re.findall(r'(\d+) to MoveInfo\("(?:\\.|[^"\\])*", (\d+), (\d+)\)', tables)}
type_meta = {int(i):(int(a),int(b)) for i,a,b in re.findall(r'(\d+) to SpeciesTypes\((\d+), (\d+)\)', tables)}
assert len(move_meta)==467 and len(type_meta)==493
out.insert(-1, 'inline constexpr int moveMeta[][2] = {'+', '.join('{'+str(move_meta.get(i,(-1,0))[0])+','+str(move_meta.get(i,(-1,0))[1])+'}' for i in range(max(move_meta)+1))+'};')
out.insert(-1, 'inline constexpr int speciesTypes[][2] = {'+', '.join('{'+str(type_meta.get(i,(-1,-1))[0])+','+str(type_meta.get(i,(-1,-1))[1])+'}' for i in range(max(type_meta)+1))+'};')
unova_types = {int(i): int(t) for i,t in re.findall(r'(\d+) to MoveInfo\("(?:\\.|[^"\\])*", (\d+), \d+\)', unova)}
assert len(unova_types) == len(maps['unovaMoves']) == 559
out.insert(-1, 'inline constexpr int unovaTypes[] = {'+', '.join(str(unova_types.get(i,-1)) for i in range(max(unova_types)+1))+'};')
unova_species_types = {int(i):(int(a),int(b)) for i,a,b in re.findall(r'(\d+) to SpeciesTypes\((\d+), (\d+)\)', unova)}
assert len(unova_species_types) == 649
out.insert(-1, 'inline constexpr int unovaSpeciesTypes[][2] = {{-1,-1},'+
           ','.join('{%d,%d}' % unova_species_types[i] for i in range(1,650))+'};')
generated = '\n'.join(out)+'\n'
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
dest = args.output
dest.write_text(generated)
