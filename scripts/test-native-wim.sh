#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
source_dir="$project_dir/app/build/native/source/wimlib"
build_dir="$project_dir/app/build/native/host-wim"
test_dir="$build_dir/tests"
bridge_dir="$project_dir/app/src/main/cpp"
mkdir -p "$test_dir"
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
(
    cd "$build_dir"
    CC="${HOST_CC:-gcc}" CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections" \
        "$source_dir/configure" --without-ntfs-3g --without-fuse --enable-static --disable-shared
    make -j"${BUILD_JOBS:-2}" libwim.la wimlib-imagex
)
"${HOST_CC:-gcc}" -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -shared -std=gnu11 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections \
    -O2 -Wall -Wextra -Werror -I "$build_dir" -I "$source_dir/include" \
    -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
    "$bridge_dir/wim_jni.c" "$bridge_dir/wim_glob.c" "$bridge_dir/unattend.c" "$build_dir/.libs/libwim.a" \
    -Wl,--gc-sections -Wl,--no-undefined -pthread -o "$test_dir/librufus_wim.so"
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
