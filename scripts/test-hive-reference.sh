#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
native_root="$project_dir/app/build/native"
if [[ ! -f "$native_root/host-wim/.libs/libwim.a" ]]; then
    echo 'Build the independent reference parser with scripts/test-native-wim.sh first.' >&2
    exit 1
fi
"${HOST_CC:-gcc}" -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 -std=gnu11 -O2 -Wall -Wextra -Werror \
    -I "$native_root/host-wim" -I "$native_root/source/wimlib/include" \
    "$project_dir/app/src/test/native/HiveReferenceTest.c" \
    "$native_root/host-wim/.libs/libwim.a" "$native_root/host-wim/librufus_wim_ntfs.a" \
    "$native_root/host/libntfs-3g/.libs/libntfs-3g.a" -pthread -o "$native_root/host-hive/hive-reference-test"
"$native_root/host-hive/hive-reference-test" "$native_root/host-hive/tests/registry-updated.hive"
