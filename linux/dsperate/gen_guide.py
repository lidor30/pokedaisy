#!/usr/bin/env python3
"""Adapt Android's encounter tables, area items and WHERE IS guide for DS."""

import json
from pathlib import Path
import re
import sys


def rows(path, prefix):
    text = path.read_text()
    block = re.search(rf'{prefix}_WILD_RAW: List<String> = listOf\((.*?)^\)', text, re.S | re.M)
    return [line.strip().split('|') for line in block[1].splitlines()
            if line.strip().startswith(('M|', 'E|'))]


def encounters(fields, game, variant, odds):
    land, water = odds['LAND_ODDS'], odds['WATER_ODDS']
    if game == 'PT':
        methods = [(0, 2, land), (3, 6, water), (7, 8, odds['PT_OLD_ROD_ODDS']),
                   (8, 10, odds['PT_ROD_ODDS']), (5, 12, odds['PT_ROD_ODDS'])]
    elif game in ('HG', 'SS'):
        rod = odds['HG_ROD_ODDS']
        methods = [(0, 2, land), (3, 4, water), (9, 6, odds['ROCK_SMASH_ODDS']),
                   (7, 8, rod), (8, 10, rod), (5, 12, rod)]
    else:
        methods = [(i, 3 + 2*i, land if i < 3 else water) for i in range(7)]
    result = []
    for method, index, odds in methods:
        if int(fields[index]) == 0:
            continue
        merged = {}
        for i, (slot, chance) in enumerate(zip(fields[index+1].split(), odds)):
            levels, species = slot.split(':')
            levels = list(map(int, levels.split('-')))
            options = list(map(int, species.split(',')))
            mon = options[variant] if len(options) > 1 else options[0]
            if game == 'PT' and method == 0 and variant and i in (2, 3):
                mon = int(fields[3+variant].split(',')[i-2])
            if game in ('HG', 'SS') and variant == 2 and ((method, i) in ((8, 3), (5, 1))):
                mon = int(fields[14]) or mon
            if mon:
                lo, hi, total = merged.get(mon, (levels[0], levels[-1], 0))
                merged[mon] = min(lo, levels[0]), max(hi, levels[-1]), total + chance
        for mon, (lo, hi, chance) in sorted(merged.items(), key=lambda entry: -entry[1][2]):
            result.append((method, mon, lo, hi, chance))
    return result


def emit(folder, filename, game):
    maps, sets, rates = {}, {}, {}
    gen4 = game in ('PT', 'HG', 'SS')
    variants = 3 if gen4 else 4
    reader = (folder / ('GuideGen4.kt' if gen4 else 'GuideGen5.kt')).read_text()
    odds = {name: [int(n) for n in numbers.split(',')]
            for name, numbers in re.findall(r'val (\w+_ODDS) = listOf\(([\d, ]+)\)', reader)}
    for fields in rows(folder / f'{filename}.kt', game):
        key = int(fields[1])
        if fields[0] == 'M':
            maps[key] = int(fields[2])
        else:
            season = -1 if gen4 else int(fields[2])
            for v in range(variants) if season == -1 else (season,):
                sets[key, v] = encounters(fields, game, v, odds)
            if gen4:
                rates[key, 0] = (int(fields[2]), int(fields[6 if game == 'PT' else 4]))
            else:
                raw = [int(fields[3 + 2*i]) for i in range(7)]
                words = [sum(n << (8*i) for i, n in enumerate(group))
                         for group in (raw[:4], raw[4:])]
                rates[key, season] = tuple(n if n < 2**31 else n - 2**32 for n in words)
    # Share identical time/season lists. Map records point into the flat rows.
    shared, output, locations = {}, [], []
    for key, encounter_set in sorted(maps.items()):
        for v in range(variants):
            entries = tuple(sets.get((encounter_set, v), ()))
            if not entries:
                continue
            if entries not in shared:
                shared[entries] = len(output)
                output.extend(entries)
            locations.append((key * 4 + v, shared[entries], len(entries)))
    assert output and locations
    name = game.lower()
    lines = [
        f'inline constexpr Encounter {name}Encounters[] = {{',
        *('    {' + ','.join(map(str, row)) + '},' for row in output),
        '};',
        f'inline constexpr Location {name}Locations[] = {{',
        *('    {' + ','.join(map(str, row)) + '},' for row in locations),
        '};',
    ]
    for v in range(1 if gen4 else 4):
        lines.append(f'inline constexpr int32_t {name}Rates{v}[] = {{')
        for key, encounter_set in sorted(maps.items()):
            if (encounter_set, v) in rates:
                lines.append('    ' + ','.join(map(str, (key, *rates[encounter_set, v]))) + ',')
        lines.append('};')
    return lines


KOTLIN_STRING = r'"(?:\\.|[^"\\])*"'


def csv_constant(text, name):
    body = re.search(rf'\b{name}\s*=\s*((?:{KOTLIN_STRING}\s*(?:\+\s*)?)+)', text)[1]
    return ''.join(map(json.loads, re.findall(KOTLIN_STRING, body)))


def place_map(folder, game):
    if game == 'PT':
        text = (folder / 'Gen4TownMapPlatinum.kt').read_text()
        body = text.split('val mapPlaceIdsPlatinum:', 1)[1].split('\n)', 1)[0]
        return [tuple(map(int, row)) for row in re.findall(r'(\d+) to (\d+)', body)]
    if game == 'HG':
        text = (folder / 'GuideGen4HeartGoldGen.kt').read_text()
        return [tuple(map(int, row.split(':'))) for row in csv_constant(text, 'HG_HEADER_PLACES').split(',')]
    suffix = 'Black2' if game.endswith('2') else 'Black'
    text = (folder / f'Gen5Tables{suffix}.kt').read_text()
    return list(enumerate(map(int, csv_constant(text, f'MAP_PLACE_IDS_{suffix.upper()}').split(','))))


def area_items(folder, filename, game):
    text = (folder / f'{filename}.kt').read_text()
    block = re.search(rf'\b{game}_AREAS_RAW: List<String> = listOf\((.*?)^\)', text, re.S | re.M)[1]
    kinds = {'ITEM': 0, 'HIDDEN': 1}
    items = []
    for line in block.splitlines():
        if not line.strip() or line.strip().startswith('"""'):
            continue
        area, kind, item, flag, qty, _, where = line.strip().split('|', 6)
        items.append((int(area), kinds[kind], int(item), int(flag), int(qty), where))
    assert items
    return sorted(items, key=lambda row: row[0])


def arguments(call):
    """Split the small page/section/entry DSL, keeping nested calls and strings whole."""
    start = call.index('(') + 1
    begin = start
    result, depth = [], 0
    for token in re.finditer(KOTLIN_STRING + r'|[(),]', call[start:]):
        pos, symbol = start + token.start(), token[0]
        if symbol == '(':
            depth += 1
        elif symbol == ')' and depth:
            depth -= 1
        elif symbol in (',', ')') and not depth:
            arg = call[begin:pos].strip()
            if arg:
                result.append(arg)
            if symbol == ')':
                return result
            begin = pos+1
    raise ValueError('unterminated guide call')


def guide_page(folder, game, label, soul_silver=False):
    suffix = {'PT': 'Platinum', 'HG': 'HeartGold', 'BLACK': 'Black', 'BLACK2': 'Black2'}[game]
    text = (folder / f'Guide{suffix}.kt').read_text()
    constants = {k: int(v, 0) for k, v in re.findall(r'const val (\w+) = (0x[0-9a-fA-F]+|\d+)', text)}
    strings = {}
    if game == 'HG':
        for key, ss, hg in re.findall(r'val (\w+) = if \(soulSilver\) ([^\n]+?) else ([^\n]+)', text):
            value = ss if soul_silver else hg
            if value.startswith('"'):
                strings[key] = json.loads(value)
            else:
                constants[key] = int(value, 0)

    def string(expr):
        if expr in strings:
            return strings[expr]
        parts = re.findall(KOTLIN_STRING, expr)
        assert parts and re.fullmatch(r'\s*' + KOTLIN_STRING + r'(?:\s*\+\s*' + KOTLIN_STRING + r')*\s*', expr)
        value = ''.join(map(json.loads, parts))
        return re.sub(r'\$(\w+)', lambda m: strings[m[1]], value)

    page = re.search(rf'\bpage\(\s*"{re.escape(label)}"', text)
    if not page:
        return []
    entries = []
    for section in arguments(text[page.start():])[1:]:
        assert section.startswith('section(')
        title, *rows = arguments(section)
        for row in rows:
            if row.startswith('hm('):
                n, move, answer, hint, areas = arguments(row)
                item = next(v for k, v in constants.items() if k.endswith('_HM01')) + int(n) - 1
                name = f'HM{int(n):02} {string(move)}'
                flag = 0
            else:
                assert row.startswith('entry(')
                name, answer, *options = arguments(row)
                name = string(name)
                hint, areas, item, flag = '""', 'listOf()', 0, 0
                for option in options:
                    option = re.sub(r'^(hint|areas)\s*=\s*', '', option)
                    if option.startswith('listOf('):
                        areas = option
                    elif option.startswith('Have.'):
                        kind, value, offset = re.fullmatch(r'Have\.(Item|Flag)\((\w+)(?:\s*\+\s*(\d+))?\)', option).groups()
                        check = (constants[value] if value in constants else int(value, 0)) + int(offset or 0)
                        item, flag = (check,0) if kind=='Item' else (0,check)
                    else:
                        hint = option
            keys = [''.join(ch for ch in string(a).upper().replace('É','E') if ch.isalnum())
                    for a in arguments(areas)]
            entries.append((string(title), name, string(hint), string(answer), item, flag, '|'.join(keys)))
    return entries


def emit_rows(name, kind, records):
    return [f'inline constexpr {kind} {name}[] = {{',
            *('    {' + ','.join(str(v) if isinstance(v, int) else json.dumps(v, ensure_ascii=False)
                               for v in row) + '},' for row in records),
            '};']


def fly_spots(folder, suffix, filename):
    text = (folder / f'{filename}.kt').read_text()
    body = re.search(rf'val flySpots{suffix}: List<FlySpot> = listOf\((.*?)^\)', text, re.S | re.M)[1]
    for call in re.findall(r'FlySpot\([^\n]+?\)', body):
        header, x, z, *options = arguments(call)
        named = dict(re.split(r'\s*=\s*', arg) for arg in options)
        assert named.keys() <= {'flag', 'nameMap', 'region'}
        yield tuple(map(int, (header, x, z, named.get('flag', '-1'),
                              named.get('nameMap', header), named.get('region', '2'))))


def boss_data(folder, filename, game):
    """Keep Android's order, progress gates and trainer-team variants."""
    raw = (folder / f'{filename}.kt').read_text()
    block = re.search(rf'\b{game}_BOSSES_RAW: List<String> = listOf\((.*?)^\)', raw, re.S | re.M)[1]
    teams, members = {}, []
    for line in block.splitlines():
        if '|' not in line:
            continue
        key, trainer, team = line.strip().split('|')
        start = len(members)
        for mon in team.split(';'):
            level, species, item, *move_list = mon.split()
            moves = list(map(int, move_list[0].split(','))) if move_list else []
            assert len(moves) <= 4
            members.append([int(level), int(species), int(item), *moves, *([0]*(4-len(moves)))])
        teams[key] = (int(trainer), start, len(members)-start)

    suffix = {'PT':'Platinum','HG':'HeartGold','BLACK':'Black','WHITE':'Black',
              'BLACK2':'Black2','WHITE2':'Black2'}[game]
    source = (folder / f'Guide{suffix}.kt').read_text()
    readers = ''.join((folder / f'{name}.kt').read_text() for name in ('Gen4Reader','Gen5Reader'))
    constants = {k:int(v,0) for k,v in re.findall(r'const val (\w+) = (0x[0-9a-fA-F]+|\d+)', raw+source+readers)}
    league = 'POKéMON LEAGUE'
    start = {'PT':'BOSSES_PLATINUM = listOf(', 'HG':'hgBosses(w: Gen4World) = listOf('}
    if game in start:
        body = source.split(start[game],1)[1]
    else:
        function = 'b2w2Bosses' if game.endswith('2') else 'bwBosses'
        body = source.split(f'private fun {function}',1)[1].split('return listOf(',1)[1]
    body = re.sub(r'(?m)^\s*//[^\n]*', '', body)
    leader8 = 'IRIS' if game=='WHITE' else 'DRAYDEN'
    records = []

    def add(title, where, key, done, done_after=0, variant='', rematch=False):
        _, offset, count = teams[key]
        if rematch:
            rematch_key = key[:-2]+'_2_C' if key.endswith('_C') else key+'_2'
            _, rematch_offset, rematch_count = teams[rematch_key]
        else:
            rematch_offset, rematch_count = 0, 0
        records.append((title,where,variant,done,done_after,
                        constants['B2W2_FLAG_CLEARED'] if rematch else 0,
                        offset,count,rematch_offset,rematch_count))

    for call in arguments('('+body):
        name = call.split('(',1)[0]
        args = arguments(call)
        if name == 'Boss':
            title,where = map(json.loads,args[:2])
            badge = re.fullmatch(r'GEN4_BADGE_FLAG \+ (\d+)',args[2])[1]
            variants = re.findall(r'"(\w+)" to world\.trainer\("(\w+)"\)',args[3])
            assert variants
            for variant,key in variants:
                add(title,where,key,constants['GEN4_BADGE_FLAG']+int(badge),variant=variant)
        elif name in ('ptGym','hgGym','gym'):
            if name == 'hgGym':
                args = args[1:]
            title,where,key = [leader8 if s=='leader8' else json.loads(s).replace('$leader8',leader8) for s in args[:3]]
            done = constants['GEN4_BADGE_FLAG']+int(args[3])
            add(title,where,key,done,variant='NORMAL' if game.endswith('2') else '')
            if game.endswith('2'):
                add(title,where,key+'_C',done,variant='CHALLENGE')
        elif name == 'ptLeague':
            title,key = map(json.loads,args)
            add(title,league,key,constants['PT_TRAINER_FLAGS']+teams[key][0],constants['PT_FLAG_GAME_COMPLETED'])
        elif name == 'hgLeague':
            title,key = map(json.loads,args[1:3])
            add(title,league,key,constants[args[3]],constants['HG_FLAG_GAME_CLEAR'])
        elif name == 'elite':
            title,key = map(json.loads,args[:2])
            sequel = game.endswith('2')
            done = constants['B2W2_FLAG_E4' if sequel else 'BW_FLAG_E4']+int(args[2])
            add(title,league,key,done,variant='NORMAL' if sequel else '',rematch=sequel)
            if sequel:
                add(title,league,key+'_C',done,variant='CHALLENGE',rematch=True)
        else:
            assert name in ('gen4Boss','gen5Boss','sequelBoss')
            title,where,key = map(json.loads,args[1:4])
            flag = args[4]
            if flag == '0':
                assert args[5]=='doneIf = { it.flag(GEN5_NATIONAL_DEX_FLAG) }'
                flag = 'GEN5_NATIONAL_DEX_FLAG'
            if flag == 'HG_TRAINER_FLAGS + w.trainer("RED")':
                done = constants['HG_TRAINER_FLAGS']+teams[key][0]
            else:
                done = constants[flag]
            if name == 'sequelBoss':
                assert args[5]=='rematch = true'
                add(title,where,key,done,variant='NORMAL',rematch=True)
                add(title,where,key+'_C',done,variant='CHALLENGE',rematch=True)
            else:
                add(title,where,key,done)
    assert records
    return members,records


def main():
    folder, out = map(Path, sys.argv[1:])
    games = [('GuideGen4PlatinumGen', 'PT'), ('GuideGen4HeartGoldGen', 'HG'), ('GuideGen4HeartGoldGen', 'SS')]
    games += [(f'GuideGen5{game}Gen', game.upper()) for game in ('Black', 'White', 'Black2', 'White2')]
    lines = ['// Generated from Android GUIDE data.', 'namespace ds_guide_data {']
    for file, game in games:
        lines += emit(folder, file, game)
        if game != 'SS':
            lines += emit_rows(game.lower()+'Items', 'AreaItem', area_items(folder, file, game))
            members,teams = boss_data(folder,file,game)
            lines += emit_rows(game.lower()+'BossMons', 'BossMon', members)
            lines += emit_rows(game.lower()+'BossTeams', 'BossTeam', teams)
        if game in ('PT', 'HG', 'BLACK', 'BLACK2'):
            lines += emit_rows(game.lower()+'Places', 'HeaderPlace', place_map(folder, game))
    for game in ('PT','HG','SS','BLACK','BLACK2'):
        for label, suffix in [('WHERE IS','Where'),('TIPS','Tips'),('STUCK?','Stuck')]:
            records = guide_page(folder, 'HG' if game=='SS' else game, label, soul_silver=game=='SS')
            if records:
                lines += emit_rows(game.lower()+suffix, 'GuideTip', records)
    for suffix, filename in [('Platinum','Gen4TablesPlatinum'), ('HeartGold','Gen4TownMapHeartGold'),
                             ('Black','Gen5TablesBlack'), ('White','Gen5TownMapWhite'),
                             ('White2','Gen5TownMapWhite2')]:
        lines += emit_rows(suffix.lower()+'Fly', 'DsFlySpot', fly_spots(folder, suffix, filename))
    for name,file in [('GEN4_BADGE_FLAG','Gen4Reader'),('GEN5_NATIONAL_DEX_FLAG','Gen5Reader')]:
        value = re.search(rf'const val {name} = (0x[0-9a-fA-F]+|\d+)',(folder/f'{file}.kt').read_text())[1]
        lines.append(f'inline constexpr int {name} = {value};')
    out.write_text('\n'.join([*lines, '}', '']))


if __name__ == '__main__':
    main()
