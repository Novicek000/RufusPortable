#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
native_dir="$project_dir/app/build/native"
classes="$native_dir/host-hive"
tests="$native_dir/wintogo-tests"
mkdir -p "$tests"
"$JAVA_HOME/bin/javac" -cp "$classes:$native_dir/host/tests" -d "$classes" \
    "$project_dir/app/src/main/java/pl/rufusportable/FatVolume.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/NtfsVolume.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/WindowsToGoBootFiles.java" \
    "$project_dir/app/src/test/native/WindowsToGoBootFilesTest.java"
"$JAVA_HOME/bin/java" -Xcheck:jni -Xmx256m -Djava.library.path="$classes:$native_dir/host/tests" \
    -cp "$classes:$native_dir/host/tests" pl.rufusportable.WindowsToGoBootFilesTest \
    "$project_dir/third_party/hivex/images/minimal" "$tests"
for sector in 512 4096; do
    "${FSCK_FAT:-fsck.fat}" -n "$tests/gpt-$sector-esp.img"
done
"${FAT_TEST_PYTHON:-python3}" - "$tests" <<'PY'
import pathlib, sys
from pyfatfs.PyFatFS import PyFatFS
root = pathlib.Path(sys.argv[1])
for sector in (512, 4096):
    with PyFatFS(str(root / f'gpt-{sector}-esp.img'), read_only=True) as fs:
        for expected in (root / 'expected').rglob('*'):
            if expected.is_file():
                with fs.openbin(str(expected.relative_to(root / 'expected'))) as data:
                    assert data.read() == expected.read_bytes(), expected
        with fs.openbin('EFI/Microsoft/Boot/BCD') as data:
            bcd = data.read()
            assert bcd[:4] == b'regf'
            (root / f'gpt-{sector}-BCD.hive').write_bytes(bcd)
print('Independent FAT parser verified EFI fallback, CA2023 files, fonts, resources and BCD presence')
PY
