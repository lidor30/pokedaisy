# n3ds_dump (README.md): Azahar's core libraries with the software renderer, built on the Mac, no Android.
cmake_minimum_required(VERSION 3.25)
project(n3ds_dump C CXX)
# Azahar builds a few Objective-C++ sources on macOS.
if(APPLE)
    enable_language(OBJC OBJCXX)
endif()

get_filename_component(AZAHAR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/azahar" ABSOLUTE)
include("${AZAHAR_DIR}/../patches/apply_azahar_patch.cmake")
# Azahar stamps its states with its git revision and refuses any other; inside a submodule it can't read it, so the
# submodule's commit is passed in (GenerateBuildInfo.cmake, our patch). States then hold across our builds of one Azahar.
execute_process(COMMAND git rev-parse HEAD WORKING_DIRECTORY "${AZAHAR_DIR}" OUTPUT_VARIABLE PK_AZAHAR_REV
    OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE PK_AZAHAR_REV_RESULT)
if(NOT PK_AZAHAR_REV_RESULT EQUAL 0 OR NOT PK_AZAHAR_REV MATCHES "^[0-9a-f]+$")
    message(FATAL_ERROR "Can't read the Azahar submodule's commit (${AZAHAR_DIR})")
endif()

# The app's rule (n3ds/src/main/cpp/CMakeLists.txt): never the key blob.
set(ENABLE_BUILTIN_KEYBLOB OFF CACHE BOOL "" FORCE)
foreach(off ENABLE_QT ENABLE_SDL2 ENABLE_TESTS ENABLE_ROOM ENABLE_ROOM_STANDALONE ENABLE_WEB_SERVICE ENABLE_SCRIPTING
        ENABLE_GDBSTUB ENABLE_CUBEB ENABLE_OPENAL ENABLE_LIBUSB ENABLE_DISCORD_RPC ENABLE_LIBRETRO ENABLE_OPENGL ENABLE_VULKAN
        CITRA_WARNINGS_AS_ERRORS CITRA_USE_PRECOMPILED_HEADERS)
    set(${off} OFF CACHE BOOL "" FORCE)
endforeach()
set(ENABLE_SOFTWARE_RENDERER ON CACHE BOOL "" FORCE)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

add_subdirectory("${AZAHAR_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/azahar" EXCLUDE_FROM_ALL)
if(ENABLE_BUILTIN_KEYBLOB)
    message(FATAL_ERROR "ENABLE_BUILTIN_KEYBLOB is on: PokeDaisy never ships console keys")
endif()

add_executable(n3ds_dump n3ds_dump.cpp)
target_include_directories(n3ds_dump PRIVATE "${AZAHAR_DIR}/src")
target_link_libraries(n3ds_dump PRIVATE citra_core citra_common audio_core video_core input_common lodepng)
# Azahar sets its renderer switches for its own directories only (src/CMakeLists.txt); its headers read them.
target_compile_definitions(n3ds_dump PRIVATE ENABLE_SOFTWARE_RENDERER)
if(APPLE)
    # citra_common's macOS bits (CoreFoundation strings, Objective-C calls).
    target_link_libraries(n3ds_dump PRIVATE "-framework CoreFoundation" "-framework Foundation" objc)
endif()
