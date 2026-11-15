# Our changes to Azahar (third_party/azahar, pinned to upstream 2126.2) live in azahar-pokedaisy.patch: its CMake copies a
# git hook into .git/hooks, which is a file in a submodule (skipped there), and its version scripts read CMAKE_SOURCE_DIR,
# which is ours when Azahar is a subdirectory (PROJECT_SOURCE_DIR there, in its externals too), and its version stamp
# (on every state) comes from PK_AZAHAR_REV, the submodule commit our build reads, since .git is a file there.
# Applied to the submodule's working tree at configure time - once: a tree that already has it is left alone - so a
# fresh checkout builds as is. Moving the submodule means re-making the patch (git -C third_party/azahar diff).
#   include(.../apply_azahar_patch.cmake) after setting AZAHAR_DIR
set(PK_AZAHAR_PATCH "${CMAKE_CURRENT_LIST_DIR}/azahar-pokedaisy.patch")
execute_process(COMMAND git apply --reverse --check "${PK_AZAHAR_PATCH}"
    WORKING_DIRECTORY "${AZAHAR_DIR}" RESULT_VARIABLE PK_AZAHAR_PATCHED OUTPUT_QUIET ERROR_QUIET)
if(NOT PK_AZAHAR_PATCHED EQUAL 0)
    execute_process(COMMAND git apply "${PK_AZAHAR_PATCH}"
        WORKING_DIRECTORY "${AZAHAR_DIR}" RESULT_VARIABLE PK_AZAHAR_APPLY ERROR_VARIABLE PK_AZAHAR_ERR)
    if(NOT PK_AZAHAR_APPLY EQUAL 0)
        message(FATAL_ERROR "Azahar patch didn't apply (${PK_AZAHAR_PATCH}): ${PK_AZAHAR_ERR}")
    endif()
    message(STATUS "Azahar: applied azahar-pokedaisy.patch")
endif()
