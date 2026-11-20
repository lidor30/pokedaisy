# The emulator core for iOS: pk_api (shared/src/commonMain/cpp/pk_api.cmake: mGBA + the portable pk_*.c) built for one iOS
# slice. build_ios_core.sh runs it for the iPad and the iPad simulator and packs the result for Kotlin (cinterop) and
# Xcode (PkCore.xcframework).
cmake_minimum_required(VERSION 3.22.1)
project(pk_core_ios C)
include("${CMAKE_CURRENT_SOURCE_DIR}/../src/commonMain/cpp/pk_api.cmake")
include("${CMAKE_CURRENT_SOURCE_DIR}/../src/commonMain/cpp/pk_ra.cmake")
