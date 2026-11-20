cmake_minimum_required(VERSION 3.18)
project(pokedaisy_windows C)
enable_language(RC) # pokedaisy.rc: the exe's icon
# PokeDaisy on Windows: the same SDL2 libretro frontend as linux/ (its host is shared, unlocked: input, sound, pacing,
# the battery save and its backups, states, fast-forward, rewind, the host menu), loading the cores' .dlls from beside
# the executable: pokedaisy_gbc_libretro.dll (GB / GBC, mGBA), pokedaisy_gba_libretro.dll (GBA, armGBA) and
# pokedaisy_ds_libretro.dll (DS, the app's melonDS engine). Build the cores first into the same folder.
#
# With MinGW-w64 from MSYS2 (C:/msys64/mingw64/bin on PATH), from the repo root:
#   cmake -G "MinGW Makefiles" -S libretro         -B build/win-gbc
#   cmake --build build/win-gbc --target pokedaisy_gbc_libretro -j 8
#   cmake -G "MinGW Makefiles" -S libretro/armgba  -B build/win-gba -DARMGBA_JIT=OFF
#   cmake --build build/win-gba --target pokedaisy_gba_libretro -j 8
#   cmake -G "MinGW Makefiles" -S windows/ds-core  -B build/win-ds
#   cmake --build build/win-ds -j 8
#   cmake -G "MinGW Makefiles" -S windows          -B build/win-host
#   cmake --build build/win-host -j 8
# ARMGBA_JIT=OFF is required: armGBA's x64 recompiler emits code for the SysV calling convention, so on Win64 it
# crashes as soon as a game runs and the core uses its exact-CPU interpreter instead.
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_path(SDL2_INCLUDE_DIR SDL.h PATH_SUFFIXES SDL2 REQUIRED)
find_library(SDL2_LIBRARY NAMES SDL2 REQUIRED)

file(GLOB PD_CORE CONFIGURE_DEPENDS ../3ds/core/*.c)
# The host's new game library: the app's own list with covers (linux/library.c), and zlib's puff for its PNGs.
add_library(pd_puff STATIC ../third_party/zlib/contrib/puff/puff.c)
target_include_directories(pd_puff PUBLIC ../third_party/zlib/contrib/puff)
# SETTINGS > VERSION: the app's versionName.
file(STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/../app/build.gradle.kts POCKET_VERSION_LINE REGEX "versionName = ")
string(REGEX MATCH "\"[^\"]+\"" POCKET_VERSION "${POCKET_VERSION_LINE}")
add_executable(PokeDaisy ../linux/main.c ../linux/library.c ../linux/cover_png.c ../3ds/desktop/pd_png.c ${PD_CORE} pokedaisy.rc)
target_include_directories(PokeDaisy PRIVATE
  ../3ds/core
  ../third_party/mgba/src/platform/libretro # libretro.h
  ${SDL2_INCLUDE_DIR})
# The host keeps its own main(): SDL's WinMain shim isn't needed, and none of SDL_main applies to it.
target_compile_definitions(PokeDaisy PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX SDL_MAIN_HANDLED)
if(POCKET_VERSION)
  target_compile_definitions(PokeDaisy PRIVATE POCKET_VERSION=${POCKET_VERSION})
endif()
target_compile_options(PokeDaisy PRIVATE -Wall -Wextra)
if(MINGW)
  # The host's library uses the same POSIX odds and ends as the cores (mkdir's mode, localtime_r, ...).
  target_include_directories(PokeDaisy PRIVATE ../windows/include)
  target_compile_options(PokeDaisy PRIVATE -include ${CMAKE_CURRENT_SOURCE_DIR}/../windows/include/pd_win_compat.h)
endif()
target_link_libraries(PokeDaisy PRIVATE pd_puff ${SDL2_LIBRARY})
set_target_properties(PokeDaisy PROPERTIES WIN32_EXECUTABLE OFF)

# SDL2.dll has to sit beside the game's exe.
get_filename_component(SDL2_LIB_DIR ${SDL2_LIBRARY} DIRECTORY)
find_file(SDL2_DLL NAMES SDL2.dll PATHS ${SDL2_LIB_DIR}/../bin ${SDL2_LIB_DIR} NO_DEFAULT_PATH)
if(SDL2_DLL)
  add_custom_command(TARGET PokeDaisy POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different ${SDL2_DLL}
                     $<TARGET_FILE_DIR:PokeDaisy>/SDL2.dll)
endif()
