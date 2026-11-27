#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
source_dir="$project_dir/app/build/native/source/wimlib"
build_dir="$project_dir/app/build/native/host-wim"
test_dir="$build_dir/tests"
bridge_dir="$project_dir/app/src/main/cpp"
mkdir -p "$test_dir"
bash "$project_dir/scripts/test-native-ntfs.sh"
ntfs_source="$project_dir/app/build/native/source/ntfs-3g"
ntfs_build="$project_dir/app/build/native/host"
ntfs_library="$ntfs_build/libntfs-3g/.libs/libntfs-3g.a"
for name in ntfs_bridge wim_ntfs; do
    "${HOST_CC:-gcc}" -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -std=gnu11 -fPIC -fvisibility=hidden -O2 \
        -Wall -Wextra -Werror -Wno-address-of-packed-member -Wno-unused-but-set-variable \
        -I "$ntfs_build" -I "$ntfs_source/include/ntfs-3g" -I "$bridge_dir" \
        -c "$bridge_dir/$name.c" -o "$build_dir/$name.o"
done
ar rcs "$build_dir/librufus_wim_ntfs.a" "$build_dir/ntfs_bridge.o" "$build_dir/wim_ntfs.o"
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
(
    cd "$build_dir"
    LIBNTFS_3G_CFLAGS="-I$ntfs_source/include" LIBNTFS_3G_LIBS="$build_dir/librufus_wim_ntfs.a $ntfs_library" \
    CC="${HOST_CC:-gcc}" CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections" \
        "$source_dir/configure" --with-ntfs-3g --without-fuse --enable-static --disable-shared
    make -j"${BUILD_JOBS:-2}" libwim.la
    make -j"${BUILD_JOBS:-2}" wimlib-imagex LIBS="$build_dir/librufus_wim_ntfs.a $ntfs_library"
)
"${HOST_CC:-gcc}" -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -shared -std=gnu11 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections \
    -O2 -Wall -Wextra -Werror -I "$build_dir" -I "$source_dir/include" \
    -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    "$bridge_dir/wim_jni.c" "$bridge_dir/wim_glob.c" "$bridge_dir/unattend.c" "$build_dir/.libs/libwim.a" "$build_dir/librufus_wim_ntfs.a" "$ntfs_library" \
    -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--no-undefined -pthread -o "$test_dir/librufus_wim.so"
"${HOST_CC:-gcc}" -std=gnu11 -Wall -Wextra -Werror -I "$bridge_dir" \
    "$bridge_dir/wim_glob.c" "$project_dir/app/src/test/native/WimGlobTest.c" \
    -o "$test_dir/wim-glob-test"
mkdir -p "$test_dir/fixture"
printf 'A small original WIM test fixture.\n' > "$test_dir/fixture/readme.txt"
fixture="$test_dir/metadata.wim"
rm -f "$fixture"
"$build_dir/wimlib-imagex" capture "$test_dir/fixture" "$fixture" 'Windows Test żółć' 'First test image'
"$build_dir/wimlib-imagex" append "$test_dir/fixture" "$fixture" 'Windows Test 😀' 'Second test image'
"$build_dir/wimlib-imagex" info "$fixture" 1 --image-property=WINDOWS/ARCH=9 \
    --image-property=WINDOWS/EDITIONID=Professional --image-property=WINDOWS/VERSION/BUILD=26100 \
    --image-property=WINDOWS/LANGUAGES/LANGUAGE=en-US \
    --image-property=DISPLAYNAME='Windows Test Pro' --image-property=WINDOWS/VERSION/MAJOR=10
"$build_dir/wimlib-imagex" info "$fixture" 2 --image-property=WINDOWS/ARCH=12
"$JAVA_HOME/bin/javac" -d "$test_dir" "$project_dir/app/src/main/java/pl/rufusportable/WimMetadata.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/BlockDevice.java" \
    "$project_dir/app/src/test/native/WimMetadataTest.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/WindowsUnattend.java" \
    "$project_dir/app/src/test/native/WindowsUnattendTest.java"
rm -f "$test_dir/pipable.wim"
"$build_dir/wimlib-imagex" export "$fixture" all "$test_dir/pipable.wim" --pipable
"$JAVA_HOME/bin/java" -Xcheck:jni -Djava.library.path="$test_dir" -cp "$test_dir" \
    pl.rufusportable.WimMetadataTest "$fixture" "$test_dir/pipable.wim"
"$test_dir/wim-glob-test" "$test_dir"

"$JAVA_HOME/bin/java" -Xcheck:jni -Djava.library.path="$test_dir" -cp "$test_dir" pl.rufusportable.WindowsUnattendTest

bash "$project_dir/scripts/test-unattend-reference.sh"

# Rich original fixture: hard links, Unicode, reparse data, ACLs and an ADS.
rich="$test_dir/apply-source"
mkdir -p "$rich/rich" "$rich/żółć"
python3 - "$rich" <<'PYFIXTURE'
import pathlib,random,sys,os
root=pathlib.Path(sys.argv[1])
(root/'rich/data.bin').write_bytes(random.Random(32).randbytes(170003))
(root/'rich/alias.bin').unlink(missing_ok=True)
os.link(root/'rich/data.bin',root/'rich/alias.bin')
(root/'żółć/日本語.txt').write_text('Original żółć 日本語 😀 data.\n',encoding='utf-8')
(root/'empty.txt').write_bytes(b'')
(root/'unicode-link').unlink(missing_ok=True)
os.symlink('żółć/日本語.txt',root/'unicode-link')
PYFIXTURE
rm -f "$test_dir/apply.wim"
"$build_dir/wimlib-imagex" capture "$rich" "$test_dir/apply.wim" 'Original apply fixture' --unix-data
"${HOST_CC:-gcc}" -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -std=gnu11 -O2 \
    -I "$build_dir" -I "$source_dir/include" "$project_dir/app/src/test/native/WimApplyFixture.c" \
    "$build_dir/.libs/libwim.a" "$build_dir/librufus_wim_ntfs.a" "$ntfs_library" -pthread -o "$test_dir/wim-apply-fixture"
"$test_dir/wim-apply-fixture" "$test_dir/apply.wim" "$project_dir/third_party/wimlib/tests/security_descriptor_1.bin"
"$JAVA_HOME/bin/javac" -d "$test_dir" "$project_dir/app/src/main/java/pl/rufusportable/BlockDevice.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/NtfsVolume.java" "$project_dir/app/src/test/native/WimApplyTest.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/WimMetadata.java"
"$JAVA_HOME/bin/java" -Xcheck:jni -Djava.library.path="$test_dir:$ntfs_build/tests" -cp "$test_dir" \
    pl.rufusportable.WimApplyTest "$test_dir/apply.wim" "$test_dir/apply.img" "$rich"
"${NTFSINFO:-ntfsinfo}" -F /rich/data.bin "$test_dir/apply.img" > "$test_dir/apply-data-info.txt"
"${NTFSINFO:-ntfsinfo}" -F /rich/alias.bin "$test_dir/apply.img" > "$test_dir/apply-alias-info.txt"
"${NTFSCAT:-ntfscat}" -n RufusTest "$test_dir/apply.img" /rich/data.bin > "$test_dir/apply-unix-ads.bin"
python3 - "$test_dir" <<'PYCHECK'
import pathlib,re,sys
root=pathlib.Path(sys.argv[1])
a=re.search(r'Dumping Inode (\d+)',(root/'apply-data-info.txt').read_text())
b=re.search(r'Dumping Inode (\d+)',(root/'apply-alias-info.txt').read_text())
assert a and b and a[1]==b[1], 'WIM hard link identity was not preserved'
assert (root/'apply-unix-ads.bin').read_bytes()==b'Original alternate data stream.\n', 'WIM named data stream changed'
print('Independent NTFS hard-link identity and named data stream checks passed')
PYCHECK

"${HOST_CC:-gcc}" -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -std=gnu11 -O2 \
    -I "$ntfs_build" -I "$ntfs_source/include/ntfs-3g" -I "$bridge_dir" \
    "$project_dir/app/src/test/native/WimApplyNtfsTest.c" "$build_dir/librufus_wim_ntfs.a" "$ntfs_library" \
    -pthread -o "$test_dir/wim-apply-ntfs-test"
"$test_dir/wim-apply-ntfs-test" "$test_dir/apply.img" "$project_dir/third_party/wimlib/tests/security_descriptor_1.bin"
