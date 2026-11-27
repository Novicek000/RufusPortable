#!/usr/bin/env python3
"""Independent FAT metadata checks and two filesystem implementations reading generated data."""
import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
from pyfatfs.PyFat import PyFat

root = pathlib.Path(sys.argv[1])
expected = {path.relative_to(root / "expected").as_posix(): hashlib.sha256(path.read_bytes()).digest()
            for path in (root / "expected").rglob("*") if path.is_file()}
for image in sorted(root.glob("fat*-*.img")):
    result = subprocess.run([os.environ.get("FSCK_FAT", "fsck.fat"), "-n", "-v", str(image)], capture_output=True, text=True)
    (root / (image.stem + "-fsck.log")).write_text(result.stdout + result.stderr)
    assert result.returncode == 0, result.stdout + result.stderr
    fs = PyFat(lazy_load=False)
    fs.open(str(image), read_only=True)
    try:
        actual = {}
        for _, _, files in fs.root_dir.walk():
            for entry in files:
                contents = b"".join(fs.read_cluster_contents(cluster) for cluster in fs.get_cluster_chain(entry.get_cluster())) if entry.get_cluster() else b""
                actual[entry.get_full_path().lstrip("/")] = hashlib.sha256(contents[:entry.filesize]).digest()
    finally:
        fs.close()
    wanted = {} if "empty" in image.name or "full" in image.name or "thai" in image.name else expected
    assert actual == wanted, (image, set(wanted) - set(actual), set(actual) - set(wanted))
    if wanted:
        destination = root / (image.stem + "-mcopy")
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir()
        result = subprocess.run([os.environ.get("MCOPY", "mcopy"), "-s", "-i", str(image), "::/*", str(destination)], capture_output=True, text=True)
        (root / (image.stem + "-mcopy.log")).write_text(result.stdout + result.stderr)
        assert result.returncode == 0, result.stdout + result.stderr
        copied = {path.relative_to(destination).as_posix(): hashlib.sha256(path.read_bytes()).digest()
                  for path in destination.rglob("*") if path.is_file()}
        # Current mtools treats UTF-16 surrogate halves as underscores. PyFat above
        # independently verifies exact names, including pairs crossing LFN entries.
        ucs2_names = {"".join("__" if ord(char) > 0xffff else char for char in name): value
                      for name, value in wanted.items()}
        assert copied == wanted or copied == ucs2_names, (image, "mtools data or name mismatch")
    print(f"{image.name}: fsck clean, {len(actual)} files with matching Unicode paths and SHA-256", flush=True)
