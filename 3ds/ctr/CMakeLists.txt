# PokeDaisy for the 3DS: libmgba (the submodule, built by its own CMake for the
# 3DS) + the portable companion core + main.c, packed into a .3dsx.
# Configure with mGBA's 3DS toolchain file (it sets up devkitARM and the `3DS`
# variable mGBA keys its platform code on) - `make 3ds` in ../ does it in Docker.
cmake_minimum_required(VERSION 3.13)
project(pokedaisy C)

get_filename_component(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/mgba" ABSOLUTE)
if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "mGBA submodule missing at ${MGBA_DIR} - run: git submodule update --init third_party/mgba")
endif()

# libmgba only - the same switches as the Android build (app/src/main/cpp/CMakeLists.txt).
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
set(M_CORE_GB           ON  CACHE BOOL "" FORCE)
set(BUILD_LTO           OFF CACHE BOOL "" FORCE)
set(SKIP_INSTALL_ALL    ON  CACHE BOOL "" FORCE)

add_subdirectory("${MGBA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

# mGBA's 3DS frontend draws 16-bit colour; the app's EXPAND reads the renderer's 32-bit pixels (each carries its layer
# in the top byte), so this build keeps them, like the Android and Linux ones.
get_target_property(MGBA_DEFS mgba COMPILE_DEFINITIONS)
list(REMOVE_ITEM MGBA_DEFS COLOR_16_BIT COLOR_5_6_5)
set_target_properties(mgba PROPERTIES COMPILE_DEFINITIONS "${MGBA_DEFS}")

file(GLOB CORE_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/../core/*.c")

file(GLOB HOST_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/../host/*.c")
add_executable(pokedaisy.elf main.c gpu.c devlink.c ${CORE_SOURCES} ${HOST_SOURCES}
    ../../app/src/main/cpp/pk_host.c ../../app/src/main/cpp/pk_call.c ../../app/src/main/cpp/pk_wide.c
    ../../app/src/main/cpp/pk_gb_audio.c ../../app/src/main/cpp/pk_cheats.c ../../app/src/main/cpp/pk_gb_field.c
    ../../app/src/main/cpp/pk_wide_gb.c ../../linux/fly.c ../../linux/field_items.c ../../linux/wild.c ../../linux/hud.c
    ../../linux/quick_menu.c)
target_include_directories(pokedaisy.elf PRIVATE
    "${MGBA_DIR}/include"
    "${MGBA_DIR}/src"
    "${CMAKE_CURRENT_BINARY_DIR}/mgba/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../core"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../linux")
# mGBA's headers size its structs by these, so ours must match the library's (see the colour note above).
target_compile_definitions(pokedaisy.elf PRIVATE M_CORE_GBA M_CORE_GB BUILD_STATIC PK_WIDE_NO_GUARD FIXED_ROM_BUFFER)
target_compile_options(pokedaisy.elf PRIVATE -std=gnu11 -O2 -Wall -Wextra)
# The dev build (make 3ds-dev): the Wi-Fi link and its own SD folder; devlink.c is empty without it.
option(PD_DEV "the dev build" OFF)
if(PD_DEV)
    target_compile_definitions(pokedaisy.elf PRIVATE PD_DEV)
endif()
# citro2d draws the screens (gpu.c); citro3d and libctru come with libmgba too.
target_link_libraries(pokedaisy.elf mgba citro2d citro3d ctru m)

if(PD_DEV)
    set(PD_TITLE "PokeDaisy dev")
else()
    set(PD_TITLE "PokeDaisy")
endif()
find_program(SMDHTOOL smdhtool HINTS "$ENV{DEVKITPRO}/tools/bin" /opt/devkitpro/tools/bin)
find_program(3DSXTOOL_BIN 3dsxtool HINTS "$ENV{DEVKITPRO}/tools/bin" /opt/devkitpro/tools/bin)

add_custom_command(
    OUTPUT pokedaisy.smdh
    COMMAND ${SMDHTOOL} --create "${PD_TITLE}" "Gen 3 Pokemon companion" "PokeDaisy"
            "${CMAKE_CURRENT_SOURCE_DIR}/icon.png" pokedaisy.smdh
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/icon.png")

add_custom_command(
    OUTPUT pokedaisy.3dsx
    COMMAND ${3DSXTOOL_BIN} pokedaisy.elf pokedaisy.3dsx --smdh=pokedaisy.smdh
    DEPENDS pokedaisy.elf pokedaisy.smdh)

add_custom_target(pokedaisy_3dsx ALL DEPENDS pokedaisy.3dsx)
