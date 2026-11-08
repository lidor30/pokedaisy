# gamedata

The per-game data every PokeDaisy platform builds from: one place to write a game's
addresses, offsets and layouts, so a new game or a new field reaches every platform built from
the same commit. Nothing reads these files at run time: `scripts/gen_gamedata.py` turns them into
each platform's code, and that code is committed.

So far this holds the Gen 3 `NativeConfig`s (phase 1) and the records the 3DS / Linux companion
reads (phase 2: retail FireRed / LeafGreen / Emerald's Pokédex, guide, trainer-card and dex-flag
tables, their bag orders and the field items' functions: `FIELD_ITEMS_*` with their `PARTY_ITEMS_*`, `PC_*`
and `FLY_*`). The outputs:

- the app's `app/src/main/kotlin/com/pokedaisy/app/companion/data/NativeConfigsGen.kt` (the configs) and
  `GameDataRecordsGen.kt` (the records);
- `3ds/core/pd_configs_gen.h`, the 3DS port's `struct pd_config`s, which the Linux host builds too
  (`targets/3ds.toml`).

The other records (the hacks' and the other languages' tables, layouts), the games' identity
(SHA-1s, codes, titles) and the name tables come later.

```
gamedata/
  schema/gen3_config.toml   every NativeConfig field: Kotlin type, schema type, default
  schema/gen3_pokedex.toml  PokedexTables' fields, and gen3_guide.toml GuideTables'
  gen3/retail/*.toml        FireRed, LeafGreen, Emerald, Ruby / Sapphire
  gen3/ports/*.toml         their other-language releases (written by scripts/port_retail.py)
  gen3/hacks/*.toml         one file per hack, every release of it
  gen3/records/*.toml       named records ([record.POKEDEX_EMERALD]) and lists the configs name
  nds/ports.toml            the DS carts in other languages (scripts/gen_nds_ports.py: its own header has the format)
  targets/3ds.toml          what the 3DS / Linux companion's C gets, field by field
```

## Commands

```
make gamedata                              # regenerate every output (Kotlin and C)
python3 scripts/gen_gamedata.py --check    # fails if it's stale (make check and CI run this)
```

Never edit a generated file by hand (`NativeConfigsGen.kt`, `GameDataRecordsGen.kt`,
`pd_configs_gen.h`): the next `make gamedata` overwrites it and the check fails.

## Adding or changing a game

1. Open the game's family file (or add one in `gen3/hacks/`; the file name is free).
2. Add a `[config.<name>]` table. `<name>` is lower snake case and becomes the Kotlin val
   `NATIVE_<NAME>` (`[config.unbound_with_dex]` is `NATIVE_UNBOUND_WITH_DEX`).
3. Set `base` to the config it starts from, then only the fields that differ. A config with no
   base sets every field the schema has no default for.
4. Write down where each value came from, in a `#` comment above it, as the existing configs do.
   Comments are carried into the generated code.
5. `make gamedata`, then build and test as usual.

```toml
# The battle menus' cursors are FireRed's (CFRU keeps its EWRAM).
[config.unbound]
base = 'firered_rev0'
actionCursor = 0x02023FF8
moveCursor = 0x02023FFC
```

A config with `private = true` is a `private val` in Kotlin: a common base that nothing outside
the generated file uses. `lazy = true` builds it on first use (`by lazy`), and `port = 'BPRD0'` lists
it in `RETAIL_PORTS` under that game code + revision; the language ports use both.

### Records

A few fields hold a record of their own (their schema field names one with `record`, like
`pokedex` and `guideTables`). Besides naming a Kotlin val (`pokedex = 'POKEDEX_SAPPHIRE'`), a
config can spell the record out in a sub-table right after it: `base` names the val it starts
from, the rest are that record's fields (`gamedata/schema/gen3_pokedex.toml`).

```toml
[config.port_axpd0.pokedex]
base = 'POKEDEX_SAPPHIRE'
entries = 0x083BD6D4
probeCategory = "SAMEN"
metric = true
```

Without a `base`, the sub-table must set every field the record's schema has no default for.

## Records and lists

A config names a record by its Kotlin val's name (`pokedex = 'POKEDEX_EMERALD'`). When that record is
in `gen3/records/`, it's data here too: the app gets it from `GameDataRecordsGen.kt` (built on first
use, `by lazy`) and the C targets can follow it. One table each:

```toml
[record.POKEDEX_EMERALD]
schema = 'gen3_pokedex'      # the record's class
entries = 0x0856B5B0
flags = 'EMERALD_DEX_FLAGS'  # another record

[record.EMERALD_DEX_FLAGS]
schema = 'gen3_dexflags'
base = 'FIRERED_DEX_FLAGS'   # .copy() of another record
seenCopies = [0x988, 0x3B24]

[list.EMERALD_BAG_POCKET_ORDER]
of = 'Int'
items = ['POCKET_ITEMS', 'POCKET_POKE_BALLS', 'POCKET_TM_HM', 'POCKET_BERRIES', 'POCKET_KEY_ITEMS']
```

A record without a `base` sets every field its schema has no default for. Records still written in
Kotlin can be named as before; a C target that needs one stops with a message saying it's not here
yet: move it (its values, comments and all), delete the Kotlin val, `make gamedata`.

## C targets

`targets/3ds.toml` is the 3DS port's `struct pd_config`: which configs it gets (`[config.<key>]`,
`name` = its C name after `PD_CONFIG_`, `parts` = which of the parts below it reads so far), each part
a list of `pd_config` field = path. A path is a config field, then through a record that field names,
that record's field (`pokedex.flags.seenCopies.0`, a list's index last). A config without the record
gets the record schema's defaults there (FireRed rev 0 has no Pokédex tables, but its save offsets
are FireRed's). `{ path = '...', none = -1 }` writes 0 for the value that means "not in this game".
`[names]` gives the C for Kotlin names a value holds (`POCKET_ITEMS`, `[names.CardStyle]`).

A record the config itself doesn't name (FLY's: the app picks those by the ROM's code, not the config)
comes in through the target: `records = { items = 'FIELD_ITEMS_EMERALD' }` on the `[config.*]`, and a path
that starts `@items.` starts from that record (`@items.fly.healWarp.toHealLocation`). A list field writes a C array
(`{ 0x07BEBC3A, ... }`, `{ { 15, 11 }, ... }` for an `int_map`), zero-filled to the struct's size.

A field the port starts reading goes in the struct (`3ds/core/pd_game.h`) and in a part here; a game
it starts supporting gets a `[config.*]` (and `pd_game.c` picks it).

## Values

One `key = value` a line (TOML inline tables, arrays and strings stay on that line). Field names
are the Kotlin ones. What a value can be depends on the field's `type` in the schema:

| Type | Written as | Kotlin |
| --- | --- | --- |
| `long`, `int` | `0x02024284`, `-1`, `999_999` (hex stays hex) | `0x02024284L`, `5` |
| `bool`, `char`, `string` | `true`, `'F'`, `'BPED'` | `true`, `'F'`, `"BPED"` |
| `ref` | the Kotlin val's name: `'POKEDEX_UNBOUND'` | `POKEDEX_UNBOUND` |
| `item_descs` | `{ preset = 'vanilla', base = 0x083DB028 }` or `{ base = ..., stride = 0x2C, descOff = -8 }` | `vanillaItems(...)`, `ItemDescTable(...)` |
| `int_range` | `[34, 35]` | `34..35` |
| `long_list`, `int_list` | `[0x5F8, 0x3A18]` | `listOf(0x5F8L, 0x3A18L)` |
| `int_set`, `long_set` | `[2, 4]`, `[0x080A16F4]` | `setOf(2, 4)`, `setOf(0x080A16F4L)` |
| `int_map` | `[[15, 11], [58, 22]]` (TOML's keys can't be numbers) | `mapOf(15 to 11, 58 to 22)` |

Any nullable field takes `'none'` (Kotlin `null`). Any field also takes:

- `{ from = 'firered_rev1' }`: the same field of another config.
- `{ ref = 'RUBY_SAPPHIRE_ITEMS' }`: a named Kotlin constant.
- `{ kotlin = 'Bag3Layout(...)' }`: a Kotlin expression, for the few records that aren't data
  here yet. Avoid it for anything new: it's what a platform other than the app can't read.

`kotlin` values, and `ref`s to Kotlin vals, point at Kotlin and go away as their records move into
gamedata/ (a `ref` to a record in `gen3/records/` is data already).

## The schemas

`schema/gen3_config.toml` lists `NativeConfig`'s fields, `gen3_pokedex.toml` / `gen3_guide.toml`
those of `PokedexTables` / `GuideTables`, `gen3_fly.toml` / `gen3_heal_warp.toml` `FlyFns` / `HealWarp`, `gen3_field_items.toml` / `gen3_party_items.toml` /
`gen3_pc.toml` `FieldItemFns` / `PartyItemFns` / `PcFns`. Each names its Kotlin class (`kotlin_class`,
`kotlin_file`) and the generator checks the two agree, so a new field means adding it to both
(with a default, or every config without a base has to set it). A schema for another class
starts with `python3 scripts/gen_gamedata.py --new-schema <name> <File.kt> <Class>`. `version`
goes up only when a field is renamed or removed.
