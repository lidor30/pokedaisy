# BananaDS as the app's third DS core (pk_nds_bnd.cpp, pk_ndsb_* behind pk_nds.h): libpkbananads.so, a shared library
# of its own, because BananaDS's melonDS (906e9eb + BananaDS's patches) and the app's melonDS (1.1 + our patch) can't
# share one: both are namespace melonDS, the same CMake target names, the same JIT symbols. Everything in here is
# hidden except pk_ndsb_* (exports.map), so the two never bind to each other.
#
# Built by the app's CMakeLists.txt as an ExternalProject with the same NDK toolchain, ABI and build type.
#
# What makes it BananaDS (as the handhelds build it: BananaLinux's buildroot/package/bananads/bananads.mk):
#   - melonDS at BananaDS's pin (third_party/melonds-bananads) with bananads/patches/melonds/*.patch on a copy of it
#   - melonDS's own AArch64 JIT (BANANADS_JIT=melonds, as shipped), with those patches' JIT work
#   - the Banana 2D engine (gpu2d/) in place of melonDS's 2D renderer
#   - Gengis Engine (gengis/), SuperDrastic's rasterizer, as the 3D renderer
cmake_minimum_required(VERSION 3.22.1)
project(pkbananads C CXX ASM)

get_filename_component(PD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(BANANADS_DIR "${PD_ROOT}/third_party/bananads/bananads")
set(MELONDS_PIN "${PD_ROOT}/third_party/melonds-bananads")
set(PD_NDS "${CMAKE_CURRENT_SOURCE_DIR}/../nds")

if(NOT EXISTS "${BANANADS_DIR}/gengis/gx.c")
    message(FATAL_ERROR "BananaDS missing at ${BANANADS_DIR} - run: git submodule update --init third_party/bananads")
endif()
if(NOT EXISTS "${MELONDS_PIN}/src/NDS.h")
    message(FATAL_ERROR "BananaDS's melonDS missing at ${MELONDS_PIN} - run: git submodule update --init third_party/melonds-bananads")
endif()

# ---- melonDS, patched as BananaDS patches it: a copy in the build folder (the submodule stays clean) ----
set(MSRC "${CMAKE_BINARY_DIR}/melonds-src")
file(GLOB MELONDS_PATCHES "${BANANADS_DIR}/patches/melonds/*.patch")
list(SORT MELONDS_PATCHES)
execute_process(COMMAND git -C "${MELONDS_PIN}" rev-parse HEAD OUTPUT_VARIABLE PIN_HEAD OUTPUT_STRIP_TRAILING_WHITESPACE)
execute_process(COMMAND git -C "${BANANADS_DIR}" rev-parse HEAD OUTPUT_VARIABLE BANANA_HEAD OUTPUT_STRIP_TRAILING_WHITESPACE)
# melonds-expand.patch: PokeDaisy's own melonDS changes EXPAND needs (third_party/patches/melonds-pokedaisy.patch's
# GPU3D hooks, the line-192 hook, the software 3D renderer's column / row range), remade for this melonDS; no frame
# skip and no OpenGL renderer (BananaDS draws with its own engines). After BananaDS's patches.
set(PK_EXPAND_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/melonds-expand.patch")
file(SHA1 "${PK_EXPAND_PATCH}" PK_EXPAND_SHA)
# (a changed patch makes the build configure again, so the copy is remade)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${PK_EXPAND_PATCH}" ${MELONDS_PATCHES})
set(STAMP_WANT "${PIN_HEAD} ${BANANA_HEAD} ${PK_EXPAND_SHA}")
set(STAMP_HAVE "")
if(EXISTS "${MSRC}/.pk-stamp")
    file(READ "${MSRC}/.pk-stamp" STAMP_HAVE)
endif()
if(NOT STAMP_HAVE STREQUAL STAMP_WANT)
    file(REMOVE_RECURSE "${MSRC}")
    file(COPY "${MELONDS_PIN}/" DESTINATION "${MSRC}" PATTERN ".git" EXCLUDE)
    foreach(P ${MELONDS_PATCHES})
        execute_process(COMMAND patch -p1 -s --no-backup-if-mismatch -i "${P}"
            WORKING_DIRECTORY "${MSRC}" RESULT_VARIABLE R ERROR_VARIABLE E OUTPUT_VARIABLE O)
        if(NOT R EQUAL 0)
            message(FATAL_ERROR "${P} doesn't apply to BananaDS's melonDS: ${O} ${E}")
        endif()
    endforeach()
    # The Banana 2D engine's two friend declarations in melonDS's GPU_Soft.h (as BananaDS's CMakeLists.txt adds them)
    file(READ "${MSRC}/src/GPU_Soft.h" SOFTTXT)
    string(REPLACE "    friend class SoftRenderer3D;\n"
        "    friend class SoftRenderer3D;\n    friend class BananaRenderer2D;\n    friend class BananaRenderer;\n"
        SOFTTXT2 "${SOFTTXT}")
    if(SOFTTXT2 STREQUAL SOFTTXT)
        message(FATAL_ERROR "melonDS's GPU_Soft.h changed; the Banana 2D engine's friend declarations need updating")
    endif()
    file(WRITE "${MSRC}/src/GPU_Soft.h" "${SOFTTXT2}")
    execute_process(COMMAND patch -p1 -s --no-backup-if-mismatch -i "${PK_EXPAND_PATCH}"
        WORKING_DIRECTORY "${MSRC}" RESULT_VARIABLE R ERROR_VARIABLE E OUTPUT_VARIABLE O)
    if(NOT R EQUAL 0)
        message(FATAL_ERROR "melonds-expand.patch doesn't apply to BananaDS's melonDS: ${O} ${E}")
    endif()
    file(WRITE "${MSRC}/.pk-stamp" "${STAMP_WANT}")
    list(LENGTH MELONDS_PATCHES NP)
    message(STATUS "BananaDS: melonDS ${PIN_HEAD} with ${NP} patches in ${MSRC}")
endif()

# android_compat.h into every source here (C and C++): bionic / clang's differences from BananaDS's glibc / GCC build.
# The Thor's CPUs (and every arm64 Android) have the CRC32 instructions Gengis's checksums use.
add_compile_options("$<$<COMPILE_LANGUAGE:C,CXX>:SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/android_compat.h>" -march=armv8-a+crc)
# Native ELF TLS, not the NDK's emulated TLS (its default below API 29): Gengis keeps its rasterizer's state in __thread
# variables (gx.c's gx_ctx, fused.c, defer.c) and melonDS its NDS::Current in a thread_local, read all the time;
# emulated, each read is a __emutls_get_address call. Needs Android 10 (API 29): the app dlopen()s this library
# (nds/pk_nds_bnd_load.cpp), so on an older Android BananaDS just doesn't start.
add_compile_options("$<$<COMPILE_LANGUAGE:C,CXX>:-fno-emulated-tls>")
add_link_options(-fno-emulated-tls)

# Every object in this library hidden; pk_ndsb_* are exported by exports.map.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_QT_SDL        OFF CACHE BOOL "" FORCE)
set(ENABLE_OGLRENDERER  OFF CACHE BOOL "" FORCE)
set(ENABLE_GDBSTUB      OFF CACHE BOOL "" FORCE)
set(ENABLE_LTO_RELEASE  OFF CACHE BOOL "" FORCE)
set(ENABLE_LTO          OFF CACHE BOOL "" FORCE)
set(ENABLE_JIT          ON  CACHE BOOL "" FORCE)
add_subdirectory("${MSRC}" "${CMAKE_BINARY_DIR}/melonds" EXCLUDE_FROM_ALL)
# (as BananaDS builds it: GCC/clang may inline across melonDS's core with no interposition to respect)
target_compile_options(core PRIVATE -fno-semantic-interposition)

# ---- Gengis Engine (gengis/: C, NEON and the generated assembly kernels) ----
set(GX "${BANANADS_DIR}/gengis")
# (the source list as BananaDS's CMakeLists.txt has it)
add_library(gengis STATIC ${GX}/gx.c ${GX}/gx2.c ${GX}/gx3.c ${GX}/gx4.c ${GX}/gx4q.c ${GX}/gx4e.c ${GX}/gx3q.c ${GX}/gx3e.c
    ${GX}/gx2q.c ${GX}/gx2e.c ${GX}/fused.c ${GX}/fused_neon.c ${GX}/fused_asm.c ${GX}/hrneon.c ${GX}/defer.c ${GX}/rast_kern.S)
target_include_directories(gengis PUBLIC ${GX})
target_compile_options(gengis PRIVATE $<$<COMPILE_LANGUAGE:C>:-O2 -ffp-contract=off -Wno-unused-function>)
set_target_properties(gengis PROPERTIES C_STANDARD 11 C_EXTENSIONS ON)

# ---- the Banana 2D engine ----
add_library(banana2d STATIC "${BANANADS_DIR}/gpu2d/GPU2D_Banana.cpp" "${BANANADS_DIR}/gpu2d/GPU_Banana.cpp")
target_include_directories(banana2d PUBLIC "${BANANADS_DIR}/gpu2d")
target_link_libraries(banana2d PUBLIC core)
target_compile_definitions(banana2d PUBLIC BANANADS_2D=1)

# ---- the core library the app loads ----
add_library(pkbananads SHARED
    pk_nds_bnd.cpp
    bnd_platform.cpp
    "${BANANADS_DIR}/src/gengis_renderer.cpp"
    "${PD_NDS}/pk_nds_field.cpp"
    "${PD_NDS}/pk_nds_field_gpu.cpp"
    "${PD_NDS}/pk_nds_wide.cpp")
# (its own objects default-visible: exports.map keeps pk_ndsb_* and hides the rest; the static libraries stay hidden)
set_target_properties(pkbananads PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON CXX_VISIBILITY_PRESET default)
# gengis_renderer.cpp includes BananaDS's emu.h (headers only); the one function it calls is in pk_nds_bnd.cpp
target_include_directories(pkbananads PRIVATE "${PD_NDS}" "${BANANADS_DIR}/src")
target_compile_definitions(pkbananads PRIVATE BANANADS_GENGIS=1 ARCHITECTURE_ARM64=1 PK_NDS_BANANADS=1 PK_NDS_BANANADS_DBG=1)
target_link_libraries(pkbananads PRIVATE banana2d gengis core android log z)
target_link_options(pkbananads PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    -Wl,--exclude-libs,ALL
    -Wl,-Bsymbolic
    -Wl,-z,max-page-size=16384)
