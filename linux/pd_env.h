/* Private libretro environment commands shared by PokeDaisy's cores and host. */
#ifndef PD_ENV_H
#define PD_ENV_H

#include <stdint.h>

/* The core's quick menu asks the frontend to turn fast-forward on or off. [data] is a bool. */
#define PD_ENV_SET_FAST_FORWARD 0x20001

/* The core's floating touch screen. The core keeps the game's bottom screen at its full 256 x 192 and asks a host that
 * can draw it to do the output scaling itself, instead of scaling the pixels down into the panel first. [data] is a
 * pd_touch_panel; the pointer stays valid until the core's next frame. active = 0 withdraws the panel. */
#define PD_ENV_SET_TOUCH_PANEL 0x20002
struct pd_touch_panel {
  const uint32_t *pixels; /* src_w x src_h, XRGB8888 (0xFFRRGGBB), as the frame's pixels are */
  int src_w, src_h;
  int x, y, w, h;         /* the screen's place in the core's canvas */
  int active;
};

#endif
