#ifndef PK_SAVEVF_H
#define PK_SAVEVF_H

#include <mgba-util/vfs.h>

// [inner] (the save file) behind a VFile whose map() never fails: a mapping the file can't give is a copy in memory,
// written back on sync / unmap (pk_savevf.c). Takes [inner]: closing the result closes it.
struct VFile* pk_save_vf(struct VFile* inner);

#endif
