#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27c}"
source_dir="$project_dir/third_party/rufus/src/syslinux/libinstaller"
bridge_dir="$project_dir/app/src/main/cpp"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
for abi in "${@:-arm64-v8a}"; do
    case "$abi" in
        arm64-v8a) compiler=aarch64-linux-android26-clang ;;
        armeabi-v7a) compiler=armv7a-linux-androideabi26-clang ;;
        x86_64) compiler=x86_64-linux-android26-clang ;;
        x86) compiler=i686-linux-android26-clang ;;
        *) echo "Unsupported Android ABI $abi" >&2; exit 1 ;;
    esac
    output="$project_dir/app/build/native/jniLibs/$abi"
    mkdir -p "$output"
    "$toolchain/$compiler" -D_GNU_SOURCE -std=gnu11 -O2 -fPIC -fvisibility=hidden \
        -Wall -Wextra -Werror -Wno-address-of-packed-member -Wno-sign-compare \
        -ffunction-sections -fdata-sections -I "$source_dir" -I "$bridge_dir" \
        "$bridge_dir/syslinux_bridge.c" "$bridge_dir/boot_jni.c" \
        "$source_dir/syslxmod.c" "$source_dir/fs.c" "$source_dir/setadv.c" \
        -shared -Wl,--no-undefined -Wl,--gc-sections -Wl,-z,max-page-size=16384 -pthread -o "$output/librufus_boot.so"
done
