// melonDS's Platform calls for BananaDS's melonDS (906e9eb + BananaDS's patches) inside libpkbananads.so: BananaDS's own
// src/platform.cpp without its frontend (no Wi-Fi, local wireless, microphone or camera; logs to logcat). Saves go to
// pk_nds_bnd.cpp through the userdata it gives melonDS (bnd_on_save). All hidden: the app's melonDS has its own.

#include "Platform.h"

#include <android/log.h>
#include <unistd.h>

#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <thread>

void bnd_on_save(void* userdata, const melonDS::u8* data, melonDS::u32 len);
void bnd_on_stop(void* userdata);

namespace bananads {
void sched_off_emu_core();
}

namespace melonDS::Platform {

void SignalStop(StopReason reason, void* userdata)
{
    __android_log_print(ANDROID_LOG_INFO, "pokedaisy/bananads", "the DS stopped (reason %d)", (int) reason);
    bnd_on_stop(userdata);
}

// (BananaDS's platform.cpp's)
static const char* modeStr(FileMode m)
{
    bool r = m & FileMode::Read, w = m & FileMode::Write;
    if (m & FileMode::Append) return r ? "a+b" : "ab";
    if (r && w) return (m & FileMode::Preserve) ? "r+b" : "w+b";
    if (w) return (m & FileMode::Preserve) ? "r+b" : "wb";
    return "rb";
}

FileHandle* OpenFile(const std::string& path, FileMode mode)
{
    if ((mode & FileMode::ReadWrite) == FileMode::None) return nullptr;
    bool exists = access(path.c_str(), F_OK) == 0;
    if ((mode & FileMode::NoCreate) && !exists) return nullptr;
    if ((mode & FileMode::Write) && (mode & FileMode::Preserve) && !exists) {
        FILE* c = fopen(path.c_str(), "wb");   // r+b needs the file to exist
        if (c) fclose(c);
    }
    return (FileHandle*) fopen(path.c_str(), modeStr(mode));
}
std::string GetLocalFilePath(const std::string& filename) { return filename; }
FileHandle* OpenLocalFile(const std::string& path, FileMode mode) { return OpenFile(path, mode); }
bool FileExists(const std::string& name) { return access(name.c_str(), F_OK) == 0; }
bool LocalFileExists(const std::string& name) { return FileExists(name); }
bool CheckFileWritable(const std::string& path) { return access(path.c_str(), W_OK) == 0; }
bool CheckLocalFileWritable(const std::string& name) { return CheckFileWritable(name); }
bool CloseFile(FileHandle* f) { return fclose((FILE*) f) == 0; }
bool IsEndOfFile(FileHandle* f) { return feof((FILE*) f) != 0; }
bool FileReadLine(char* str, int count, FileHandle* f) { return fgets(str, count, (FILE*) f) != nullptr; }
u64 FilePosition(FileHandle* f) { return (u64) ftello((FILE*) f); }
bool FileSeek(FileHandle* f, s64 off, FileSeekOrigin o)
{
    int w = o == FileSeekOrigin::Start ? SEEK_SET : o == FileSeekOrigin::Current ? SEEK_CUR : SEEK_END;
    return fseeko((FILE*) f, (off_t) off, w) == 0;
}
void FileRewind(FileHandle* f) { rewind((FILE*) f); }
u64 FileRead(void* data, u64 size, u64 count, FileHandle* f) { return fread(data, size, count, (FILE*) f); }
bool FileFlush(FileHandle* f) { return fflush((FILE*) f) == 0; }
u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* f) { return fwrite(data, size, count, (FILE*) f); }
u64 FileWriteFormatted(FileHandle* f, const char* fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    int n = vfprintf((FILE*) f, fmt, ap);
    va_end(ap);
    return n < 0 ? 0 : (u64) n;
}
u64 FileLength(FileHandle* f)
{
    FILE* fp = (FILE*) f;
    off_t at = ftello(fp);
    fseeko(fp, 0, SEEK_END);
    off_t n = ftello(fp);
    fseeko(fp, at, SEEK_SET);
    return (u64) n;
}

// The ROM is ours (a plain new[] buffer melonDS frees itself).
bool ROMRelease(u8*) { return false; }

void Log(LogLevel level, const char* fmt, ...)
{
    if (level < LogLevel::Info) return;
    va_list ap;
    va_start(ap, fmt);
    int prio = level >= LogLevel::Error ? ANDROID_LOG_ERROR : level >= LogLevel::Warn ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_vprint(prio, "pokedaisy/bananads", fmt, ap);
    va_end(ap);
}

struct Thread { std::thread t; };
Thread* Thread_Create(std::function<void()> func)
{
    return new Thread{std::thread([f = std::move(func)] { bananads::sched_off_emu_core(); f(); })};
}
void Thread_Free(Thread* t)
{
    if (t->t.joinable()) t->t.detach();
    delete t;
}
void Thread_Wait(Thread* t)
{
    if (t->t.joinable()) t->t.join();
}

struct Semaphore {
    std::mutex m;
    std::condition_variable cv;
    int count = 0;
};
Semaphore* Semaphore_Create() { return new Semaphore; }
void Semaphore_Free(Semaphore* s) { delete s; }
void Semaphore_Reset(Semaphore* s)
{
    std::lock_guard<std::mutex> l(s->m);
    s->count = 0;
}
void Semaphore_Wait(Semaphore* s)
{
    std::unique_lock<std::mutex> l(s->m);
    s->cv.wait(l, [s] { return s->count > 0; });
    s->count--;
}
bool Semaphore_TryWait(Semaphore* s, int timeout_ms)
{
    std::unique_lock<std::mutex> l(s->m);
    if (!s->cv.wait_for(l, std::chrono::milliseconds(timeout_ms), [s] { return s->count > 0; })) return false;
    s->count--;
    return true;
}
void Semaphore_Post(Semaphore* s, int count)
{
    {
        std::lock_guard<std::mutex> l(s->m);
        s->count += count;
    }
    if (count == 1) s->cv.notify_one();
    else s->cv.notify_all();
}

struct Mutex { std::mutex m; };
Mutex* Mutex_Create() { return new Mutex; }
void Mutex_Free(Mutex* m) { delete m; }
void Mutex_Lock(Mutex* m) { m->m.lock(); }
void Mutex_Unlock(Mutex* m) { m->m.unlock(); }
bool Mutex_TryLock(Mutex* m) { return m->m.try_lock(); }

void Sleep(u64 usecs) { std::this_thread::sleep_for(std::chrono::microseconds(usecs)); }
u64 GetUSCount()
{
    using namespace std::chrono;
    return (u64) duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}
u64 GetMSCount() { return GetUSCount() / 1000; }

void WriteNDSSave(const u8* data, u32 len, u32, u32, void* userdata) { bnd_on_save(userdata, data, len); }
void WriteGBASave(const u8*, u32, u32, u32, void*) {}
void WriteFirmware(const Firmware&, u32, u32, void*) {}
void WriteDateTime(int, int, int, int, int, int, void*) {}

void MP_Begin(void*) {}
void MP_End(void*) {}
int MP_SendPacket(u8*, int len, u64, void*) { return len; }
int MP_RecvPacket(u8*, u64*, void*) { return 0; }
int MP_SendCmd(u8*, int len, u64, void*) { return len; }
int MP_SendReply(u8*, int len, u64, u16, void*) { return len; }
int MP_SendAck(u8*, int len, u64, void*) { return len; }
int MP_RecvHostPacket(u8*, u64*, void*) { return 0; }
u16 MP_RecvReplies(u8*, u64, u16, void*) { return 0; }

int Net_SendPacket(u8*, int len, void*) { return len; }
int Net_RecvPacket(u8*, void*) { return 0; }

void Camera_Start(int, void*) {}
void Camera_Stop(int, void*) {}
void Camera_CaptureFrame(int, u32* frame, int width, int height, bool, void*)
{
    for (int i = 0; i < width * height; i++) frame[i] = 0x80808080;
}

void Mic_Start(void*) {}
void Mic_Stop(void*) {}
int Mic_ReadInput(s16*, int, void*) { return 0; }

AACDecoder* AAC_Init() { return nullptr; }
void AAC_DeInit(AACDecoder*) {}
bool AAC_Configure(AACDecoder*, int, int) { return false; }
bool AAC_DecodeFrame(AACDecoder*, const void*, int, void*, int) { return false; }

bool Addon_KeyDown(KeyType, void*) { return false; }
void Addon_RumbleStart(u32, void*) {}
void Addon_RumbleStop(void*) {}
float Addon_MotionQuery(MotionQueryType, void*) { return 0.f; }

DynamicLibrary* DynamicLibrary_Load(const char*) { return nullptr; }
void DynamicLibrary_Unload(DynamicLibrary*) {}
void* DynamicLibrary_LoadFunction(DynamicLibrary*, const char*) { return nullptr; }

} // namespace melonDS::Platform
