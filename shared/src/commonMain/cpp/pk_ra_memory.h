#ifndef PK_RA_MEMORY_H
#define PK_RA_MEMORY_H

#include <stdint.h>

struct mCore;

uint32_t pk_ra_read_memory(struct mCore* core, uint32_t address, uint8_t* out, uint32_t bytes);

#endif
