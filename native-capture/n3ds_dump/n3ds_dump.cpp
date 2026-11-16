// n3ds_dump: headless Azahar for 3DS fixtures (README.md). Boots a decrypted cart with the software renderer, runs a
// script from stdin and writes screenshots, memory dumps and states, the way nds_dump does for the DS games.
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <tuple>
#include <vector>

#include <lodepng.h>

#include "common/file_util.h"
#include "common/logging/backend.h"
#include "common/logging/log.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/frontend/emu_window.h"
#include "core/hle/kernel/kernel.h"
#include "core/hle/kernel/process.h"
#include "core/hle/kernel/vm_manager.h"
#include "core/hle/service/service.h"
#include "core/loader/loader.h"
#include "core/memory.h"

namespace Memory {
extern void (*pk_watch_write)(VAddr vaddr, u32 size, u64 value); // our Azahar patch
}
#include "input_common/keyboard.h"
#include "input_common/main.h"
#include "video_core/gpu.h"
#include "video_core/renderer_software/renderer_software.h"

namespace fs = std::filesystem;

namespace {

// A window nobody sees: the software renderer ends every frame with PollEvents, which counts it.
class HeadlessWindow final : public Frontend::EmuWindow {
public:
    HeadlessWindow() {
        UpdateCurrentFramebufferLayout(400, 480);
    }
    void PollEvents() override {
        ++frames;
    }
    u64 frames = 0;
};

// The 3DS's buttons, each a key code of InputCommon's keyboard device (its code is the button's index).
const std::map<std::string, int> BUTTONS = {
    {"A", Settings::NativeButton::A},         {"B", Settings::NativeButton::B},
    {"X", Settings::NativeButton::X},         {"Y", Settings::NativeButton::Y},
    {"UP", Settings::NativeButton::Up},       {"DOWN", Settings::NativeButton::Down},
    {"LEFT", Settings::NativeButton::Left},   {"RIGHT", Settings::NativeButton::Right},
    {"L", Settings::NativeButton::L},         {"R", Settings::NativeButton::R},
    {"START", Settings::NativeButton::Start}, {"SELECT", Settings::NativeButton::Select},
    {"ZL", Settings::NativeButton::ZL},       {"ZR", Settings::NativeButton::ZR},
    {"HOME", Settings::NativeButton::Home},
};

std::vector<int> held;

void setKeys(const std::string& spec) {
    auto* keyboard = InputCommon::GetKeyboard();
    for (int code : held) keyboard->ReleaseKey(code);
    held.clear();
    if (spec == "NONE") return;
    std::stringstream in(spec);
    std::string name;
    while (std::getline(in, name, ',')) {
        auto it = BUTTONS.find(name);
        if (it == BUTTONS.end()) {
            std::fprintf(stderr, "unknown key %s\n", name.c_str());
            continue;
        }
        keyboard->PressKey(it->second);
        held.push_back(it->second);
    }
}

HeadlessWindow* window = nullptr;

void runFrames(u64 n) {
    auto& system = Core::System::GetInstance();
    const u64 target = window->frames + n;
    while (window->frames < target) {
        const auto result = system.RunLoop();
        if (result != Core::System::ResultStatus::Success) {
            std::fprintf(stderr, "RunLoop: %d %s\n", static_cast<int>(result), system.GetStatusDetails().c_str());
            std::exit(1);
        }
    }
}

// Top (400x240) over bottom (320x240, centred): a 400x480 PNG, as the 3DS holds them.
bool writeShot(const std::string& file) {
    auto* renderer = dynamic_cast<SwRenderer::RendererSoftware*>(&Core::System::GetInstance().GPU().Renderer());
    if (!renderer) return false;
    std::vector<u8> out(400 * 480 * 4, 0);
    for (int i = 0; i < 4; i++) out[i] = 0;
    auto blit = [&](VideoCore::ScreenId id, int ox, int oy) {
        const auto& s = renderer->Screen(id);
        // ScreenInfo is the framebuffer turned upright: width = its height register, rows of info.height pixels.
        const u32 w = s.height, h = s.width;
        for (u32 y = 0; y < h && oy + y < 480; y++)
            for (u32 x = 0; x < w && ox + x < 400; x++) {
                const u8* p = &s.pixels[(y * w + x) * 4];
                u8* d = &out[((oy + y) * 400 + ox + x) * 4];
                d[0] = p[0], d[1] = p[1], d[2] = p[2], d[3] = 255;
            }
    };
    blit(VideoCore::ScreenId::TopLeft, 0, 0);
    blit(VideoCore::ScreenId::Bottom, 40, 240);
    return lodepng::encode(file, out, 400, 480) == 0;
}

std::shared_ptr<Kernel::Process> process() {
    return Core::System::GetInstance().Kernel().GetCurrentProcess();
}

// Every mapped region of the game's process with memory behind it, as `<base>.bin` in dir and a list on stdout.
void dumpRegions(const std::string& dir, bool write) {
    auto proc = process();
    if (!proc) {
        std::puts("no process");
        return;
    }
    if (write) fs::create_directories(dir);
    for (const auto& [base, vma] : proc->vm_manager.vma_map) {
        if (vma.type != Kernel::VMAType::BackingMemory || !vma.backing_memory || vma.size == 0) continue;
        std::printf("%08x %08x perms=%d\n", vma.base, static_cast<u32>(vma.size), static_cast<int>(vma.permissions));
        if (!write) continue;
        char name[32];
        std::snprintf(name, sizeof name, "%08x.bin", vma.base);
        std::ofstream(fs::path(dir) / name, std::ios::binary)
            .write(reinterpret_cast<const char*>(vma.backing_memory.GetPtr()), vma.size);
    }
}

// The save archive's format each game gives it when it makes a new save (FS:FormatSaveData), as Azahar keeps it in
// `00000001.metadata` beside the save's folder. Without that file Azahar answers the game's GetFormatInfo with "not
// formatted", and the game formats again, deleting the save: so a save is only put in for a game listed here.
struct SaveFormat {
    u64 programId;
    u32 totalSize, directories, files;
    u8 duplicate;
};
constexpr SaveFormat SAVE_FORMATS[] = {
    {0x0004000000055D00, 0x40000, 1, 1, 1}, // Pokemon X (USA), seen when it formats a new save
    {0x0004000000055E00, 0x40000, 1, 1, 1}, // Pokemon Y (USA): X's save format
    {0x000400000011C400, 0x40000, 1, 1, 1}, // Pokemon Omega Ruby (USA), seen when it formats a new save
    {0x000400000011C500, 0x40000, 1, 1, 1}, // Pokemon Alpha Sapphire (USA): Omega Ruby's
    {0x0004000000164800, 0x40000, 1, 1, 1}, // Pokemon Sun (USA), seen when it formats a new save
    {0x0004000000175E00, 0x40000, 1, 1, 1}, // Pokemon Moon (USA): Sun's
};

// Each game's static module (CRS) header, where `modules` starts (found with `findcrs`).
struct ModuleRoot {
    u64 programId;
    u32 root;
};
constexpr ModuleRoot MODULE_ROOTS[] = {
    {0x000400000011C400, 0x01307000}, // Omega Ruby
    {0x000400000011C500, 0x01307000}, // Alpha Sapphire
    {0x0004000000164800, 0x00869000}, // Sun
    {0x0004000000175E00, 0x00869000}, // Moon
};

// The static module's (CRS) header, where the loaded modules' chain starts: Pokemon X / Y's by default, `modroot`
// sets another game's (`findcrs` looks for it).
u32 modRoot = 0x012B6000;

u32 number(const std::string& s) {
    return static_cast<u32>(std::stoul(s, nullptr, 0));
}

} // namespace

// The loaded CRO modules, as the app's moduleNames (pk_n3ds_jni.cpp): the static module's header (Pokemon X:
// 0x012B6000), each header's name at +0x84 and the next one at +0x88. Each name after a space.
static std::string moduleNames(Memory::MemorySystem& mem, u32 root) {
    std::string out;
    // Only mapped pages: the static module's page isn't there until the game loads it (Sun's walk crashed reading it).
    auto proc = process();
    auto ok = [&](u32 a) { return proc && mem.IsValidVirtualAddress(*proc, a) && mem.IsValidVirtualAddress(*proc, a + 0x88); };
    if (!ok(root) || mem.Read32(root + 0x80) != 0x304F5243) return out;
    u32 m = mem.Read32(root + 0x88);
    for (int n = 0; n < 32 && m != 0 && ok(m); n++) {
        const u32 name = mem.Read32(m + 0x84);
        if (!ok(name)) break;
        std::string nm;
        for (u32 i = 0; i < 48; i++) {
            const char c = static_cast<char>(mem.Read8(name + i));
            if (c < 0x20 || c > 0x7E) break;
            nm += c;
        }
        out += ' ' + nm;
        m = mem.Read32(m + 0x88);
    }
    return out;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: n3ds_dump CART.3ds USERDIR [--save MAIN] [--new3ds] < script.txt\n");
        return 2;
    }
    const std::string cart = argv[1];
    const std::string user = fs::absolute(argv[2]).string() + "/";
    std::string saveIn;
    bool new3ds = false;
    for (int i = 3; i < argc; i++) {
        if (!std::strcmp(argv[i], "--save") && i + 1 < argc) saveIn = argv[++i];
        else if (!std::strcmp(argv[i], "--new3ds")) new3ds = true;
    }

    // First of all: Azahar's paths are set once (a later SetUserPath leaves them), and the log opens its file there.
    FileUtil::SetUserPath(user);
    Common::Log::Initialize();
    Common::Log::SetColorConsoleBackendEnabled(true);
    Common::Log::Start();
    Common::Log::Filter filter;
    filter.ParseFilterString(std::getenv("N3DS_LOG") ? std::getenv("N3DS_LOG") : "*:Warning");
    Common::Log::SetGlobalFilter(filter);

    Settings::values.graphics_api = Settings::GraphicsAPI::Software;
    Settings::values.use_cpu_jit = std::getenv("N3DS_INTERP") == nullptr; // N3DS_INTERP=1: the interpreter (Sun / Moon crash the JIT on the Mac as the field loads)
    Settings::values.is_new_3ds = new3ds;
    Settings::values.frame_limit = 0;              // flat out
    Settings::values.use_disk_shader_cache = false;
    Settings::values.init_clock = Settings::InitClock::FixedTime;
    Settings::values.init_ticks_type = Settings::InitTicks::Fixed;
    for (const auto& [name, code] : BUTTONS)
        Settings::values.current_input_profile.buttons[code] = InputCommon::GenerateKeyboardParam(code);
    Settings::values.current_input_profile.touch_device = "engine:emu_window"; // the window's touches
    InputCommon::Init();
    // Every system module emulated (HLE), none loaded from a console's NAND (there's none): a front end fills this
    // from its config, and Service::Init looks each one up.
    for (const auto& module : Service::service_module_map) Settings::values.lle_modules[module.name] = false;

    // The save goes where Azahar reads a cart's save data (its extracted files: Pokemon X / Y's is one file, `main`).
    std::unique_ptr<Loader::AppLoader> loader = Loader::GetLoader(cart);
    u64 programId = 0;
    if (!loader || loader->ReadProgramId(programId) != Loader::ResultStatus::Success) {
        std::fprintf(stderr, "can't read %s\n", cart.c_str());
        return 1;
    }
    loader.reset();
    char saveDir[128];
    std::snprintf(saveDir, sizeof saveDir, "Nintendo 3DS/%s/%s/title/%08x/%08x/data/00000001/",
                  "00000000000000000000000000000000", "00000000000000000000000000000000",
                  static_cast<u32>(programId >> 32), static_cast<u32>(programId));
    const fs::path saveFolder = fs::path(FileUtil::GetUserPath(FileUtil::UserPath::SDMCDir)) / saveDir;
    if (!saveIn.empty()) {
        const SaveFormat* format = nullptr;
        for (const auto& f : SAVE_FORMATS)
            if (f.programId == programId) format = &f;
        if (!format) {
            std::fprintf(stderr, "no known save format for %016llx: boot it once without --save, then add the "
                         "00000001.metadata it writes to SAVE_FORMATS\n", static_cast<unsigned long long>(programId));
            return 1;
        }
        fs::create_directories(saveFolder);
        fs::copy_file(saveIn, saveFolder / "main", fs::copy_options::overwrite_existing);
        u8 meta[16] = {};
        std::memcpy(meta + 0, &format->totalSize, 4);
        std::memcpy(meta + 4, &format->directories, 4);
        std::memcpy(meta + 8, &format->files, 4);
        meta[12] = format->duplicate;
        std::ofstream(saveFolder.parent_path().parent_path() / "00000001.metadata", std::ios::binary)
            .write(reinterpret_cast<const char*>(meta), sizeof meta);
    }
    for (const auto& r : MODULE_ROOTS)
        if (r.programId == programId) modRoot = r.root;
    std::printf("program %016llx, save folder %s\n", static_cast<unsigned long long>(programId), saveFolder.c_str());

    HeadlessWindow emuWindow;
    window = &emuWindow;
    auto& system = Core::System::GetInstance();
    const auto status = system.Load(emuWindow, cart);
    if (status != Core::System::ResultStatus::Success) {
        std::fprintf(stderr, "Load: %d %s\n", static_cast<int>(status), system.GetStatusDetails().c_str());
        return 1;
    }
    system.RegisterCoreLoopThreadId();
    std::puts("loaded");

    std::string line;
    while (std::getline(std::cin, line)) {
        if (auto hash = line.find('#'); hash != std::string::npos) line.resize(hash);
        std::stringstream in(line);
        std::string cmd;
        if (!(in >> cmd)) continue;
        std::vector<std::string> a;
        for (std::string w; in >> w;) a.push_back(w);
        auto& mem = system.Memory();
        if (cmd == "wait") {
            runFrames(number(a.at(0)));
        } else if (cmd == "keys") {
            setKeys(a.at(0));
        } else if (cmd == "press" || cmd == "hold") {
            setKeys(a.at(0));
            runFrames(cmd == "hold" ? number(a.at(1)) : 1);
            setKeys("NONE");
            runFrames(1);
        } else if (cmd == "touch") {
            const auto& l = emuWindow.GetFramebufferLayout().bottom_screen;
            const u32 x = l.left + number(a.at(0)) * (l.right - l.left) / 320;
            const u32 y = l.top + number(a.at(1)) * (l.bottom - l.top) / 240;
            emuWindow.TouchPressed(x, y);
            runFrames(a.size() > 2 ? number(a[2]) : 2);
            emuWindow.TouchReleased();
            runFrames(1);
        } else if (cmd == "shot") {
            std::printf("shot %s %s\n", a.at(0).c_str(), writeShot(a[0]) ? "ok" : "FAILED");
        } else if (cmd == "shots") {
            const u32 every = number(a.at(1)), count = number(a.at(2));
            for (u32 i = 0; i < count; i++) {
                char name[512];
                std::snprintf(name, sizeof name, "%s_%04u.png", a[0].c_str(), i);
                writeShot(name);
                runFrames(every);
            }
        } else if (cmd == "regions") {
            dumpRegions("", false);
        } else if (cmd == "dump") {
            dumpRegions(a.at(0), true);
        } else if (cmd == "peek8" || cmd == "peek16" || cmd == "peek32") {
            const u32 addr = number(a.at(0));
            const u32 v = cmd == "peek8" ? mem.Read8(addr) : cmd == "peek16" ? mem.Read16(addr) : mem.Read32(addr);
            std::printf("%s %08x = %x\n", cmd.c_str(), addr, v);
        } else if (cmd == "poke8" || cmd == "poke16" || cmd == "poke32") {
            const u32 addr = number(a.at(0)), v = number(a.at(1));
            if (cmd == "poke8") mem.Write8(addr, v);
            else if (cmd == "poke16") mem.Write16(addr, v);
            else mem.Write32(addr, v);
        } else if (cmd == "savestate") {
            const auto buffer = system.SaveStateBuffer();
            std::ofstream(a.at(0), std::ios::binary).write(reinterpret_cast<const char*>(buffer.data()), buffer.size());
            std::printf("savestate %s %zu bytes\n", a[0].c_str(), buffer.size());
        } else if (cmd == "loadstate") {
            std::ifstream f(a.at(0), std::ios::binary);
            std::vector<u8> buffer((std::istreambuf_iterator<char>(f)), {});
            std::printf("loadstate %s %s\n", a[0].c_str(), system.LoadStateBuffer(std::move(buffer)) ? "ok" : "FAILED");
        } else if (cmd == "bench") {
            const u32 n = number(a.at(0));
            const auto t0 = std::chrono::steady_clock::now();
            runFrames(n);
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            std::printf("bench %u frames %.2f ms a frame\n", n, ms / n);
        } else if (cmd == "modules") {
            std::printf("modules%s\n", moduleNames(mem, a.empty() ? modRoot : number(a[0])).c_str());
        } else if (cmd == "waitmod" || cmd == "waitnomod") {
            // Frames until a module of that name is loaded (waitnomod: until none is), at most N (default 3000).
            const std::string want = ' ' + a.at(0) + ' ';
            const u32 most = a.size() > 1 ? number(a[1]) : 3000;
            u32 n = 0;
            for (; n < most; n++) {
                const bool has = (moduleNames(mem, modRoot) + ' ').find(want) != std::string::npos;
                if (has == (cmd == "waitmod")) break;
                runFrames(1);
            }
            std::printf("%s %s %s after %u frames:%s\n", cmd.c_str(), a[0].c_str(), n < most ? "ok" : "TIMED OUT", n,
                moduleNames(mem, modRoot).c_str());
        } else if (cmd == "pressuntil") {
            // pressuntil KEYS MODULE [N]: KEYS for 2 frames every 60 until MODULE is loaded, at most N frames (3000).
            const std::string want = ' ' + a.at(1) + ' ';
            const u32 most = a.size() > 2 ? number(a[2]) : 3000;
            u32 n = 0;
            for (; n < most && (moduleNames(mem, modRoot) + ' ').find(want) == std::string::npos; n++) {
                setKeys(n % 60 < 2 ? a[0] : "NONE");
                runFrames(1);
            }
            setKeys("NONE");
            std::printf("pressuntil %s %s after %u frames:%s\n", a[1].c_str(), n < most ? "ok" : "TIMED OUT", n,
                moduleNames(mem, modRoot).c_str());
        } else if (cmd == "watchw") {
            // watchw ADDR [FRAMES] [LEN]: runs FRAMES (default 2) with the page watched and prints who wrote the LEN
            // bytes at ADDR (default 4): the CPU's PC and LR at the write (dynarmic's PC can be its block's start) and
            // the values, counted. Needs our Azahar patch's Memory::pk_watch_write.
            const u32 addr = number(a.at(0));
            const u32 frames = a.size() > 1 ? number(a[1]) : 2;
            const u32 len = a.size() > 2 ? number(a[2]) : 4;
            static std::map<std::tuple<u32, u32, u32, u64>, u32> seen;
            static u32 watchLo, watchHi;
            seen.clear();
            watchLo = addr, watchHi = addr + len;
            Memory::pk_watch_write = [](VAddr v, u32 size, u64 value) {
                if (v + size <= watchLo || v >= watchHi) return;
                auto& core = Core::System::GetInstance().GetRunningCore();
                seen[{v, core.GetPC(), core.GetReg(14), value}]++;
            };
            auto proc = process();
            mem.RegisterWatchpoint(*proc, addr & ~0xFFFu, 0x1000);
            runFrames(frames);
            mem.UnregisterWatchpoint(*proc, addr & ~0xFFFu, 0x1000);
            Memory::pk_watch_write = nullptr;
            for (const auto& [k, n] : seen)
                std::printf("watchw %08x pc=%08x lr=%08x value=%llx x%u\n", std::get<0>(k), std::get<1>(k), std::get<2>(k),
                            static_cast<unsigned long long>(std::get<3>(k)), n);
            std::printf("watchw done (%zu kinds)\n", seen.size());
        } else if (cmd == "modroot") {
            modRoot = number(a.at(0));
            std::printf("modroot %08x:%s\n", modRoot, moduleNames(mem, modRoot).c_str());
        } else if (cmd == "findcrs") {
            // Every page whose +0x80 reads "CRO0" and whose chain (+0x88) leads to another such header: the static
            // module's header is the one no other header points at.
            for (const auto& vma : process()->vm_manager.vma_map) {
                const auto& v = vma.second;
                if (v.type != Kernel::VMAType::BackingMemory) continue;
                for (u32 a0 = v.base; a0 + 0x1000 <= v.base + v.size; a0 += 0x1000) {
                    if (mem.Read32(a0 + 0x80) != 0x304F5243) continue;
                    std::printf("findcrs %08x next=%08x:%s\n", a0, mem.Read32(a0 + 0x88), moduleNames(mem, a0).c_str());
                }
            }
        } else if (cmd == "frames") {
            std::printf("frames %llu\n", static_cast<unsigned long long>(emuWindow.frames));
        } else {
            std::fprintf(stderr, "unknown command %s\n", cmd.c_str());
        }
        std::fflush(stdout);
    }
    system.Shutdown();
    return 0;
}
