/* Force-included (-include) into the translation units that were written against mGBA's headers and are built here over
 * armGBA (pk_wide.c): the three names they use for the emulator's types become the view's (pd_agba_view.h). Not for any
 * file that sees armGBA's own struct GBA. GPL-3.0, see ../../../NOTICE. */
#ifndef PD_NAMES_H
#define PD_NAMES_H
#define GBA PdGbaView
#define GBAVideoRenderer PdRenderer
#define mCore PdCore
#endif
