/* The library (library.h): the app's LibraryActivity drawn with the companion's pieces (3ds/core). */
#include "library.h"

// Names and paths are cut to their buffers on purpose.
#pragma GCC diagnostic ignored "-Wformat-truncation"

#include <SDL.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#ifndef _WIN32
#include <spawn.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#ifndef _WIN32
#include <sys/wait.h>
#endif
#include <time.h>
#include <unistd.h>

#include "cover_png.h"
#include "pd_fs.h"
#include "pd_games.h"
#include "pd_icons_gen.h"
#include "pd_library.h"
#include "pd_settings.h"
#include "pd_sha1.h"
#include "pd_style.h"

/* The 3DS library's art (pd_library_draw.c): the rail's icons and the flower at 24 px. */
extern const struct pd_art LIB_ICON_GAMES, LIB_ICON_EDIT, LIB_ICON_TRADE, LIB_ICON_SETTINGS;
void lib_draw_logo_24(struct pd_canvas *c, int x, int y);

#define COVER 320 // a cover's square, in pixels: drawn at the screen's resolution, not the canvas's
#define MAX_GAMES 1024
#define FOCUS_RING 0xFFB238 // AppColors.focusRing
#define M 8                 // the page's margin
#define HEADER_H 30
#define PILL_H 22
#define GAP 8

enum console { C_GB, C_GBC, C_GBA, C_DS };
enum view { V_ALL, V_FAV, V_RECENT, V_GB, V_GBC, V_GBA, V_DS, V_HACKS, V_COUNT };
enum page { P_GAMES, P_TOOLS, P_SETTINGS, P_COUNT };
enum focus { F_RAIL, F_HEADER, F_PILLS, F_GAMES };
enum sort { S_NAME, S_RECENT, S_COUNT };
enum { CS_NONE, CS_PATH, CS_READY, CS_FAILED };

struct game {
  char path[PATH_MAX], file[256], title[96], coverPath[PATH_MAX];
  enum console console;
  bool hack, fav, favSet, hasSave;
  unsigned long long played; // YYYYMMDDhhmmss, 0 = never
  long size, mtime;
  char sha1[41];
  bool sha1Known, named;
  // Filled by the worker: the cover's pixels, then its state; a hash, then hashed.
  uint32_t *coverPx;
  SDL_atomic_t coverState, hashed;
  char workerSha1[41]; // the worker's own copy (it never reads sha1, the UI thread's): set, then hashed = 2
  unsigned serial;
  char coverFile[PATH_MAX]; // DATA/covers/<file>.png: a fetched cover
  bool coverFit;            // a box (no square icon on SteamGridDB): drawn whole
  char code[5];             // a DS cart's game code
};

static struct game *games;
static int gameCount;
static int shown[MAX_GAMES], shownCount, counts[V_COUNT];
static char dataDir[PATH_MAX], savesDir[PATH_MAX], stateFile[PATH_MAX], titlesFile[PATH_MAX], settingsFile[PATH_MAX];
static const struct lib_host *host;
static struct pd_settings settings;
static int page = P_GAMES, focus = F_GAMES, view = V_ALL, sortBy = S_NAME, railCursor, headerCursor;
static bool grid = true, quit, menuOpen, closeAsk;
static int faceColors; // BUTTON COLORS: 0 the app's (red A, yellow B, blue X, green Y), 1 green A, red B, blue X, yellow Y
static int selected, scroll, columns = 4, menuCursor, settingsCursor, settingsScroll, toolCursor;
static const char *picked;
static char notice[96];
static unsigned noticeUntil;
static SDL_Thread *worker;
static SDL_atomic_t stopWorker, pauseWorker;
// Where the games were drawn last frame (for library_covers) and the content's place.
static int contentY, contentH, contentX, contentW;

/* What the last draw laid out under the pointer: the same rectangles it drew, so clicks need no second copy of the layout. */
enum {
  LIB_HIT_RAIL = 1, LIB_HIT_SORT, LIB_HIT_GRID, LIB_HIT_PILL, LIB_HIT_GAME, LIB_HIT_SETTING, LIB_HIT_TOOL, LIB_HIT_MENU, LIB_HIT_CLOSE,
};
struct lib_hit {
  int x, y, w, h, action, arg;
};
static struct lib_hit libHits[512];
static int libHitCount;
static void hit_add(int x, int y, int w, int h, int action, int arg) {
  if (w > 0 && h > 0 && libHitCount < (int)(sizeof libHits / sizeof *libHits))
    libHits[libHitCount++] = (struct lib_hit){x, y, w, h, action, arg};
}
/* A target cut to the clip its draw used, so a click on the hidden part of a scrolled row doesn't hit it. */
static void hit_add_clipped(int x, int y, int w, int h, int clipX, int clipY, int clipW, int clipH, int action, int arg) {
  int x0 = x > clipX ? x : clipX, y0 = y > clipY ? y : clipY;
  int x1 = x + w < clipX + clipW ? x + w : clipX + clipW;
  int y1 = y + h < clipY + clipH ? y + h : clipY + clipH;
  if (x1 > x0 && y1 > y0)
    hit_add(x0, y0, x1 - x0, y1 - y0, action, arg);
}

/* ---- names ---- */

static const char *const RETAIL[] = {
    "Red", "Blue", "Green", "Yellow", "Gold", "Silver", "Crystal", "Ruby", "Sapphire", "Emerald", "FireRed", "LeafGreen",
    "Diamond", "Pearl", "Platinum", "HeartGold", "SoulSilver", "Black", "White", "Rote", "Blaue", "Grune", "Gelbe",
    "Goldene", "Silberne", "Kristall", "Rubin", "Saphir", "Smaragd", "Feuerrote", "Blattgrune", "Diamant", "Perl",
    "Platin", "Schwarze", "Weisse", "Weiße", "Kin", "Gin", "Aka", "Midori", "Ao", "Pikachu"};

/* A retail cart's name: "<Pokemon> - <word>" with nothing after but Version / Edition (and its other half) or a 2. */
static bool retail_name(const char *title) {
  const char *rest = title;
  if (!strncasecmp(rest, "Pokemon - ", 10))
    rest += 10;
  else if (!strncasecmp(rest, "Pokémon ", 9))
    rest += 9;
  else if (!strncasecmp(rest, "Pokemon ", 8))
    rest += 8;
  else if (!strncasecmp(rest, "Pocket Monsters ", 16))
    rest += 16;
  else if (!strncasecmp(rest, "Pocket Monsters - ", 18))
    rest += 18;
  else
    return false;
  for (size_t i = 0; i < sizeof RETAIL / sizeof *RETAIL; i++) {
    size_t n = strlen(RETAIL[i]);
    if (strncasecmp(rest, RETAIL[i], n))
      continue;
    const char *after = rest + n;
    if (!*after || !strcasecmp(after, " Version") || !strncasecmp(after, " Edition", 8) ||
        !strncasecmp(after, "-Edition", 8) || !strcmp(after, " 2") || !strcmp(after, " 2 Version"))
      return true;
  }
  return false;
}

/* The app's way of naming a file it doesn't know: "Pokemon - Crystal Version" is "Pokémon Crystal". */
static void pretty(const char *tidy, char *out, size_t len) {
  const char *rest = tidy;
  if (!strncmp(rest, "Pokemon - ", 10))
    rest += 10;
  else if (!strncmp(rest, "Pokemon ", 8))
    rest += 8;
  else
    rest = NULL;
  if (rest)
    snprintf(out, len, "Pokémon %s", rest);
  else
    snprintf(out, len, "%s", tidy);
  size_t n = strlen(out);
  if (n > 8 && !strcmp(out + n - 8, " Version"))
    out[n - 8] = 0;
}

static void name_game(struct game *g) {
  bool hack = false, supported = false;
  char tidy[96];
  pd_library_title(g->file, tidy, sizeof tidy, &hack, &supported);
  if (supported) {
    snprintf(g->title, sizeof g->title, "%s", tidy);
    g->hack = hack;
    g->named = true;
    return;
  }
  pretty(tidy, g->title, sizeof g->title);
  g->hack = !retail_name(tidy);
}

static void name_by_sha1(struct game *g) {
  const struct pd_game_entry *e = pd_game_entry_for(g->sha1);
  if (!e)
    return;
  snprintf(g->title, sizeof g->title, "%s", e->title);
  g->hack = strncmp(e->file, "Pokemon - ", 10) && strncmp(e->file, "Pocket Monsters - ", 18);
  g->named = true;
}

/* ---- the saved state: DATA/library.txt and the titles' cache ---- */

static struct game *by_file(const char *file) {
  for (int i = 0; i < gameCount; i++)
    if (!strcmp(games[i].file, file))
      return &games[i];
  return NULL;
}

static void state_save(void) {
  FILE *f = fopen(stateFile, "w");
  if (!f)
    return;
  fprintf(f, "view=%d\nsort=%d\ngrid=%d\nfaces=%d\n", view, sortBy, grid, faceColors);
  for (int i = 0; i < gameCount; i++) {
    if (games[i].favSet)
      fprintf(f, "fav\t%d\t%s\n", games[i].fav, games[i].file);
    if (games[i].played)
      fprintf(f, "played\t%llu\t%s\n", games[i].played, games[i].file);
  }
  fclose(f);
}

static void state_load(void) {
  FILE *f = fopen(stateFile, "r");
  char line[PATH_MAX + 64];
  while (f && fgets(line, sizeof line, f)) {
    line[strcspn(line, "\r\n")] = 0;
    int v;
    unsigned long long t;
    char file[256];
    if (sscanf(line, "view=%d", &v) == 1 && v >= 0 && v < V_COUNT)
      view = v;
    else if (sscanf(line, "sort=%d", &v) == 1 && v >= 0 && v < S_COUNT)
      sortBy = v;
    else if (sscanf(line, "grid=%d", &v) == 1)
      grid = v != 0;
    else if (sscanf(line, "faces=%d", &v) == 1)
      faceColors = v == 1;
    else if (sscanf(line, "fav\t%d\t%255[^\n]", &v, file) == 2 && by_file(file)) {
      by_file(file)->fav = v != 0;
      by_file(file)->favSet = true;
    } else if (sscanf(line, "played\t%llu\t%255[^\n]", &t, file) == 2 && by_file(file))
      by_file(file)->played = t;
  }
  if (f)
    fclose(f);
}

static void titles_save(void) {
  FILE *f = fopen(titlesFile, "w");
  for (int i = 0; f && i < gameCount; i++)
    if (games[i].sha1Known)
      fprintf(f, "%ld\t%ld\t%s\t%s\n", games[i].size, games[i].mtime, games[i].sha1, games[i].file);
  if (f)
    fclose(f);
}

static void titles_load(void) {
  FILE *f = fopen(titlesFile, "r");
  char line[PATH_MAX + 128], sha1[41], file[256];
  long size, mtime;
  while (f && fgets(line, sizeof line, f)) {
    line[strcspn(line, "\r\n")] = 0;
    struct game *g;
    if (sscanf(line, "%ld\t%ld\t%40[0-9a-f]\t%255[^\n]", &size, &mtime, sha1, file) == 4 && (g = by_file(file)) &&
        g->size == size && g->mtime == mtime) {
      snprintf(g->sha1, sizeof g->sha1, "%s", sha1);
      snprintf(g->workerSha1, sizeof g->workerSha1, "%s", sha1); // before the worker starts
      g->sha1Known = true;
      SDL_AtomicSet(&g->hashed, 1);
      name_by_sha1(g);
    }
  }
  if (f)
    fclose(f);
}

/* ---- the firmware's gamelist.xml: covers, favorites, last played ---- */

static void xml_text(const char *from, const char *to, char *out, size_t len) {
  size_t o = 0;
  while (from < to && o + 1 < len) {
    static const struct { const char *ent, *ch; } ENT[] = {{"&amp;", "&"}, {"&apos;", "'"}, {"&quot;", "\""}, {"&lt;", "<"}, {"&gt;", ">"}};
    bool hit = false;
    for (size_t i = 0; i < sizeof ENT / sizeof *ENT; i++) {
      size_t n = strlen(ENT[i].ent);
      if (from + n <= to && !strncmp(from, ENT[i].ent, n)) {
        out[o++] = ENT[i].ch[0];
        from += n;
        hit = true;
        break;
      }
    }
    if (!hit)
      out[o++] = *from++;
  }
  out[o] = 0;
}

static bool xml_tag(const char *block, const char *end, const char *tag, char *out, size_t len) {
  char open[32], close[32];
  snprintf(open, sizeof open, "<%s>", tag);
  snprintf(close, sizeof close, "</%s>", tag);
  const char *a = strstr(block, open);
  if (!a || a >= end)
    return false;
  a += strlen(open);
  const char *b = strstr(a, close);
  if (!b || b > end)
    return false;
  xml_text(a, b, out, len);
  return true;
}

static bool is_png(const char *path) {
  size_t n = strlen(path);
  struct stat st;
  return n > 4 && !strcasecmp(path + n - 4, ".png") && !stat(path, &st);
}

static void gamelist_read(const char *dir) {
  char path[PATH_MAX];
  snprintf(path, sizeof path, "%s/gamelist.xml", dir);
  FILE *f = fopen(path, "rb");
  if (!f)
    return;
  fseek(f, 0, SEEK_END);
  long size = ftell(f);
  fseek(f, 0, SEEK_SET);
  char *xml = size > 0 && size < (32l << 20) ? malloc((size_t)size + 1) : NULL;
  if (xml && fread(xml, 1, (size_t)size, f) == (size_t)size)
    xml[size] = 0;
  else {
    free(xml);
    xml = NULL;
  }
  fclose(f);
  for (char *at = xml ? strstr(xml, "<game") : NULL; at; at = strstr(at + 5, "<game")) {
    char *end = strstr(at, "</game>");
    if (!end)
      break;
    char rel[PATH_MAX], value[PATH_MAX];
    if (!xml_tag(at, end, "path", rel, sizeof rel)) {
      at = end;
      continue;
    }
    const char *file = strrchr(rel, '/') ? strrchr(rel, '/') + 1 : rel;
    for (int i = 0; i < gameCount; i++) {
      struct game *g = &games[i];
      if (strcmp(g->file, file))
        continue;
      static const char *const ART[] = {"thumbnail"}; // box art; "image" is often a screenshot
      for (size_t k = 0; k < 1 && !g->coverPath[0]; k++) {
        if (!xml_tag(at, end, ART[k], value, sizeof value))
          continue;
        char full[PATH_MAX * 2];
        snprintf(full, sizeof full, "%s/%s", dir, value[0] == '.' && value[1] == '/' ? value + 2 : value);
        if (value[0] == '/')
          snprintf(full, sizeof full, "%s", value);
        if (is_png(full))
          snprintf(g->coverPath, sizeof g->coverPath, "%s", full);
      }
      if (!g->favSet && xml_tag(at, end, "favorite", value, sizeof value))
        g->fav = !strcmp(value, "true");
      if (!g->played && xml_tag(at, end, "lastplayed", value, sizeof value)) {
        char digits[20];
        int n = 0;
        for (const char *p = value; *p && n < 14; p++)
          if (isdigit((unsigned char)*p))
            digits[n++] = *p;
        digits[n] = 0;
        if (n == 14)
          g->played = strtoull(digits, NULL, 10);
      }
    }
    at = end;
  }
  free(xml);
}

/* A game EmulationStation never listed: its scraper's file name in an images/ folder beside any of the games. */
static void cover_guess(struct game *g) {
  char base[256];
  snprintf(base, sizeof base, "%s", g->file);
  char *dot = strrchr(base, '.');
  if (dot)
    *dot = 0;
  for (int i = 0; i < gameCount && !g->coverPath[0]; i++) {
    char dir[PATH_MAX];
    snprintf(dir, sizeof dir, "%s", games[i].path);
    char *slash = strrchr(dir, '/');
    if (!slash)
      continue;
    *slash = 0;
    static const char *const KIND[] = {"thumb"};
    for (size_t k = 0; k < 1 && !g->coverPath[0]; k++) {
      char path[PATH_MAX * 2];
      snprintf(path, sizeof path, "%s/images/%s-%s.png", dir, base, KIND[k]);
      if (is_png(path))
        snprintf(g->coverPath, sizeof g->coverPath, "%s", path);
    }
  }
}

/* ---- SteamGridDB (the app's SteamGridDbClient): square icons, the preferred uploader's first, through the firmware's
 * curl. Only with the player's own key (DATA/steamgriddb-key.txt; never bundled), each game once: DATA/covers/<file>.png,
 * or <file>.none when SteamGridDB answered that it has nothing. No answer at all (offline, a timeout, no curl) writes
 * nothing: that game is asked for again next start, and a few in a row end the pass for this one. ---- */

#define SGDB_API "https://www.steamgriddb.com/api/v2"
#define SGDB_PREFERRED "76561198034412095" // SteamGridDbClient.PREFERRED_AUTHOR_STEAM64
static char sgdbKey[128];

/* SteamGridDbGames.BY_SHA1: the hacks' entries, by the whole file's SHA-1. */
static const struct { const char *sha1; int id; } SGDB_GAMES[] = {
    {"b4776b82a4c7915d0fadeaa27e013523f99dfd94", 5274554}, {"0ce2a880aa097f1dce4e1db8ee513d0e82d15859", 5274554},
    {"d5b1e77975fcda831e0e9a7b527906bf3f40ecd0", 5345283}, {"964f951a0fdaf209e4ea1344883ef0d557bb3a80", 5274514},
    {"8745ddbdbfadf6abaf66de4e9055923b62eb4668", 5445119}, {"79ee6df0869c1773c8c6a5f764afc1f8d833d8bb", 5503721},
    {"7dcdc7e280bc4631487e13dd37e6e0cea04adea6", 5505418}, {"81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77", 5355865},
    {"7600af1fe08444c850c3c1227fd7dfd81336ae8e", 5361485}, {"b9f4d332d30fc88c379f9e037f9eae3b2755ead4", 5462842},
};

static void sgdb_key_load(void) {
  char path[PATH_MAX + 32];
  snprintf(path, sizeof path, "%s/steamgriddb-key.txt", dataDir);
  sgdbKey[0] = 0;
  FILE *f = fopen(path, "r");
  if (!f)
    return;
  char line[256] = {0};
  if (fgets(line, sizeof line, f)) {
    size_t n = 0;
    for (const char *p = line; *p && n + 1 < sizeof sgdbKey; p++)
      if (isalnum((unsigned char)*p))
        sgdbKey[n++] = *p;
      else if (!isspace((unsigned char)*p)) {
        n = 0; // not a key
        break;
      }
    sgdbKey[n] = 0;
  }
  fclose(f);
}

/* While a game plays the worker waits (no hashing, no downloads); true once it's to stop. */
static bool stopping(void) {
  while (SDL_AtomicGet(&pauseWorker) && !SDL_AtomicGet(&stopWorker))
    SDL_Delay(100);
  return SDL_AtomicGet(&stopWorker) != 0;
}

/* Only what a URL of ours or SteamGridDB's CDN holds. */
static bool url_safe(const char *u) {
  if (strncmp(u, "https://", 8))
    return false;
  for (const char *p = u; *p; p++)
    if (!isalnum((unsigned char)*p) && !strchr(":/._-%?=&~", *p))
      return false;
  return true;
}

enum net {
  NET_OK,   // an answer with something in it
  NET_NONE, // SteamGridDB answered: nothing there
  NET_FAIL, // no answer (offline, DNS, a timeout, a failed download, the server's own trouble): ask again next start
  NET_STOP, // no use going on: no curl, or the key refused
};

/* curl runs without a shell, the key in a config file only we can read (never on its command line, which `ps` shows);
 * library_close kills a running one, so quitting doesn't wait for a download. */
static char curlConfig[PATH_MAX];
static SDL_mutex *curlLock;
#ifndef _WIN32
static pid_t curlPid;
extern char **environ;

static bool curl_config_write(void) {
  const char *tmp = getenv("TMPDIR");
  for (int at = 0; at < 3; at++) {
    const char *dir = at == 0 ? tmp : at == 1 ? "/tmp" : dataDir;
    if (!dir || !*dir)
      continue;
    snprintf(curlConfig, sizeof curlConfig, "%s/pokedaisy-sgdb-XXXXXX", dir);
    int fd = mkstemp(curlConfig); // 0600
    if (fd < 0)
      continue;
    char text[256];
    int n = snprintf(text, sizeof text, "header = \"Authorization: Bearer %s\"\n", sgdbKey);
    bool ok = write(fd, text, (size_t)n) == n;
    close(fd);
    if (ok)
      return true;
    remove(curlConfig);
  }
  curlConfig[0] = 0;
  return false;
}

/* curl [args] -> its exit status, -1 when it couldn't start (or the worker is stopping); its output into *out (malloc'd,
 * NUL-ended) when out is given. */
static int curl_run(const char *const *args, char **out) {
  if (out)
    *out = NULL;
  int fd[2] = {-1, -1};
  if (out && pipe(fd))
    return -1;
  if (out) { // ours alone: not handed to curl but as its stdout, nor to anything else started meanwhile
    fcntl(fd[0], F_SETFD, FD_CLOEXEC);
    fcntl(fd[1], F_SETFD, FD_CLOEXEC);
  }
  posix_spawn_file_actions_t fa;
  posix_spawn_file_actions_init(&fa);
  posix_spawn_file_actions_addopen(&fa, 2, "/dev/null", O_WRONLY, 0);
  if (out)
    posix_spawn_file_actions_adddup2(&fa, fd[1], 1);
  else
    posix_spawn_file_actions_addopen(&fa, 1, "/dev/null", O_WRONLY, 0);
  const char *argv[24];
  int n = 0;
  argv[n++] = "curl";
  argv[n++] = "-K";
  argv[n++] = curlConfig;
  for (int i = 0; args[i] && n < 23; i++)
    argv[n++] = args[i];
  argv[n] = NULL;
  pid_t pid = 0;
  SDL_LockMutex(curlLock);
  int err = SDL_AtomicGet(&stopWorker) ? ECANCELED : posix_spawnp(&pid, "curl", &fa, NULL, (char *const *)argv, environ);
  if (!err)
    curlPid = pid;
  SDL_UnlockMutex(curlLock);
  posix_spawn_file_actions_destroy(&fa);
  if (out)
    close(fd[1]);
  if (err) {
    if (out)
      close(fd[0]);
    return -1;
  }
  if (out) {
    size_t len = 0, cap = 1 << 16;
    char *body = malloc(cap), drain[4096];
    bool whole = body != NULL;
    for (;;) {
      ssize_t got = whole ? read(fd[0], body + len, cap - len - 1) : read(fd[0], drain, sizeof drain);
      if (got < 0 && errno == EINTR)
        continue;
      if (got <= 0)
        break;
      if (!whole)
        continue; // too big: read to its end so curl can finish, keep none of it
      len += (size_t)got;
      if (len + 1 >= cap) {
        char *grown = cap < (4u << 20) ? realloc(body, cap * 2) : NULL;
        if (grown) {
          body = grown;
          cap *= 2;
        } else {
          free(body);
          body = NULL;
          whole = false;
        }
      }
    }
    close(fd[0]);
    if (body)
      body[len] = 0;
    *out = body;
  }
  SDL_LockMutex(curlLock);
  curlPid = 0; // still unreaped here, so a kill until now hit this curl, never another process
  SDL_UnlockMutex(curlLock);
  int status = 0;
  while (waitpid(pid, &status, 0) < 0 && errno == EINTR)
    ;
  int rc = WIFEXITED(status) ? WEXITSTATUS(status) : 255;
  if (rc && out) {
    free(*out);
    *out = NULL;
  }
  return rc;
}

static void curl_kill(void) {
  if (!curlLock)
    return;
  SDL_LockMutex(curlLock);
  if (curlPid > 0)
    kill(curlPid, SIGKILL);
  SDL_UnlockMutex(curlLock);
}
#else
/* No posix_spawn on Windows: local covers still show; SteamGridDB downloads wait for a port that can run curl. */
static bool curl_config_write(void) { return false; }
static int curl_run(const char *const *args, char **out) {
  (void)args;
  if (out)
    *out = NULL;
  return -1;
}
static void curl_kill(void) {}
#endif

/* GET [url] with the key: NET_OK with the body (malloc'd) in *body, else what kind of nothing. */
static enum net http_get(const char *url, char **body) {
  *body = NULL;
  if (!url_safe(url))
    return NET_NONE;
  if (stopping())
    return NET_FAIL;
  const char *args[] = {"-sS", "--connect-timeout", "8", "--max-time", "20", "-w", "\n%{http_code}", url, NULL};
  char *out = NULL;
  int rc = curl_run(args, &out);
  if (rc < 0) {
    if (!SDL_AtomicGet(&stopWorker))
      fprintf(stderr, "Cover: no curl: no covers from SteamGridDB\n");
    return SDL_AtomicGet(&stopWorker) ? NET_FAIL : NET_STOP;
  }
  if (rc || !out)
    return NET_FAIL;
  char *nl = strrchr(out, '\n');
  int code = nl ? atoi(nl + 1) : 0;
  if (nl)
    *nl = 0;
  if (code == 200) {
    *body = out;
    return NET_OK;
  }
  free(out);
  if (code == 404)
    return NET_NONE;
  if (code == 401 || code == 403) {
    fprintf(stderr, "Cover: SteamGridDB refused the key (HTTP %d)\n", code);
    return NET_STOP;
  }
  return NET_FAIL; // 429, 5xx: next start
}

/* A JSON string's value after [key] from [from] (\/ unescaped), false if none before [end]. */
static bool json_string(const char *from, const char *end, const char *key, char *out, size_t len) {
  char pat[48];
  snprintf(pat, sizeof pat, "\"%s\":\"", key);
  const char *a = strstr(from, pat);
  if (!a || (end && a >= end))
    return false;
  a += strlen(pat);
  size_t o = 0;
  for (; *a && *a != '"' && o + 1 < len; a++) {
    if (*a == '\\' && a[1])
      a++;
    out[o++] = *a;
  }
  out[o] = 0;
  return true;
}

/* A hit's name holds every telling word of ours (not "Pokemon", "Version", "Edition"): letters and digits, any case.
 * [loose]: a last single digit of ours may be missing (Heart & Soul 2 is SteamGridDB's Heart & Soul). */
static bool name_matches(const char *ours, const char *hit, bool loose) {
  char words[8][32];
  int n = 0, len = 0;
  for (const char *p = ours;; p++) {
    if (*p && isalnum((unsigned char)*p) && len < 31) {
      words[n][len++] = (char)tolower((unsigned char)*p);
      continue;
    }
    if (len) {
      words[n][len] = 0;
      if (strcmp(words[n], "pokemon") && strcmp(words[n], "pok") && strcmp(words[n], "mon") && strcmp(words[n], "version") &&
          strcmp(words[n], "edition") && n < 7)
        n++;
      len = 0;
    }
    if (!*p)
      break;
  }
  if (loose && n > 1 && strlen(words[n - 1]) == 1 && isdigit((unsigned char)words[n - 1][0]))
    n--;
  if (!n)
    return false;
  bool pokemon = !strncasecmp(ours, "Pok", 3);
  char low[256];
  size_t k = 0;
  for (const char *p = hit; *p && k + 1 < sizeof low; p++) // letters and digits only: R.O.W.E. is rowe
    if (isalnum((unsigned char)*p))
      low[k++] = (char)tolower((unsigned char)*p);
  low[k] = 0;
  if (pokemon && strncmp(low, "pok", 3)) // ours is a Pokemon game: so is the hit (a bare "Ambrosia" is another game)
    return false;
  for (int i = 0; i < n; i++)
    if (!strstr(low, words[i]))
      return false;
  return true;
}

static void url_encode(const char *in, char *out, size_t len) {
  size_t o = 0;
  for (const char *p = in; *p && o + 4 < len; p++) {
    unsigned char ch = (unsigned char)*p;
    if (isalnum(ch) || ch == '-' || ch == '.')
      out[o++] = (char)ch;
    else
      o += (size_t)snprintf(out + o, len - o, "%%%02X", ch);
  }
  out[o] = 0;
}

/* A retail DS cart in another language goes by its English name (Weiße Edition 2 is White Version 2 there). */
static const char *ds_english(const struct game *g) {
  static const struct { const char *code, *name; } DS[] = {
      {"ADA", "Pokémon Diamond"}, {"APA", "Pokémon Pearl"}, {"CPU", "Pokémon Platinum"}, {"IPK", "Pokémon HeartGold"},
      {"IPG", "Pokémon SoulSilver"}, {"IRB", "Pokémon Black Version"}, {"IRA", "Pokémon White Version"},
      {"IRE", "Pokémon Black Version 2"}, {"IRD", "Pokémon White Version 2"}};
  for (size_t i = 0; g->console == C_DS && !g->hack && i < sizeof DS / sizeof *DS; i++)
    if (!strncmp(g->code, DS[i].code, 3))
      return DS[i].name;
  return NULL;
}

/* The game's SteamGridDB id into *id: NET_OK, NET_NONE when no search found it, else no answer. */
static enum net sgdb_game(const struct game *g, int *id) {
  *id = 0;
  bool known = g->workerSha1[0] != 0;
  for (size_t i = 0; known && i < sizeof SGDB_GAMES / sizeof *SGDB_GAMES; i++)
    if (!strcmp(SGDB_GAMES[i].sha1, g->workerSha1)) {
      *id = SGDB_GAMES[i].id;
      return NET_OK;
    }
  // Else SteamGridDB's own search, by the game's name, taken only when the hit names it. The name as the worker knows it
  // (the UI thread renames a game by its hash meanwhile).
  const struct pd_game_entry *e = known ? pd_game_entry_for(g->workerSha1) : NULL;
  const char *title = e ? e->title : ds_english(g) ? ds_english(g) : g->title;
  // Its search is literal: the name, the name without "Pokémon", and that with a capitals-only word dotted (R.O.W.E.).
  char queries[4][128];
  snprintf(queries[0], sizeof queries[0], "%s", title);
  const char *bare = !strncmp(title, "Pokémon ", 9) ? title + 9 : !strncmp(title, "Pokemon ", 8) ? title + 8 : title;
  snprintf(queries[1], sizeof queries[1], "%s", bare);
  size_t o = 0;
  for (const char *p = bare; *p && o + 3 < sizeof queries[2];) {
    const char *w = p;
    while (*p && *p != ' ')
      p++;
    bool caps = p - w >= 3 && p - w <= 6;
    for (const char *q = w; caps && q < p; q++)
      caps = isupper((unsigned char)*q) != 0;
    for (const char *q = w; q < p && o + 3 < sizeof queries[2]; q++) {
      queries[2][o++] = *q;
      if (caps)
        queries[2][o++] = '.';
    }
    if (*p && o + 2 < sizeof queries[2])
      queries[2][o++] = *p++;
  }
  queries[2][o] = 0;
  snprintf(queries[3], sizeof queries[3], "Pokemon %s", queries[2]);
  for (int loose = 0; loose < 2; loose++)
    for (int q = 0; q < 4; q++) {
      if (q && !strcmp(queries[q], queries[q - 1]))
        continue;
      char term[384], url[512];
      url_encode(queries[q], term, sizeof term);
      snprintf(url, sizeof url, SGDB_API "/search/autocomplete/%s", term);
      char *body;
      enum net r = http_get(url, &body);
      if (r == NET_FAIL || r == NET_STOP)
        return r;
      for (const char *at = body ? strstr(body, "\"id\":") : NULL; at && !*id; at = strstr(at + 5, "\"id\":")) {
        int hit = atoi(at + 5);
        const char *next = strstr(at + 5, "\"id\":");
        char name[160];
        if (hit && json_string(at, next, "name", name, sizeof name) && name_matches(title, name, loose))
          *id = hit;
      }
      free(body);
      if (*id)
        return NET_OK;
    }
  return NET_NONE;
}

/* The game's icon into DATA/covers/<file>.png: the preferred uploader's, else the best voted (the API's order). NET_OK
 * when it's there, NET_NONE when SteamGridDB has none, else no answer. */
static enum net sgdb_fetch(struct game *g) {
  int id;
  enum net r = sgdb_game(g, &id);
  if (r == NET_NONE)
    fprintf(stderr, "Cover: %s: not on SteamGridDB\n", g->file);
  if (r != NET_OK)
    return r;
  char url[256], best[512] = "", icon[512];
  bool preferred = false;
  for (int page = 0; page < 6 && !preferred; page++) {
    snprintf(url, sizeof url, SGDB_API "/icons/game/%d?mimes=image/png&page=%d", id, page);
    char *body;
    r = http_get(url, &body);
    if ((r == NET_FAIL || r == NET_STOP) && !best[0])
      return r; // a later page lost: the best of those read
    if (!body || !strstr(body, "\"success\":true")) {
      free(body);
      break;
    }
    bool any = false;
    for (const char *at = strstr(body, "\"url\":\""); at; at = strstr(at + 6, "\"url\":\"")) {
      const char *next = strstr(at + 6, "\"url\":\"");
      char author[32] = "";
      if (!json_string(at, NULL, "url", icon, sizeof icon) || !url_safe(icon))
        continue;
      any = true;
      json_string(at, next, "steam64", author, sizeof author);
      if (!best[0])
        snprintf(best, sizeof best, "%s", icon);
      if (!strcmp(author, SGDB_PREFERRED)) {
        snprintf(best, sizeof best, "%s", icon);
        preferred = true;
        break;
      }
    }
    free(body);
    if (!any)
      break;
  }
  // No square icon: the game's box (SteamGridDB's 600 x 900 grids), drawn whole.
  if (!best[0]) {
    snprintf(url, sizeof url, SGDB_API "/grids/game/%d?mimes=image/png&dimensions=600x900", id);
    char *body;
    r = http_get(url, &body);
    if (r == NET_FAIL || r == NET_STOP)
      return r;
    for (const char *at = body ? strstr(body, "\"url\":\"") : NULL; at && !best[0]; at = strstr(at + 6, "\"url\":\""))
      if (json_string(at, NULL, "url", icon, sizeof icon) && url_safe(icon))
        snprintf(best, sizeof best, "%s", icon);
    free(body);
    if (best[0]) {
      size_t n = strlen(g->coverFile);
      if (n > 4 && n + 4 < sizeof g->coverFile)
        snprintf(g->coverFile + n - 4, sizeof g->coverFile - (n - 4), ".box.png");
      g->coverFit = true;
    }
  }
  fprintf(stderr, "Cover: %s: SteamGridDB game %d, %s\n", g->file, id, best[0] ? (g->coverFit ? "a box" : "an icon") : "no art");
  if (!best[0])
    return NET_NONE;
  if (stopping())
    return NET_FAIL;
  char tmp[PATH_MAX + 8];
  snprintf(tmp, sizeof tmp, "%s.tmp", g->coverFile);
  const char *args[] = {"-fsSL", "--connect-timeout", "8", "--max-time", "30", "-o", tmp, best, NULL};
  int rc = curl_run(args, NULL);
  if (rc || rename(tmp, g->coverFile)) {
    remove(tmp);
    return rc < 0 && !SDL_AtomicGet(&stopWorker) ? NET_STOP : NET_FAIL;
  }
  return NET_OK;
}

/* ---- the worker: covers, then hashes ---- */

static int work(void *unused) {
  (void)unused;
  for (int i = 0; i < gameCount && !stopping(); i++) {
    struct game *g = &games[i];
    if (SDL_AtomicGet(&g->coverState) != CS_PATH)
      continue;
    uint32_t *px = malloc((size_t)COVER * COVER * 4);
    if (px && cover_png_square(g->coverPath, COVER, px, g->coverFit)) {
      g->coverPx = px;
      SDL_MemoryBarrierRelease();
      SDL_AtomicSet(&g->coverState, CS_READY);
    } else {
      free(px);
      SDL_AtomicSet(&g->coverState, CS_FAILED);
    }
  }
  static unsigned char buf[1 << 20];
  for (int i = 0; i < gameCount && !stopping(); i++) {
    struct game *g = &games[i];
    // DS carts run to 512 MB: they go by their file name.
    if (g->console == C_DS || SDL_AtomicGet(&g->hashed))
      continue;
    FILE *f = fopen(g->path, "rb");
    if (!f)
      continue;
    struct pd_sha1 s;
    pd_sha1_init(&s);
    size_t n;
    while (!stopping() && (n = fread(buf, 1, sizeof buf, f)) > 0)
      pd_sha1_update(&s, buf, n);
    fclose(f);
    if (SDL_AtomicGet(&stopWorker))
      break;
    pd_sha1_hex(&s, g->workerSha1); // the UI thread copies it into sha1 once hashed is 2 (library_tick)
    SDL_MemoryBarrierRelease();
    SDL_AtomicSet(&g->hashed, 2);
  }
  // No art from the firmware: SteamGridDB's, with the player's key. ".none" only once it answered there's nothing.
  bool config = sgdbKey[0] && curl_config_write();
  for (int i = 0, failed = 0; config && i < gameCount && !stopping(); i++) {
    struct game *g = &games[i];
    if (SDL_AtomicGet(&g->coverState) != CS_NONE)
      continue;
    char none[PATH_MAX + 8];
    snprintf(none, sizeof none, "%.*s.none", (int)(strlen(g->coverFile) - 4), g->coverFile);
    struct stat st;
    if (!stat(none, &st))
      continue;
    enum net r = sgdb_fetch(g);
    if (r == NET_STOP)
      break;
    if (r == NET_FAIL) {
      if (!SDL_AtomicGet(&stopWorker) && ++failed >= 3) {
        fprintf(stderr, "Cover: SteamGridDB doesn't answer: the rest next start\n");
        break;
      }
      continue;
    }
    failed = 0;
    if (r == NET_NONE) {
      FILE *f = fopen(none, "w");
      if (f)
        fclose(f);
      continue;
    }
    uint32_t *px = malloc((size_t)COVER * COVER * 4);
    if (px && cover_png_square(g->coverFile, COVER, px, g->coverFit)) {
      g->coverPx = px;
      SDL_MemoryBarrierRelease();
      SDL_AtomicSet(&g->coverState, CS_READY);
    } else
      free(px);
  }
  if (config)
    remove(curlConfig);
  return 0;
}

/* ---- the views and the order ---- */

static bool in_view(const struct game *g, int v) {
  switch (v) {
  case V_ALL: return true;
  case V_FAV: return g->fav;
  case V_RECENT: return g->played != 0;
  case V_GB: return g->console == C_GB;
  case V_GBC: return g->console == C_GBC;
  case V_GBA: return g->console == C_GBA;
  case V_DS: return g->console == C_DS;
  case V_HACKS: return g->hack;
  default: return false;
  }
}

static int compare(const void *a, const void *b) {
  const struct game *x = &games[*(const int *)a], *y = &games[*(const int *)b];
  if (x->fav != y->fav)
    return x->fav ? -1 : 1;
  if (sortBy == S_RECENT && x->played != y->played)
    return x->played > y->played ? -1 : 1;
  int byTitle = strcasecmp(x->title, y->title);
  return byTitle ? byTitle : strcasecmp(x->file, y->file);
}

static void relist(void) {
  int keep = selected >= 0 && selected < shownCount ? shown[selected] : -1;
  for (int v = 0; v < V_COUNT; v++) {
    counts[v] = 0;
    for (int i = 0; i < gameCount; i++)
      counts[v] += in_view(&games[i], v);
  }
  if (view != V_ALL && !counts[view])
    view = V_ALL;
  shownCount = 0;
  for (int i = 0; i < gameCount; i++)
    if (in_view(&games[i], view))
      shown[shownCount++] = i;
  if (view == V_RECENT) {
    int save = sortBy;
    sortBy = S_RECENT;
    qsort(shown, (size_t)shownCount, sizeof *shown, compare);
    sortBy = save;
  } else
    qsort(shown, (size_t)shownCount, sizeof *shown, compare);
  selected = 0;
  for (int i = 0; i < shownCount; i++)
    if (shown[i] == keep)
      selected = i;
}

/* ---- opening ---- */

static long file_size(const char *path, long *mtime) {
  struct stat st;
  if (stat(path, &st))
    return -1;
  *mtime = (long)st.st_mtime;
  return (long)st.st_size;
}

static void look_save(void) {
  char dir[PATH_MAX + 16];
  snprintf(dir, sizeof dir, "%s/pokedaisy", savesDir);
  mkdir(dir, 0755);
  pd_settings_save(&settings, settingsFile);
  pd_look_apply(NULL, &settings);
}

static void check_saves(struct game *g) {
  char sav[PATH_MAX * 2];
  pd_save_path(savesDir, g->file, sav, sizeof sav);
  struct stat st;
  g->hasSave = !stat(sav, &st) && st.st_size > 0;
}

void library_open(char *const *paths, int count, const char *data, const char *saves, const struct lib_host *h) {
  library_close();
  host = h;
  snprintf(dataDir, sizeof dataDir, "%s", data);
  snprintf(savesDir, sizeof savesDir, "%s", saves);
  snprintf(stateFile, sizeof stateFile, "%s/library.txt", data);
  snprintf(titlesFile, sizeof titlesFile, "%s/library-titles.txt", data);
  snprintf(settingsFile, sizeof settingsFile, "%s/pokedaisy/settings.ini", saves);
  // The cores' own settings (the companion's DARK MODE, THEME, ASPECT): one look in the library and the games. The first
  // start writes them with EXPAND: a handheld's games fill its screen (the cores' own default is ORIGINAL, for any frontend).
  if (!pd_settings_load(&settings, settingsFile)) {
    settings.aspect = PD_ASPECT_EXPAND;
    look_save();
  }
  pd_look_apply(NULL, &settings);
  games = calloc((size_t)(count < MAX_GAMES ? count : MAX_GAMES), sizeof *games);
  gameCount = 0;
  for (int i = 0; games && i < count && gameCount < MAX_GAMES; i++) {
    const char *file = strrchr(paths[i], '/') ? strrchr(paths[i], '/') + 1 : paths[i];
    if (by_file(file)) // the same game in DATA/roms and a firmware folder: the first one listed
      continue;
    struct game *g = &games[gameCount++];
    snprintf(g->path, sizeof g->path, "%s", paths[i]);
    snprintf(g->file, sizeof g->file, "%s", file);
    const char *ext = strrchr(file, '.');
    g->console = !ext ? C_GBA : !strcasecmp(ext, ".nds") ? C_DS : !strcasecmp(ext, ".gbc") ? C_GBC : !strcasecmp(ext, ".gb") ? C_GB : C_GBA;
    g->size = file_size(g->path, &g->mtime);
    name_game(g);
    check_saves(g);
  }
  state_load();
  titles_load();
  // Each folder's gamelist.xml once.
  for (int i = 0; i < gameCount; i++) {
    char dir[PATH_MAX];
    snprintf(dir, sizeof dir, "%s", games[i].path);
    char *slash = strrchr(dir, '/');
    if (!slash)
      continue;
    *slash = 0;
    bool seen = false;
    for (int j = 0; j < i && !seen; j++)
      seen = !strncmp(games[j].path, dir, strlen(dir)) && games[j].path[strlen(dir)] == '/' && !strchr(games[j].path + strlen(dir) + 1, '/');
    if (!seen)
      gamelist_read(dir);
  }
  char covers[PATH_MAX + 16];
  snprintf(covers, sizeof covers, "%s/covers", dataDir);
  mkdir(covers, 0755);
  sgdb_key_load();
  for (int i = 0; i < gameCount; i++) {
    struct game *g = &games[i];
    snprintf(g->coverFile, sizeof g->coverFile, "%s/%.*s.png", covers, (int)(strrchr(g->file, '.') ? strrchr(g->file, '.') - g->file : (long)strlen(g->file)), g->file);
    cover_guess(g);
    char box[PATH_MAX + 8];
    snprintf(box, sizeof box, "%.*s.box.png", (int)strlen(g->coverFile) - 4, g->coverFile);
    if (!g->coverPath[0] && is_png(g->coverFile)) // fetched before
      snprintf(g->coverPath, sizeof g->coverPath, "%s", g->coverFile);
    else if (!g->coverPath[0] && is_png(box)) {
      snprintf(g->coverPath, sizeof g->coverPath, "%s", box);
      g->coverFit = true;
    }
    if (g->console == C_DS) {
      FILE *f = fopen(g->path, "rb");
      if (f && !fseek(f, 0x0C, SEEK_SET) && fread(g->code, 1, 4, f) == 4)
        g->code[4] = 0;
      if (f)
        fclose(f);
    }
    SDL_AtomicSet(&g->coverState, g->coverPath[0] ? CS_PATH : CS_NONE);
  }
  relist();
  SDL_AtomicSet(&stopWorker, 0);
  SDL_AtomicSet(&pauseWorker, 0);
  if (!curlLock)
    curlLock = SDL_CreateMutex();
  worker = gameCount && curlLock ? SDL_CreateThread(work, "library", NULL) : NULL;
}

void library_close(void) {
  if (worker) {
    SDL_AtomicSet(&stopWorker, 1);
    curl_kill(); // a download in flight ends now, not at its timeout
    SDL_WaitThread(worker, NULL);
    worker = NULL;
  }
  for (int i = 0; games && i < gameCount; i++)
    free(games[i].coverPx);
  free(games);
  games = NULL;
  gameCount = shownCount = 0;
}

void library_pause(bool paused) { SDL_AtomicSet(&pauseWorker, paused); }

void library_tick(void) {
  bool renamed = false;
  for (int i = 0; i < gameCount; i++) {
    struct game *g = &games[i];
    if (SDL_AtomicGet(&g->hashed) == 2) {
      SDL_MemoryBarrierAcquire();
      snprintf(g->sha1, sizeof g->sha1, "%s", g->workerSha1); // the worker is done with it: hashed was 2
      g->sha1Known = true;
      SDL_AtomicSet(&g->hashed, 1);
      char before[96];
      snprintf(before, sizeof before, "%s", g->title);
      name_by_sha1(g);
      renamed |= strcmp(before, g->title) != 0;
      titles_save();
    }
  }
  if (renamed)
    relist();
}

/* ---- input ---- */

static void say(const char *text) {
  snprintf(notice, sizeof notice, "%s", text);
  noticeUntil = SDL_GetTicks() + 2500;
}

static int visible_views(int *out) {
  int n = 0;
  for (int v = 0; v < V_COUNT; v++)
    if (v == V_ALL || counts[v])
      out[n++] = v;
  return n;
}

static void step_view(int dir) {
  int vs[V_COUNT], n = visible_views(vs), at = 0;
  for (int i = 0; i < n; i++)
    if (vs[i] == view)
      at = i;
  at = (at + dir + n) % n;
  view = vs[at];
  selected = 0;
  scroll = 0;
  relist();
  selected = 0;
  state_save();
}

/* SETTINGS' rows: the library's three, then the host's, BUTTON COLORS (after the host's A/B BUTTONS), then VERSION. */
enum { SET_DARK, SET_THEME, SET_ASPECT, SET_OWN };
static int host_rows(void) { return host ? host->settings : 0; }
static int settings_rows(void) { return SET_OWN + host_rows() + 2; }
static const char *setting_group(int row) {
  if (row < SET_OWN)
    return "SCREEN";
  if (row - SET_OWN < host_rows())
    return host->group(row - SET_OWN);
  return row == SET_OWN + host_rows() ? "CONTROLS" : "APP";
}
static void setting_label(int row, char *label, int llen, char *value, int vlen) {
  value[0] = 0;
  if (row == SET_DARK) {
    snprintf(label, (size_t)llen, "DARK MODE");
    snprintf(value, (size_t)vlen, "%s", settings.darkMode ? "ON" : "OFF");
  } else if (row == SET_THEME) {
    snprintf(label, (size_t)llen, "THEME");
    for (int i = 0; i < pd_theme_count; i++)
      if (pd_themes[i].id == settings.theme)
        snprintf(value, (size_t)vlen, "%s", pd_themes[i].label);
  } else if (row == SET_ASPECT) {
    snprintf(label, (size_t)llen, "ASPECT");
    snprintf(value, (size_t)vlen, "%s", pd_aspect_name(settings.aspect));
  } else if (row - SET_OWN < host_rows()) {
    snprintf(label, (size_t)llen, "%s", host->label(row - SET_OWN));
    host->value(row - SET_OWN, value, vlen);
  } else if (row == SET_OWN + host_rows()) {
    snprintf(label, (size_t)llen, "BUTTON COLORS");
    snprintf(value, (size_t)vlen, "%s", faceColors ? "GREEN A" : "RED A");
  } else {
    snprintf(label, (size_t)llen, "VERSION");
    snprintf(value, (size_t)vlen, "%s", host && host->version ? host->version : "");
  }
}
static void setting_step(int row, int dir) {
  if (row == SET_DARK) {
    settings.darkMode = !settings.darkMode;
    look_save();
  } else if (row == SET_THEME) {
    // The year-round themes (the seasonal ones come and go on Android's calendar).
    int ids[64], n = 0, at = 0;
    for (int i = 0; i < pd_theme_count && n < 64; i++)
      if (!pd_themes[i].seasonal)
        ids[n++] = pd_themes[i].id;
    for (int i = 0; i < n; i++)
      if (ids[i] == settings.theme)
        at = i;
    if (n) {
      settings.theme = ids[(at + dir + n) % n];
      look_save();
    }
  } else if (row == SET_ASPECT) {
    settings.aspect = (settings.aspect + dir + PD_ASPECTS) % PD_ASPECTS;
    look_save();
  } else if (row - SET_OWN < host_rows())
    host->step(row - SET_OWN, dir);
  else if (row == SET_OWN + host_rows()) {
    faceColors = !faceColors;
    state_save();
  }
}

static void play(void) {
  if (selected >= 0 && selected < shownCount)
    picked = games[shown[selected]].path;
}

/* The game's B menu (PLAY / FAVORITE / CANCEL): A, or a click on its row. */
static void menu_choose(void) {
  if (selected < 0 || selected >= shownCount) {
    menuOpen = false;
    return;
  }
  struct game *g = &games[shown[selected]];
  if (menuCursor == 0)
    play();
  else if (menuCursor == 1) {
    g->fav = !g->fav;
    g->favSet = true;
    state_save();
    relist();
  }
  menuOpen = false;
}

static void move_games(unsigned down) {
  int cols = grid ? columns : 1;
  if (!shownCount) {
    if (down & LIB_UP)
      focus = F_PILLS;
    if (down & LIB_LEFT)
      focus = F_RAIL;
    return;
  }
  if (down & LIB_UP) {
    if (selected - cols >= 0)
      selected -= cols;
    else
      focus = F_PILLS;
  }
  if (down & LIB_DOWN) {
    if (selected + cols < shownCount)
      selected += cols;
    else if (selected / cols < (shownCount - 1) / cols)
      selected = shownCount - 1; // a short last row: its last game
    else if (selected != shownCount - 1 && cols > 1 && (shownCount - 1) / cols == selected / cols)
      selected = shownCount - 1;
  }
  if (down & LIB_LEFT) {
    if (grid && selected % cols)
      selected--;
    else
      focus = F_RAIL, railCursor = P_GAMES;
  }
  if ((down & LIB_RIGHT) && grid && selected % cols < cols - 1 && selected + 1 < shownCount)
    selected++;
}

void library_keys(unsigned down) {
  if (!down)
    return;
  if (closeAsk) {
    if (down & (LIB_LEFT | LIB_RIGHT | LIB_UP | LIB_DOWN))
      menuCursor ^= 1;
    if (down & LIB_A) {
      if (menuCursor == 0)
        quit = true;
      closeAsk = false;
    }
    if (down & (LIB_B | LIB_START | LIB_MENU))
      closeAsk = false;
    return;
  }
  if (menuOpen) {
    if (down & LIB_UP)
      menuCursor = (menuCursor + 2) % 3;
    if (down & LIB_DOWN)
      menuCursor = (menuCursor + 1) % 3;
    if (down & LIB_A)
      menu_choose();
    if (down & (LIB_B | LIB_START))
      menuOpen = false;
    return;
  }
  if (down & (LIB_START | LIB_MENU)) {
    closeAsk = true;
    menuCursor = 1;
    return;
  }
  if (focus == F_RAIL) {
    if (down & LIB_UP)
      railCursor = (railCursor + P_COUNT - 1) % P_COUNT;
    if (down & LIB_DOWN)
      railCursor = (railCursor + 1) % P_COUNT;
    if (down & (LIB_A | LIB_RIGHT)) {
      page = railCursor;
      focus = F_GAMES;
    }
    if ((down & LIB_B) && page != P_GAMES)
      page = P_GAMES, railCursor = P_GAMES;
    return;
  }
  if (page == P_SETTINGS) {
    int n = settings_rows();
    if (down & LIB_UP)
      settingsCursor = (settingsCursor + n - 1) % n;
    if (down & LIB_DOWN)
      settingsCursor = (settingsCursor + 1) % n;
    if (down & (LIB_A | LIB_RIGHT))
      setting_step(settingsCursor, 1);
    if (down & LIB_LEFT)
      focus = F_RAIL, railCursor = P_SETTINGS;
    if (down & LIB_B)
      page = P_GAMES, focus = F_GAMES;
    return;
  }
  if (page == P_TOOLS) {
    if (down & LIB_UP)
      toolCursor = 0;
    if (down & LIB_DOWN)
      toolCursor = 1;
    if (down & LIB_A)
      say("Coming to the Linux version soon");
    if (down & LIB_LEFT)
      focus = F_RAIL, railCursor = P_TOOLS;
    if (down & LIB_B)
      page = P_GAMES, focus = F_GAMES;
    return;
  }
  // GAMES.
  if (down & LIB_Y) {
    page = P_SETTINGS;
    settingsCursor = 0;
    return;
  }
  if (down & LIB_X) {
    grid = !grid;
    state_save();
  }
  if (down & LIB_L)
    step_view(-1);
  if (down & LIB_R)
    step_view(1);
  if (focus == F_HEADER) {
    if (down & LIB_LEFT) {
      if (headerCursor)
        headerCursor--;
      else
        focus = F_RAIL, railCursor = P_GAMES;
    }
    if ((down & LIB_RIGHT) && headerCursor < 1)
      headerCursor++;
    if (down & LIB_DOWN)
      focus = F_PILLS;
    if (down & LIB_A) {
      if (headerCursor == 0) {
        sortBy = (sortBy + 1) % S_COUNT;
        say(sortBy == S_NAME ? "Sorted by name" : "Sorted by last played");
        relist();
      } else
        grid = !grid;
      state_save();
    }
    if (down & LIB_B)
      focus = F_GAMES;
    return;
  }
  if (focus == F_PILLS) {
    int vs[V_COUNT], n = visible_views(vs), at = 0;
    for (int i = 0; i < n; i++)
      if (vs[i] == view)
        at = i;
    if (down & LIB_LEFT) {
      if (at)
        step_view(-1);
      else
        focus = F_RAIL, railCursor = P_GAMES;
    }
    if ((down & LIB_RIGHT) && at < n - 1)
      step_view(1);
    if (down & LIB_UP)
      focus = F_HEADER;
    if (down & (LIB_DOWN | LIB_A | LIB_B))
      focus = F_GAMES;
    return;
  }
  move_games(down);
  if (shownCount && (down & LIB_A))
    play();
  if (shownCount && (down & LIB_B)) {
    menuOpen = true;
    menuCursor = 0;
  }
}

const char *library_picked(void) {
  const char *p = picked;
  picked = NULL;
  return p;
}

bool library_quit(void) { return quit; }

void library_played(const char *path) {
  time_t now = time(NULL);
  struct tm tm;
  localtime_r(&now, &tm);
  unsigned long long stamp = (unsigned long long)(tm.tm_year + 1900) * 10000000000ull + (unsigned long long)(tm.tm_mon + 1) * 100000000ull +
                             (unsigned long long)tm.tm_mday * 1000000ull + (unsigned long long)tm.tm_hour * 10000ull +
                             (unsigned long long)tm.tm_min * 100ull + (unsigned long long)tm.tm_sec;
  for (int i = 0; i < gameCount; i++)
    if (!strcmp(games[i].path, path)) {
      games[i].played = stamp;
      check_saves(&games[i]);
    }
  // The game may have changed the look (the companion's DARK MODE / THEME).
  pd_settings_load(&settings, settingsFile);
  pd_look_apply(NULL, &settings);
  state_save();
  relist();
  for (int i = 0; i < shownCount; i++)
    if (!strcmp(games[shown[i]].path, path))
      selected = i;
}

/* ---- drawing ---- */

struct look { uint32_t back, stripe, text, shadow; };
static const struct look LOOKS[V_COUNT] = {
    {0x4A5670, 0x404B63, 0xFFFFFF, 0x1C2233}, {0xE0A010, 0xCC9008, 0xFFFFFF, 0x6E4A00},
    {0x2F7FD8, 0x2670C4, 0xFFFFFF, 0x0C315E}, {0x8A8A94, 0x7A7A84, 0xFFFFFF, 0x3A3A42},
    {0xF5C524, 0xE6B312, 0xFFFFFF, 0x8A5A00}, {0x6A35A8, 0x5A2A94, 0xFFFFFF, 0x24093F},
    {0x1FB8AA, 0x17A396, 0xFFFFFF, 0x0A4C46}, {0xD9302E, 0xC22422, 0xFFFFFF, 0x5C0A0A},
};
static const char *const VIEW_NAMES[V_COUNT] = {"ALL", "FAVORITES", "RECENT", "GB", "GBC", "GBA", "DS", "HACKS"};
static const char *const PAGE_NAMES[P_COUNT] = {"GAMES", "TOOLS", "SETTINGS"};

static const char *const TOOLBOX_ROWS[] = {
    "................", ".....kkkkkk.....", "....kggggggk....", "....kgkkkkgk....", ".kkkkkkkkkkkkkk.", "khhhhhhhhhhhhhhk",
    "krrrrrrrrrrrrrrk", "krrrrrrrrrrrrrrk", "kddddddkkddddddk", "kkkkkkkyykkkkkkk", "krrrrrrkkrrrrrrk", "krrrrrrrrrrrrrrk",
    "krrrrrrrrrrrrrrk", "kddddddddddddddk", ".kkkkkkkkkkkkkk.", "................",
};
static const uint32_t TOOLBOX_COLORS[] = {0x303038, 0xA8A8B8, 0xFF9080, 0xE04040, 0xA02828, 0xF8D030};
static const struct pd_art TOOLBOX = {16, 16, TOOLBOX_ROWS, "kghrdy", TOOLBOX_COLORS};

static const char *const SAVE_ROWS[] = {"1111111..", "1.1111.1.", "1.1111.11", "1.......1", "1.11111.1",
                                        "1.1...1.1", "1.11111.1", "111111111"};
static const struct pd_mono SAVE_ICON = {9, 8, SAVE_ROWS};
static const char *const SORT_ROWS[] = {"111111111", ".........", "1111111..", ".........", "11111....", ".........", "111......"};
static const struct pd_mono SORT_ICON = {9, 7, SORT_ROWS};
static const char *const GRID_ROWS[] = {"111.111", "111.111", "111.111", ".......", "111.111", "111.111", "111.111"};
static const struct pd_mono GRID_ICON = {7, 7, GRID_ROWS};
static const char *const LIST_ROWS[] = {"1.11111", ".......", "1.11111", ".......", "1.11111", ".......", "1.11111"};
static const struct pd_mono LIST_ICON = {7, 7, LIST_ROWS};

static void mono(struct pd_canvas *c, const struct pd_mono *m, int x, int y, int scale, uint32_t color) {
  for (int r = 0; r < m->h; r++)
    for (int k = 0; k < m->w && m->rows[r][k]; k++)
      if (m->rows[r][k] != '.')
        pd_fill(c, x + k * scale, y + r * scale, scale, scale, color);
}

/* D-pad focus: a ring flush inside the element's own outline (the app's gbaFocusRing), two pixels of orange. */
static void ring(struct pd_canvas *c, int x, int y, int w, int h) {
  pd_fill(c, x + 2, y, w - 4, 2, FOCUS_RING);
  pd_fill(c, x + 2, y + h - 2, w - 4, 2, FOCUS_RING);
  pd_fill(c, x, y + 2, 2, h - 4, FOCUS_RING);
  pd_fill(c, x + w - 2, y + 2, 2, h - 4, FOCUS_RING);
  pd_fill(c, x + 1, y + 1, 2, 2, FOCUS_RING);
  pd_fill(c, x + w - 3, y + 1, 2, 2, FOCUS_RING);
  pd_fill(c, x + 1, y + h - 3, 2, 2, FOCUS_RING);
  pd_fill(c, x + w - 3, y + h - 3, 2, 2, FOCUS_RING);
}

/* The PokéDaisy page (the app's DaisyBackdrop, still): soft blobs and a dot grid; kept per size and light / dark. */
static uint32_t *pagePx;
static int pageW, pageH, pageDark = -1;
static void backdrop(struct pd_canvas *c) {
  if (!pagePx || pageW != c->w || pageH != c->h || pageDark != pd_dark) {
    free(pagePx);
    pagePx = malloc((size_t)c->w * c->h * 4);
    pageW = c->w;
    pageH = c->h;
    pageDark = pd_dark;
    static const struct { int x, y, yw, r; uint32_t day, night; } BLOBS[3] = {
        {11, 0, 9, 23, 0xFFD2CC, 0x6A2F45}, {90, 18, 20, 20, 0xFFF0B0, 0x6A4418}, {45, 100, 5, 25, 0xD6E2FF, 0x233A7A}};
    int big = c->w > c->h ? c->w : c->h;
    for (int y = 0; pagePx && y < c->h; y++)
      for (int x = 0; x < c->w; x++) {
        uint32_t col = pd_dark ? 0x12141C : 0xF7F6FB;
        for (int i = 0; i < 3; i++) {
          int bx = BLOBS[i].x * c->w / 100, by = BLOBS[i].y * c->h / 100 + BLOBS[i].yw * big / 100;
          int r = BLOBS[i].r * big / 100, reach = 2 * r, dx = x - bx, dy = y - by;
          long d2 = (long)dx * dx + (long)dy * dy;
          if (d2 >= (long)reach * reach)
            continue;
          int k = 140;
          if (d2 > (long)r * r) {
            int d = 0;
            while ((long)(d + 1) * (d + 1) <= d2)
              d++;
            k = 140 * (reach - d) / (reach - r);
          }
          col = pd_mix(col, pd_dark ? BLOBS[i].night : BLOBS[i].day, k);
        }
        if (x % 12 == 0 && y % 12 == 0)
          col = pd_mix(col, pd_dark ? 0xFFFFFF : 0x1D1B26, pd_dark ? 16 : 23);
        pagePx[(size_t)y * c->w + x] = col;
      }
  }
  if (pagePx)
    memcpy(c->px, pagePx, (size_t)c->w * c->h * 4);
}

static int rail_w(void) {
  int w = 0;
  for (int i = 0; i < P_COUNT; i++)
    if (pd_text_width(PAGE_NAMES[i]) > w)
      w = pd_text_width(PAGE_NAMES[i]);
  return w + 24 < 72 ? 72 : w + 24;
}

static void draw_rail(struct pd_canvas *c) {
  int rw = rail_w();
  // Docked to the screen's edge: its frame only on the side facing the page.
  pd_layered_box(c, -8, -8, rw + 8, c->h + 16, 3, pd_cur->list, pd_cur->listCount, PD_LIST_FILL);
  const struct pd_art *art[P_COUNT] = {&LIB_ICON_GAMES, &TOOLBOX, &LIB_ICON_SETTINGS};
  int ih = 56;
  for (int i = 0; i < P_COUNT; i++) {
    int x = 6, y = M + i * (ih + 6), w = rw - 14;
    bool on = page == i;
    if (on)
      pd_round_rect(c, x, y, w, ih, 3, PD_TITLE_FILL);
    ui_art_at(c, art[i], x + (w - 32) / 2, y + 5, 2);
    int tw = pd_text_width(PAGE_NAMES[i]);
    pd_text(c, x + (w - tw) / 2, y + 36, PAGE_NAMES[i], on ? PD_VALUE : PD_LABEL, on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
    if (focus == F_RAIL && railCursor == i)
      ring(c, x, y, w, ih);
    hit_add(x, y, w, ih, LIB_HIT_RAIL, i);
  }
}

static void draw_header(struct pd_canvas *c, int x, int w, const char *title, bool buttons) {
  int f = pd_title_box(c, x, M, w, HEADER_H, PD_TITLE_FILL);
  lib_draw_logo_24(c, x + f + 3, M + (HEADER_H - 24) / 2);
  pd_text(c, x + f + 32, pd_text_y(M, HEADER_H), title, PD_TITLE_TEXT, PD_TITLE_SHADOW);
  if (!buttons)
    return;
  // SORT and GRID / LIST: the app's HeaderIcons, at the window's right end.
  for (int i = 0; i < 2; i++) {
    int bw = 26, bh = HEADER_H - 2 * f - 4, bx = x + w - f - 4 - (2 - i) * (bw + 4), by = M + f + 2;
    pd_layered_two_tone(c, bx, by, bw, bh, 3, pd_cur->chip, pd_cur->chipCount, pd_cur->tabIdleFill, 0);
    const struct pd_mono *icon = i == 0 ? &SORT_ICON : grid ? &LIST_ICON : &GRID_ICON;
    mono(c, icon, bx + (bw - icon->w * 2) / 2, by + (bh - icon->h * 2) / 2, 2, PD_LABEL);
    if (focus == F_HEADER && headerCursor == i)
      ring(c, bx, by, bw, bh);
    hit_add(bx, by, bw, bh, i == 0 ? LIB_HIT_SORT : LIB_HIT_GRID, 0);
  }
}

static int pill_width(int v) {
  char count[16];
  snprintf(count, sizeof count, "%d", counts[v]);
  return 16 + ((v == V_FAV || v == V_RECENT) ? 13 : 0) + pd_text_width(VIEW_NAMES[v]) + 5 + pd_text_width(count);
}

static void draw_pills(struct pd_canvas *c, int x, int y, int w) {
  int vs[V_COUNT], n = visible_views(vs), total = 0, at = 0;
  for (int i = 0; i < n; i++) {
    total += pill_width(vs[i]) + 6;
    if (vs[i] == view)
      at = i;
  }
  // Scrolled so the open pill shows (a narrow screen's row is wider than it).
  int shift = 0, left = 0;
  for (int i = 0; i < at; i++)
    left += pill_width(vs[i]) + 6;
  if (total > w && left + pill_width(view) > w)
    shift = left + pill_width(view) - w + 6;
  pd_clip(c, x, y - 2, w, PILL_H + 4);
  int px = x - shift;
  for (int i = 0; i < n; i++) {
    int v = vs[i], pw = pill_width(v);
    const struct look *k = &LOOKS[v];
    bool on = v == view;
    if (on) {
      struct pd_layer edge = {k->shadow, 2};
      pd_layered_box(c, px, y, pw, PILL_H, 5, &edge, 1, k->back);
      for (int sy = y + 2; sy < y + PILL_H - 2; sy += 2)
        for (int sx = px + 2; sx < px + pw - 2; sx += 2)
          if (((sx - px) / 2 + (sy - y) / 2) % 4 < 1)
            pd_fill(c, sx, sy, 2, 2, k->stripe);
    } else {
      struct pd_layer edge = {k->back, 3};
      pd_layered_box(c, px, y, pw, PILL_H, 5, &edge, 1, PD_TITLE_FILL);
    }
    uint32_t text = on ? 0xFFFFFF : pd_dark ? PD_TITLE_TEXT : k->shadow, shadow = on ? k->shadow : PD_TITLE_SHADOW;
    int tx = px + 8, ty = pd_text_y(y, PILL_H);
    if (v == V_FAV || v == V_RECENT) {
      mono(c, v == V_FAV ? &PD_ICON_STAR : &PD_ICON_CLOCK, tx, y + (PILL_H - 9) / 2, 1, on ? 0xFFFFFF : k->back);
      tx += 13;
    }
    tx += pd_text(c, tx, ty, VIEW_NAMES[v], text, shadow) + 5;
    char count[16];
    snprintf(count, sizeof count, "%d", counts[v]);
    pd_text(c, tx, ty, count, pd_mix(text, on ? k->back : PD_TITLE_FILL, 77), PD_NO_SHADOW);
    if (focus == F_PILLS && on)
      ring(c, px, y, pw, PILL_H);
    hit_add_clipped(px, y, pw, PILL_H, x, y - 2, w, PILL_H + 4, LIB_HIT_PILL, v);
    px += pw + 6;
  }
  pd_unclip(c);
}

/* A game with no cover: the app's CategoryArt, its kind's initials (GB, GBC, GBA, NDS, HACK) in a 3 x 5 pixel font over
 * diagonal stripes in the kind's colours, on one grid of whole pixels. */
static const char *glyph(char ch) {
  switch (ch) {
  case 'A': return "010101111101101";
  case 'B': return "110101110101110";
  case 'C': return "011100100100011";
  case 'D': return "110101101101110";
  case 'G': return "011100101101011";
  case 'H': return "101101111101101";
  case 'K': return "101101110101101";
  case 'S': return "011100010001110";
  default: return "000000000000000";
  }
}

static void category_art(struct pd_canvas *c, const struct game *g, int x, int y, int s) {
  int v = g->hack ? V_HACKS : g->console == C_GB ? V_GB : g->console == C_GBC ? V_GBC : g->console == C_GBA ? V_GBA : V_DS;
  const char *label = g->hack ? "HACK" : g->console == C_GB ? "GB" : g->console == C_GBC ? "GBC" : g->console == C_GBA ? "GBA" : "NDS";
  const struct look *k = &LOOKS[v];
  enum { GRID = 18 };
  int cell = s / GRID > 0 ? s / GRID : 1, left = x + (s - cell * GRID) / 2, top = y + (s - cell * GRID) / 2;
  pd_fill(c, x, y, s, s, k->back);
  for (int gy = 0; gy * cell < s; gy++)
    for (int gx = 0; gx * cell < s; gx++)
      if ((gx + gy) % 4 < 1)
        pd_fill(c, x + gx * cell, y + gy * cell, cell, cell, k->stripe);
  int width = -1;
  for (const char *p = label; *p; p++)
    width += (*p == 'N' ? 4 : 3) + 1;
  for (int pass = 0; pass < 2; pass++) {
    int gx = (GRID - width) / 2, gy = (GRID - 5) / 2;
    for (const char *p = label; *p; p++) {
      static const char N[] = "10011101101110011001";
      int w = *p == 'N' ? 4 : 3;
      const char *rows = *p == 'N' ? N : glyph(*p);
      for (int r = 0; r < 5; r++)
        for (int col = 0; col < w; col++)
          if (rows[r * w + col] == '1')
            pd_fill(c, left + (gx + col) * cell, top + (gy + r + (pass ? 0 : 1)) * cell, cell, cell, pass ? k->text : k->shadow);
      gx += w + 1;
    }
  }
}

static struct lib_cover spots[64];
static int spotCount;

static void cover_well(struct pd_canvas *c, const struct game *g, int x, int y, int s, int clipY, int clipH) {
  struct pd_layer frame[2] = {{pd_cur->frameDark, 1}, {pd_cur->frameLight, 1}};
  pd_layered_box(c, x, y, s, s, 3, frame, 2, pd_cur->imageWell);
  if (SDL_AtomicGet((SDL_atomic_t *)&g->coverState) == CS_READY && spotCount < 64) {
    SDL_MemoryBarrierAcquire();
    spots[spotCount++] = (struct lib_cover){x + 2, y + 2, s - 4, s - 4, contentX, clipY, contentW, clipH,
                                            (int)(g - games), g->serial, g->coverPx, COVER};
  } else {
    int y0 = y + 2 > clipY ? y + 2 : clipY, y1 = y + s - 2 < clipY + clipH ? y + s - 2 : clipY + clipH;
    if (y1 <= y0)
      return;
    pd_clip(c, x + 2, y0, s - 4, y1 - y0);
    category_art(c, g, x + 2, y + 2, s - 4);
    pd_clip(c, contentX - 4, clipY, contentW + 8, clipH);
  }
}

static void title_line(struct pd_canvas *c, const struct game *g, int x, int y, int w) {
  int icons = (g->fav ? 13 : 0) + (g->hasSave ? 13 : 0);
  pd_text_fit(c, x, y, w - icons, g->title, PD_LABEL, PD_LABEL_SHADOW);
  int ix = x + w - icons;
  if (g->fav) {
    mono(c, &PD_ICON_STAR, ix + 2, y + 4, 1, pd_cur->favorite);
    ix += 13;
  }
  if (g->hasSave)
    mono(c, &SAVE_ICON, ix + 2, y + 4, 1, PD_LABEL);
}

static void draw_games(struct pd_canvas *c) {
  if (!shownCount) {
    int f = pd_list_box(c, contentX, contentY, contentW, 44);
    pd_text_fit(c, contentX + f + 8, contentY + 14, contentW - 2 * f - 16,
                gameCount ? "No games in this view yet." : "No Pokemon games found. Put them in roms/ beside PokeDaisy.",
                PD_LABEL, PD_LABEL_SHADOW);
    return;
  }
  pd_clip(c, contentX - 4, contentY, contentW + 8, contentH);
  if (grid) {
    columns = (contentW + GAP) / (118 + GAP);
    if (columns < 2)
      columns = 2;
    int tw = (contentW - (columns - 1) * GAP) / columns, f = 3, cs = tw - 2 * (f + 3), th = 2 * f + 3 + cs + 3 + 18 + 1;
    int rowH = th + GAP, row = selected / columns;
    if (row * rowH < scroll)
      scroll = row * rowH;
    if (row * rowH + th > scroll + contentH)
      scroll = row * rowH + th - contentH;
    for (int i = 0; i < shownCount; i++) {
      int x = contentX + (i % columns) * (tw + GAP), y = contentY + (i / columns) * rowH - scroll;
      if (y + th < contentY || y > contentY + contentH)
        continue;
      const struct game *g = &games[shown[i]];
      bool cur = i == selected && focus == F_GAMES;
      pd_layered_box(c, x, y, tw, th, 3, pd_cur->list, pd_cur->listCount, cur ? PD_ROW_SELECTED : PD_LIST_FILL);
      cover_well(c, g, x + f + 3, y + f + 3, cs, contentY, contentH);
      title_line(c, g, x + f + 3, y + f + 3 + cs + 2, cs);
      if (cur)
        ring(c, x, y, tw, th);
      hit_add_clipped(x, y, tw, th, contentX - 4, contentY, contentW + 8, contentH, LIB_HIT_GAME, i);
    }
  } else {
    columns = 1;
    int rh = 46, rowH = rh + 6;
    if (selected * rowH < scroll)
      scroll = selected * rowH;
    if (selected * rowH + rh > scroll + contentH)
      scroll = selected * rowH + rh - contentH;
    for (int i = 0; i < shownCount; i++) {
      int y = contentY + i * rowH - scroll;
      if (y + rh < contentY || y > contentY + contentH)
        continue;
      const struct game *g = &games[shown[i]];
      bool cur = i == selected && focus == F_GAMES;
      pd_layered_box(c, contentX, y, contentW, rh, 3, pd_cur->list, pd_cur->listCount, cur ? PD_ROW_SELECTED : PD_LIST_FILL);
      cover_well(c, g, contentX + 6, y + 5, rh - 10, contentY, contentH);
      int tx = contentX + rh + 4;
      title_line(c, g, tx, y + 5, contentW - rh - 12);
      int v = g->console == C_GB ? V_GB : g->console == C_GBC ? V_GBC : g->console == C_GBA ? V_GBA : V_DS;
      const char *tags[2] = {VIEW_NAMES[v], g->hack ? "HACK" : NULL};
      int lv[2] = {v, V_HACKS};
      for (int k = 0; k < 2 && tags[k]; k++) {
        int bw = pd_text_width(tags[k]) + 8;
        pd_round_rect(c, tx, y + 25, bw, 15, 3, LOOKS[lv[k]].back);
        pd_text(c, tx + 4, y + 23, tags[k], 0xFFFFFF, LOOKS[lv[k]].shadow);
        tx += bw + 4;
      }
      if (cur)
        ring(c, contentX, y, contentW, rh);
      hit_add_clipped(contentX, y, contentW, rh, contentX - 4, contentY, contentW + 8, contentH, LIB_HIT_GAME, i);
    }
  }
  pd_unclip(c);
}

static void draw_settings(struct pd_canvas *c) {
  int n = settings_rows(), rowH = 24, f = 4;
  int lines = 0;
  // Each group a title row over its rows.
  const char *last = NULL;
  int cursorLine = 0;
  for (int i = 0; i < n; i++) {
    if (!last || strcmp(last, setting_group(i)))
      lines++, last = setting_group(i);
    if (i == settingsCursor)
      cursorLine = lines;
    lines++;
  }
  int viewH = contentH - 2 * f;
  if (cursorLine * rowH < settingsScroll)
    settingsScroll = cursorLine * rowH - rowH;
  if ((cursorLine + 1) * rowH > settingsScroll + viewH)
    settingsScroll = (cursorLine + 1) * rowH - viewH;
  if (settingsScroll < 0)
    settingsScroll = 0;
  f = pd_list_box(c, contentX, contentY, contentW, contentH);
  pd_clip(c, contentX + f, contentY + f, contentW - 2 * f, contentH - 2 * f);
  int y = contentY + f - settingsScroll;
  last = NULL;
  for (int i = 0; i < n; i++) {
    if (!last || strcmp(last, setting_group(i))) {
      last = setting_group(i);
      pd_text(c, contentX + f + 8, pd_text_y(y, rowH), last, PD_MUTED, PD_MUTED_SHADOW);
      pd_fill(c, contentX + f + 8, y + rowH - 3, contentW - 2 * f - 16, 1, PD_DIVIDER);
      y += rowH;
    }
    char label[48], value[48];
    setting_label(i, label, sizeof label, value, sizeof value);
    bool cur = i == settingsCursor && focus == F_GAMES;
    if (cur)
      pd_fill(c, contentX + f, y, contentW - 2 * f, rowH, PD_ROW_SELECTED);
    pd_text(c, contentX + f + 12, pd_text_y(y, rowH), label, PD_LABEL, PD_LABEL_SHADOW);
    pd_text_right(c, contentX + contentW - f - 12, pd_text_y(y, rowH), value, PD_VALUE, PD_VALUE_SHADOW);
    hit_add_clipped(contentX + f, y, contentW - 2 * f, rowH, contentX + f, contentY + f, contentW - 2 * f, contentH - 2 * f,
                    LIB_HIT_SETTING, i);
    y += rowH;
  }
  pd_unclip(c);
}

static void draw_tools(struct pd_canvas *c) {
  static const char *const NAMES[2] = {"SAVE EDITOR", "TRADE SAVES"};
  static const char *const BLURBS[2] = {"Your party, PC boxes, money and bag.", "Trade Pokemon between two of your saves."};
  const struct pd_art *art[2] = {&LIB_ICON_EDIT, &LIB_ICON_TRADE};
  for (int i = 0; i < 2; i++) {
    int y = contentY + i * 64, h = 56;
    bool cur = focus == F_GAMES && toolCursor == i;
    int f = pd_layered_box(c, contentX, y, contentW, h, 3, pd_cur->title, pd_cur->titleCount, cur ? PD_ROW_SELECTED : PD_TITLE_FILL);
    ui_art_at(c, art[i], contentX + f + 6, y + (h - 32) / 2, 2);
    pd_text(c, contentX + f + 48, y + f + 6, NAMES[i], PD_LABEL, PD_LABEL_SHADOW);
    pd_text_fit(c, contentX + f + 48, y + f + 24, contentW - f * 2 - 56, BLURBS[i], PD_MUTED, PD_MUTED_SHADOW);
    if (cur)
      ring(c, contentX, y, contentW, h);
    hit_add(contentX, y, contentW, h, LIB_HIT_TOOL, i);
  }
}

void library_draw(struct pd_canvas *c) {
  pd_look_apply(NULL, &settings);
  backdrop(c);
  spotCount = 0;
  libHitCount = 0;
  int rw = rail_w(), x = rw + M, w = c->w - x - M;
  draw_rail(c);
  contentX = x;
  contentW = w;
  if (page == P_GAMES) {
    draw_header(c, x, w, "POKéDAISY", true);
    int py = M + HEADER_H + GAP;
    draw_pills(c, x, py, w);
    contentY = py + PILL_H + GAP;
    contentH = c->h - contentY;
    draw_games(c);
  } else {
    draw_header(c, x, w, page == P_TOOLS ? "TOOLS" : "SETTINGS", false);
    contentY = M + HEADER_H + GAP;
    contentH = c->h - contentY - M;
    if (page == P_TOOLS)
      draw_tools(c);
    else
      draw_settings(c);
  }
}

int library_covers(struct lib_cover *out, int max) {
  int n = spotCount < max ? spotCount : max;
  memcpy(out, spots, (size_t)n * sizeof *out);
  return n;
}

static void face(struct pd_canvas *c, int x, int y, char letter) {
  // The legend's buttons in your device's colours (BUTTON COLORS): the app's, or green A, red B, blue X, yellow Y.
  static const uint32_t COLORS[2][4] = {{0xD83A3A, 0xE8B020, 0x3A6AD8, 0x30A050}, {0x30A050, 0xD83A3A, 0x3A6AD8, 0xE8B020}};
  int k = letter == 'A' ? 0 : letter == 'B' ? 1 : letter == 'X' ? 2 : 3;
  uint32_t col = COLORS[faceColors][k];
  pd_round_rect(c, x, y, 18, 18, 8, pd_mix(col, 0x000000, 70));
  pd_round_rect(c, x + 1, y + 1, 16, 16, 7, col);
  char s[2] = {letter, 0};
  pd_text(c, x + (18 - pd_text_width(s)) / 2, y - 1, s, 0xFFFFFF, pd_mix(col, 0x000000, 128));
}

static int window(struct pd_canvas *c, int x, int y, int w, int h, const char *title, const char *const *rows, int n, int cursor) {
  int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
  int ry = y + f + 4;
  if (title) {
    pd_text_fit(c, x + f + 8, ry, w - 2 * f - 16, title, PD_VALUE, PD_VALUE_SHADOW);
    ry += 24;
  }
  for (int i = 0; i < n; i++) {
    if (i == cursor) {
      pd_fill(c, x + f + 2, ry, w - 2 * f - 4, 22, PD_ROW_SELECTED);
      pd_triangle(c, x + f + 6, ry + 6, 8, PD_RIGHT, PD_VALUE);
    }
    pd_text_fit(c, x + f + 18, pd_text_y(ry, 22), w - 2 * f - 26, rows[i], PD_LABEL, PD_LABEL_SHADOW);
    ry += 24;
  }
  return f;
}

bool library_dimmed(void) { return menuOpen || closeAsk; }

void library_draw_top(struct pd_canvas *c, uint32_t clear) {
  pd_fill(c, 0, 0, c->w, c->h, clear);
  // The button legend, floating bottom right (the launcher's ButtonLegend).
  if (!menuOpen && !closeAsk) {
    static const char *const GAMES_LEGEND[][2] = {{"A", "OPEN"}, {"B", "OPTIONS"}, {"Y", "SETTINGS"}};
    static const char *const PAGE_LEGEND[][2] = {{"A", "CHOOSE"}, {"B", "BACK"}};
    bool gamesPage = page == P_GAMES;
    int n = gamesPage ? 3 : 2, w = 10;
    for (int i = 0; i < n; i++)
      w += 22 + pd_text_width(gamesPage ? GAMES_LEGEND[i][1] : PAGE_LEGEND[i][1]) + 10;
    int h = 28, x = c->w - M - w, y = c->h - M - h;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    int lx = x + f + 5;
    for (int i = 0; i < n; i++) {
      const char *k = gamesPage ? GAMES_LEGEND[i][0] : PAGE_LEGEND[i][0], *l = gamesPage ? GAMES_LEGEND[i][1] : PAGE_LEGEND[i][1];
      face(c, lx, y + (h - 18) / 2, k[0]);
      lx += 22;
      lx += pd_text(c, lx, pd_text_y(y, h), l, PD_LABEL, PD_LABEL_SHADOW) + 10;
    }
  }
  if (menuOpen && selected < shownCount) {
    const struct game *g = &games[shown[selected]];
    const char *rows[3] = {"PLAY", g->fav ? "UNFAVORITE" : "FAVORITE", "CANCEL"};
    int w = 240, h = 2 * 6 + 24 + 3 * 24 + 4;
    int x = (c->w - w) / 2, y = (c->h - h) / 2;
    int f = window(c, x, y, w, h, g->title, rows, 3, menuCursor);
    for (int i = 0; i < 3; i++)
      hit_add(x + f + 2, y + f + 4 + 24 + i * 24, w - 2 * f - 4, 22, LIB_HIT_MENU, i);
  }
  if (closeAsk) {
    const char *rows[2] = {"YES", "NO"};
    int w = 240, h = 2 * 6 + 24 + 2 * 24 + 4;
    int x = (c->w - w) / 2, y = (c->h - h) / 2;
    int f = window(c, x, y, w, h, "CLOSE POKéDAISY?", rows, 2, menuCursor);
    for (int i = 0; i < 2; i++)
      hit_add(x + f + 2, y + f + 4 + 24 + i * 24, w - 2 * f - 4, 22, LIB_HIT_CLOSE, i);
  }
  if (SDL_GetTicks() < noticeUntil) {
    int w = pd_text_width(notice) + 24, h = 28, x = (c->w - w) / 2, y = c->h - M - h - 36;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    pd_text(c, x + f + 8, pd_text_y(y, h), notice, PD_LABEL, PD_LABEL_SHADOW);
  }
}

/* The topmost target under a canvas point (the menus are added after the page, so they win). */
static int hit_under(int x, int y) {
  for (int i = libHitCount - 1; i >= 0; i--) {
    const struct lib_hit *h = &libHits[i];
    if (x >= h->x && y >= h->y && x < h->x + h->w && y < h->y + h->h)
      return i;
  }
  return -1;
}

/* A wheel notch on GAMES: one row in the grid, one game in the list. */
static void wheel_games(int dir) {
  if (!shownCount)
    return;
  int cols = grid ? columns : 1;
  if (cols < 1)
    cols = 1;
  if (dir < 0) {
    if (selected >= cols)
      selected -= cols;
    else
      selected = 0;
  } else {
    if (selected + cols < shownCount)
      selected += cols;
    else
      selected = shownCount - 1;
  }
  focus = F_GAMES;
}

void library_pointer(int x, int y, bool click, int wheel) {
  int under = hit_under(x, y);
  const struct lib_hit *h = under >= 0 ? &libHits[under] : NULL;

  if (menuOpen || closeAsk) {
    if (click) {
      if (h && ((menuOpen && h->action == LIB_HIT_MENU) || (closeAsk && h->action == LIB_HIT_CLOSE))) {
        menuCursor = h->arg;
        if (menuOpen)
          menu_choose();
        else {
          if (menuCursor == 0)
            quit = true;
          closeAsk = false;
        }
      } else if (menuOpen)
        menuOpen = false; // a click beside the menu closes it, as B does
      else
        closeAsk = false;
      return;
    }
    if (wheel) {
      int n = menuOpen ? 3 : 2, dir = wheel > 0 ? -1 : 1;
      for (int i = wheel > 0 ? wheel : -wheel; i > 0; i--)
        menuCursor = (menuCursor + dir + n) % n;
    }
    return;
  }
  if (click) {
    if (!h)
      return;
    if (h->action == LIB_HIT_RAIL) {
      page = railCursor = h->arg;
      focus = F_GAMES;
    } else if (h->action == LIB_HIT_SORT) {
      sortBy = (sortBy + 1) % S_COUNT;
      say(sortBy == S_NAME ? "Sorted by name" : "Sorted by last played");
      relist();
      state_save();
      focus = F_HEADER;
      headerCursor = 0;
    } else if (h->action == LIB_HIT_GRID) {
      grid = !grid;
      state_save();
      focus = F_HEADER;
      headerCursor = 1;
    } else if (h->action == LIB_HIT_PILL) {
      view = h->arg;
      selected = scroll = 0;
      relist();
      state_save();
      focus = F_GAMES;
    } else if (h->action == LIB_HIT_GAME) {
      if (h->arg >= 0 && h->arg < shownCount) {
        selected = h->arg;
        focus = F_GAMES;
        play();
      }
    } else if (h->action == LIB_HIT_SETTING) {
      settingsCursor = h->arg;
      focus = F_GAMES;
      setting_step(settingsCursor, 1);
    } else if (h->action == LIB_HIT_TOOL) {
      toolCursor = h->arg;
      focus = F_GAMES;
      say("Coming to the Linux version soon");
    }
    return;
  }
  if (!wheel)
    return;
  int steps = wheel > 0 ? wheel : -wheel, dir = wheel > 0 ? -1 : 1;
  if (steps > 20)
    steps = 20;
  if (h) {
    if (h->action == LIB_HIT_RAIL) {
      for (int i = 0; i < steps; i++)
        railCursor = (railCursor + dir + P_COUNT) % P_COUNT;
      focus = F_RAIL;
    } else if (h->action == LIB_HIT_PILL) {
      for (int i = 0; i < steps; i++)
        step_view(dir);
      focus = F_GAMES;
    } else if (h->action == LIB_HIT_GAME) {
      for (int i = 0; i < steps; i++)
        wheel_games(dir);
    } else if (h->action == LIB_HIT_SETTING) {
      int n = settings_rows();
      for (int i = 0; i < steps; i++)
        settingsCursor = (settingsCursor + dir + n) % n;
      focus = F_GAMES;
    } else if (h->action == LIB_HIT_TOOL) {
      for (int i = 0; i < steps; i++)
        toolCursor = (toolCursor + dir + 2) % 2;
      focus = F_GAMES;
    }
    return;
  }
  if (page == P_SETTINGS) {
    int n = settings_rows();
    for (int i = 0; i < steps; i++)
      settingsCursor = (settingsCursor + dir + n) % n;
    focus = F_GAMES;
  } else if (page == P_TOOLS) {
    for (int i = 0; i < steps; i++)
      toolCursor = (toolCursor + dir + 2) % 2;
    focus = F_GAMES;
  } else {
    for (int i = 0; i < steps; i++)
      wheel_games(dir);
  }
}
