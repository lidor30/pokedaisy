# pd_sim: the 3DS app's host logic (../../host) on the real mGBA core, with no screens: a script drives it and takes
# PNGs. Everything but the 3DS's own display, sound and HID runs here.
cmake_minimum_required(VERSION 3.13)
project(pd_sim C CXX)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
get_filename_component(REPO "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)

foreach(off BUILD_SHARED BUILD_GL BUILD_GLES2 BUILD_GLES3 BUILD_PERF BUILD_TEST BUILD_SUITE BUILD_LIBRETRO BUILD_PYTHON
            USE_DEBUGGERS USE_GDB_STUB USE_EDITLINE USE_FFMPEG USE_DISCORD_RPC USE_LZMA USE_LUA USE_ELF USE_SQLITE3
            USE_LIBZIP USE_MINIZIP USE_PNG USE_ZLIB BUILD_LTO)
    set(${off} OFF CACHE BOOL "" FORCE)
endforeach()
foreach(on BUILD_STATIC DISABLE_FRONTENDS DISABLE_DEPS M_CORE_GBA M_CORE_GB SKIP_INSTALL_ALL)
    set(${on} ON CACHE BOOL "" FORCE)
endforeach()
set(SKIP_LIBRARY OFF CACHE BOOL "" FORCE)
add_subdirectory("${REPO}/third_party/mgba" mgba EXCLUDE_FROM_ALL)
# armGBA's interpreter here (a desktop's recompiler would need its own executable memory: a Mac's wants MAP_JIT), with
# its renderer's SIMD half for the CPU it's built for.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    set(ARMGBA_RENDER_SIMD render-neon.c)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    set(ARMGBA_RENDER_SIMD render-x86.c)
endif()
include("${REPO}/3ds/emu/armgba.cmake")
find_package(ZLIB REQUIRED)
find_package(Threads REQUIRED)

file(GLOB CORE_SOURCES "${REPO}/3ds/core/*.c")
file(GLOB HOST_SOURCES "${REPO}/3ds/host/*.c")
add_executable(pd_sim pd_sim.c ${HOST_SOURCES} ${CORE_SOURCES} "${REPO}/3ds/desktop/pd_png.c"
    "${REPO}/app/src/main/cpp/pk_host.c" "${REPO}/app/src/main/cpp/pk_call.c" "${REPO}/app/src/main/cpp/pk_cheats.c" "${REPO}/app/src/main/cpp/pk_gb_field.c"
    "${REPO}/app/src/main/cpp/pk_wide.c" "${REPO}/app/src/main/cpp/pk_wide_gb.c"
    "${REPO}/linux/fly.c" "${REPO}/linux/flash.c" "${REPO}/linux/flight.c" "${REPO}/app/src/main/cpp/pk_flight.c" "${REPO}/linux/tm_teach.c" "${REPO}/linux/field_items.c" "${REPO}/linux/gb_field.c" "${REPO}/linux/wild.c" "${REPO}/linux/hud.c"
    "${REPO}/linux/quick_menu.c" ${PD_ARMGBA_GLUE})
target_include_directories(pd_sim PRIVATE "${REPO}/third_party/mgba/include" "${REPO}/third_party/mgba/src"
    "${CMAKE_CURRENT_BINARY_DIR}/mgba/include" "${REPO}/3ds/core" "${REPO}/linux" "${REPO}/app/src/main/cpp")
target_compile_definitions(pd_sim PRIVATE M_CORE_GBA M_CORE_GB BUILD_STATIC PK_WIDE_CACHE)
# Every native mix compared with the game's own (pd_mixer.c); off, the native one alone, as the console runs it.
option(PD_SIM_MIXER_CHECK "compare the native mixer with the game's on every mix" ON)
if(PD_SIM_MIXER_CHECK)
    target_compile_definitions(pd_sim PRIVATE PD_MIXER_CHECK)
endif()
target_compile_options(pd_sim PRIVATE -std=gnu11 -O2 -g -Wall -Wextra)
target_link_libraries(pd_sim mgba pd_armgba ZLIB::ZLIB Threads::Threads m)
pd_mgba_renames(pd_sim)
