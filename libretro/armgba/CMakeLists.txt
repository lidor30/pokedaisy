cmake_minimum_required(VERSION 3.18)
project(pokedaisy_gba_libretro C CXX)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# The PokeDaisy core over armGBA (the GBA's recompiler) instead of mGBA: libretro/pd_libretro.c with -DPD_ARMGBA. armGBA's
# own libretro glue is included by pd_backend.h with its entry points renamed (never edited); its Game Boy side
# (Gambatte, GPL-2.0 only) is left out and answered by pd_agba_gb_stub.c. Sources and flags come from armGBA's own targets,
# as the mGBA build takes mGBA's.
set(ARMGBA ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/armgba)
if(NOT EXISTS ${ARMGBA}/CMakeLists.txt)
  message(FATAL_ERROR "Initialize armGBA: git submodule update --init third_party/armgba")
endif()
# The old-glibc shims (glibc_compat.h / .c) only where the C library is glibc: a musl toolchain has no __xstat@GLIBC_2.17.
include(CheckCSourceCompiles)
check_c_source_compiles("#include <stdlib.h>\n#ifndef __GLIBC__\n#error not glibc\n#endif\nint main(void) { return 0; }" PD_GLIBC)
# Every C file (armGBA's too): the old glibc versions of exp / pow (glibc_compat.h).
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64" AND CMAKE_SYSTEM_NAME STREQUAL "Linux" AND PD_GLIBC)
  set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -include ${CMAKE_CURRENT_SOURCE_DIR}/glibc_compat.h")
endif()

# armGBA's glue needs zlib (crc32, inflate, compress2). The cross toolchain image has none, so a cross build vendors it.
find_package(ZLIB QUIET)
if(CMAKE_CROSSCOMPILING OR NOT ZLIB_FOUND)
  set(ZSRC ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/zlib)
  if(NOT EXISTS ${ZSRC}/CMakeLists.txt)
    message(FATAL_ERROR "Initialize zlib: git submodule update --init third_party/zlib")
  endif()
  set(ZLIB_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
  add_subdirectory(${ZSRC} zlib EXCLUDE_FROM_ALL)
  include_directories(${ZSRC} ${CMAKE_CURRENT_BINARY_DIR}/zlib)
  set(PD_ZLIB zlibstatic)
else()
  set(PD_ZLIB ZLIB::ZLIB)
endif()
add_subdirectory(${ARMGBA} armgba EXCLUDE_FROM_ALL)
# EXPAND records what each line is drawn with, from a hook at the call that draws it: video.c's calls become ours
# (pd_agba_view.c), which tell pk_wide.c and then draw. A compile-time rename, so nothing of armGBA's is edited and its
# whole-program optimisation can't route round it.
set_source_files_properties(${ARMGBA}/src/video/video.c TARGET_DIRECTORY armgba PROPERTIES
  COMPILE_DEFINITIONS "renderScanline=pdRenderScanline;renderThreadScanline=pdRenderThreadScanline")

get_target_property(AG_GLUE armgba_libretro SOURCES)
list(FILTER AG_GLUE EXCLUDE REGEX "(^|/)libretro\\.c$")        # pd_libretro.c includes it, renamed
list(FILTER AG_GLUE EXCLUDE REGEX "gb-gambatte\\.cpp$")        # Gambatte: pd_agba_gb_stub.c answers for it
list(TRANSFORM AG_GLUE PREPEND ${ARMGBA}/)

set(PD ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(APP ${PD}/../app/src/main/cpp)
file(GLOB PD_CORE CONFIGURE_DEPENDS ${PD}/../3ds/core/*.c)
add_library(pokedaisy_gba_libretro SHARED ${AG_GLUE}
  ${PD}/pd_libretro.c ${PD}/pd_flight_area.c ${PD}/pd_lr_mem_agba.c ${PD}/pd_agba_view.c ${PD}/pd_agba_call.c ${APP}/pk_wide.c ${APP}/pk_flight.c ${PD}/../linux/fly.c ${PD}/../linux/flight.c ${PD}/../linux/flash.c ${PD}/../linux/field_items.c ${PD}/pd_agba_gb_stub.c ${PD}/pd_agba_stubs.c ${PD_CORE}
  ${PD}/../linux/hud.c ${PD}/../linux/quick_menu.c ${PD}/../linux/wild.c)
set_source_files_properties(${PD}/pd_libretro.c PROPERTIES COMPILE_OPTIONS "-Wno-unused-function")
# pk_wide.c is the app's own file, written against mGBA's headers: shim/ gives it the view's types under mGBA's names.
set_source_files_properties(${APP}/pk_wide.c ${APP}/pk_flight.c ${PD}/../linux/fly.c ${PD}/../linux/flight.c ${PD}/../linux/flash.c ${PD}/../linux/field_items.c PROPERTIES
  COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/shim/pd_names.h;-Wno-unused-parameter")
# pokedaisy_gba_libretro.so: Game Boy Advance on armGBA. Game Boy and Game Boy Color stay on mGBA (pokedaisy_gbc_libretro.so).
set_target_properties(pokedaisy_gba_libretro PROPERTIES PREFIX ""
  COMPILE_DEFINITIONS "PD_ARMGBA=1;_GNU_SOURCE")
target_include_directories(pokedaisy_gba_libretro PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/shim ${ARMGBA}/libretro ${ARMGBA}/src ${ARMGBA}/libretro/deps/libretro-common/include
  ${PD} ${PD}/../3ds/core ${PD}/../linux ${APP})
find_package(Threads REQUIRED)
target_link_libraries(pokedaisy_gba_libretro PRIVATE armgba retro-archive Threads::Threads ${PD_ZLIB} m)
# Only retro_* leaves the object, as in the mGBA core (a second core may be resident beside this one).
target_link_options(pokedaisy_gba_libretro PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,--version-script=${PD}/retro.map)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64" AND CMAKE_SYSTEM_NAME STREQUAL "Linux" AND PD_GLIBC)
  target_sources(pokedaisy_gba_libretro PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/glibc_compat.c)
  target_link_options(pokedaisy_gba_libretro PRIVATE -Wl,--wrap=stat -Wl,--wrap=fstat -Wl,--wrap=pthread_create -Wl,--wrap=pthread_join)
endif()
