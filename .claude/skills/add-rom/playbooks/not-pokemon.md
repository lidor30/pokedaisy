# Playbook: not-pokemon (no GBA / Game Boy / DS header identify recognises)

The file isn't a cart the app reads: another console's ROM, an archive that wasn't unpacked (`.zip` / `.7z`: unpack it
into its own folder first and identify the ROM inside), a patch (`.bps`: PATCH A ROM applies it to the base ROM, then
identify the result), or a broken / truncated dump. Change nothing in the repo; tell the user what the file is.

A real Pokemon game on a platform the app doesn't run (3DS, Switch) isn't something this skill adds.
