#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
source_dir="$project_dir/third_party/hivex/lib"
bridge_dir="$project_dir/app/src/main/cpp"
build_dir="$project_dir/app/build/native/host-hive"
mkdir -p "$build_dir"
objects=()
for name in handle node offset-list utf16 util value visit write hive_iconv hive_jni; do
    input="$source_dir/$name.c"
    if [[ "$name" == hive_* ]]; then input="$bridge_dir/$name.c"; fi
    "${HOST_CC:-gcc}" -D_GNU_SOURCE -D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64 -std=gnu11 \
        -O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections \
        -Wall -Wextra -Werror -Wno-unused-parameter \
        -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" \
        -I "$bridge_dir/hive_compat" -I "$source_dir" -c "$input" -o "$build_dir/$name.o"
    objects+=("$build_dir/$name.o")
done
"${HOST_CC:-gcc}" -shared "${objects[@]}" -Wl,--no-undefined -Wl,--gc-sections -pthread -o "$build_dir/librufus_hive.so"
"${HOST_CC:-gcc}" -std=gnu11 -O2 -Wall -Wextra -Werror \
    "$project_dir/app/src/test/native/HiveIconvTest.c" "$bridge_dir/hive_iconv.c" -o "$build_dir/hive-iconv-test"
"$build_dir/hive-iconv-test"
"$JAVA_HOME/bin/javac" -d "$build_dir" "$project_dir/app/src/main/java/pl/rufusportable/RegistryHive.java" \
    "$project_dir/app/src/test/native/RegistryHiveTest.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/DiskLayout.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/WindowsToGoLayout.java" \
    "$project_dir/app/src/main/java/pl/rufusportable/WindowsBootConfiguration.java" \
    "$project_dir/app/src/test/native/WindowsBootConfigurationTest.java"
"$JAVA_HOME/bin/java" -Xcheck:jni -Djava.library.path="$build_dir" -cp "$build_dir" \
    pl.rufusportable.RegistryHiveTest "$project_dir/third_party/hivex/images/minimal" "$build_dir/tests"
"$JAVA_HOME/bin/java" -Xcheck:jni -Djava.library.path="$build_dir" -cp "$build_dir" \
    pl.rufusportable.WindowsBootConfigurationTest "$project_dir/third_party/hivex/images/minimal" "$build_dir/tests"
