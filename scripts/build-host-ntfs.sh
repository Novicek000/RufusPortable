#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
native_root="$project_dir/app/build/native"
source_dir="$native_root/source/ntfs-3g"
build_dir="$native_root/host"
mkdir -p "$build_dir"
if [[ ! -f "$source_dir/configure" ]]; then
    mkdir -p "$source_dir"
    cp -R "$project_dir/third_party/ntfs-3g/." "$source_dir/"
    (cd "$source_dir" && autoreconf -fi)
fi
for source_patch in "$project_dir/scripts/patches/ntfs-3g/"*.patch; do
    if ! patch --directory="$source_dir" --strip=1 --reverse --dry-run --silent --force < "$source_patch" >/dev/null 2>&1; then
        patch --directory="$source_dir" --strip=1 --forward --batch < "$source_patch"
    fi
done
(
    cd "$build_dir"
    CC="${HOST_CC:-gcc}" CFLAGS="-std=gnu11 -O2 -fPIC" "$source_dir/configure" \
        --disable-ntfs-3g --disable-ntfsprogs --disable-crypto \
        --disable-device-default-io-ops --enable-static --disable-shared
    make -j"${BUILD_JOBS:-2}" -C libntfs-3g
)
