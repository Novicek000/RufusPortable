#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27c}"
native_root="$project_dir/app/build/native"
source_dir="$project_dir/third_party/hivex/lib"
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
    build_dir="$native_root/$abi/hive"
    output_dir="$native_root/jniLibs/$abi"
    mkdir -p "$build_dir" "$output_dir"
    objects=()
    for name in handle node offset-list utf16 util value visit write hive_iconv hive_jni; do
        input="$source_dir/$name.c"
        if [[ "$name" == hive_* ]]; then input="$bridge_dir/$name.c"; fi
        "$toolchain/$compiler" -D_GNU_SOURCE -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 -std=gnu11 \
            -O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections \
            -Wall -Wextra -Werror -Wno-unused-parameter \
            -I "$bridge_dir/hive_compat" -I "$source_dir" -c "$input" -o "$build_dir/$name.o"
        objects+=("$build_dir/$name.o")
    done
    "$toolchain/$compiler" -shared "${objects[@]}" -Wl,-z,max-page-size=16384 \
        -Wl,--no-undefined -Wl,--gc-sections -pthread -o "$output_dir/librufus_hive.so"
done
