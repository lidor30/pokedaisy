# ---------------------------------------------------------------------------
# Vendored rcheevos (git submodule, pinned to v12.5.0) — RetroAchievements'
# own client library. GBA ROM hashing only: no disc, encrypted or zip hashing.
# ---------------------------------------------------------------------------
get_filename_component(RCHEEVOS_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/rcheevos" ABSOLUTE)

if(NOT EXISTS "${RCHEEVOS_DIR}/include/rc_client.h")
    message(FATAL_ERROR "rcheevos submodule missing at ${RCHEEVOS_DIR} — run: git submodule update --init third_party/rcheevos")
endif()

file(GLOB RCHEEVOS_SOURCES
    "${RCHEEVOS_DIR}/src/*.c"
    "${RCHEEVOS_DIR}/src/rapi/*.c"
    "${RCHEEVOS_DIR}/src/rcheevos/*.c"
    "${RCHEEVOS_DIR}/src/rhash/hash.c"
    "${RCHEEVOS_DIR}/src/rhash/hash_rom.c"
    "${RCHEEVOS_DIR}/src/rhash/md5.c")
list(FILTER RCHEEVOS_SOURCES EXCLUDE REGEX "rc_libretro\\.c$|rc_client_external\\.c$|rc_client_raintegration\\.c$")

# The pinned rc_client clears client->game before delivering pending events on unload.
# Patch a build copy so a late mastery notification uses the still-live game being closed.
set(RCHEEVOS_PATCH "${CMAKE_CURRENT_LIST_DIR}/pk_ra_unload.patch")
set(RCHEEVOS_BUILD_DIR "${CMAKE_CURRENT_BINARY_DIR}/rcheevos")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${RCHEEVOS_PATCH}")
configure_file("${RCHEEVOS_DIR}/src/rc_client.c" "${RCHEEVOS_BUILD_DIR}/src/rc_client.c" COPYONLY)
file(REAL_PATH "${RCHEEVOS_BUILD_DIR}" RCHEEVOS_PATCH_DIR)
execute_process(COMMAND git apply --unidiff-zero --unsafe-paths "--directory=${RCHEEVOS_PATCH_DIR}" "${RCHEEVOS_PATCH}"
    RESULT_VARIABLE RCHEEVOS_PATCH_RESULT ERROR_VARIABLE RCHEEVOS_PATCH_ERROR)
if(NOT RCHEEVOS_PATCH_RESULT EQUAL 0)
    message(FATAL_ERROR "rcheevos unload patch failed: ${RCHEEVOS_PATCH_ERROR}")
endif()
list(REMOVE_ITEM RCHEEVOS_SOURCES "${RCHEEVOS_DIR}/src/rc_client.c")
list(APPEND RCHEEVOS_SOURCES "${RCHEEVOS_BUILD_DIR}/src/rc_client.c")

add_library(rcheevos STATIC EXCLUDE_FROM_ALL ${RCHEEVOS_SOURCES})
target_include_directories(rcheevos PUBLIC "${RCHEEVOS_DIR}/include" PRIVATE "${RCHEEVOS_DIR}/src")
target_compile_definitions(rcheevos PUBLIC
    RC_CLIENT_SUPPORTS_HASH RC_HASH_NO_DISC RC_HASH_NO_ENCRYPTED RC_HASH_NO_ZIP)

add_library(pk_ra_requests STATIC "${CMAKE_CURRENT_LIST_DIR}/pk_ra_requests.c")
target_include_directories(pk_ra_requests PUBLIC "${CMAKE_CURRENT_LIST_DIR}" "${RCHEEVOS_DIR}/include")
set_target_properties(pk_ra_requests PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 11 C_STANDARD_REQUIRED ON)

# The portable client is built on iOS and Android. The latter also supplies the DS memory adapter.
add_library(pk_ra_client STATIC EXCLUDE_FROM_ALL "${CMAKE_CURRENT_LIST_DIR}/pk_ra_client.c")
set_target_properties(pk_ra_client PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(pk_ra_client PUBLIC pk_api pk_ra_requests rcheevos)
