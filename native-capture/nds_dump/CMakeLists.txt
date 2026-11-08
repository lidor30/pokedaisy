cmake_minimum_required(VERSION 3.16)
project(nds_dump CXX C)

set(CMAKE_CXX_STANDARD 20)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# melonDS's core library alone: no Qt frontend, no OpenGL, no GDB stub.
set(BUILD_QT_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_OGLRENDERER OFF CACHE BOOL "" FORCE)
set(ENABLE_GDBSTUB OFF CACHE BOOL "" FORCE)
get_filename_component(MELONDS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/melonds" ABSOLUTE)
include("${MELONDS_DIR}/../patches/apply_melonds_patch.cmake")
add_subdirectory("${MELONDS_DIR}" melonds EXCLUDE_FROM_ALL)

find_package(ZLIB REQUIRED)

add_executable(nds_dump
    nds_dump.cpp
    ../../app/src/main/cpp/nds/pk_nds_platform.cpp
    ../../app/src/main/cpp/nds/pk_nds_field.cpp
    ../../app/src/main/cpp/nds/pk_nds_field_gpu.cpp
    ../../app/src/main/cpp/nds/pk_nds_call.cpp
    ../../app/src/main/cpp/nds/pk_nds_call_melonds.cpp
    ../../app/src/main/cpp/nds/pk_nds_wide.cpp
    ../../app/src/main/cpp/nds/pk_nds_music.cpp)
target_include_directories(nds_dump PRIVATE ../../app/src/main/cpp/nds)
target_link_libraries(nds_dump PRIVATE core ZLIB::ZLIB)

# DS RESOLUTION's GPU renderer (nds/pk_nds_gl.cpp) through ANGLE: -DNDS_DUMP_GL=ON -DANGLE_DIR=<libEGL.dylib and
# libGLESv2.dylib> -DGLES_INCLUDE=<the EGL, GLES2, GLES3 and KHR header folders, e.g. copied from the NDK's sysroot>.
option(NDS_DUMP_GL "melonDS's OpenGL ES renderer and our compositor, through ANGLE" OFF)
if (NDS_DUMP_GL)
    target_sources(core PRIVATE
        "${MELONDS_DIR}/src/GPU_OpenGL.cpp"
        "${MELONDS_DIR}/src/GPU3D_OpenGL.cpp"
        "${MELONDS_DIR}/src/OpenGLSupport.cpp")
    target_compile_definitions(core PUBLIC OGLRENDERER_ENABLED MELONDS_GLES "MELONDS_GL_HEADER=<GLES3/gl32.h>")
    target_include_directories(core PUBLIC "${GLES_INCLUDE}")
    target_link_libraries(core PUBLIC "${ANGLE_DIR}/libEGL.dylib" "${ANGLE_DIR}/libGLESv2.dylib")
    target_sources(nds_dump PRIVATE ../../app/src/main/cpp/nds/pk_nds_gl.cpp)
    target_compile_definitions(nds_dump PRIVATE NDS_DUMP_GL)
endif()
