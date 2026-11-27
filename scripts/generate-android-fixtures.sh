#!/usr/bin/env bash
# Only original, self-generated dummy data. This ISO cannot boot an operating system.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
output_dir="${1:-$project_dir/app/build/native/android-fixtures}"
wim_tool="${WIMLIB_IMAGEX:-$project_dir/app/build/native/host-wim/wimlib-imagex}"
iso_tool="${GENISOIMAGE:-genisoimage}"
mkdir -p "$output_dir/capture" "$output_dir/iso/sources" "$output_dir/iso/efi/boot"
printf 'A small original WIM test fixture.\n' > "$output_dir/capture/readme.txt"
metadata="$output_dir/metadata.wim"
rm -f "$metadata"
"$wim_tool" capture "$output_dir/capture" "$metadata" 'Windows Test żółć' 'First test image'
"$wim_tool" append "$output_dir/capture" "$metadata" 'Windows Test 😀' 'Second test image'
"$wim_tool" info "$metadata" 1 --image-property=WINDOWS/ARCH=9 \
    --image-property=WINDOWS/EDITIONID=Professional --image-property=WINDOWS/VERSION/BUILD=26100 \
    --image-property=WINDOWS/LANGUAGES/LANGUAGE=en-US
"$wim_tool" info "$metadata" 2 --image-property=WINDOWS/ARCH=12 \
    --image-property=WINDOWS/EDITIONID=Professional --image-property=WINDOWS/VERSION/BUILD=26100 \
    --image-property=WINDOWS/LANGUAGES/LANGUAGE=en-US
cp "$metadata" "$output_dir/iso/sources/boot.wim"
cp "$metadata" "$output_dir/iso/sources/install.wim"
printf 'Original dummy boot manager.\n' > "$output_dir/iso/bootmgr"
printf 'Original dummy appraiser.\n' > "$output_dir/iso/sources/appraiserres.dll"
printf 'Original dummy EFI image.\n' > "$output_dir/iso/efi/boot/bootaa64.efi"
python3 - "$output_dir/iso/setup.exe" <<'PY'
import pathlib,sys
payload=bytearray(256)
payload[:2]=b'MZ';payload[60]=64;payload[64:70]=b'PE\0\0\x64\xaa'
pathlib.Path(sys.argv[1]).write_bytes(payload)
PY
"$iso_tool" -quiet -R -J -V RUFUSTEST -o "$output_dir/windows-test.iso" "$output_dir/iso"
printf 'Generated fixtures in %s\n' "$output_dir"
