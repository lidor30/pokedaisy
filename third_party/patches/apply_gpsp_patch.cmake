# Our changes to gpSP (third_party/gpsp, bheppe's gpSP 3D at v0.1.7) live in gpsp-pokedaisy.patch: the cartridge in the
# host's buffer (the 3DS port's 32 MB arena, gpsp_host_rom_arena) rather than 32 blocks of 1 MB of its own, and its render
# thread's semaphores on macOS (dispatch semaphores: no unnamed POSIX ones there; the simulator). Applied to
# the submodule's working tree at configure time, once: a tree that already has it is left alone (looked for by its
# text: a Docker build sees the tree, not always its git), so a fresh checkout builds as is. `patch` rather than `git
# apply`: a worktree's submodule points its .git at a host path a container doesn't have.
# Moving the submodule means re-making the patch (git -C third_party/gpsp diff > third_party/patches/gpsp-pokedaisy.patch).
#   include(.../apply_gpsp_patch.cmake) after setting GPSP_DIR
set(PK_GPSP_PATCH "${CMAKE_CURRENT_LIST_DIR}/gpsp-pokedaisy.patch")
file(READ "${GPSP_DIR}/gba_memory.c" PK_GPSP_MEMORY)
file(READ "${GPSP_DIR}/video_thread_sys.c" PK_GPSP_VTSYS)
string(FIND "${PK_GPSP_MEMORY}" "gpsp_host_rom_arena" PK_GPSP_PATCHED)
string(FIND "${PK_GPSP_VTSYS}" "dispatch_semaphore" PK_GPSP_PATCHED_SEM)
if(NOT PK_GPSP_PATCHED EQUAL -1 AND PK_GPSP_PATCHED_SEM EQUAL -1)
    # A tree with the first patch only: gba_memory.c back to gpSP's (patch -R of that part isn't kept apart), then all of it.
    message(FATAL_ERROR "gpSP holds part of gpsp-pokedaisy.patch: git -C third_party/gpsp checkout . and configure again")
endif()
if(PK_GPSP_PATCHED EQUAL -1)
    execute_process(COMMAND patch -p1 -N -i "${PK_GPSP_PATCH}"
        WORKING_DIRECTORY "${GPSP_DIR}" RESULT_VARIABLE PK_GPSP_APPLY OUTPUT_VARIABLE PK_GPSP_OUT ERROR_VARIABLE PK_GPSP_ERR)
    if(NOT PK_GPSP_APPLY EQUAL 0)
        message(FATAL_ERROR "gpSP patch didn't apply (${PK_GPSP_PATCH}): ${PK_GPSP_OUT}${PK_GPSP_ERR}")
    endif()
    message(STATUS "gpSP: applied gpsp-pokedaisy.patch")
endif()
