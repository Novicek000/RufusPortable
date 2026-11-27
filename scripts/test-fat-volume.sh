#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
build_dir="${FAT_TEST_DIR:-$project_dir/app/build/fat-tests}"
mkdir -p "$build_dir/classes"
"$JAVA_HOME/bin/javac" -d "$build_dir/classes" \
    "$project_dir/app/src/main/java/pl/rufusportable/BlockDevice.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/IsoTree.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/FatVolume.java" \
    "$project_dir/app/src/test/native/FatVolumeTest.java"
"$JAVA_HOME/bin/java" -Xmx256m -cp "$build_dir/classes" pl.rufusportable.FatVolumeTest "$build_dir"
"${FAT_TEST_PYTHON:-python3}" "$project_dir/scripts/verify-fat-volume.py" "$build_dir"
