// melonDS's Platform layer for PokeDaisy: files, threads, clocks and the save write-back, shared by
// the app's DS core (pk_nds.cpp) and the headless tool (native-capture/nds_dump). No wifi, camera,
// microphone or add-ons: those calls answer "nothing there".
//
// The userdata melonDS hands back is a PkNdsHost (pk_nds_platform.h), so each embedder decides where
// a save goes and where logs land.

#include "pk_nds_platform.h"

#include <Platform.h>
#include <SPI_Firmware.h>

#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <thread>

#ifdef _WIN32
#include <windows.h> // LoadLibrary / GetProcAddress: no dlfcn on Windows
#else
#include <dlfcn.h>
#endif
#include <sys/stat.h>
#include <unistd.h>

namespace melonDS::Platform
{

static PkNdsLogFn logFn = nullptr;

void SignalStop(StopReason reason, void* userdata)
{
    auto* host = static_cast<PkNdsHost*>(userdata);
    if (host && host->onStop) host->onStop(host, (int) reason);
}

// --- files: plain stdio ---

struct FileHandle { FILE* f; };

static const char* modeString(FileMode mode)
{
    bool text = mode & FileMode::Text;
    if (mode & FileMode::Append) return text ? "a+" : "a+b";
    if ((mode & FileMode::Read) && (mode & FileMode::Write)) {
        if (mode & FileMode::Preserve) return text ? "r+" : "r+b";
        return text ? "w+" : "w+b";
    }
    if (mode & FileMode::Write) return text ? "w" : "wb";
    return text ? "r" : "rb";
}

std::string GetLocalFilePath(const std::string& filename) { return filename; }

FileHandle* OpenFile(const std::string& path, FileMode mode)
{
    if ((mode & FileMode::NoCreate) && access(path.c_str(), F_OK) != 0) return nullptr;
    if ((mode & FileMode::Preserve) && (mode & FileMode::Write) && access(path.c_str(), F_OK) != 0) {
        FILE* make = fopen(path.c_str(), "wb");
        if (!make) return nullptr;
        fclose(make);
    }
    FILE* f = fopen(path.c_str(), modeString(mode));
    return f ? new FileHandle{f} : nullptr;
}

FileHandle* OpenLocalFile(const std::string& path, FileMode mode) { return OpenFile(path, mode); }
bool FileExists(const std::string& name) { return access(name.c_str(), F_OK) == 0; }
bool LocalFileExists(const std::string& name) { return FileExists(name); }
bool CheckFileWritable(const std::string& filepath)
{
    return access(filepath.c_str(), F_OK) != 0 || access(filepath.c_str(), W_OK) == 0;
}
bool CheckLocalFileWritable(const std::string& filepath) { return CheckFileWritable(filepath); }
bool CloseFile(FileHandle* file)
{
    if (!file) return false;
    bool ok = fclose(file->f) == 0;
    delete file;
    return ok;
}
bool IsEndOfFile(FileHandle* file) { return feof(file->f) != 0; }
bool FileReadLine(char* str, int count, FileHandle* file) { return fgets(str, count, file->f) != nullptr; }
u64 FilePosition(FileHandle* file) { return (u64) ftello(file->f); }
bool FileSeek(FileHandle* file, s64 offset, FileSeekOrigin origin)
{
    int whence = origin == FileSeekOrigin::Start ? SEEK_SET : origin == FileSeekOrigin::Current ? SEEK_CUR : SEEK_END;
    return fseeko(file->f, offset, whence) == 0;
}
void FileRewind(FileHandle* file) { rewind(file->f); }
u64 FileRead(void* data, u64 size, u64 count, FileHandle* file) { return fread(data, size, count, file->f); }
bool FileFlush(FileHandle* file) { return fflush(file->f) == 0; }
u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* file) { return fwrite(data, size, count, file->f); }
u64 FileWriteFormatted(FileHandle* file, const char* fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    int n = vfprintf(file->f, fmt, args);
    va_end(args);
    return n < 0 ? 0 : (u64) n;
}
u64 FileLength(FileHandle* file)
{
    struct stat st {};
    return fstat(fileno(file->f), &st) == 0 ? (u64) st.st_size : 0;
}

// --- logging ---

void Log(LogLevel level, const char* fmt, ...)
{
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (logFn) logFn((int) level, buf);
}

// --- threads ---

struct Thread { std::thread t; };
Thread* Thread_Create(std::function<void()> func) { return new Thread{std::thread(std::move(func))}; }
void Thread_Free(Thread* thread)
{
    if (thread->t.joinable()) thread->t.detach();
    delete thread;
}
void Thread_Wait(Thread* thread)
{
    if (thread->t.joinable()) thread->t.join();
}

struct Semaphore {
    std::mutex m;
    std::condition_variable cv;
    int count = 0;
};
Semaphore* Semaphore_Create() { return new Semaphore(); }
void Semaphore_Free(Semaphore* sema) { delete sema; }
void Semaphore_Reset(Semaphore* sema)
{
    std::lock_guard<std::mutex> lock(sema->m);
    sema->count = 0;
}
void Semaphore_Wait(Semaphore* sema)
{
    std::unique_lock<std::mutex> lock(sema->m);
    sema->cv.wait(lock, [sema] { return sema->count > 0; });
    sema->count--;
}
bool Semaphore_TryWait(Semaphore* sema, int timeout_ms)
{
    std::unique_lock<std::mutex> lock(sema->m);
    if (timeout_ms == 0) {
        if (sema->count == 0) return false;
    } else if (!sema->cv.wait_for(lock, std::chrono::milliseconds(timeout_ms), [sema] { return sema->count > 0; })) {
        return false;
    }
    sema->count--;
    return true;
}
void Semaphore_Post(Semaphore* sema, int count)
{
    {
        std::lock_guard<std::mutex> lock(sema->m);
        sema->count += count;
    }
    sema->cv.notify_all();
}

struct Mutex { std::mutex m; };
Mutex* Mutex_Create() { return new Mutex(); }
void Mutex_Free(Mutex* mutex) { delete mutex; }
void Mutex_Lock(Mutex* mutex) { mutex->m.lock(); }
void Mutex_Unlock(Mutex* mutex) { mutex->m.unlock(); }
bool Mutex_TryLock(Mutex* mutex) { return mutex->m.try_lock(); }

void Sleep(u64 usecs) { std::this_thread::sleep_for(std::chrono::microseconds(usecs)); }
u64 GetMSCount()
{
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}
u64 GetUSCount()
{
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

// --- saves: the embedder writes them (the app goes through its save rules, the tool to its copy) ---

void WriteNDSSave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
    auto* host = static_cast<PkNdsHost*>(userdata);
    if (host && host->onSave) host->onSave(host, savedata, savelen, writeoffset, writelen);
}
void WriteGBASave(const u8*, u32, u32, u32, void*) {}
void WriteFirmware(const Firmware&, u32, u32, void*) {}
void WriteDateTime(int, int, int, int, int, int, void*) {}

// --- local multiplayer, wifi, camera, microphone, AAC, add-ons: none (dynamic libraries: below) ---

void MP_Begin(void*) {}
void MP_End(void*) {}
int MP_SendPacket(u8*, int, u64, void*) { return 0; }
int MP_RecvPacket(u8*, u64*, void*) { return 0; }
int MP_SendCmd(u8*, int, u64, void*) { return 0; }
int MP_SendReply(u8*, int, u64, u16, void*) { return 0; }
int MP_SendAck(u8*, int, u64, void*) { return 0; }
int MP_RecvHostPacket(u8*, u64*, void*) { return 0; }
u16 MP_RecvReplies(u8*, u64, u16, void*) { return 0; }
int Net_SendPacket(u8*, int, void*) { return 0; }
int Net_RecvPacket(u8*, void*) { return 0; }
void Camera_Start(int, void*) {}
void Camera_Stop(int, void*) {}
void Camera_CaptureFrame(int, u32*, int, int, bool, void*) {}
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
float Addon_MotionQuery(MotionQueryType type, void*)
{
    // At rest, face up: gravity on Z.
    return type == MotionAccelerationZ ? 1.0f : 0.0f;
}
// The JIT's memory on Android comes from libandroid's ASharedMemory_create, looked up through these
// (without them melonDS falls back to opening /dev/ashmem, which apps can't since API 29).
#ifdef _WIN32
DynamicLibrary* DynamicLibrary_Load(const char* lib) { return reinterpret_cast<DynamicLibrary*>(LoadLibraryA(lib)); }
void DynamicLibrary_Unload(DynamicLibrary* lib)
{
    if (lib) FreeLibrary(reinterpret_cast<HMODULE>(lib));
}
void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name)
{
    return lib ? reinterpret_cast<void*>(GetProcAddress(reinterpret_cast<HMODULE>(lib), name)) : nullptr;
}
#else
DynamicLibrary* DynamicLibrary_Load(const char* lib) { return reinterpret_cast<DynamicLibrary*>(dlopen(lib, RTLD_NOW | RTLD_LOCAL)); }
void DynamicLibrary_Unload(DynamicLibrary* lib)
{
    if (lib) dlclose(lib);
}
void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name) { return lib ? dlsym(lib, name) : nullptr; }
#endif

} // namespace melonDS::Platform

void pkNdsSetLog(PkNdsLogFn fn) { melonDS::Platform::logFn = fn; }
