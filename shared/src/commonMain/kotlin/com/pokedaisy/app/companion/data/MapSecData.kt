package com.pokedaisy.app.companion.data

data class MapSecInfo(val name: String, val region: Int, val x: Int, val y: Int, val w: Int, val h: Int)

// index 0=kanto, 1=sevii123, 2=sevii45, 3=sevii67 - the regionmap/*.png files RomArt rebuilds from the ROM
val regionMapImages = arrayOf("kanto", "sevii123", "sevii45", "sevii67")

// x/y here are ALREADY +4 tiles from pokefirered's raw sMapSectionTopLeftCorners
// table — do NOT regenerate this file straight from that table without adding
// (4, 4) to every (x, y) again, or every highlight box silently lands 4 tiles
// (32px) too far north-west of the real location, which is exactly the bug
// this was fixed from (2026-09-15). Root cause: sMapSectionTopLeftCorners is in
// the *cursor/mapsec-lookup* grid's own coordinate space
// (`sRegionMapSections_Kanto[MAP_HEIGHT=15][MAP_WIDTH=22]`, see region_map.c),
// which is a smaller window inset by a fixed 4-tile border on all sides from
// the *visual* 30x20 tile canvas the composited PNGs (and REGION_MAP_TILE_SIZE
// highlight math in SnapshotView.kt) actually use. Verified exhaustively: built
// a from-scratch reconstruction of assets/regionmap/kanto.png directly from the
// decomp's own graphics/region_map/{kanto.bin,region_map.png}, found the 11
// city "dot" tiles' real positions in that 30x20 visual grid, and brute-forced
// every integer (ox,oy) offset against sRegionMapSections_Kanto's 22x15 grid —
// (4,4) is the unique offset that maps all 11 dots to their correct city, with
// zero ambiguity. Applied uniformly to every region (Sevii included, same
// MAP_WIDTH/MAP_HEIGHT + 240x160 canvas mechanism — not independently
// re-verified per-Sevii-map the way Kanto was, but the same fixed UI frame
// should apply). Mirrored into tools/telemetry-viewer/mapsec_firered_gen.go.
// Sections the table leaves at its {0,0} "not on the map" corner (dungeons,
// buildings) are 0,0,0,0 here, not (4,4) - nothing highlights there; the Map
// tab finds dungeons on the cursor grid's dungeon layer (RegionMapModel).
val mapSecData: Map<Int, MapSecInfo> = mapOf(
    88 to MapSecInfo("Pallet Town", 0, 8, 15, 1, 1),
    89 to MapSecInfo("Viridian City", 0, 8, 12, 1, 1),
    90 to MapSecInfo("Pewter City", 0, 8, 8, 1, 1),
    91 to MapSecInfo("Cerulean City", 0, 18, 7, 1, 1),
    92 to MapSecInfo("Lavender Town", 0, 22, 10, 1, 1),
    93 to MapSecInfo("Vermilion City", 0, 18, 13, 1, 1),
    94 to MapSecInfo("Celadon City", 0, 15, 10, 1, 1),
    95 to MapSecInfo("Fuchsia City", 0, 16, 16, 1, 1),
    96 to MapSecInfo("Cinnabar Island", 0, 8, 18, 1, 1),
    97 to MapSecInfo("Indigo Plateau", 0, 6, 7, 1, 1),
    98 to MapSecInfo("Saffron City", 0, 18, 10, 1, 1),
    99 to MapSecInfo("Route 4 Pokecenter", 0, 12, 7, 1, 1),
    100 to MapSecInfo("Route 10 Pokecenter", 0, 22, 7, 1, 1),
    101 to MapSecInfo("Route 1", 0, 8, 13, 1, 2),
    102 to MapSecInfo("Route 2", 0, 8, 9, 1, 3),
    103 to MapSecInfo("Route 3", 0, 9, 8, 4, 1),
    104 to MapSecInfo("Route 4", 0, 12, 7, 6, 1),
    105 to MapSecInfo("Route 5", 0, 18, 8, 1, 2),
    106 to MapSecInfo("Route 6", 0, 18, 11, 1, 2),
    107 to MapSecInfo("Route 7", 0, 16, 10, 2, 1),
    108 to MapSecInfo("Route 8", 0, 19, 10, 3, 1),
    109 to MapSecInfo("Route 9", 0, 19, 7, 3, 1),
    110 to MapSecInfo("Route 10", 0, 22, 7, 1, 3),
    111 to MapSecInfo("Route 11", 0, 19, 13, 3, 1),
    112 to MapSecInfo("Route 12", 0, 22, 11, 1, 5),
    113 to MapSecInfo("Route 13", 0, 20, 15, 2, 1),
    114 to MapSecInfo("Route 14", 0, 19, 15, 1, 2),
    115 to MapSecInfo("Route 15", 0, 17, 16, 2, 1),
    116 to MapSecInfo("Route 16", 0, 11, 10, 4, 1),
    117 to MapSecInfo("Route 17", 0, 11, 11, 1, 5),
    118 to MapSecInfo("Route 18", 0, 11, 16, 5, 1),
    119 to MapSecInfo("Route 19", 0, 16, 17, 1, 2),
    120 to MapSecInfo("Route 20", 0, 9, 18, 7, 1),
    121 to MapSecInfo("Route 21", 0, 8, 16, 1, 2),
    122 to MapSecInfo("Route 22", 0, 6, 12, 2, 1),
    123 to MapSecInfo("Route 23", 0, 6, 8, 1, 4),
    124 to MapSecInfo("Route 24", 0, 18, 5, 1, 2),
    125 to MapSecInfo("Route 25", 0, 19, 5, 2, 1),
    126 to MapSecInfo("Viridian Forest", 0, 0, 0, 0, 0),
    127 to MapSecInfo("Mt Moon", 0, 0, 0, 0, 0),
    128 to MapSecInfo("S.S. Anne", 0, 0, 0, 0, 0),
    129 to MapSecInfo("Underground Path", 0, 0, 0, 0, 0),
    130 to MapSecInfo("Underground Path 2", 0, 0, 0, 0, 0),
    131 to MapSecInfo("Digletts Cave", 0, 0, 0, 0, 0),
    132 to MapSecInfo("Kanto Victory Road", 0, 0, 0, 0, 0),
    133 to MapSecInfo("Rocket Hideout", 0, 0, 0, 0, 0),
    134 to MapSecInfo("Silph Co", 0, 0, 0, 0, 0),
    135 to MapSecInfo("Pokemon Mansion", 0, 0, 0, 0, 0),
    136 to MapSecInfo("Kanto Safari Zone", 0, 0, 0, 0, 0),
    137 to MapSecInfo("Pokemon League", 0, 0, 0, 0, 0),
    138 to MapSecInfo("Rock Tunnel", 0, 0, 0, 0, 0),
    139 to MapSecInfo("Seafoam Islands", 0, 0, 0, 0, 0),
    140 to MapSecInfo("Pokemon Tower", 0, 0, 0, 0, 0),
    141 to MapSecInfo("Cerulean Cave", 0, 0, 0, 0, 0),
    142 to MapSecInfo("Power Plant", 0, 0, 0, 0, 0),
    143 to MapSecInfo("One Island", 1, 5, 12, 1, 1),
    144 to MapSecInfo("Two Island", 1, 13, 13, 1, 1),
    145 to MapSecInfo("Three Island", 1, 22, 16, 1, 1),
    146 to MapSecInfo("Four Island", 2, 7, 8, 1, 1),
    147 to MapSecInfo("Five Island", 2, 20, 15, 1, 1),
    148 to MapSecInfo("Seven Island", 3, 9, 12, 1, 1),
    149 to MapSecInfo("Six Island", 3, 21, 9, 1, 1),
    150 to MapSecInfo("Kindle Road", 1, 6, 7, 1, 6),
    151 to MapSecInfo("Treasure Beach", 1, 5, 13, 1, 2),
    152 to MapSecInfo("Cape Brink", 1, 13, 11, 1, 2),
    153 to MapSecInfo("Bond Bridge", 1, 17, 16, 4, 1),
    154 to MapSecInfo("Three Isle Port", 1, 22, 17, 2, 1),
    155 to MapSecInfo("Sevii Isle 6", 2, 8, 7, 1, 1),
    156 to MapSecInfo("Sevii Isle 7", 2, 9, 8, 1, 1),
    157 to MapSecInfo("Sevii Isle 8", 2, 5, 8, 3, 1),
    158 to MapSecInfo("Sevii Isle 9", 2, 8, 9, 1, 2),
    159 to MapSecInfo("Resort Gorgeous", 2, 20, 13, 3, 1),
    160 to MapSecInfo("Water Labyrinth", 2, 18, 14, 3, 1),
    161 to MapSecInfo("Five Isle Meadow", 2, 21, 14, 1, 3),
    162 to MapSecInfo("Memorial Pillar", 2, 22, 16, 1, 3),
    163 to MapSecInfo("Outcast Island", 3, 19, 4, 1, 3),
    164 to MapSecInfo("Green Path", 3, 19, 7, 3, 1),
    165 to MapSecInfo("Water Path", 3, 22, 7, 1, 5),
    166 to MapSecInfo("Ruin Valley", 3, 20, 11, 2, 2),
    167 to MapSecInfo("Trainer Tower", 3, 9, 10, 1, 2),
    168 to MapSecInfo("Canyon Entrance", 3, 9, 13, 1, 1),
    169 to MapSecInfo("Sevault Canyon", 3, 10, 13, 1, 3),
    170 to MapSecInfo("Tanoby Ruins", 3, 7, 16, 7, 1),
    171 to MapSecInfo("Sevii Isle 22", 3, 13, 16, 1, 3),
    172 to MapSecInfo("Sevii Isle 23", 3, 7, 18, 6, 1),
    173 to MapSecInfo("Sevii Isle 24", 3, 6, 16, 1, 3),
    174 to MapSecInfo("Navel Rock", 2, 14, 12, 1, 1),
    175 to MapSecInfo("Mt Ember", 1, 0, 0, 0, 0),
    176 to MapSecInfo("Berry Forest", 1, 0, 0, 0, 0),
    177 to MapSecInfo("Icefall Cave", 2, 0, 0, 0, 0),
    178 to MapSecInfo("Rocket Warehouse", 2, 0, 0, 0, 0),
    179 to MapSecInfo("Trainer Tower 2", 3, 0, 0, 0, 0),
    180 to MapSecInfo("Dotted Hole", 3, 0, 0, 0, 0),
    181 to MapSecInfo("Lost Cave", 2, 0, 0, 0, 0),
    182 to MapSecInfo("Pattern Bush", 3, 0, 0, 0, 0),
    183 to MapSecInfo("Altering Cave", 3, 0, 0, 0, 0),
    184 to MapSecInfo("Tanoby Chambers", 3, 0, 0, 0, 0),
    185 to MapSecInfo("Three Isle Path", 1, 0, 0, 0, 0),
    186 to MapSecInfo("Tanoby Key", 3, 0, 0, 0, 0),
    187 to MapSecInfo("Birth Island", 3, 22, 17, 1, 1),
    188 to MapSecInfo("Monean Chamber", 3, 0, 0, 0, 0),
    189 to MapSecInfo("Liptoo Chamber", 3, 0, 0, 0, 0),
    190 to MapSecInfo("Weepth Chamber", 3, 0, 0, 0, 0),
    191 to MapSecInfo("Dilford Chamber", 3, 0, 0, 0, 0),
    192 to MapSecInfo("Scufib Chamber", 3, 0, 0, 0, 0),
    193 to MapSecInfo("Rixy Chamber", 3, 0, 0, 0, 0),
    194 to MapSecInfo("Viapois Chamber", 3, 0, 0, 0, 0),
    195 to MapSecInfo("Ember Spa", 1, 0, 0, 0, 0),
    196 to MapSecInfo("Special Area", -1, 0, 0, 0, 0),
    198 to MapSecInfo("Count", -1, 0, 0, 1, 1),
)
