# Our changes to armGBA (third_party/armgba, ratherDashing's 3DS fork at 3ds-v0.0.3-alpha) live in armgba-pokedaisy.patch:
# a hook for a Thumb BKPT in its interpreter, which the 3DS port's native m4a mixer (3ds/host/pd_mixer.c) takes over the
# game's own with, one before an H-blank interrupt is raised (3ds/emu/pd_armgba.c: a stock handler with no callback), and
# a table CRC-32 in cart/overrides.c that skips ROMs past 16 MB (its bit-by-bit one cost a 32 MB hack's start ~1.4 s), and
# Fast Flash Saves' two routines found in one pass (cart/savedata.c; two passes a byte at a time cost it ~0.4 s). Applied to the submodule's working tree at configure time - once: a tree that already has it is left
# alone (looked for by its text: a Docker build sees the tree, not always its git) - so a fresh checkout builds as is.
# Moving the submodule means re-making the patch (git -C third_party/armgba diff > third_party/patches/armgba-pokedaisy.patch).
#   include(.../apply_armgba_patch.cmake) after setting ARMGBA_DIR
set(PK_ARMGBA_PATCH "${CMAKE_CURRENT_LIST_DIR}/armgba-pokedaisy.patch")
file(READ "${ARMGBA_DIR}/src/cpu/step.c" PK_ARMGBA_STEP)
file(READ "${ARMGBA_DIR}/src/video/video.c" PK_ARMGBA_VIDEO)
file(READ "${ARMGBA_DIR}/src/cart/overrides.c" PK_ARMGBA_OVERRIDES)
file(READ "${ARMGBA_DIR}/src/cart/savedata.c" PK_ARMGBA_SAVEDATA)
string(FIND "${PK_ARMGBA_STEP}" "gbaBkptHook" PK_ARMGBA_PATCHED)
string(FIND "${PK_ARMGBA_VIDEO}" "gbaHblankIrqHook" PK_ARMGBA_PATCHED_VIDEO)
string(FIND "${PK_ARMGBA_OVERRIDES}" "a hack without its checksum (PokeDaisy)" PK_ARMGBA_PATCHED_CRC)
string(FIND "${PK_ARMGBA_SAVEDATA}" "savedataSkipFlashTimerAt" PK_ARMGBA_PATCHED_FLASH)
if((PK_ARMGBA_PATCHED_CRC EQUAL -1 OR PK_ARMGBA_PATCHED_FLASH EQUAL -1) AND NOT PK_ARMGBA_PATCHED EQUAL -1 AND NOT PK_ARMGBA_PATCHED_VIDEO EQUAL -1)
    # A tree with an older patch (without the CRC or Fast Flash Saves part): its files back to armGBA's, then the whole
    # patch below.
    execute_process(COMMAND git checkout -- src/cpu/step.c src/video/video.c src/cart/overrides.c src/cart/savedata.c src/gba.h
        WORKING_DIRECTORY "${ARMGBA_DIR}")
    set(PK_ARMGBA_PATCHED -1)
    set(PK_ARMGBA_PATCHED_VIDEO -1)
    set(PK_ARMGBA_PATCHED_CRC -1)
    set(PK_ARMGBA_PATCHED_FLASH -1)
endif()
if(PK_ARMGBA_PATCHED EQUAL -1 AND PK_ARMGBA_PATCHED_VIDEO EQUAL -1 AND PK_ARMGBA_PATCHED_CRC EQUAL -1 AND PK_ARMGBA_PATCHED_FLASH EQUAL -1)
    execute_process(COMMAND git apply "${PK_ARMGBA_PATCH}"
        WORKING_DIRECTORY "${ARMGBA_DIR}" RESULT_VARIABLE PK_ARMGBA_APPLY ERROR_VARIABLE PK_ARMGBA_ERR)
    if(NOT PK_ARMGBA_APPLY EQUAL 0)
        message(FATAL_ERROR "armGBA patch didn't apply (${PK_ARMGBA_PATCH}): ${PK_ARMGBA_ERR}")
    endif()
    message(STATUS "armGBA: applied armgba-pokedaisy.patch")
elseif(PK_ARMGBA_PATCHED EQUAL -1 OR PK_ARMGBA_PATCHED_VIDEO EQUAL -1 OR PK_ARMGBA_PATCHED_CRC EQUAL -1 OR PK_ARMGBA_PATCHED_FLASH EQUAL -1)
    message(FATAL_ERROR "armGBA holds part of armgba-pokedaisy.patch: git -C third_party/armgba checkout . and configure again")
endif()
