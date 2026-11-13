cmake_minimum_required(VERSION 3.22.1)
project(pokedaisy C CXX)

# ---------------------------------------------------------------------------
# Vendored mGBA (git submodule, pinned to 0.10.5) — core only.
# No Qt/SDL/GL frontends, no external deps (libzip/sqlite/ffmpeg/png/lua).
# This mirrors how mGBA's own 3DS/Switch/Vita targets build the bare core.
# ---------------------------------------------------------------------------
get_filename_component(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mgba" ABSOLUTE)

if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "mGBA submodule missing at ${MGBA_DIR} — run: git submodule update --init third_party/mgba")
endif()

set(BUILD_STATIC        ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED        OFF CACHE BOOL "" FORCE)
set(DISABLE_FRONTENDS   ON  CACHE BOOL "" FORCE)
set(DISABLE_DEPS        ON  CACHE BOOL "" FORCE)
set(SKIP_LIBRARY        OFF CACHE BOOL "" FORCE)
set(BUILD_GL            OFF CACHE BOOL "" FORCE)
set(BUILD_GLES2         OFF CACHE BOOL "" FORCE)
set(BUILD_GLES3         OFF CACHE BOOL "" FORCE)
set(BUILD_PERF          OFF CACHE BOOL "" FORCE)
set(BUILD_TEST          OFF CACHE BOOL "" FORCE)
set(BUILD_SUITE         OFF CACHE BOOL "" FORCE)
set(BUILD_LIBRETRO      OFF CACHE BOOL "" FORCE)
set(BUILD_PYTHON        OFF CACHE BOOL "" FORCE)
set(USE_DEBUGGERS       OFF CACHE BOOL "" FORCE)
set(USE_GDB_STUB        OFF CACHE BOOL "" FORCE)
set(USE_EDITLINE        OFF CACHE BOOL "" FORCE)
set(USE_FFMPEG          OFF CACHE BOOL "" FORCE)
set(USE_DISCORD_RPC     OFF CACHE BOOL "" FORCE)
set(USE_LZMA            OFF CACHE BOOL "" FORCE)
set(USE_LUA             OFF CACHE BOOL "" FORCE)
set(USE_ELF             OFF CACHE BOOL "" FORCE)
set(USE_SQLITE3         OFF CACHE BOOL "" FORCE)
set(USE_LIBZIP          OFF CACHE BOOL "" FORCE)
set(USE_MINIZIP         OFF CACHE BOOL "" FORCE)
set(USE_PNG             OFF CACHE BOOL "" FORCE)
set(USE_ZLIB            OFF CACHE BOOL "" FORCE)
set(M_CORE_GBA          ON  CACHE BOOL "" FORCE)
set(M_CORE_GB           ON  CACHE BOOL "" FORCE)   # Game Boy / Color (Pokémon Red/Blue/Yellow, G/S/C)
set(BUILD_LTO           OFF CACHE BOOL "" FORCE)
set(SKIP_INSTALL_ALL    ON  CACHE BOOL "" FORCE)

add_subdirectory("${MGBA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# Vendored rcheevos (git submodule, pinned to v12.5.0) — RetroAchievements'
# own client library. GBA ROM hashing only: no disc, encrypted or zip hashing.
# ---------------------------------------------------------------------------
get_filename_component(RCHEEVOS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rcheevos" ABSOLUTE)

if(NOT EXISTS "${RCHEEVOS_DIR}/include/rc_client.h")
    message(FATAL_ERROR "rcheevos submodule missing at ${RCHEEVOS_DIR} — run: git submodule update --init third_party/rcheevos")
endif()

file(GLOB RCHEEVOS_SOURCES
    "${RCHEEVOS_DIR}/src/*.c"
    "${RCHEEVOS_DIR}/src/rapi/*.c"
    "${RCHEEVOS_DIR}/src/rcheevos/*.c"
    "${RCHEEVOS_DIR}/src/rhash/hash.c"
    "${RCHEEVOS_DIR}/src/rhash/hash_rom.c"
    "${RCHEEVOS_DIR}/src/rhash/md5.c")
list(FILTER RCHEEVOS_SOURCES EXCLUDE REGEX "rc_libretro\\.c$|rc_client_external\\.c$|rc_client_raintegration\\.c$")

add_library(rcheevos STATIC ${RCHEEVOS_SOURCES})
target_include_directories(rcheevos PUBLIC "${RCHEEVOS_DIR}/include")
target_compile_definitions(rcheevos PUBLIC
    RC_CLIENT_SUPPORTS_HASH RC_HASH_NO_DISC RC_HASH_NO_ENCRYPTED RC_HASH_NO_ZIP)

# ---------------------------------------------------------------------------
# Vendored melonDS (git submodule, pinned to 1.1) — its core library only, for
# Nintendo DS carts (nds/pk_nds.cpp). The software 3D renderer, and its OpenGL one
# ported to OpenGL ES 3.2 by our patch (DS RESOLUTION, nds/pk_nds_gl.cpp: added
# below, not through ENABLE_OGLRENDERER, which brings glad and the compute
# renderer), the ARM64 JIT, no GDB stub, no Qt frontend, no LTO (it would leak
# into our link).
# ---------------------------------------------------------------------------
get_filename_component(MELONDS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/melonds" ABSOLUTE)

if(NOT EXISTS "${MELONDS_DIR}/src/NDS.h")
    message(FATAL_ERROR "melonDS submodule missing at ${MELONDS_DIR} — run: git submodule update --init third_party/melonds")
endif()

set(BUILD_QT_SDL        OFF CACHE BOOL "" FORCE)
set(ENABLE_OGLRENDERER  OFF CACHE BOOL "" FORCE)
set(ENABLE_GDBSTUB      OFF CACHE BOOL "" FORCE)
set(ENABLE_LTO_RELEASE  OFF CACHE BOOL "" FORCE)
set(ENABLE_LTO          OFF CACHE BOOL "" FORCE)
set(ENABLE_JIT          ON  CACHE BOOL "" FORCE)

include("${MELONDS_DIR}/../patches/apply_melonds_patch.cmake")
add_subdirectory("${MELONDS_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/melonds" EXCLUDE_FROM_ALL)
target_sources(core PRIVATE
    "${MELONDS_DIR}/src/GPU_OpenGL.cpp"
    "${MELONDS_DIR}/src/GPU3D_OpenGL.cpp"
    "${MELONDS_DIR}/src/OpenGLSupport.cpp")
target_compile_definitions(core PUBLIC OGLRENDERER_ENABLED MELONDS_GLES "MELONDS_GL_HEADER=<GLES3/gl32.h>")
target_link_libraries(core PUBLIC GLESv3 EGL)

# DSperate (git submodule): the second DS core, for weak devices (nds/pk_nds_dsp.cpp). Its core library only.
include("${CMAKE_CURRENT_SOURCE_DIR}/nds/dsperate.cmake")

# ---------------------------------------------------------------------------
# Our JNI bridge.
# ---------------------------------------------------------------------------
add_library(pokedaisy SHARED pokedaisy_jni.c pokedaisy_ra.c pk_call.c pk_host.c pk_cheats.c pk_coop.c pk_coop_field.c pk_coop_after.c pk_coop_lock.c pk_coop_link.c pk_coop_link_start.c pk_coop_link_wild.c pk_coop_link_story.c pk_coop_link_drop.c pk_coop_link_rewards.c pk_coop_link_end.c pk_coop_lock_jni.c pk_link.c pk_link_jni.c pk_rewind.c pk_savevf.c pk_wide.c pk_wide_gb.c pk_flight.c pk_gb_field.c pk_gb_audio.c
    nds/pk_nds_core.cpp nds/pk_nds_dsp.cpp nds/pk_nds.cpp nds/pk_nds_platform.cpp nds/pk_nds_field.cpp nds/pk_nds_field_gpu.cpp nds/pk_nds_call.cpp nds/pk_nds_call_melonds.cpp nds/pk_nds_wide.cpp
    nds/pk_nds_music.cpp nds/pk_nds_gl.cpp)
set_target_properties(pokedaisy PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
# melonDS's headers test these (its own sources get them from its directory's add_definitions).
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    target_compile_definitions(pokedaisy PRIVATE ARCHITECTURE_ARM64=1)
endif()

# mGBA's own sources get these (add_definitions in its directory only). Some of its
# structs change shape with them - mCoreRewindContext is ~100 bytes longer with
# USE_PTHREADS - so our files must see the same headers, or mGBA writes past ours.
target_compile_definitions(pokedaisy PRIVATE USE_PTHREADS M_CORE_GBA M_CORE_GB)

target_include_directories(pokedaisy PRIVATE
    "${MGBA_DIR}/include"
    "${MGBA_DIR}/src"
    "${CMAKE_CURRENT_BINARY_DIR}/mgba/include")

target_link_libraries(pokedaisy
    mgba
    rcheevos
    core
    dsperate_core
    z
    android
    log)
