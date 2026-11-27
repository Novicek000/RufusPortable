#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$project_dir/app/build/native/unattend-reference"
mkdir -p "$test_dir"
source_file="$test_dir/wue.c"
if [[ -n "${RUFUS_REFERENCE_DIR:-}" ]]; then
    cp "$RUFUS_REFERENCE_DIR/src/wue.c" "$source_file"
elif [[ -f "$project_dir/third_party/rufus/src/wue.c" ]]; then
    cp "$project_dir/third_party/rufus/src/wue.c" "$source_file"
elif [[ ! -f "$source_file" ]]; then
    curl -fsSL 'https://raw.githubusercontent.com/pbatard/rufus/8337c3e7e534b5d5b5045dd8ecb9a7ea60e9232c/src/wue.c' -o "$source_file"
fi
printf 'e62eaaacf1cb0347bea7b49e981f1f0e3b63c0890bed3866251af24b913c849c  %s\n' "$source_file" | sha256sum -c -
python3 - "$source_file" "$test_dir/unattend_reference.inc" <<'PY'
import pathlib,sys
source=pathlib.Path(sys.argv[1]).read_text()
start=source.index('char* CreateUnattendXml(')
end=source.index('\n/// <summary>',start)
pathlib.Path(sys.argv[2]).write_text(source[start:end])
PY
"${HOST_CC:-gcc}" -std=gnu11 -O2 -Wall -Wextra -Werror -Wno-old-style-declaration \
    -I "$project_dir/app/src/main/cpp" -I "$test_dir" \
    "$project_dir/app/src/main/cpp/unattend.c" \
    "$project_dir/app/src/test/native/UnattendReferenceTest.c" -o "$test_dir/reference-test"
"$test_dir/reference-test" "$test_dir"
