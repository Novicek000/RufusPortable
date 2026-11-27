#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
native_root="$project_dir/app/build/native"
source_dir="$native_root/source/ntfs-3g"
build_dir="$native_root/host"
test_dir="$build_dir/tests"
mkdir -p "$test_dir"
bash "$project_dir/scripts/build-host-ntfs.sh"
bridge_dir="$project_dir/app/src/main/cpp"
tests_dir="$project_dir/app/src/test/native"
library="$build_dir/libntfs-3g/.libs/libntfs-3g.a"
includes=(-I "$build_dir" -I "$source_dir/include/ntfs-3g" -I "$source_dir/ntfsprogs" -I "$bridge_dir"
    -include "$build_dir/config.h")
format_sources=("$bridge_dir/ntfs_format.c" "$source_dir/ntfsprogs/attrdef.c"
    "$source_dir/ntfsprogs/boot.c" "$source_dir/ntfsprogs/sd.c" "$source_dir/ntfsprogs/utils.c")
compile_options=(-std=gnu11 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections -O2
    -Wall -Wextra -Werror -Wno-address-of-packed-member -Wno-unused-but-set-variable)
link_options=(-Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--no-undefined -pthread)
"${HOST_CC:-gcc}" "${compile_options[@]}" "${includes[@]}" \
    "$bridge_dir/ntfs_bridge.c" "$tests_dir/ntfs_bridge_test.c" "${format_sources[@]}" "$library" "${link_options[@]}" \
    -o "$test_dir/ntfs-bridge-test"
"${HOST_CC:-gcc}" -shared -fPIC "${compile_options[@]}" "${includes[@]}" \
    -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    "$bridge_dir/ntfs_bridge.c" "$bridge_dir/ntfs_jni.c" "${format_sources[@]}" "$library" \
    "${link_options[@]}" -o "$test_dir/librufus_ntfs.so"
"$JAVA_HOME/bin/javac" -d "$test_dir" \
    "$project_dir/app/src/main/java/pl/rufusportable/BlockDevice.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/NtfsVolume.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/IsoTree.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/WindowsLayout.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/DiskLayout.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/WindowsInstaller.java" \
    "$tests_dir/NtfsJavaTest.java" "$tests_dir/WindowsInstallerTest.java"
for name in c jni; do
    truncate -s 0 "$test_dir/$name.img"
    truncate -s 256M "$test_dir/$name.img"
    "${MKNTFS:-mkntfs}" -FQ "$test_dir/$name.img"
done
"$test_dir/ntfs-bridge-test" "$test_dir/c.img"
"$JAVA_HOME/bin/java" -Xcheck:jni -Djava.library.path="$test_dir" \
    -cp "$test_dir" pl.rufusportable.NtfsJavaTest "$test_dir/jni.img"
"$JAVA_HOME/bin/java" -Xcheck:jni -Djava.library.path="$test_dir" \
    -cp "$test_dir" pl.rufusportable.WindowsInstallerTest "$test_dir/windows.img" \
    "$project_dir/app/src/main/assets/rufus"
"${NTFSCAT:-ntfscat}" "$test_dir/c.img" /sources/test.bin > "$test_dir/c.bin"
"${NTFSCAT:-ntfscat}" "$test_dir/jni.img" /efi/boot/payload.bin > "$test_dir/jni.bin"
"${NTFSCAT:-ntfscat}" "$test_dir/jni.img" /unfinished.bin > "$test_dir/closed.bin"
"${NTFSCAT:-ntfscat}" "$test_dir/jni.img" /random.bin > "$test_dir/random.bin"
python3 - "$test_dir" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
assert (root / "c.bin").read_bytes() == bytes((i * 37 + 11) % 256 for i in range(131072)) * 5
expected = bytes((i * 17 + 9) % 256 for i in range(170001))[5:169998]
assert (root / "jni.bin").read_bytes() == expected
assert (root / "closed.bin").read_bytes() == bytes([2, 4, 6, 8])
random = bytes(103) + bytes(((i+3)*13+7)%256 for i in range(70001-103)) + bytes(140003-70001)
assert (root / "random.bin").read_bytes() == random
print("Independent NTFS readback checks passed")
PY
