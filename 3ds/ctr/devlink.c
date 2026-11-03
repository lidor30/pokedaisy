// The dev build's Wi-Fi link: devlink.h. One text line a command, answered "OK <payload bytes>\n" + payload or
// "ERR <why>\n". Its thread lives on a core of its own where there's one, so a transfer doesn't take the game's
// time. Files only go in and out of the dev folder and the dev build's own .3dsx.
#ifdef PD_DEV
#include "devlink.h"

#include <3ds.h>

#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <malloc.h>
#include <netinet/in.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#define SOC_BUFFER_SIZE 0x100000
#define THREAD_STACK (64 * 1024)
#define DEV_ROOT "sdmc:/pokedaisy-dev/"
#define LOG_BYTES 16384
#define TOP_BYTES (400 * 240 * 3)
#define BOTTOM_BYTES (320 * 240 * 3)

static u32* socBuffer;
static Thread thread;
static volatile bool stopping;
static char address[32];

static LightLock lock;
static uint32_t injectedKeys;
static int injectedFrames;
static int touchX, touchY, touchFrames;
static int dragToX, dragToY, dragTotal;
static char launch[256];
static volatile bool leaveWanted, restartWanted;
static struct devlink_stats stats;
static char logRing[LOG_BYTES];
static unsigned logEnd;

// A screenshot: asked for by the link's thread, filled by the main thread between frames.
static uint8_t* shotBuffer;
static volatile bool shotWanted;
static LightEvent shotDone;

void devlink_log(const char* format, ...) {
    char line[256];
    va_list args;
    va_start(args, format);
    int n = vsnprintf(line, sizeof(line) - 1, format, args);
    va_end(args);
    if (n < 0) return;
    if (n > (int) sizeof(line) - 2) n = sizeof(line) - 2;
    line[n++] = '\n';
    LightLock_Lock(&lock);
    for (int i = 0; i < n; i++) logRing[logEnd++ % LOG_BYTES] = line[i];
    LightLock_Unlock(&lock);
}

uint32_t devlink_keys(void) {
    LightLock_Lock(&lock);
    uint32_t keys = injectedFrames > 0 ? injectedKeys : 0;
    if (injectedFrames > 0) injectedFrames--;
    LightLock_Unlock(&lock);
    return keys;
}

bool devlink_touch(int* x, int* y) {
    LightLock_Lock(&lock);
    bool held = touchFrames > 0;
    if (held) {
        if (dragTotal > 0) {
            int done = dragTotal - touchFrames;
            *x = touchX + (dragToX - touchX) * done / dragTotal;
            *y = touchY + (dragToY - touchY) * done / dragTotal;
        } else {
            *x = touchX;
            *y = touchY;
        }
        if (--touchFrames == 0) dragTotal = 0;
    }
    LightLock_Unlock(&lock);
    return held;
}

bool devlink_take_launch(char* out, unsigned outLen) {
    LightLock_Lock(&lock);
    bool has = launch[0] != 0;
    if (has) {
        snprintf(out, outLen, "%s", launch);
        launch[0] = 0;
    }
    LightLock_Unlock(&lock);
    return has;
}

bool devlink_take_leave(void) {
    bool wanted = leaveWanted;
    leaveWanted = false;
    return wanted;
}

bool devlink_restart_wanted(void) {
    return restartWanted;
}

void devlink_report(const struct devlink_stats* s) {
    LightLock_Lock(&lock);
    stats = *s;
    LightLock_Unlock(&lock);
}

const char* devlink_address(void) {
    return address;
}

// The screens as they are shown: the buffers the display isn't being given next, top then bottom, 3 bytes a pixel,
// in the hardware's order (a column at a time, bottom up); pdlink.py turns them round.
void devlink_after_frame(void) {
    if (!shotWanted) return;
    u16 w, h;
    u8* top = gfxGetFramebuffer(GFX_TOP, GFX_LEFT, &w, &h);
    GSPGPU_InvalidateDataCache(top, TOP_BYTES);
    memcpy(shotBuffer, top, TOP_BYTES);
    u8* bottom = gfxGetFramebuffer(GFX_BOTTOM, GFX_LEFT, &w, &h);
    GSPGPU_InvalidateDataCache(bottom, BOTTOM_BYTES);
    memcpy(shotBuffer + TOP_BYTES, bottom, BOTTOM_BYTES);
    shotWanted = false;
    LightEvent_Signal(&shotDone);
}

// --- the connection ---

static bool send_all(int fd, const void* data, size_t len) {
    const char* p = data;
    while (len > 0) {
        ssize_t n = send(fd, p, len > 0x4000 ? 0x4000 : len, 0);
        if (n <= 0) return false;
        p += n;
        len -= (size_t) n;
    }
    return true;
}

static bool recv_all(int fd, void* data, size_t len) {
    char* p = data;
    while (len > 0) {
        ssize_t n = recv(fd, p, len, 0);
        if (n <= 0) return false;
        p += n;
        len -= (size_t) n;
    }
    return true;
}

static bool recv_line(int fd, char* line, size_t size) {
    size_t n = 0;
    while (n + 1 < size) {
        char c;
        if (recv(fd, &c, 1, 0) <= 0) return false;
        if (c == '\n') break;
        line[n++] = c;
    }
    line[n] = 0;
    return true;
}

static bool reply(int fd, const void* payload, size_t len) {
    char head[32];
    int n = snprintf(head, sizeof(head), "OK %u\n", (unsigned) len);
    return send_all(fd, head, (size_t) n) && (len == 0 || send_all(fd, payload, len));
}

static bool reply_error(int fd, const char* why) {
    char head[128];
    int n = snprintf(head, sizeof(head), "ERR %s\n", why);
    return send_all(fd, head, (size_t) n);
}

// [name] under the dev folder, or the dev build itself ("self"); false for anything else.
static bool dev_path(const char* name, char* out, size_t size) {
    if (!strcmp(name, "self")) {
        snprintf(out, size, "sdmc:" DEVLINK_SELF);
        return true;
    }
    if (strstr(name, "..") || name[0] == '/') return false;
    snprintf(out, size, DEV_ROOT "%s", name);
    return true;
}

static void make_parents(const char* path) {
    char dir[320];
    snprintf(dir, sizeof(dir), "%s", path);
    for (char* p = dir + strlen("sdmc:/"); *p; p++) {
        if (*p != '/') continue;
        *p = 0;
        mkdir(dir, 0777);
        *p = '/';
    }
}

static bool cmd_put(int fd, const char* name, size_t size) {
    char path[320], temp[330];
    if (!dev_path(name, path, sizeof(path))) return reply_error(fd, "outside the dev folder");
    snprintf(temp, sizeof(temp), "%s.part", path);
    make_parents(path);
    FILE* f = fopen(temp, "wb");
    if (!f) return reply_error(fd, "can't write");
    static char chunk[0x8000];
    size_t left = size;
    bool ok = true;
    while (left > 0) {
        size_t n = left > sizeof(chunk) ? sizeof(chunk) : left;
        if (!recv_all(fd, chunk, n)) {
            fclose(f);
            remove(temp);
            return false;
        }
        ok = ok && fwrite(chunk, 1, n, f) == n;
        left -= n;
    }
    ok = !fclose(f) && ok;
    if (ok) {
        remove(path);
        ok = !rename(temp, path);
    }
    if (!ok) {
        remove(temp);
        return reply_error(fd, "write failed");
    }
    return reply(fd, NULL, 0);
}

static bool cmd_get(int fd, const char* name) {
    char path[320];
    if (!dev_path(name, path, sizeof(path))) return reply_error(fd, "outside the dev folder");
    FILE* f = fopen(path, "rb");
    if (!f) return reply_error(fd, "no such file");
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    char head[32];
    int n = snprintf(head, sizeof(head), "OK %ld\n", size);
    bool ok = send_all(fd, head, (size_t) n);
    static char chunk[0x8000];
    size_t got;
    while (ok && (got = fread(chunk, 1, sizeof(chunk), f)) > 0) ok = send_all(fd, chunk, got);
    fclose(f);
    return ok;
}

static bool cmd_ls(int fd, const char* name) {
    char path[320];
    if (!dev_path(name, path, sizeof(path))) return reply_error(fd, "outside the dev folder");
    DIR* d = opendir(path);
    if (!d) return reply_error(fd, "no such folder");
    static char list[8192];
    size_t n = 0;
    struct dirent* e;
    while ((e = readdir(d)) && n + 300 < sizeof(list)) n += (size_t) snprintf(list + n, 300, "%s\n", e->d_name);
    closedir(d);
    return reply(fd, list, n);
}

static bool cmd_shot(int fd) {
    if (!shotBuffer) shotBuffer = malloc(TOP_BYTES + BOTTOM_BYTES);
    if (!shotBuffer) return reply_error(fd, "out of memory");
    LightEvent_Clear(&shotDone);
    shotWanted = true;
    if (LightEvent_WaitTimeout(&shotDone, 2000000000LL)) {
        shotWanted = false;
        return reply_error(fd, "no frame");
    }
    return reply(fd, shotBuffer, TOP_BYTES + BOTTOM_BYTES);
}

static bool cmd_stat(int fd) {
    struct devlink_stats s;
    LightLock_Lock(&lock);
    s = stats;
    LightLock_Unlock(&lock);
    char text[256];
    int n = snprintf(text, sizeof(text),
                     "frames=%u core_us=%u ui_us=%u draw_us=%u worst_us=%u late=%u linear_free=%u new3ds=%d\n",
                     s.frames, s.coreUs, s.uiUs, s.drawUs, s.worstUs, s.late, (unsigned) linearSpaceFree(), 0);
    return reply(fd, text, (size_t) n);
}

static bool cmd_log(int fd) {
    static char text[LOG_BYTES];
    LightLock_Lock(&lock);
    unsigned count = logEnd < LOG_BYTES ? logEnd : LOG_BYTES;
    for (unsigned i = 0; i < count; i++) text[i] = logRing[(logEnd - count + i) % LOG_BYTES];
    LightLock_Unlock(&lock);
    return reply(fd, text, count);
}

// One command; false ends the connection.
static bool serve(int fd, char* line) {
    char name[256];
    unsigned a, b;
    int x0, y0, x1, y1;
    if (!strcmp(line, "ping")) return reply(fd, "pong\n", 5);
    if (sscanf(line, "press %x %u", &a, &b) == 2) {
        LightLock_Lock(&lock);
        injectedKeys = a;
        injectedFrames = (int) b;
        LightLock_Unlock(&lock);
        return reply(fd, NULL, 0);
    }
    if (sscanf(line, "touch %d %d %u", &x0, &y0, &b) == 3) {
        LightLock_Lock(&lock);
        touchX = x0;
        touchY = y0;
        touchFrames = (int) b;
        dragTotal = 0;
        LightLock_Unlock(&lock);
        return reply(fd, NULL, 0);
    }
    if (sscanf(line, "drag %d %d %d %d %u", &x0, &y0, &x1, &y1, &b) == 5) {
        LightLock_Lock(&lock);
        touchX = x0;
        touchY = y0;
        dragToX = x1;
        dragToY = y1;
        touchFrames = dragTotal = (int) b;
        LightLock_Unlock(&lock);
        return reply(fd, NULL, 0);
    }
    if (!strcmp(line, "shot")) return cmd_shot(fd);
    if (!strcmp(line, "stat")) return cmd_stat(fd);
    if (!strcmp(line, "log")) return cmd_log(fd);
    if (!strcmp(line, "leave")) {
        leaveWanted = true;
        return reply(fd, NULL, 0);
    }
    if (!strcmp(line, "restart")) {
        restartWanted = true;
        return reply(fd, NULL, 0);
    }
    if (sscanf(line, "launch %255[^\n]", name) == 1) {
        LightLock_Lock(&lock);
        snprintf(launch, sizeof(launch), "%s", name);
        LightLock_Unlock(&lock);
        return reply(fd, NULL, 0);
    }
    if (sscanf(line, "put %u %255[^\n]", &a, name) == 2) return cmd_put(fd, name, a);
    if (sscanf(line, "get %255[^\n]", name) == 1) return cmd_get(fd, name);
    if (sscanf(line, "ls %255[^\n]", name) == 1) return cmd_ls(fd, name);
    if (!strcmp(line, "ls")) return cmd_ls(fd, "");
    if (sscanf(line, "rm %255[^\n]", name) == 1) {
        char path[320];
        if (!dev_path(name, path, sizeof(path))) return reply_error(fd, "outside the dev folder");
        return remove(path) ? reply_error(fd, "can't remove") : reply(fd, NULL, 0);
    }
    return reply_error(fd, "unknown command");
}

// Says where this console is, every two seconds, for `pdlink.py find`.
static void beacon(int fd, struct sockaddr_in* to) {
    char text[64];
    int n = snprintf(text, sizeof(text), "pokedaisy-dev %s", address);
    sendto(fd, text, (size_t) n, 0, (struct sockaddr*) to, sizeof(*to));
}

static void link_thread(void* arg) {
    (void) arg;
    int server = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (server < 0) return;
    struct sockaddr_in here = { 0 };
    here.sin_family = AF_INET;
    here.sin_port = htons(DEVLINK_PORT);
    here.sin_addr.s_addr = gethostid();
    snprintf(address, sizeof(address), "%s", inet_ntoa(here.sin_addr));
    if (bind(server, (struct sockaddr*) &here, sizeof(here)) || listen(server, 2)) {
        close(server);
        return;
    }
    fcntl(server, F_SETFL, fcntl(server, F_GETFL, 0) | O_NONBLOCK);

    int udp = socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
    int yes = 1;
    if (udp >= 0) setsockopt(udp, SOL_SOCKET, SO_BROADCAST, &yes, sizeof(yes));
    struct sockaddr_in everyone = { 0 };
    everyone.sin_family = AF_INET;
    everyone.sin_port = htons(DEVLINK_BEACON_PORT);
    everyone.sin_addr.s_addr = 0xFFFFFFFF;

    u64 lastBeacon = 0;
    while (!stopping) {
        u64 now = osGetTime();
        if (udp >= 0 && now - lastBeacon >= 2000) {
            lastBeacon = now;
            beacon(udp, &everyone);
        }
        struct sockaddr_in peer;
        socklen_t peerLen = sizeof(peer);
        int fd = accept(server, (struct sockaddr*) &peer, &peerLen);
        if (fd < 0) {
            svcSleepThread(50 * 1000000LL);
            continue;
        }
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) & ~O_NONBLOCK);
        char line[320];
        while (!stopping && recv_line(fd, line, sizeof(line)) && serve(fd, line)) {}
        close(fd);
    }
    if (udp >= 0) close(udp);
    close(server);
}

void devlink_start(void) {
    LightLock_Init(&lock);
    LightEvent_Init(&shotDone, RESET_ONESHOT);
    socBuffer = memalign(0x1000, SOC_BUFFER_SIZE);
    if (!socBuffer || socInit(socBuffer, SOC_BUFFER_SIZE)) {
        free(socBuffer);
        socBuffer = NULL;
        return;
    }
    // The New 3DS's third core; else the system core, which needs its time limit raised.
    s32 priority = 0x30;
    svcGetThreadPriority(&priority, CUR_THREAD_HANDLE);
    thread = threadCreate(link_thread, NULL, THREAD_STACK, priority + 1, 2, false);
    if (!thread) {
        APT_SetAppCpuTimeLimit(30);
        thread = threadCreate(link_thread, NULL, THREAD_STACK, priority + 1, 1, false);
    }
}

void devlink_stop(void) {
    stopping = true;
    if (thread) {
        threadJoin(thread, 2000000000LL);
        threadFree(thread);
    }
    if (socBuffer) {
        socExit();
        free(socBuffer);
    }
    free(shotBuffer);
}

bool devlink_chainload(void) {
    Handle ldr;
    if (R_FAILED(svcConnectToPort(&ldr, "hb:ldr"))) return false;
    static const char path[] = DEVLINK_SELF;
    u32* cmd = getThreadCommandBuffer();
    cmd[0] = IPC_MakeHeader(2, 0, 2); // SetTarget
    cmd[1] = IPC_Desc_StaticBuffer(sizeof(path), 0);
    cmd[2] = (u32) path;
    Result rc = svcSendSyncRequest(ldr);
    if (R_SUCCEEDED(rc)) rc = (Result) cmd[1];
    // argv: one argument, the path (ENTRY_ARGBUFSIZE's layout: the count, then the strings).
    static u32 args[0x100];
    memset(args, 0, sizeof(args));
    args[0] = 1;
    snprintf((char*) &args[1], sizeof(args) - 4, "sdmc:%s", path);
    cmd = getThreadCommandBuffer();
    cmd[0] = IPC_MakeHeader(3, 0, 2); // SetArgv
    cmd[1] = IPC_Desc_StaticBuffer(sizeof(args), 1);
    cmd[2] = (u32) args;
    Result rc2 = svcSendSyncRequest(ldr);
    svcCloseHandle(ldr);
    return R_SUCCEEDED(rc) && R_SUCCEEDED(rc2);
}
#endif
