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
for source_patch in "$project_dir/scripts/patches/ntfs-3g/"*.patch; do
    if ! patch --directory="$source_dir" --strip=1 --reverse --dry-run --silent --force < "$source_patch" >/dev/null 2>&1; then
        patch --directory="$source_dir" --strip=1 --forward --batch < "$source_patch"
    fi
done
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
        NM="$toolchain/llvm-nm" STRIP="$toolchain/llvm-strip" CFLAGS="-std=gnu11 -O2 -fPIC" \
            "$source_dir/configure" --host="$target" --disable-ntfs-3g --disable-ntfsprogs \
            --disable-crypto --disable-device-default-io-ops --enable-static --disable-shared
        make -j"${BUILD_JOBS:-2}" -C libntfs-3g
        output_dir="$native_root/jniLibs/$abi"
        mkdir -p "$output_dir"
        "$toolchain/$compiler" -shared -std=gnu11 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections -O2 -Wall -Wextra -Werror -Wno-address-of-packed-member -Wno-unused-but-set-variable \
            -I "$build_dir" -I "$source_dir/include/ntfs-3g" -I "$source_dir/ntfsprogs" \
            -include "$build_dir/config.h" \
            "$project_dir/app/src/main/cpp/ntfs_bridge.c" \
            "$project_dir/app/src/main/cpp/ntfs_jni.c" \
            "$project_dir/app/src/main/cpp/ntfs_format.c" \
            "$source_dir/ntfsprogs/attrdef.c" "$source_dir/ntfsprogs/boot.c" \
            "$source_dir/ntfsprogs/sd.c" "$source_dir/ntfsprogs/utils.c" \
            "$build_dir/libntfs-3g/.libs/libntfs-3g.a" \
            -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--no-undefined -Wl,--gc-sections -pthread \
            -o "$output_dir/librufus_ntfs.so"
    )
done
