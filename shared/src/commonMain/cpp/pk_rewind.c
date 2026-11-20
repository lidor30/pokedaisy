/* Adapted from mGBA's src/core/rewind.c, Copyright (c) 2013-2016 Jeffrey Pfau.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * REWIND for the player's core: mGBA's own ring of state diffs (struct
 * mCoreRewindContext, its PatchFast entries), but the states are taken and put
 * back WITHOUT the save data. mCoreRewindAppend / Restore pass
 * SAVESTATE_SAVEDATA, and loading a state with it writes the state's copy of the
 * save over the save file - every rewound frame would rewrite it. Here the save
 * file only ever changes when the game itself saves (CLAUDE.md: save files are
 * sacred). Single-threaded (the emu thread), so no diffing thread.
 */
#include "pk_rewind.h"

#include <mgba/core/core.h>
#include <mgba/core/serialize.h>
#include <mgba-util/patch/fast.h>
#include <mgba-util/vfs.h>

// libmgba is built with threading, so its mCoreRewindContext carries the thread
// fields; built without them here, mCoreRewindContextInit / Deinit would write past
// ours (REWIND OFF once froze the game and corrupted the core lock next to it).
#ifdef DISABLE_THREADING
#error "pk_rewind.c must see mGBA's threaded mCoreRewindContext: define USE_PTHREADS (CMakeLists.txt)"
#endif

#define PK_REWIND_FLAGS SAVESTATE_RTC

static void pk_rewindDiff(struct mCoreRewindContext* context) {
	++context->current;
	if (context->size < mCoreRewindPatchesSize(&context->patchMemory)) {
		++context->size;
	}
	if (context->current >= mCoreRewindPatchesSize(&context->patchMemory)) {
		context->current = 0;
	}
	struct PatchFast* patch = mCoreRewindPatchesGetPointer(&context->patchMemory, context->current);
	size_t size2 = context->currentState->size(context->currentState);
	size_t size = context->previousState->size(context->previousState);
	if (size2 > size) {
		context->previousState->truncate(context->previousState, size2);
		size = size2;
	} else if (size > size2) {
		context->currentState->truncate(context->currentState, size);
	}
	void* current = context->previousState->map(context->previousState, size, MAP_READ);
	void* next = context->currentState->map(context->currentState, size, MAP_READ);
	diffPatchFast(patch, current, next, size);
	context->previousState->unmap(context->previousState, current, size);
	context->currentState->unmap(context->currentState, next, size);
}

void pk_rewind_init(struct mCoreRewindContext* context, size_t entries) {
	mCoreRewindContextInit(context, entries, false);
	context->current = 0;
}

void pk_rewind_deinit(struct mCoreRewindContext* context) {
	mCoreRewindContextDeinit(context);
}

void pk_rewind_append(struct mCoreRewindContext* context, struct mCore* core) {
	if (!context->currentState) {
		return;
	}
	struct VFile* nextState = context->previousState;
	mCoreSaveStateNamed(core, nextState, PK_REWIND_FLAGS);
	context->previousState = context->currentState;
	context->currentState = nextState;
	pk_rewindDiff(context);
}

bool pk_rewind_restore(struct mCoreRewindContext* context, struct mCore* core) {
	if (!context->currentState || !context->size) {
		return false;
	}
	--context->size;

	mCoreLoadStateNamed(core, context->previousState, PK_REWIND_FLAGS);
	if (context->current == 0) {
		context->current = mCoreRewindPatchesSize(&context->patchMemory);
	}
	--context->current;

	if (context->size) {
		struct PatchFast* patch = mCoreRewindPatchesGetPointer(&context->patchMemory, context->current);
		size_t size2 = context->previousState->size(context->previousState);
		size_t size = context->currentState->size(context->currentState);
		if (size2 < size) {
			size = size2;
		}
		void* current = context->currentState->map(context->currentState, size, MAP_READ);
		void* previous = context->previousState->map(context->previousState, size, MAP_WRITE);
		patch->d.applyPatch(&patch->d, previous, size, current, size);
		context->currentState->unmap(context->currentState, current, size);
		context->previousState->unmap(context->previousState, previous, size);
	}
	struct VFile* nextState = context->previousState;
	context->previousState = context->currentState;
	context->currentState = nextState;
	return true;
}
