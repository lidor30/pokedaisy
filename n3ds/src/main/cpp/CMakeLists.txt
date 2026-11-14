# 3DS games: Azahar's core libraries (third_party/azahar, upstream 2126.2 + our patch) and our front end, libpk_n3ds.so.
# Azahar's own Android app (src/android) and frontends are left out; only the libraries the front end links are built.
cmake_minimum_required(VERSION 3.25)
project(pk_n3ds C CXX)

get_filename_component(AZAHAR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/azahar" ABSOLUTE)
if(NOT EXISTS "${AZAHAR_DIR}/CMakeLists.txt" OR NOT EXISTS "${AZAHAR_DIR}/externals/dynarmic/CMakeLists.txt")
    message(FATAL_ERROR "Azahar submodule missing at ${AZAHAR_DIR} - run: git submodule update --init --recursive third_party/azahar")
endif()
include("${AZAHAR_DIR}/../patches/apply_azahar_patch.cmake")
# Azahar stamps its states with its git revision and refuses any other; inside a submodule it can't read it, so the
# submodule's commit is passed in (GenerateBuildInfo.cmake, our patch). States then hold across our builds of one Azahar.
execute_process(COMMAND git rev-parse HEAD WORKING_DIRECTORY "${AZAHAR_DIR}" OUTPUT_VARIABLE PK_AZAHAR_REV
    OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE PK_AZAHAR_REV_RESULT)
if(NOT PK_AZAHAR_REV_RESULT EQUAL 0 OR NOT PK_AZAHAR_REV MATCHES "^[0-9a-f]+$")
    message(FATAL_ERROR "Can't read the Azahar submodule's commit (${AZAHAR_DIR})")
endif()

# Never the console's keys: Azahar builds an obfuscated key blob in by default (src/core/hw/default_keys.h). Off here,
# and the configure stops if anything turns it back on (scripts/check_azahar_patch.py checks both lines are here).
# Only decrypted carts play; an encrypted one reports ErrorLoader_ErrorEncrypted.
set(ENABLE_BUILTIN_KEYBLOB OFF CACHE BOOL "" FORCE)

# What a front end inside our app needs, nothing else: no Qt / SDL / libusb / Discord / web services / rooms / scripting
# RPC / GDB stub / tests, no audio backends (we take the samples ourselves), Vulkan and OpenGL ES renderers.
foreach(off ENABLE_QT ENABLE_SDL2 ENABLE_TESTS ENABLE_ROOM ENABLE_ROOM_STANDALONE ENABLE_WEB_SERVICE ENABLE_SCRIPTING
        ENABLE_GDBSTUB ENABLE_CUBEB ENABLE_OPENAL ENABLE_LIBUSB ENABLE_DISCORD_RPC ENABLE_LIBRETRO CITRA_WARNINGS_AS_ERRORS)
    set(${off} OFF CACHE BOOL "" FORCE)
endforeach()
set(ENABLE_OPENGL ON CACHE BOOL "" FORCE)
set(ENABLE_VULKAN ON CACHE BOOL "" FORCE)

add_subdirectory("${AZAHAR_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/azahar" EXCLUDE_FROM_ALL)

if(ENABLE_BUILTIN_KEYBLOB)
    message(FATAL_ERROR "ENABLE_BUILTIN_KEYBLOB is on: PokeDaisy never ships console keys")
endif()

add_library(pk_n3ds SHARED pk_n3ds_jni.cpp)
target_include_directories(pk_n3ds PRIVATE "${AZAHAR_DIR}/src")
target_link_libraries(pk_n3ds PRIVATE citra_core citra_common audio_core video_core input_common android log)
# Azahar sets its renderer switches for its own directories only (src/CMakeLists.txt); its headers read them.
target_compile_definitions(pk_n3ds PRIVATE ENABLE_OPENGL ENABLE_VULKAN)
