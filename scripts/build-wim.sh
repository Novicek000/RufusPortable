#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27c}"
native_root="$project_dir/app/build/native"
source_dir="$native_root/source/wimlib"
bridge_dir="$project_dir/app/src/main/cpp"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -f "$source_dir/configure" ]]; then
    mkdir -p "$source_dir"
    cp -R "$project_dir/third_party/wimlib/." "$source_dir/"
    (cd "$source_dir" && autoreconf -fi)
fi
for source_patch in "$project_dir/scripts/patches/wimlib/"*.patch; do
    if ! patch --directory="$source_dir" --strip=1 --reverse --dry-run --silent --force < "$source_patch" >/dev/null 2>&1; then
        patch --directory="$source_dir" --strip=1 --forward --batch < "$source_patch"
    fi
done
cp "$bridge_dir/wim_glob.h" "$source_dir/include/wimlib/rufus_glob.h"
cp "$bridge_dir/wim_proxy.h" "$source_dir/include/wimlib/rufus_proxy.h"
cp "$bridge_dir/wim_ntfs.h" "$source_dir/include/wimlib/rufus_ntfs.h"
cp "$bridge_dir/ntfs_bridge.h" "$source_dir/include/wimlib/ntfs_bridge.h"
for abi in "${@:-arm64-v8a}"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android; compiler=aarch64-linux-android26-clang ;;
        armeabi-v7a) target=arm-linux-androideabi; compiler=armv7a-linux-androideabi26-clang ;;
        x86_64) target=x86_64-linux-android; compiler=x86_64-linux-android26-clang ;;
        x86) target=i686-linux-android; compiler=i686-linux-android26-clang ;;
        *) echo "Unsupported Android ABI $abi" >&2; exit 1 ;;
    esac
    build_dir="$native_root/$abi/wim"
    mkdir -p "$build_dir"
    ntfs_source="$native_root/source/ntfs-3g"
    ntfs_build="$native_root/$abi/ntfs"
    ntfs_library="$ntfs_build/libntfs-3g/.libs/libntfs-3g.a"
    if [[ ! -f "$ntfs_library" ]]; then bash "$project_dir/scripts/build-ntfs.sh" "$abi"; fi
    for name in ntfs_bridge wim_ntfs; do
        "$toolchain/$compiler" -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -std=gnu11 -fPIC -fvisibility=hidden -O2 \
            -Wall -Wextra -Werror -Wno-address-of-packed-member -Wno-unused-but-set-variable \
            -I "$ntfs_build" -I "$ntfs_source/include/ntfs-3g" -I "$bridge_dir" \
            -c "$bridge_dir/$name.c" -o "$build_dir/$name.o"
    done
    "$toolchain/llvm-ar" rcs "$build_dir/librufus_wim_ntfs.a" "$build_dir/ntfs_bridge.o" "$build_dir/wim_ntfs.o"
    (
        cd "$build_dir"
        LIBNTFS_3G_CFLAGS="-I$ntfs_source/include -DHAVE_FFS=1" LIBNTFS_3G_LIBS="$build_dir/librufus_wim_ntfs.a $ntfs_library" \
        CC="$toolchain/$compiler" AR="$toolchain/llvm-ar" RANLIB="$toolchain/llvm-ranlib" \
        NM="$toolchain/llvm-nm" STRIP="$toolchain/llvm-strip" \
        CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections" \
            "$source_dir/configure" --host="$target" --with-ntfs-3g --without-fuse \
            --enable-static --disable-shared
        make -j"${BUILD_JOBS:-2}" libwim.la
        output_dir="$native_root/jniLibs/$abi"
        mkdir -p "$output_dir"
        "$toolchain/$compiler" -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -shared -std=gnu11 -fPIC -fvisibility=hidden \
            -ffunction-sections -fdata-sections -O2 -Wall -Wextra -Werror \
            -I "$build_dir" -I "$source_dir/include" \
            "$bridge_dir/wim_jni.c" "$bridge_dir/wim_glob.c" "$bridge_dir/unattend.c" ".libs/libwim.a" "$build_dir/librufus_wim_ntfs.a" "$ntfs_library" \
            -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--no-undefined -pthread \
            -o "$output_dir/librufus_wim.so"
    )
done
