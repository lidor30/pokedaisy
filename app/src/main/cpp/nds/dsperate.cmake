# DSperate (third_party/dsperate, GPL-3.0-or-later): the second Nintendo DS core, for devices too slow for melonDS
# (nds/pk_nds_dsp.cpp). Its core library only - src/core, through its own CMakeLists - never its frontends, Wi-Fi or
# RetroAchievements (we have our own). Its top-level CMakeLists is skipped on purpose: it turns LTO on for everything
# under it and picks the host from CMAKE_SYSTEM_PROCESSOR, which the NDK spells "armv7-a" (its ARMv7 check wants
# "armv7"), and it builds ARMv7 with -mfloat-abi=hard, which isn't Android's ABI (softfp). So the host is set here.
get_filename_component(DSPERATE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/dsperate" ABSOLUTE)

if(NOT EXISTS "${DSPERATE_DIR}/src/core/nds.h")
    message(FATAL_ERROR "DSperate submodule missing at ${DSPERATE_DIR} - run: git submodule update --init third_party/dsperate")
endif()

set(DSPERATE_JIT ON)
set(DSPERATE_NEON ON)
set(DSPERATE_HOST_AARCH64 OFF)
set(DSPERATE_HOST_ARM32 OFF)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(DSPERATE_HOST_AARCH64 ON)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    # Its ARMv7 recompiler: melonDS has no JIT on 32-bit ARM, which is why the 32-bit devices need this core.
    set(DSPERATE_HOST_ARM32 ON)
else()
    # x86_64 (the emulator): the interpreter, no NEON kernels.
    set(DSPERATE_JIT OFF)
    set(DSPERATE_NEON OFF)
endif()
# 3D is drawn on the CPU (its worker threads): the Vulkan / GLES rasters are built as the stubs that decline.
set(DSPERATE_VULKAN OFF CACHE BOOL "" FORCE)
set(DSPERATE_GLES OFF CACHE BOOL "" FORCE)

add_subdirectory("${DSPERATE_DIR}/src/core" "${CMAKE_CURRENT_BINARY_DIR}/dsperate" EXCLUDE_FROM_ALL)
set_target_properties(dsperate_core PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF
    POSITION_INDEPENDENT_CODE ON INTERPROCEDURAL_OPTIMIZATION OFF)
# Its own build's flags (top-level CMakeLists): -O3, no stack protector / fortify in the pixel loops.
target_compile_options(dsperate_core PRIVATE -O3 -fno-stack-protector -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0
    -Wno-unused-parameter)
if(DSPERATE_HOST_ARM32)
    # NEON + VFPv4 as DSperate pins them, but Android's softfp calling convention.
    target_compile_options(dsperate_core PRIVATE -mfpu=neon-vfpv4)
endif()

# DSperate is built with GCC upstream; the NDK's clang and bionic need a few things it doesn't. None of them is a change to
# the submodule (the Linux port builds it unmodified): the sources clang refuses are copied into the build directory with
# the fix, and a header forced into every file covers bionic (dsperate_android.h).
target_compile_options(dsperate_core PRIVATE "SHELL:-include ${CMAKE_CURRENT_LIST_DIR}/dsperate_android.h")
# Replaces [from] with [to] in src/core/[rel] (each must be there: a moved line fails the build, not the fix), into a copy
# the library builds instead.
function(dsperate_fix rel)
    file(READ "${DSPERATE_DIR}/src/core/${rel}" text)
    set(pairs ${ARGN})
    list(LENGTH pairs n)
    math(EXPR last "${n} - 1")
    foreach(i RANGE 0 ${last} 2)
        math(EXPR j "${i} + 1")
        list(GET pairs ${i} from)
        list(GET pairs ${j} to)
        string(FIND "${text}" "${from}" at)
        if(at EQUAL -1)
            message(FATAL_ERROR "dsperate.cmake: '${from}' isn't in ${rel} any more - check the fix against the new DSperate")
        endif()
        string(REPLACE "${from}" "${to}" text "${text}")
    endforeach()
    set(out "${CMAKE_CURRENT_BINARY_DIR}/dsperate-android/${rel}")
    file(WRITE "${out}.new" "${text}")
    file(COPY_FILE "${out}.new" "${out}" ONLY_IF_DIFFERENT)
    get_target_property(srcs dsperate_core SOURCES)
    list(TRANSFORM srcs REPLACE "^${rel}$" "${out}")
    set_target_properties(dsperate_core PROPERTIES SOURCES "${srcs}")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${DSPERATE_DIR}/src/core/${rel}")
endfunction()
# clang's arm_neon.h makes the intrinsics macros, where a braced vector's comma splits the arguments. And byte buffers
# read or written through a u16* / u32* (32-bit ARM faults on clang's aligned forms of those: dsperate_android.h's
# helpers). One call per file: each starts from the submodule's copy.
dsperate_fix(gpu/kernels_neon.cpp
    "uint32x2_t{c, cs}" "(uint32x2_t{c, cs})" "uint32x2_t{cd, c}" "(uint32x2_t{cd, c})"
    "vld1_dup_u16(reinterpret_cast<const u16*>(ctl))" "vdup_n_u16(pk_dsp_load16(ctl))"
    "*reinterpret_cast<const u32*>(w + i)" "pk_dsp_load32(w + i)"
    "vst1_lane_u32(reinterpret_cast<u32*>(pass + i), vreinterpret_u32_u8(v8), 0)"
    "pk_dsp_store32(pass + i, vget_lane_u32(vreinterpret_u32_u8(v8), 0))")
# clang won't reinterpret_cast a const void* to a function pointer ("casts away qualifiers"); GCC does.
dsperate_fix(gpu/render3d.cpp "reinterpret_cast<GatherNFn>(sh.gather4)" "reinterpret_cast<GatherNFn>(const_cast<void*>(sh.gather4))")
# DSperate and melonDS both bundle tiny-AES-c, each its own build of it (DSperate's has an ARMv8 path): its copy is
# renamed in all of its sources so each core keeps its own.
foreach(sym AES_CBC_decrypt_buffer AES_CTR_next_keystream AES_CTR_xcrypt_buffer AES_ECB_encrypt AES_ctx_set_iv AES_force_software AES_init_ctx AES_init_ctx_iv )
    target_compile_definitions(dsperate_core PRIVATE "${sym}=pk_dsp_${sym}")
endforeach()
