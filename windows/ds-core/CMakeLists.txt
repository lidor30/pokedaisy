cmake_minimum_required(VERSION 3.16)
project(pokedaisy_ds_libretro LANGUAGES C CXX)

# The Windows DS core: the app's own melonDS engine (pk_nds.cpp) behind libretro (pd_ds_core.cpp). Built with the same
# melonDS and the same patch as the Android app and native-capture/nds_dump.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

get_filename_component(ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
get_filename_component(MELONDS_DIR "${ROOT}/third_party/melonds" ABSOLUTE)
set(APP ${ROOT}/app/src/main/cpp/nds)

# melonDS's core library alone: no Qt frontend, no OpenGL, no GDB stub.
set(BUILD_QT_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_OGLRENDERER OFF CACHE BOOL "" FORCE)
set(ENABLE_GDBSTUB OFF CACHE BOOL "" FORCE)
include("${ROOT}/third_party/patches/apply_melonds_patch.cmake")
add_subdirectory("${MELONDS_DIR}" melonds EXCLUDE_FROM_ALL)

find_package(ZLIB REQUIRED)

# The companion and the quick menu: the same presentation the Linux DS core (linux/libretro-ds/) and the 3DS port draw -
# the app's portable UI (3ds/core), the DS views, the companion's reader - with the tables generated from the app's
# Kotlin (linux/dsperate/gen_data.py, gen_guide.py).
set(KOTLIN ${ROOT}/app/src/main/kotlin/com/pokedaisy/app)
find_package(Python3 COMPONENTS Interpreter REQUIRED)
set(DS_DATA ${CMAKE_CURRENT_BINARY_DIR}/data_gen.h)
set(DATA_READER ${ROOT}/linux/dsperate/gen_data.py)
set(DATA_INPUTS ${KOTLIN}/GameTitles.kt)
foreach(name Gen4Reader Gen4TablesPlatinum Gen4TablesHeartGold Gen4Dex Gen4MachinesPlatinum Gen4TownMapPlatinum
             Gen4TownMapHeartGold PokegearMap Gen5TownMapBlack Gen5TownMapBlack2 Gen5Reader Gen5DexBlack Gen5TablesBlack
             Gen5ItemsBlack Gen5TablesBlack2 NdsArt RegionMapModel MonStats Gen4Charmap)
  list(APPEND DATA_INPUTS ${KOTLIN}/companion/data/${name}.kt)
endforeach()
add_custom_command(OUTPUT ${DS_DATA}
  COMMAND ${CMAKE_COMMAND} -E env PYTHONUTF8=1 ${Python3_EXECUTABLE} ${DATA_READER} --output ${DS_DATA}
  DEPENDS ${DATA_READER} ${DATA_INPUTS} VERBATIM)
set(GUIDE_DATA ${CMAKE_CURRENT_BINARY_DIR}/guide_gen.h)
set(GUIDE_READER ${ROOT}/linux/dsperate/gen_guide.py)
set(GUIDE_INPUTS ${KOTLIN}/companion/data/GuideGen4.kt ${KOTLIN}/companion/data/GuideGen5.kt)
foreach(name GuideGen4PlatinumGen GuideGen4HeartGoldGen GuideGen5BlackGen GuideGen5WhiteGen GuideGen5Black2Gen
             GuideGen5White2Gen GuidePlatinum GuideHeartGold GuideBlack GuideBlack2 Gen4TownMapPlatinum Gen4TablesPlatinum
             Gen4TownMapHeartGold Gen5TownMapWhite Gen5TownMapWhite2 Gen5TablesBlack Gen5TablesBlack2 Gen4Reader Gen5Reader)
  list(APPEND GUIDE_INPUTS ${KOTLIN}/companion/data/${name}.kt)
endforeach()
add_custom_command(OUTPUT ${GUIDE_DATA}
  COMMAND ${CMAKE_COMMAND} -E env PYTHONUTF8=1 ${Python3_EXECUTABLE} ${GUIDE_READER} ${KOTLIN}/companion/data ${GUIDE_DATA}
  DEPENDS ${GUIDE_READER} ${GUIDE_INPUTS} VERBATIM)
add_custom_target(ds-app-data DEPENDS ${DS_DATA} ${GUIDE_DATA})

file(GLOB PD_APP_CORE CONFIGURE_DEPENDS ${ROOT}/3ds/core/*.c)
add_library(pd_ds_presentation STATIC
    ${ROOT}/linux/libretro-ds/companion_ui.cpp
    ${ROOT}/linux/libretro-ds/layout.cpp
    ${PD_APP_CORE}
    ${ROOT}/linux/hud.c
    ${ROOT}/linux/quick_menu.c
    ${ROOT}/linux/dsperate/companion.cpp
    ${ROOT}/linux/dsperate/party_view.cpp
    ${ROOT}/linux/dsperate/bag_view.cpp
    ${ROOT}/linux/dsperate/dex_view.cpp
    ${ROOT}/linux/dsperate/guide_view.cpp
    ${ROOT}/linux/dsperate/map_view.cpp
    ${ROOT}/linux/dsperate/map_art.cpp
    ${APP}/pk_nds_field.cpp)
add_dependencies(pd_ds_presentation ds-app-data)
target_include_directories(pd_ds_presentation PUBLIC
    ${CMAKE_CURRENT_BINARY_DIR} ${ROOT}/3ds/core ${ROOT}/linux/dsperate ${APP}
    ${ROOT}/third_party/dsperate-lr/include                     # libretro.h
    ${ROOT}/third_party/armgba/libretro/deps/libretro-common/include)
set_target_properties(pd_ds_presentation PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(pokedaisy_ds_libretro MODULE
    pd_ds_core.cpp
    pd_ds_overlay.cpp
    pk_nds_gl_stub.cpp
    ${APP}/pk_nds.cpp
    ${APP}/pk_nds_platform.cpp
    ${APP}/pk_nds_field_gpu.cpp
    ${APP}/pk_nds_call.cpp
    ${APP}/pk_nds_call_melonds.cpp
    ${APP}/pk_nds_wide.cpp
    ${APP}/pk_nds_music.cpp)

target_include_directories(pokedaisy_ds_libretro PRIVATE
    ${APP}
    ${CMAKE_CURRENT_BINARY_DIR}                              # data_gen.h / guide_gen.h
    ${ROOT}/3ds/core
    ${ROOT}/linux/libretro-ds
    ${ROOT}/linux/dsperate
    ${ROOT}/linux                                             # hud.h / quick_menu.h
    ${MELONDS_DIR}/src                                      # sha1/sha1.h (the ROM's digest names the companion)
    ${ROOT}/windows/include                                   # android/log.h
    ${ROOT}/third_party/dsperate-lr/include                   # libretro.h
    ${ROOT}/third_party/armgba/libretro/deps/libretro-common/include)
target_link_libraries(pokedaisy_ds_libretro PRIVATE core pd_ds_presentation ZLIB::ZLIB)
set_target_properties(pokedaisy_ds_libretro PROPERTIES PREFIX "" OUTPUT_NAME "pokedaisy_ds_libretro")

if(MINGW)
    # The app's engine sources are POSIX-flavoured (localtime_r, ...): windows/include's shims, as the DSperate core gets.
    target_compile_options(pokedaisy_ds_libretro PRIVATE -include ${ROOT}/windows/include/pd_win_compat_cxx.h)
    # The shared companion's own sources too: its settings file uses fsync / mkdir / access.
    target_include_directories(pd_ds_presentation PRIVATE ${ROOT}/windows/include)
    target_compile_options(pd_ds_presentation PRIVATE -include ${ROOT}/windows/include/pd_win_compat_cxx.h)
    # A core the host loads with SDL_LoadObject: no libgcc/libstdc++ DLLs to carry beside it.
    target_link_options(pokedaisy_ds_libretro PRIVATE -static-libgcc -static-libstdc++)
endif()
