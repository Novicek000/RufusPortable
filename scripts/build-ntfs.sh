#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27c}"
native_root="$project_dir/app/build/native"
source_dir="$native_root/source/ntfs-3g"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$native_root/source"
if [[ ! -f "$source_dir/configure" ]]; then
    mkdir -p "$source_dir"
    cp -R "$project_dir/third_party/ntfs-3g/." "$source_dir/"
    (cd "$source_dir" && autoreconf -fi)
fi
for abi in "${@:-arm64-v8a}"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android; compiler=aarch64-linux-android26-clang ;;
        armeabi-v7a) target=arm-linux-androideabi; compiler=armv7a-linux-androideabi26-clang ;;
        x86_64) target=x86_64-linux-android; compiler=x86_64-linux-android26-clang ;;
        x86) target=i686-linux-android; compiler=i686-linux-android26-clang ;;
        *) echo "Unsupported Android ABI $abi" >&2; exit 1 ;;
    esac
    build_dir="$native_root/$abi/ntfs"
    mkdir -p "$build_dir"
    (
        cd "$build_dir"
        CC="$toolchain/$compiler" AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib" \
        NM="$toolchain/llvm-nm" STRIP="$toolchain/llvm-strip" CFLAGS="-O2 -fPIC" \
            "$source_dir/configure" --host="$target" --disable-ntfs-3g --disable-ntfsprogs \
            --disable-crypto --disable-device-default-io-ops --enable-static --disable-shared
        make -j"${BUILD_JOBS:-2}" -C libntfs-3g
    )
done
