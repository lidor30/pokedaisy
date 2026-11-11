# armGBA (third_party/armgba) for the 3DS port's GBA games: its core, its libretro layer with no Game Boy side
# (gb_none.c, no Gambatte) and the archive readers that layer links, as one library, pd_armgba. Included by the 3DS
# build and the simulator; ARMGBA_JIT picks the recompiler where armGBA has a backend for the CPU (the 3DS's a32).
# Also: PD_ARMGBA_GLUE, the glue built with the rest of the app (mGBA's headers), and pd_mgba_renames(target), which
# builds the app's pk_call.c / pk_host.c / pk_cheats.c entry points as *_mgba (emu/pd_dispatch.c picks the core).
get_filename_component(ARMGBA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../third_party/armgba" ABSOLUTE)
if(NOT EXISTS "${ARMGBA_DIR}/src/gba.c")
    message(FATAL_ERROR "armGBA submodule missing at ${ARMGBA_DIR} - run: git submodule update --init third_party/armgba")
endif()
include("${CMAKE_CURRENT_LIST_DIR}/../../third_party/patches/apply_armgba_patch.cmake")
set(ARMGBA_SRC bios-image.c bus.c cheats.c gba.c memory.c state.c timing.c audio/audio.c audio/audio-thread.c audio/psg.c
    cart/cart.c cart/ereader.c cart/gpio.c cart/overrides.c cart/savedata.c cpu/arm.c cpu/decode.c cpu/step.c io/dma.c
    io/io.c io/rfu.c io/sio.c io/timer.c video/render-thread.c video/render.c video/video.c)
if(NOT DEFINED ARMGBA_BACKEND)
    set(ARMGBA_BACKEND "")
endif()
if(ARMGBA_BACKEND)
    list(APPEND ARMGBA_SRC cpu/jit.c cpu/${ARMGBA_BACKEND}/backend.c)
else()
    list(APPEND ARMGBA_SRC cpu/nojit.c)
endif()
if(ARMGBA_RENDER_SIMD)
    list(APPEND ARMGBA_SRC video/${ARMGBA_RENDER_SIMD})
endif()
list(TRANSFORM ARMGBA_SRC PREPEND "${ARMGBA_DIR}/src/")
set(ARMGBA_LIBRETRO_SRC libretro.c clock.c lcd.c options.c saves.c dolphin.c link.c games.c savefiles.c patch.c dmg07.c
    ereader.c printer.c printout.c workers.c)
list(TRANSFORM ARMGBA_LIBRETRO_SRC PREPEND "${ARMGBA_DIR}/libretro/")
file(GLOB_RECURSE ARMGBA_ARCHIVE_SRC "${ARMGBA_DIR}/libretro/deps/libretro-common/*.c" "${ARMGBA_DIR}/libretro/deps/7zip/*.c")

# RetroArch's archive readers, with their own libretro.h (not the core's).
add_library(pd_armgba_archive STATIC ${ARMGBA_ARCHIVE_SRC})
target_include_directories(pd_armgba_archive PRIVATE "${ARMGBA_DIR}/libretro/deps/libretro-common/include" "${ARMGBA_DIR}/libretro/deps")
target_compile_definitions(pd_armgba_archive PRIVATE HAVE_ZLIB HAVE_7ZIP)
target_compile_options(pd_armgba_archive PRIVATE -O2 -w)

add_library(pd_armgba STATIC ${ARMGBA_SRC} ${ARMGBA_LIBRETRO_SRC} ${ARMGBA_HOST_SRC}
    "${CMAKE_CURRENT_LIST_DIR}/gb_none.c" "${CMAKE_CURRENT_LIST_DIR}/pd_armgba.c" "${CMAKE_CURRENT_LIST_DIR}/pd_armgba_call.c")
target_include_directories(pd_armgba PRIVATE "${ARMGBA_DIR}/src" "${ARMGBA_DIR}/libretro"
    "${ARMGBA_DIR}/libretro/deps/libretro-common/include" "${ARMGBA_DIR}/libretro/deps")
target_compile_definitions(pd_armgba PRIVATE HAVE_ZLIB HAVE_7ZIP)
# -fwrapv: armGBA's clocks are 32-bit counters compared by subtracting (without it every game hangs 128 s in).
target_compile_options(pd_armgba PRIVATE -std=gnu11 -O3 -fwrapv -w)
target_link_libraries(pd_armgba PUBLIC pd_armgba_archive)

set(PD_ARMGBA_GLUE "${CMAKE_CURRENT_LIST_DIR}/pd_armgba_core.c" "${CMAKE_CURRENT_LIST_DIR}/pd_dispatch.c")

function(pd_mgba_renames target)
    get_filename_component(cpp "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../app/src/main/cpp" ABSOLUTE)
    set_source_files_properties("${cpp}/pk_call.c" TARGET_DIRECTORY ${target} PROPERTIES COMPILE_DEFINITIONS
        "pk_call=pk_call_mgba;pk_call_safe=pk_call_safe_mgba;pk_call_set_cap=pk_call_set_cap_mgba;pk_rest_note=pk_rest_note_mgba;pk_rest_reset=pk_rest_reset_mgba")
    set_source_files_properties("${cpp}/pk_host.c" TARGET_DIRECTORY ${target} PROPERTIES COMPILE_DEFINITIONS
        "pk_save_dirty=pk_save_dirty_mgba;pk_finish_save=pk_finish_save_mgba;pk_heal_rtc=pk_heal_rtc_mgba")
    set_source_files_properties("${cpp}/pk_cheats.c" TARGET_DIRECTORY ${target} PROPERTIES COMPILE_DEFINITIONS
        "pk_cheats_clear=pk_cheats_clear_mgba;pk_cheats_apply=pk_cheats_apply_mgba")
endfunction()
