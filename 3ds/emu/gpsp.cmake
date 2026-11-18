# gpSP (third_party/gpsp, bheppe's gpSP 3D at v0.1.7): the 3DS port's third GBA core, the one the library's GBA CORE
# calls GPSP. Its core, its libretro layer and our glue (pd_gpsp.c) as one library, pd_gpsp, for the 3DS build and the
# simulator. GPSP_DYNAREC picks the ARM11 recompiler (the 3DS); else gpSP's interpreter (the simulator).
#
# gpSP and armGBA are both libretro cores: retro_init, environ_cb and many more names clash, and gpSP's globals have
# plain names (reg, ewram, vram ...). So gpSP's objects and pd_gpsp.c are linked into one object first and every global
# but pd_gpsp.h's names made local to it (objcopy --keep-global-symbols; Apple's ld -r -exported_symbols_list on a Mac).
# pd_gpsp.c is the only file that sees gpSP's headers.
get_filename_component(GPSP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../third_party/gpsp" ABSOLUTE)
if(NOT EXISTS "${GPSP_DIR}/gba_memory.c")
    message(FATAL_ERROR "gpSP submodule missing at ${GPSP_DIR} - run: git submodule update --init third_party/gpsp")
endif()
include("${CMAKE_CURRENT_LIST_DIR}/../../third_party/patches/apply_gpsp_patch.cmake")
enable_language(CXX ASM) # its CPU and renderer are C++, its BIOS blob assembler

set(GPSP_C main.c gba_memory.c savestate.c input.c sound.c cheats.c memmap.c serial.c gbp.c rfu.c serial_proto.c
    libretro/libretro.c gba_cc_lut.c profiler.c video_thread_sys.c
    libretro/libretro-common/compat/compat_posix_string.c libretro/libretro-common/compat/compat_strl.c
    libretro/libretro-common/compat/fopen_utf8.c libretro/libretro-common/encodings/encoding_utf.c
    libretro/libretro-common/file/file_path.c libretro/libretro-common/file/file_path_io.c
    libretro/libretro-common/streams/file_stream.c libretro/libretro-common/string/stdstring.c
    libretro/libretro-common/time/rtime.c libretro/libretro-common/vfs/vfs_implementation.c)
set(GPSP_CXX video.cc video_thread.cc cpu.cc)
set(GPSP_ASM bios_data.S)
set(GPSP_DEFS HAVE_STRINGS_H HAVE_STDINT_H HAVE_INTTYPES_H __LIBRETRO__ INLINE=inline FRONTEND_SUPPORTS_RGB565)
if(GPSP_DYNAREC)
    list(APPEND GPSP_C cpu_threaded.c 3ds/3ds_utils.c)
    list(APPEND GPSP_ASM arm/arm_stub.S 3ds/3ds_cache_utils.S)
    list(APPEND GPSP_DEFS HAVE_DYNAREC ARM_ARCH ARM11 _3DS)
    # The stock 3DS caches need 10.5 MiB after the 32 MiB cartridge and
    # companion. The core's small preset fits the Old3DS memory budget.
    option(PD_GPSP_SMALL_CACHE "Use gpSP's 2 MiB ROM / 384 KiB RAM JIT caches" ON)
    if(PD_GPSP_SMALL_CACHE)
        list(APPEND GPSP_DEFS SMALL_TRANSLATION_CACHE)
    endif()
elseif(GPSP_3DS)
    list(APPEND GPSP_DEFS ARM11 _3DS)
endif()
set(GPSP_SRC ${GPSP_C} ${GPSP_CXX} ${GPSP_ASM})
list(TRANSFORM GPSP_SRC PREPEND "${GPSP_DIR}/")

add_library(pd_gpsp_objs OBJECT ${GPSP_SRC} "${CMAKE_CURRENT_LIST_DIR}/pd_gpsp.c")
target_include_directories(pd_gpsp_objs PRIVATE "${GPSP_DIR}" "${GPSP_DIR}/libretro" "${GPSP_DIR}/libretro/libretro-common/include")
if(GPSP_DYNAREC OR GPSP_3DS)
    target_include_directories(pd_gpsp_objs PRIVATE "${CTRULIB}/include")
endif()
target_compile_definitions(pd_gpsp_objs PRIVATE ${GPSP_DEFS})
target_compile_options(pd_gpsp_objs PRIVATE -O3 -w -fno-strict-aliasing
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti -fno-exceptions -std=c++11>
    $<$<COMPILE_LANGUAGE:ASM>:-x assembler-with-cpp -Wa,-I${GPSP_DIR}>)
set_source_files_properties("${CMAKE_CURRENT_LIST_DIR}/pd_gpsp.c" PROPERTIES COMPILE_OPTIONS "-Wall")

# The one object, pd_gpsp.h's names its only globals.
set(PD_GPSP_KEEP "${CMAKE_CURRENT_LIST_DIR}/pd_gpsp.keep")
set(PD_GPSP_OBJ "${CMAKE_CURRENT_BINARY_DIR}/pd_gpsp_merged.o")
if(APPLE)
    # Mach-O names carry a leading underscore.
    set(PD_GPSP_EXPORTS "${CMAKE_CURRENT_BINARY_DIR}/pd_gpsp.exports")
    add_custom_command(OUTPUT "${PD_GPSP_OBJ}"
        COMMAND sed "s/^/_/" "${PD_GPSP_KEEP}" > "${PD_GPSP_EXPORTS}"
        COMMAND ld -r -o "${PD_GPSP_OBJ}" -exported_symbols_list "${PD_GPSP_EXPORTS}" $<TARGET_OBJECTS:pd_gpsp_objs>
        DEPENDS pd_gpsp_objs $<TARGET_OBJECTS:pd_gpsp_objs> "${PD_GPSP_KEEP}" COMMAND_EXPAND_LISTS VERBATIM)
else()
    if(NOT CMAKE_OBJCOPY)
        string(REGEX REPLACE "gcc$" "objcopy" CMAKE_OBJCOPY "${CMAKE_C_COMPILER}")
    endif()
    add_custom_command(OUTPUT "${PD_GPSP_OBJ}"
        COMMAND "${CMAKE_LINKER}" -r -o "${PD_GPSP_OBJ}.all" $<TARGET_OBJECTS:pd_gpsp_objs>
        COMMAND "${CMAKE_OBJCOPY}" --keep-global-symbols=${PD_GPSP_KEEP} "${PD_GPSP_OBJ}.all" "${PD_GPSP_OBJ}"
        DEPENDS pd_gpsp_objs $<TARGET_OBJECTS:pd_gpsp_objs> "${PD_GPSP_KEEP}" COMMAND_EXPAND_LISTS VERBATIM)
endif()
add_custom_target(pd_gpsp_merge DEPENDS "${PD_GPSP_OBJ}")
add_library(pd_gpsp STATIC "${PD_GPSP_OBJ}")
set_source_files_properties("${PD_GPSP_OBJ}" PROPERTIES EXTERNAL_OBJECT TRUE GENERATED TRUE)
set_target_properties(pd_gpsp PROPERTIES LINKER_LANGUAGE C)
add_dependencies(pd_gpsp pd_gpsp_merge)

set(PD_GPSP_GLUE "${CMAKE_CURRENT_LIST_DIR}/pd_gpsp_core.c")
