# Our change to armGBA (third_party/armgba, ratherDashing's 3DS fork at 3ds-v0.0.2-alpha) lives in armgba-pokedaisy.patch:
# a hook for a Thumb BKPT in its interpreter, which the 3DS port's native m4a mixer (3ds/host/pd_mixer.c) takes over the
# game's own with. Applied to the submodule's working tree at configure time - once: a tree that already has it is left
# alone (looked for by its text: a Docker build sees the tree, not always its git) - so a fresh checkout builds as is.
# Moving the submodule means re-making the patch (git -C third_party/armgba diff > third_party/patches/armgba-pokedaisy.patch).
#   include(.../apply_armgba_patch.cmake) after setting ARMGBA_DIR
set(PK_ARMGBA_PATCH "${CMAKE_CURRENT_LIST_DIR}/armgba-pokedaisy.patch")
file(READ "${ARMGBA_DIR}/src/cpu/step.c" PK_ARMGBA_STEP)
string(FIND "${PK_ARMGBA_STEP}" "gbaBkptHook" PK_ARMGBA_PATCHED)
if(PK_ARMGBA_PATCHED EQUAL -1)
    execute_process(COMMAND git apply "${PK_ARMGBA_PATCH}"
        WORKING_DIRECTORY "${ARMGBA_DIR}" RESULT_VARIABLE PK_ARMGBA_APPLY ERROR_VARIABLE PK_ARMGBA_ERR)
    if(NOT PK_ARMGBA_APPLY EQUAL 0)
        message(FATAL_ERROR "armGBA patch didn't apply (${PK_ARMGBA_PATCH}): ${PK_ARMGBA_ERR}")
    endif()
    message(STATUS "armGBA: applied armgba-pokedaisy.patch")
endif()
