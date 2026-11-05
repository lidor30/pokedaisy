cmake_minimum_required(VERSION 3.18)
project(pokedaisy_libretro C)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5) # mGBA 0.10 declares an old minimum
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
# Everything below, the pinned mGBA included, is built the way mGBA's own libretro target builds it.
foreach(feature BUILD_QT BUILD_SDL BUILD_GL BUILD_GLES2 BUILD_GLES3 BUILD_SHARED USE_DISCORD_RPC USE_LUA USE_FFMPEG USE_LIBZIP USE_PNG USE_SQLITE3 USE_ELF USE_LZMA USE_DEBUGGERS)
  set(${feature} OFF CACHE BOOL "" FORCE)
endforeach()
set(BUILD_STATIC OFF CACHE BOOL "" FORCE)
set(SKIP_LIBRARY ON CACHE BOOL "" FORCE)
set(BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
set(MGBA ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/mgba)
if(NOT EXISTS ${MGBA}/CMakeLists.txt)
  message(FATAL_ERROR "Initialize the pinned core: git submodule update --init third_party/mgba")
endif()
add_subdirectory(${MGBA} mgba EXCLUDE_FROM_ALL)
# mGBA's own libretro target names the sources, definitions and includes of the minimal
# core; this one compiles the same set, with the companion layer added, into one object.
get_target_property(MGBA_SRC mgba_libretro SOURCES)
get_target_property(MGBA_DEFS mgba_libretro COMPILE_DEFINITIONS)
get_directory_property(MGBA_DIR_DEFS DIRECTORY ${MGBA} COMPILE_DEFINITIONS)
list(REMOVE_ITEM MGBA_DIR_DEFS USE_PTHREADS) # threading is off; pk_rewind.c is not built
get_target_property(MGBA_INC mgba_libretro INCLUDE_DIRECTORIES)
list(FILTER MGBA_SRC EXCLUDE REGEX "platform/libretro/libretro\\.c$") # pd_libretro.c includes it, renamed
set(APP ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
file(GLOB PD_CORE CONFIGURE_DEPENDS ../3ds/core/*.c)
add_library(pokedaisy_libretro SHARED ${MGBA_SRC} pd_libretro.c pd_lr_mem.c ${PD_CORE}
  ../linux/hud.c ../linux/quick_menu.c ../linux/fly.c ../linux/field_items.c ../linux/wild.c
  ${APP}/pk_call.c ${APP}/pk_wide.c ${APP}/pk_wide_gb.c ${APP}/pk_gb_field.c)
set_source_files_properties(${APP}/pk_wide.c ${APP}/pk_wide_gb.c PROPERTIES COMPILE_OPTIONS -Wno-unused-parameter)
set_target_properties(pokedaisy_libretro PROPERTIES PREFIX "" COMPILE_DEFINITIONS "${MGBA_DEFS};${MGBA_DIR_DEFS};_GNU_SOURCE")
target_include_directories(pokedaisy_libretro PRIVATE ${MGBA_INC} ${MGBA}/include ${CMAKE_CURRENT_BINARY_DIR}/mgba/include ${MGBA}/src/platform/libretro ${MGBA}/src ../3ds/core ../linux ${APP})
target_link_libraries(pokedaisy_libretro PRIVATE m)
# Only retro_* leaves the object: a second mGBA is resident beside the stock core (ADR-0006).
target_link_options(pokedaisy_libretro PRIVATE -Wl,--exclude-libs,ALL -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/retro.map)
