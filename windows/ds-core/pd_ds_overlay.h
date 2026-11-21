// The PokeDaisy overlay over the Windows DS core: the app's companion (PARTY / BAG / MAP / GUIDE / POKéDEX / SETTINGS)
// and the quick menu, drawn by the same portable presentation the Linux DS core and the 3DS port use
// (linux/libretro-ds/companion_ui.cpp, 3ds/core's pd_ui, linux/quick_menu.c). The game keeps running underneath, sound
// and all; the overlay takes the picture and the pad until it closes.
#pragma once

#include <cstdint>
#include <vector>

// Once the ROM is loaded: reads it for the companion's ids and art, opens its settings beside the save, and takes the
// first snapshot. [saveDir] holds pokedaisy-ds/settings.ini, as the GB / GBA cores' pokedaisy/settings.ini does.
void overlay_load(const char* romPath, const char* saveDir);
// A core reset or a state load: closed, with the next open reading the save again.
void overlay_reset();
// Once a frame, before composing: a snapshot every twelve frames while the overlay shows.
void overlay_tick(int fieldBits);
// The companion has the picture (the quick menu keeps the game's). The core skips the touch screen and R2 then.
bool overlay_open();
// Where this frame puts the game's top screen in the picture: the HUD (when the settings have it on) is drawn there, at
// the screen's own 256x192, over the field and under any overlay. Called once a frame, before overlay_compose.
void overlay_view(int topX, int topY, bool topShown);
// A pointer press on the picture (the overlay's own size while it shows, canvas pixels): true when the companion, the
// HUD or the quick menu takes it, so the game's touch screen doesn't. A click on a HUD part runs it; on a quick menu
// tile, runs the tile; on the companion, its tab, row, button or gear.
bool overlay_pointer(int x, int y, bool down);
// The wheel, in notches (>0 down): the companion's open list moves as UP / DOWN would. True when the overlay took it.
bool overlay_wheel(int notches);
// When the overlay shows, [frame] (the game's composed picture, [w] x [h]) becomes the overlay's at twice the size, and
// [w] / [h] its own. False leaves the frame alone.
bool overlay_compose(std::vector<uint32_t>& frame, int& w, int& h);
// This frame's keys, in libretro ids: L3 toggles the quick menu; L2 opens the companion (a tap toggles it), held for a
// moment it opens the quick menu too. Returns the keys the game must not see (all of them while the overlay is open).
unsigned overlay_keys(unsigned pressed, unsigned held);
// The quick menu's SAVE STATE / LOAD STATE: the frontend's state slot for this ROM (states/<ROM>.ss), so the ring and the
// host's hotkey share one file. Load closes the overlay and puts the state back.
bool overlay_state_exists();
bool overlay_state_save();
bool overlay_state_load();
// The quick menu's FF tile: the host owns the speed, so ask it to toggle. False when the frontend doesn't know the command.
bool overlay_fast_forward();
bool overlay_set_fast_forward(bool on);
