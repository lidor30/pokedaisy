#!/bin/sh
# Builds the emulator core (pk_api.h: mGBA + the portable pk_*.c) for iOS, on a Mac with Xcode:
#   <out>/ios-arm64/libpkcore.a            the iPad
#   <out>/ios-arm64-simulator/libpkcore.a  the iPad simulator (Apple silicon)
#   <out>/include/ for the game, link and RetroAchievements public headers
#   <out>/PkCore.xcframework               both, for an Xcode project
# shared/build.gradle.kts runs it before the iOS cinterop; by hand: shared/native/build_ios_core.sh [out dir].
# GPL-3.0 (see NOTICE).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
out=${1:-"$root/shared/build/pkcore"}
mkdir -p "$out"
out=$(cd "$out" && pwd)
jobs=$(sysctl -n hw.ncpu 2>/dev/null || echo 4)

if [ ! -f "$root/third_party/mgba/CMakeLists.txt" ]; then
    echo "mGBA submodule missing - run: git submodule update --init third_party/mgba" >&2
    exit 1
fi

if [ ! -f "$root/third_party/rcheevos/include/rc_client.h" ]; then
    echo "rcheevos submodule missing - run: git submodule update --init third_party/rcheevos" >&2
    exit 1
fi

for slice in "ios-arm64 iphoneos" "ios-arm64-simulator iphonesimulator"; do
    set -- $slice
    name=$1 sdk=$2
    build="$out/cmake-$name"
    cmake -S "$here" -B "$build" -G "Unix Makefiles" \
        -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$sdk" -DCMAKE_OSX_ARCHITECTURES=arm64 \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 >/dev/null
    cmake --build "$build" --target pk_api mgba pk_ra_client -j "$jobs"
    mkdir -p "$out/$name"
    # One archive, so Kotlin and Xcode link a single library.
    libtool -static -o "$out/$name/libpkcore.a" "$build/libpk_api.a" "$build/mgba/libmgba.a" \
        "$build/libpk_ra_client.a" "$build/libpk_ra_requests.a" "$build/librcheevos.a"
done

mkdir -p "$out/include"
cp "$root/shared/src/commonMain/cpp/pk_api.h" "$root/shared/src/commonMain/cpp/pk_link_api.h" \
    "$root/shared/src/commonMain/cpp/pk_ra_client.h" "$root/shared/src/commonMain/cpp/pk_coop_api.h" "$out/include/"
rm -rf "$out/PkCore.xcframework"
xcodebuild -create-xcframework \
    -library "$out/ios-arm64/libpkcore.a" -headers "$out/include" \
    -library "$out/ios-arm64-simulator/libpkcore.a" -headers "$out/include" \
    -output "$out/PkCore.xcframework" >/dev/null
echo "pk_api for iOS: $out"
