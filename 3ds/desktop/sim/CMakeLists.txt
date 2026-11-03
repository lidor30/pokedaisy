# pd_sim: the 3DS app's host logic (../../host) on the real mGBA core, with no screens: a script drives it and takes
# PNGs. Everything but the 3DS's own display, sound and HID runs here.
cmake_minimum_required(VERSION 3.13)
project(pd_sim C)
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

file(GLOB CORE_SOURCES "${REPO}/3ds/core/*.c")
file(GLOB HOST_SOURCES "${REPO}/3ds/host/*.c")
add_executable(pd_sim pd_sim.c ${HOST_SOURCES} ${CORE_SOURCES} "${REPO}/3ds/desktop/pd_png.c"
    "${REPO}/app/src/main/cpp/pk_host.c" "${REPO}/app/src/main/cpp/pk_call.c" "${REPO}/app/src/main/cpp/pk_cheats.c" "${REPO}/app/src/main/cpp/pk_gb_field.c"
    "${REPO}/app/src/main/cpp/pk_wide.c" "${REPO}/app/src/main/cpp/pk_wide_gb.c"
    "${REPO}/linux/fly.c" "${REPO}/linux/field_items.c" "${REPO}/linux/wild.c" "${REPO}/linux/hud.c"
    "${REPO}/linux/quick_menu.c")
target_include_directories(pd_sim PRIVATE "${REPO}/third_party/mgba/include" "${REPO}/third_party/mgba/src"
    "${CMAKE_CURRENT_BINARY_DIR}/mgba/include" "${REPO}/3ds/core" "${REPO}/linux")
target_compile_definitions(pd_sim PRIVATE M_CORE_GBA M_CORE_GB BUILD_STATIC)
target_compile_options(pd_sim PRIVATE -std=gnu11 -O2 -g -Wall -Wextra)
target_link_libraries(pd_sim mgba m)
