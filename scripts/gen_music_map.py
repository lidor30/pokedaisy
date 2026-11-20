#!/usr/bin/env python3
"""Generate the location->song-ID table PokeDaisy's FF music prefetcher/
player use (id-only — no audio; see FfMusicRenderer.kt for how the actual
clips get recorded, on-device, from the real ROM).

Emits (into shared/src/commonMain/kotlin/.../companion/data/):
  - MapMusicFireRed.kt / MapMusicEmerald.kt

Source of truth: a pokefirered/pokeemerald checkout (pinned commit, see this
repo's root CLAUDE.md). Dev-time generator, not wired into build.sh - re-run
only if the pinned decomp commit changes.

This is only the prefetcher's work list: which songs to pre-record for retail
FireRed / Emerald. Clips are keyed by the song the game is really playing
(FfMusicKey reads it from the sound engine), so the list just has to cover
what a player hears - each MAPSEC's field music plus every battle theme.

Usage:
  python3 scripts/gen_music_map.py /path/to/pokefirered firered
  python3 scripts/gen_music_map.py /path/to/pokeemerald emerald
"""
import json
import os
import re
import sys


def parse_song_ids(decomp_dir: str) -> dict[str, int]:
    """MUS_* name -> numeric song-table id, from include/constants/songs.h."""
    txt = open(os.path.join(decomp_dir, "include/constants/songs.h")).read()
    ids = {}
    for m in re.finditer(r"#define\s+(MUS_[A-Z0-9_]+)\s+(\d+)", txt):
        ids[m.group(1)] = int(m.group(2))
    return ids


def parse_map_music(decomp_dir: str) -> dict[str, str]:
    """map dir name -> its "music" field (a MUS_* name), from each map.json."""
    maps_dir = os.path.join(decomp_dir, "data/maps")
    out = {}
    for name in sorted(os.listdir(maps_dir)):
        mj = os.path.join(maps_dir, name, "map.json")
        if not os.path.isfile(mj):
            continue
        data = json.load(open(mj))
        music = data.get("music")
        if music:
            out[name] = music
    return out


def parse_map_mapsec(decomp_dir: str) -> dict[str, str]:
    """map dir name -> its region_map_section (MAPSEC_* name)."""
    maps_dir = os.path.join(decomp_dir, "data/maps")
    out = {}
    for name in sorted(os.listdir(maps_dir)):
        mj = os.path.join(maps_dir, name, "map.json")
        if not os.path.isfile(mj):
            continue
        data = json.load(open(mj))
        sec = data.get("region_map_section")
        if sec:
            out[name] = sec
    return out


def build_mapsec_music(decomp_dir: str, song_ids: dict[str, int]) -> dict[str, int]:
    """MAPSEC_* name -> representative song id.

    Several individual maps can share one MAPSEC (a town's outdoor map plus
    its houses/labs); indoor maps usually - but not always - inherit the
    outdoor map's music (see e.g. Pallet Town's lab, which has its own
    theme). Picking the shortest map name per MAPSEC is a simple, reliable
    way to prefer that outdoor/"base" map over its nested indoor variants,
    since decomp map dirs are named `<Base><_Suffix>` for indoor rooms.
    """
    music = parse_map_music(decomp_dir)
    mapsec = parse_map_mapsec(decomp_dir)
    by_sec: dict[str, list[str]] = {}
    for map_name, sec in mapsec.items():
        if map_name in music:
            by_sec.setdefault(sec, []).append(map_name)

    result = {}
    for sec, map_names in by_sec.items():
        base = min(map_names, key=len)
        mus_name = music[base]
        if mus_name in song_ids:
            result[sec] = song_ids[mus_name]
    return result


# Battle music: every MUS_VS_* theme (wild, trainer, gym leader, champion,
# legendaries, ...). Not MUS_RS_VS_*: FireRed still carries Ruby/Sapphire's
# battle songs under those names, unused - picking MUS_RS_VS_TRAINER here once
# made FireRed's fast-forward play Emerald's trainer battle theme.
BATTLE_MUSIC_PREFIX = "MUS_VS_"


def kt_header(package: str) -> str:
    return f"""// GENERATED FILE - do not edit by hand.
// Regenerate with: python3 scripts/gen_music_map.py <decomp-checkout> <game>
package {package}
"""


def main() -> None:
    if len(sys.argv) != 3 or sys.argv[2] not in ("firered", "emerald"):
        print(__doc__)
        sys.exit(1)
    decomp_dir, game = sys.argv[1], sys.argv[2]

    song_ids = parse_song_ids(decomp_dir)

    # MAPSEC_* name -> numeric id. These are a single flat, sequential enum
    # (0-indexed by declaration order) in region_map_sections.h, not #defines
    # with explicit values - same numbering MapSecData.kt already relies on.
    mapsec_name_to_id = {}
    txt = open(os.path.join(decomp_dir, "include/constants/region_map_sections.h")).read()
    enum_body = re.search(r"enum\s*\{(.*?)\n\};", txt, re.S).group(1)
    idx = 0
    for line in enum_body.splitlines():
        name = re.sub(r"//.*", "", line).strip().rstrip(",").strip()
        if not name or not name.startswith("MAPSEC_"):
            continue
        mapsec_name_to_id[name] = idx
        idx += 1

    mapsec_name_to_song = build_mapsec_music(decomp_dir, song_ids)
    mapsec_id_to_song: dict[int, int] = {}
    for sec_name, song_id in mapsec_name_to_song.items():
        sec_id = mapsec_name_to_id.get(sec_name)
        if sec_id is not None:
            mapsec_id_to_song[sec_id] = song_id

    battle_song_ids = sorted(v for k, v in song_ids.items() if k.startswith(BATTLE_MUSIC_PREFIX))
    assert battle_song_ids, "no MUS_VS_* songs in songs.h"

    class_suffix = "FireRed" if game == "firered" else "Emerald"
    out_dir = os.path.join(
        os.path.dirname(__file__), "..",
        "shared/src/commonMain/kotlin/com/pokedaisy/app/companion/data",
    )
    out_path = os.path.join(out_dir, f"MapMusic{class_suffix}.kt")

    lines = [kt_header("com.pokedaisy.app.companion.data")]
    lines.append(f"/** {class_suffix}'s songs for FfMusicRenderer to pre-record: each MAPSEC's field music and every battle theme. */")
    lines.append(f"object MapMusic{class_suffix} {{")
    lines.append(f"    /** Every MUS_VS_* battle theme. */")
    lines.append(f"    val battleSongIds: Set<Int> = setOf({', '.join(map(str, battle_song_ids))})")
    lines.append("    val fieldSongIds: Map<Int, Int> = mapOf(")
    for sec_id in sorted(mapsec_id_to_song.keys()):
        lines.append(f"        {sec_id} to {mapsec_id_to_song[sec_id]},")
    lines.append("    )")
    lines.append("")
    lines.append("    /** Every song id this game's field/battle music can need — the prefetcher's work list. */")
    lines.append("    val allSongIds: Set<Int> = fieldSongIds.values.toSet() + battleSongIds")
    lines.append("}")
    lines.append("")
    open(out_path, "w").write("\n".join(lines))
    print(f"wrote {out_path} ({len(mapsec_id_to_song)} field tracks, battle={battle_song_ids})")


if __name__ == "__main__":
    main()
