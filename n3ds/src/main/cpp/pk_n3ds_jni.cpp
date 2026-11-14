// The app's way into Azahar (3DS games): boot a cart on the caller's thread, draw with Vulkan to one or two Android
// surfaces, and take buttons, the circle pad and touches. The JNI functions belong to com.pokedaisy.app.n3ds.N3dsNative.
//
// Screens: with two surfaces, the main one shows the top screen alone and the second one the touch screen alone (the
// Thor's two displays, or ONE SCREEN's floating touch window later); with one, the main surface shows both
// (LargeScreen). Which it is is decided at boot, from the surfaces there are.
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "audio_core/sink_details.h"
#include "common/android_utils.h"
#include "common/dynamic_library/dynamic_library.h"
#include "common/file_util.h"
#include "common/logging/backend.h"
#include "common/logging/filter.h"
#include "common/logging/log.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/frontend/applets/default_applets.h"
#include "core/frontend/emu_window.h"
#include "core/frontend/input.h"
#include "core/hle/service/service.h"
#include "core/loader/loader.h"
#include "core/perf_stats.h"
#include "video_core/gpu.h"
#include "video_core/renderer_base.h"

#define PKLOG(...) __android_log_print(ANDROID_LOG_INFO, "pokedaisy/n3ds", __VA_ARGS__)

namespace {

std::string fromJava(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// On Android Azahar reads every path relative to its user folder (AndroidUtils::TranslateFilePath, the folder from
// N3dsAndroidUtils.getUserDirectory); "!" in front marks a real path, which a cart's is.
std::string nativePath(const std::string& path) {
    return path.empty() || path[0] == '!' ? path : "!" + path;
}

// ---- Vulkan and the windows --------------------------------------------------------------------------------------

std::shared_ptr<Common::DynamicLibrary> vulkanLibrary;

class VulkanContext final : public Frontend::GraphicsContext {
public:
    std::shared_ptr<Common::DynamicLibrary> GetDriverLibrary() override {
        return vulkanLibrary;
    }
};

// One Android surface. Vulkan presents from the game thread itself, so there's no context to make current.
class PkWindow final : public Frontend::EmuWindow {
public:
    PkWindow(ANativeWindow* surface, bool secondary) : EmuWindow(secondary) {
        SetSurface(surface);
    }
    // True when it changed (the renderer then has to rebuild its swapchain).
    bool SetSurface(ANativeWindow* surface) {
        const int w = surface ? ANativeWindow_getWidth(surface) : 0;
        const int h = surface ? ANativeWindow_getHeight(surface) : 0;
        if (surface == current && w == width && h == height) return false;
        current = surface, width = w, height = h;
        window_info.type = Frontend::WindowSystemType::Android;
        window_info.render_surface = surface;
        UpdateCurrentFramebufferLayout(std::max(w, 1), std::max(h, 1), false);
        return true;
    }
    void PollEvents() override {
        ++frames;
    }
    std::unique_ptr<Frontend::GraphicsContext> CreateSharedContext() const override {
        return std::make_unique<VulkanContext>();
    }
    std::shared_ptr<Common::DynamicLibrary> GetDriverLibrary() override {
        return vulkanLibrary;
    }
    std::atomic<u64> frames{0};

private:
    ANativeWindow* current = nullptr;
    int width = 0, height = 0;
};

std::mutex surfaceMutex;
ANativeWindow* surfaces[2] = {};           // [0] main, [1] second
std::unique_ptr<PkWindow> windows[2];     // live while a game runs

// ---- Input: our own devices, read by the HID service every frame -------------------------------------------------

std::atomic<u32> buttonBits{0};
std::atomic<float> stickX{0}, stickY{0};

class PkButton final : public Input::ButtonDevice {
public:
    explicit PkButton(int bit) : bit(bit) {}
    bool GetStatus() const override {
        return (buttonBits.load(std::memory_order_relaxed) >> bit) & 1;
    }

private:
    int bit;
};

class PkButtonFactory final : public Input::Factory<Input::ButtonDevice> {
public:
    std::unique_ptr<Input::ButtonDevice> Create(const Common::ParamPackage& params) override {
        return std::make_unique<PkButton>(params.Get("code", 0));
    }
};

class PkStick final : public Input::AnalogDevice {
public:
    std::tuple<float, float> GetStatus() const override {
        return {stickX.load(std::memory_order_relaxed), stickY.load(std::memory_order_relaxed)};
    }
};

class PkStickFactory final : public Input::Factory<Input::AnalogDevice> {
public:
    std::unique_ptr<Input::AnalogDevice> Create(const Common::ParamPackage&) override {
        return std::make_unique<PkStick>();
    }
};

// ---- The run loop's state ----------------------------------------------------------------------------------------

std::mutex runMutex;                       // one game at a time
std::atomic<bool> stopRun{false};
std::atomic<bool> paused{false};
std::mutex pauseMutex;
std::condition_variable pauseCv;

// States are taken / loaded between two RunLoop calls on the game thread: requests wait here.
std::mutex requestMutex;
std::string saveStateRequest, loadStateRequest;
std::condition_variable requestDone;
bool requestOk = false;
bool requestPending = false;

bool initialized = false;

void runRequests(Core::System& system) {
    std::unique_lock lock(requestMutex);
    if (!requestPending) return;
    bool ok = false;
    if (!saveStateRequest.empty()) {
        const std::vector<u8> buffer = system.SaveStateBuffer();
        if (FILE* f = std::fopen((saveStateRequest + ".tmp").c_str(), "wb")) {
            ok = std::fwrite(buffer.data(), 1, buffer.size(), f) == buffer.size();
            ok = (std::fclose(f) == 0) && ok;
            ok = ok && std::rename((saveStateRequest + ".tmp").c_str(), saveStateRequest.c_str()) == 0;
        }
    } else if (!loadStateRequest.empty()) {
        std::vector<u8> buffer;
        if (FILE* f = std::fopen(loadStateRequest.c_str(), "rb")) {
            std::fseek(f, 0, SEEK_END);
            buffer.resize(std::ftell(f));
            std::fseek(f, 0, SEEK_SET);
            ok = std::fread(buffer.data(), 1, buffer.size(), f) == buffer.size();
            std::fclose(f);
        }
        ok = ok && system.LoadStateBuffer(std::move(buffer));
    }
    saveStateRequest.clear(), loadStateRequest.clear();
    requestOk = ok, requestPending = false;
    requestDone.notify_all();
}

bool request(bool save, const std::string& path) {
    std::unique_lock lock(requestMutex);
    if (!Core::System::GetInstance().IsPoweredOn()) return false;
    (save ? saveStateRequest : loadStateRequest) = path;
    requestPending = true;
    pauseCv.notify_all(); // a paused game still serves requests
    requestDone.wait(lock, [] { return !requestPending || stopRun; });
    return !requestPending && requestOk;
}

} // namespace

extern "C" {

// "<status>\t<program id hex>\t<title>": status is Loader::ResultStatus (0 = Success, 8 = ErrorEncrypted, ...); the id
// and title are empty when the cart couldn't be read that far. null when no loader knows the file at all.
JNIEXPORT jstring JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_probe(JNIEnv* env, jclass, jstring path) {
    std::unique_ptr<Loader::AppLoader> loader = Loader::GetLoader(nativePath(fromJava(env, path)));
    if (!loader) return nullptr;
    u64 programId = 0;
    const Loader::ResultStatus status = loader->ReadProgramId(programId);
    std::string title;
    if (status == Loader::ResultStatus::Success) loader->ReadTitle(title);
    char id[17] = "";
    if (status == Loader::ResultStatus::Success) snprintf(id, sizeof id, "%016llx", (unsigned long long)programId);
    const std::string out = std::to_string(static_cast<int>(status)) + "\t" + id + "\t" + title;
    return env->NewStringUTF(out.c_str());
}

// Once per process, before anything else: Azahar's folders (its emulated SD card and NAND, config, shader caches) under
// userDir, its log to logcat, the input devices, every system module emulated (no console files), Vulkan.
// utils: com.pokedaisy.app.n3ds.N3dsAndroidUtils, whose getUserDirectory() is userDir (set by the caller first).
JNIEXPORT jboolean JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_init(JNIEnv* env, jclass, jstring userDir,
                                                                      jclass utils) {
    if (initialized) return JNI_TRUE;
    const std::string dir = fromJava(env, userDir);
    AndroidUtils::InitJNI(env, static_cast<jclass>(env->NewGlobalRef(utils)));
    FileUtil::SetUserPath(); // first: Azahar sets its paths once; on Android they're "/..." under the user folder
    Common::Log::Initialize();
    Common::Log::Start();
    Common::Log::Filter filter;
    filter.ParseFilterString("*:Warning");
    Common::Log::SetGlobalFilter(filter);

    Settings::values.graphics_api = Settings::GraphicsAPI::Vulkan;
    Settings::values.use_cpu_jit = true;
    Settings::values.is_new_3ds = true;   // more CPU for the game: Pokemon X / Y run on both
    Settings::values.use_disk_shader_cache = true;
    Settings::values.async_shader_compilation = true;
    Settings::values.frame_limit = 100;
    Settings::values.output_type = AudioCore::SinkType::Auto;
    for (int i = 0; i < Settings::NativeButton::NumButtons; i++)
        Settings::values.current_input_profile.buttons[i] = "engine:pk,code:" + std::to_string(i);
    Settings::values.current_input_profile.analogs[Settings::NativeAnalog::CirclePad] = "engine:pk";
    Settings::values.current_input_profile.touch_device = "engine:emu_window";
    for (const auto& module : Service::service_module_map) Settings::values.lle_modules[module.name] = false;
    Input::RegisterFactory<Input::ButtonDevice>("pk", std::make_shared<PkButtonFactory>());
    Input::RegisterFactory<Input::AnalogDevice>("pk", std::make_shared<PkStickFactory>());

    vulkanLibrary = std::make_shared<Common::DynamicLibrary>(dlopen("libvulkan.so", RTLD_NOW));
    initialized = true;
    PKLOG("init: user dir %s", dir.c_str());
    return JNI_TRUE;
}

// The main (0) or second (1) surface, null when it went away.
JNIEXPORT void JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_setSurface(JNIEnv* env, jclass, jint which,
                                                                        jobject surface) {
    if (which < 0 || which > 1) return;
    std::scoped_lock lock(surfaceMutex);
    if (surfaces[which]) ANativeWindow_release(surfaces[which]);
    surfaces[which] = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (windows[which] && windows[which]->SetSurface(surfaces[which])) {
        auto& system = Core::System::GetInstance();
        if (system.IsPoweredOn() && surfaces[which]) system.GPU().Renderer().NotifySurfaceChanged(which == 1);
    }
}

// Boots path and runs it on this thread until stop(); returns Core::System::ResultStatus (0 = stopped normally).
JNIEXPORT jint JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_run(JNIEnv* env, jclass, jstring jpath) {
    std::scoped_lock runLock(runMutex);
    const std::string path = nativePath(fromJava(env, jpath));
    auto& system = Core::System::GetInstance();
    stopRun = false;
    paused = false;
    {
        std::scoped_lock lock(surfaceMutex);
        const bool two = surfaces[1] != nullptr;
        // Two surfaces: the top screen alone on the main one, the touch screen alone on the second. One: both.
        Settings::values.layout_option = two ? Settings::LayoutOption::SingleScreen : Settings::LayoutOption::LargeScreen;
        Settings::values.secondary_display_layout = Settings::SecondaryDisplayLayout::BottomScreenOnly;
        Settings::values.swap_screen = false;
        windows[0] = std::make_unique<PkWindow>(surfaces[0], false);
        windows[1] = two ? std::make_unique<PkWindow>(surfaces[1], true) : nullptr;
    }
    FileUtil::SetCurrentRomPath(path);
    Frontend::RegisterDefaultApplets(system);
    system.ApplySettings();
    const auto status = system.Load(*windows[0], path, windows[1].get());
    if (status != Core::System::ResultStatus::Success) {
        PKLOG("load failed: %d %s", static_cast<int>(status), system.GetStatusDetails().c_str());
        std::scoped_lock lock(surfaceMutex);
        windows[0].reset(), windows[1].reset();
        return static_cast<jint>(status);
    }
    u64 programId = 0;
    system.GetAppLoader().ReadProgramId(programId);
    system.GPU().ApplyPerProgramSettings(programId);
    std::atomic_bool stopLoading{false};
    system.GPU().Renderer().Rasterizer()->LoadDefaultDiskResources(stopLoading, nullptr);
    system.RegisterCoreLoopThreadId();
    PKLOG("running %016llx", static_cast<unsigned long long>(programId));

    jint result = 0;
    auto lastPerf = std::chrono::steady_clock::now();
    while (!stopRun) {
        runRequests(system);
        if (paused) {
            std::unique_lock lock(pauseMutex);
            // Polled as well: a request's notify can come between this check and the wait.
            pauseCv.wait_for(lock, std::chrono::milliseconds(50), [] {
                std::scoped_lock r(requestMutex);
                return !paused || stopRun || requestPending;
            });
            continue;
        }
        const auto r = system.RunLoop();
        // The speed line every 5 s (logcat pokedaisy/n3ds): what to read first when a game runs slow.
        if (const auto now = std::chrono::steady_clock::now(); now - lastPerf > std::chrono::seconds(5)) {
            lastPerf = now;
            const auto stats = system.GetAndResetPerfStats();
            PKLOG("perf: game %.1f fps, system %.1f fps, speed %.0f%%, frame: gpu %.1f ms swap %.1f ms svc %.1f ms "
                  "ipc %.1f ms rest %.1f ms",
                  stats.game_fps, stats.system_fps, stats.emulation_speed * 100, stats.time_gpu * 1000,
                  stats.time_swap * 1000, stats.time_hle_svc * 1000, stats.time_hle_ipc * 1000,
                  stats.time_remaining * 1000);
        }
        if (r == Core::System::ResultStatus::Success) continue;
        if (r == Core::System::ResultStatus::ShutdownRequested) break;
        PKLOG("run loop: %d %s", static_cast<int>(r), system.GetStatusDetails().c_str());
        result = static_cast<jint>(r);
        break;
    }
    {
        std::scoped_lock lock(requestMutex);
        requestPending = false;
        requestDone.notify_all();
    }
    system.Shutdown();
    std::scoped_lock lock(surfaceMutex);
    windows[0].reset(), windows[1].reset();
    PKLOG("stopped");
    return result;
}

JNIEXPORT void JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_stop(JNIEnv*, jclass) {
    stopRun = true;
    pauseCv.notify_all();
    requestDone.notify_all();
}

JNIEXPORT void JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_setPaused(JNIEnv*, jclass, jboolean p) {
    paused = p;
    pauseCv.notify_all();
}

// The 3DS buttons as Settings::NativeButton bits (A 0, B 1, X 2, Y 3, UP 4, DOWN 5, LEFT 6, RIGHT 7, L 8, R 9,
// START 10, SELECT 11, ZL 14, ZR 15, HOME 16).
JNIEXPORT void JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_setButtons(JNIEnv*, jclass, jint bits) {
    buttonBits = static_cast<u32>(bits);
}

// The circle pad, -1..1 each way, up positive.
JNIEXPORT void JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_setStick(JNIEnv*, jclass, jfloat x, jfloat y) {
    stickX = std::clamp(x, -1.f, 1.f);
    stickY = std::clamp(y, -1.f, 1.f);
}

// A touch on the main (0) or second (1) surface, in that surface's pixels; Azahar maps it to the touch screen where it
// drew it (a touch outside the touch screen is ignored).
JNIEXPORT void JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_touch(JNIEnv*, jclass, jint which, jint x, jint y,
                                                                   jint action) {
    std::scoped_lock lock(surfaceMutex);
    if (which < 0 || which > 1 || !windows[which]) return;
    auto& w = *windows[which];
    const unsigned ux = static_cast<unsigned>(std::max(x, 0)), uy = static_cast<unsigned>(std::max(y, 0));
    if (action == 0) w.TouchPressed(ux, uy);
    else if (action == 1) w.TouchMoved(ux, uy);
    else w.TouchReleased();
}

JNIEXPORT jboolean JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_saveState(JNIEnv* env, jclass, jstring path) {
    return request(true, fromJava(env, path)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_loadState(JNIEnv* env, jclass, jstring path) {
    return request(false, fromJava(env, path)) ? JNI_TRUE : JNI_FALSE;
}

// Speed in percent of real time; 0 = as fast as it goes.
JNIEXPORT void JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_setSpeed(JNIEnv*, jclass, jint percent) {
    Settings::values.frame_limit = static_cast<double>(std::max(percent, 0));
}

// Frames presented on the main surface so far, and the game's frames a second (Azahar's own measure).
JNIEXPORT jlong JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_frames(JNIEnv*, jclass) {
    std::scoped_lock lock(surfaceMutex);
    return windows[0] ? static_cast<jlong>(windows[0]->frames.load()) : 0;
}

JNIEXPORT jdouble JNICALL Java_com_pokedaisy_app_n3ds_N3dsNative_gameFps(JNIEnv*, jclass) {
    auto& system = Core::System::GetInstance();
    if (!system.IsPoweredOn() || !system.perf_stats) return 0;
    return system.perf_stats->GetLastStats().game_fps;
}

} // extern "C"
