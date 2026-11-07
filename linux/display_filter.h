/* GPL-3.0. Subtle RGB aperture mask and horizontal LCD structure.
 * Output-space display effect; never changes emulator or companion buffers. */
#ifndef POCKET_DISPLAY_FILTER_H
#define POCKET_DISPLAY_FILTER_H
#include <stdint.h>
static inline uint32_t pocket_lcd_pixel(unsigned x,unsigned y) {
  unsigned rgb[3]={239,239,239};rgb[x%3]=255;
  if(y%3==2)for(unsigned i=0;i<3;i++)rgb[i]=rgb[i]*232/255;
  return 0xff000000u | rgb[0]<<16 | rgb[1]<<8 | rgb[2];
}
#endif
