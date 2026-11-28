#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
classes="$project_dir/app/build/native/host-boot"
tests="$project_dir/app/build/syslinux-tests"
mkdir -p "$tests"
"$JAVA_HOME/bin/javac" -d "$classes" \
    "$project_dir/app/src/main/java/pl/rufusportable/BlockDevice.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/DiskLayout.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/MbrLayout.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/IsoTree.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/FatVolume.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/SyslinuxPatch.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/RufusSignature.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/SyslinuxVolume.java" \
    "$project_dir/app/src/test/native/SyslinuxVolumeTest.java"
"$JAVA_HOME/bin/java" -Xcheck:jni -Xmx256m -Djava.library.path="$classes" -cp "$classes" \
    pl.rufusportable.SyslinuxVolumeTest "$project_dir/app/src/main/assets/syslinux" "$tests"
"${FAT_TEST_PYTHON:-python3}" "$project_dir/scripts/test-syslinux-volume.py" "$tests"
