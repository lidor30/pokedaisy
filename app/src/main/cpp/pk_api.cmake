# PokeDaisy's emulator core as a plain C library (pk_api.h): mGBA and the portable pk_*.c it runs a GBA or Game Boy
# game with, no JNI. Android's CMakeLists.txt links it under the JNI bridge; another host (the iPad app, a test)
# includes this file and links pk_api. GPL-3.0 (see NOTICE).
#
# Needs CMake 3.22.1+ (project() first). Leaves the targets `mgba` and `pk_api`.

set(PK_API_DIR "${CMAKE_CURRENT_LIST_DIR}")
# ---------------------------------------------------------------------------
# Vendored mGBA (git submodule, pinned to 0.10.5) — core only.
# No Qt/SDL/GL frontends, no external deps (libzip/sqlite/ffmpeg/png/lua).
# This mirrors how mGBA's own 3DS/Switch/Vita targets build the bare core.
# ---------------------------------------------------------------------------
get_filename_component(MGBA_DIR "${PK_API_DIR}/../../../../third_party/mgba" ABSOLUTE)

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
# The pk_*.c that pk_api.h runs a game with (pk_session.c and what it calls).
# ---------------------------------------------------------------------------
set(PK_API_SOURCES
    pk_session.c pk_call.c pk_host.c pk_cheats.c pk_rewind.c pk_savevf.c pk_wide.c pk_wide_gb.c pk_flight.c pk_gb_audio.c
    pk_coop.c pk_coop_field.c pk_coop_after.c pk_coop_lock.c pk_coop_link.c pk_coop_link_start.c pk_coop_link_wild.c
    pk_coop_link_story.c pk_coop_link_drop.c pk_coop_link_rewards.c pk_coop_link_end.c)
list(TRANSFORM PK_API_SOURCES PREPEND "${PK_API_DIR}/")

add_library(pk_api STATIC ${PK_API_SOURCES})
set_target_properties(pk_api PROPERTIES POSITION_INDEPENDENT_CODE ON)
# mGBA's own sources get these (add_definitions in its directory only). Some of its
# structs change shape with them - mCoreRewindContext is ~100 bytes longer with
# USE_PTHREADS - so every file that includes its headers must see the same ones, or mGBA writes past ours.
target_compile_definitions(pk_api PUBLIC USE_PTHREADS M_CORE_GBA M_CORE_GB)
target_include_directories(pk_api PUBLIC
    "${PK_API_DIR}"
    "${MGBA_DIR}/include"
    "${MGBA_DIR}/src"
    "${CMAKE_CURRENT_BINARY_DIR}/mgba/include")
target_link_libraries(pk_api PUBLIC mgba)
if(ANDROID)
    target_link_libraries(pk_api PUBLIC log)
endif()
