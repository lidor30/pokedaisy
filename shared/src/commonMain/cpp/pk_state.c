// Save states behind the shared C API. Android keeps only the DS dispatch in JNI.
#include "pk_host.h"
#include "pk_wide.h"
#include <mgba/core/serialize.h>
#include <mgba-util/vfs.h>
#include <fcntl.h>
#include <string.h>
#include "pk_session.h"   // last: g is a macro

bool pk_api_save_state(const char* path) {
    if (!path || !*path) return false;
    pthread_rwlock_wrlock(&pk_coreLock);
    bool ok = false;
    if (g.core) {
        struct VFile* vf = VFileOpen(path, O_RDWR | O_CREAT | O_TRUNC);
        if (vf) {
            ok = mCoreSaveStateNamed(g.core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_METADATA);
            vf->close(vf);
        }
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return ok;
}

// True if the save file holds exactly [data] (bytes it doesn't have yet count as
// 0xFF, erased flash: mGBA fills a short file with that). Only the first
// [size] bytes count, so mGBA's RTC footer on a .sav doesn't.
static bool pk_save_file_equals(const uint8_t* data, size_t size) {
    struct VFile* vf = g.saveVf;
    if (!vf || g.saveReadOnly) {
        return true;                    // no file a state load could overwrite
    }
    uint8_t buf[4096];
    size_t off = 0;
    vf->seek(vf, 0, SEEK_SET);
    while (off < size) {
        size_t want = size - off < sizeof(buf) ? size - off : sizeof(buf);
        ssize_t n = vf->read(vf, buf, want);
        if (n <= 0) {
            break;
        }
        if (memcmp(buf, data + off, (size_t) n) != 0) {
            return false;
        }
        off += (size_t) n;
    }
    for (; off < size; ++off) {
        if (data[off] != 0xFF) {
            return false;
        }
    }
    return true;
}


int pk_api_state_matches_save(const char* path) {
    if (!path || !*path) return -1;
    pthread_rwlock_wrlock(&pk_coreLock);
    int result = -1;
    struct VFile* vf = g.core ? VFileOpen(path, O_RDONLY) : NULL;
    if (vf) {
        struct mStateExtdata extdata;
        mStateExtdataInit(&extdata);
        if (mCoreExtractExtdata(g.core, vf, &extdata)) {
            struct mStateExtdataItem item;
            if (mStateExtdataGet(&extdata, EXTDATA_SAVEDATA, &item) && item.data && item.size > 0) {
                result = pk_save_file_equals(item.data, (size_t) item.size) ? 1 : 0;
            }
        }
        mStateExtdataDeinit(&extdata);
        vf->close(vf);
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return result;
}

bool pk_api_load_state(const char* path) {
    if (!path || !*path) return false;
    pthread_rwlock_wrlock(&pk_coreLock);
    bool ok = false;
    struct VFile* vf = g.core ? VFileOpen(path, O_RDONLY) : NULL;
    if (vf) {
        ok = mCoreLoadStateNamed(g.core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
        vf->close(vf);
        if (ok) {
            pk_heal_rtc(g.core);
            g.audioAvail = 0;
            pkRewindReset();
        }
        pk_wide_reset();
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return ok;
}
