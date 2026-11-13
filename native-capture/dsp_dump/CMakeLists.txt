cmake_minimum_required(VERSION 3.22)
project(dsp_dump C CXX)

set(CMAKE_CXX_STANDARD 17)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# DSperate's core as the app builds it (app/src/main/cpp/nds/dsperate.cmake); its host is picked by ANDROID_ABI there.
if (NOT ANDROID_ABI)
    if (CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
        set(ANDROID_ABI arm64-v8a)
    elseif (CMAKE_SYSTEM_PROCESSOR MATCHES "^arm")
        set(ANDROID_ABI armeabi-v7a)
    else()
        set(ANDROID_ABI x86_64)
    endif()
endif()
include(../../app/src/main/cpp/nds/dsperate.cmake)

find_package(ZLIB REQUIRED)
add_executable(dsp_dump
    dsp_dump.cpp
    ../../app/src/main/cpp/nds/pk_nds_dsp.cpp
    ../../app/src/main/cpp/nds/pk_nds_field.cpp)
target_include_directories(dsp_dump PRIVATE shim ../../app/src/main/cpp/nds)
target_link_libraries(dsp_dump PRIVATE dsperate_core ZLIB::ZLIB pthread)
