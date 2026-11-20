#ifndef PK_REWIND_H
#define PK_REWIND_H

#include <stdbool.h>
#include <stddef.h>

#include <mgba/core/rewind.h>

struct mCore;

/** A ring of [entries] states (mGBA's diff ring) that never touches the save data - see pk_rewind.c. */
void pk_rewind_init(struct mCoreRewindContext* context, size_t entries);
void pk_rewind_deinit(struct mCoreRewindContext* context);
/** The core's state now, as the newest entry. */
void pk_rewind_append(struct mCoreRewindContext* context, struct mCore* core);
/** Back to the newest entry (and drops it); false when the ring is empty. */
bool pk_rewind_restore(struct mCoreRewindContext* context, struct mCore* core);

#endif
