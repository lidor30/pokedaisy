// The save file as mGBA sees it: the file's own VFile, except that a map() the file can't give is made from memory.
//
// mGBA maps the save (flash, SRAM, EEPROM) with mmap(MAP_SHARED, PROT_WRITE) and reads it through that pointer from
// then on, never checking the result. A save opened read-only (the file belongs to another app and All files access
// is off: pkInit falls back to O_RDONLY) can't be mapped writable, so the map came back NULL and the game's first flash
// read crashed in GBASavedataReadFlash (Crashlytics, 1.2.1 / 1.3.0 on the Thor). A file system that refuses shared
// mappings ends the same way. Here such a map is a copy of the file in memory, written back where mGBA syncs or unmaps
// it - what mGBA itself does on platforms without mmap (vfs-fd.c's other _vfdMap). A read-only file's writes just
// don't stick, as before.
#include "pk_savevf.h"

#include <stdlib.h>
#include <string.h>

struct PkSaveVf {
    struct VFile d;
    struct VFile* inner;
    void* copy;         // the memory map handed out when the file's own failed; NULL = none
    size_t copySize;
    bool writeBack;     // mapped MAP_WRITE: unmap / sync put it in the file
};

static bool pkSvClose(struct VFile* vf) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    bool ok = sv->inner->close(sv->inner);
    free(sv->copy);
    free(sv);
    return ok;
}

static off_t pkSvSeek(struct VFile* vf, off_t offset, int whence) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    return sv->inner->seek(sv->inner, offset, whence);
}

static ssize_t pkSvRead(struct VFile* vf, void* buffer, size_t size) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    return sv->inner->read(sv->inner, buffer, size);
}

static ssize_t pkSvReadline(struct VFile* vf, char* buffer, size_t size) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    return sv->inner->readline(sv->inner, buffer, size);
}

static ssize_t pkSvWrite(struct VFile* vf, const void* buffer, size_t size) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    return sv->inner->write(sv->inner, buffer, size);
}

// The copy into the file's start, the file position kept.
static bool pkSvWriteBack(struct PkSaveVf* sv, const void* data, size_t size) {
    off_t pos = sv->inner->seek(sv->inner, 0, SEEK_CUR);
    sv->inner->seek(sv->inner, 0, SEEK_SET);
    ssize_t n = sv->inner->write(sv->inner, data, size);
    sv->inner->seek(sv->inner, pos, SEEK_SET);
    return n == (ssize_t) size;
}

static void* pkSvMap(struct VFile* vf, size_t size, int flags) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    void* mapped = sv->inner->map(sv->inner, size, flags);
    if (mapped || !size) {
        return mapped;
    }
    // One copy at a time (mGBA maps the save once, unmapping before it maps again).
    free(sv->copy);
    sv->copy = calloc(1, size);
    if (!sv->copy) {
        sv->copySize = 0;
        return NULL;
    }
    sv->copySize = size;
    sv->writeBack = (flags & MAP_WRITE) != 0;
    off_t pos = sv->inner->seek(sv->inner, 0, SEEK_CUR);
    sv->inner->seek(sv->inner, 0, SEEK_SET);
    ssize_t n = sv->inner->read(sv->inner, sv->copy, size);
    sv->inner->seek(sv->inner, pos, SEEK_SET);
    // Past the file's end: what a mapping shows there (zeros); mGBA fills erased flash itself.
    (void) n;
    return sv->copy;
}

static void pkSvUnmap(struct VFile* vf, void* memory, size_t size) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    if (memory && memory == sv->copy) {
        if (sv->writeBack) {
            pkSvWriteBack(sv, sv->copy, size < sv->copySize ? size : sv->copySize);
        }
        free(sv->copy);
        sv->copy = NULL;
        sv->copySize = 0;
        return;
    }
    sv->inner->unmap(sv->inner, memory, size);
}

static void pkSvTruncate(struct VFile* vf, size_t size) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    sv->inner->truncate(sv->inner, size);
}

static ssize_t pkSvSize(struct VFile* vf) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    return sv->inner->size(sv->inner);
}

static bool pkSvSync(struct VFile* vf, void* buffer, size_t size) {
    struct PkSaveVf* sv = (struct PkSaveVf*) vf;
    if (buffer && buffer == sv->copy) {
        bool ok = !sv->writeBack || pkSvWriteBack(sv, sv->copy, size < sv->copySize ? size : sv->copySize);
        return sv->inner->sync(sv->inner, NULL, 0) && ok;
    }
    return sv->inner->sync(sv->inner, buffer, size);
}

struct VFile* pk_save_vf(struct VFile* inner) {
    if (!inner) {
        return NULL;
    }
    struct PkSaveVf* sv = calloc(1, sizeof(*sv));
    if (!sv) {
        return inner;
    }
    sv->inner = inner;
    sv->d.close = pkSvClose;
    sv->d.seek = pkSvSeek;
    sv->d.read = pkSvRead;
    sv->d.readline = pkSvReadline;
    sv->d.write = pkSvWrite;
    sv->d.map = pkSvMap;
    sv->d.unmap = pkSvUnmap;
    sv->d.truncate = pkSvTruncate;
    sv->d.size = pkSvSize;
    sv->d.sync = pkSvSync;
    return &sv->d;
}
