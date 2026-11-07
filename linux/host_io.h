/* Linux host policies around upstream pk_savevf; no game-specific addresses. */
#ifndef POCKET_HOST_IO_H
#define POCKET_HOST_IO_H
#include <mgba-util/vfs.h>
#include <time.h>
struct pocket_save_status {
  bool failed, closed;
};
/* Takes inner on success or failure. status must outlive the returned VFile. */
struct VFile *pocket_save_wrap(struct VFile *inner,
                               struct pocket_save_status *status);
/* Copies a battery save into DIR/pokedaisy-backups/ before the game opens it,
 * as the Android app does (SaveBackups.kt): <name>.backup-<yyyymmdd-hhmmss>.<ext>,
 * only when it differs from the newest copy (mGBA's 16-byte RTC footer
 * ignored), keeping the newest POCKET_BACKUP_KEEP per save. False only when a
 * needed copy couldn't be written; no save, or an empty one, needs none. */
#define POCKET_BACKUP_DIR "pokedaisy-backups"
#define POCKET_BACKUP_KEEP 10
/* plorpOS: where the copies go instead of beside the save; NULL = beside it. */
extern const char *pocket_backup_dir;
bool pocket_save_backup(const char *savePath, time_t now);
#endif
