#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
source_dir="$project_dir/third_party/rufus/src/syslinux/libinstaller"
bridge_dir="$project_dir/app/src/main/cpp"
build_dir="$project_dir/app/build/native/host-boot"
mkdir -p "$build_dir"
sources=("$bridge_dir/syslinux_bridge.c" "$source_dir/syslxmod.c" "$source_dir/fs.c" "$source_dir/setadv.c")
options=(-D_GNU_SOURCE -std=gnu11 -O2 -fPIC -fvisibility=hidden -Wall -Wextra -Werror
    -Wno-address-of-packed-member -Wno-sign-compare -I "$source_dir" -I "$bridge_dir")
"${HOST_CC:-gcc}" "${options[@]}" "${sources[@]}" "$project_dir/app/src/test/native/SyslinuxNativeTest.c" -pthread -o "$build_dir/syslinux-test"
"$build_dir/syslinux-test" "$project_dir/app/src/main/assets/syslinux"
"${HOST_CC:-gcc}" "${options[@]}" -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize=alignment \
    "${sources[@]}" "$project_dir/app/src/test/native/SyslinuxNativeTest.c" -pthread -o "$build_dir/syslinux-sanitizer-test"
ASAN_OPTIONS="${BOOT_ASAN_OPTIONS:-detect_leaks=1}" UBSAN_OPTIONS=halt_on_error=1 "$build_dir/syslinux-sanitizer-test" "$project_dir/app/src/main/assets/syslinux"
"${HOST_CC:-gcc}" "${options[@]}" -shared -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    "${sources[@]}" "$bridge_dir/boot_jni.c" -Wl,--no-undefined -pthread -o "$build_dir/librufus_boot.so"
"$JAVA_HOME/bin/javac" -d "$build_dir" "$project_dir/app/src/main/java/pl/rufusportable/SyslinuxPatch.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/RufusSignature.java" "$project_dir/app/src/test/native/SyslinuxJavaTest.java"
"$JAVA_HOME/bin/java" -Xcheck:jni -Djava.library.path="$build_dir" -cp "$build_dir" \
    pl.rufusportable.SyslinuxJavaTest "$project_dir/app/src/main/assets/syslinux"
