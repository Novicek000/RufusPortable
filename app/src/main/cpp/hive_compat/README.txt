Hivex portability support

The unmodified registry parser/writer sources are pinned to hivex v1.3.24,
commit 7996d79cf910758fcb29554e83f44c94560f170d in third_party/hivex.
Its LGPL-2.1 license is provided there. The C header hivex.h is the generated
public header from the matching official release, so building the library does
not require its OCaml language binding generator or command-line programs.

Release: https://download.libguestfs.org/hivex/hivex-1.3.24.tar.gz
Archive SHA-256: a52fa45cecc9a78adb2d28605d68261e4f1fd4514a778a5473013d2ccc8a193c
include/hivex.h SHA-256: 720162fe88cd7a6b7a0ddd2246cff72a2daf974a8024da4280f46b5acc9e9c37

The other headers are small local adapters for ASCII character classification,
complete read/write loops and the POSIX features available in Bionic API 26.
The iconv header redirects only hivex's UTF-8 / UTF-16LE / Latin-1 conversions
to hive_iconv.c, avoiding a dependency on Bionic iconv (introduced in API 28).
The host reference test compares all Unicode scalar values with libc iconv.

Hivex 1.3.24 hashes newly created lh key names using bytes. The JNI wrapper
therefore allows creation of ASCII keys needed by Rufus and preserves existing
Unicode keys. Unicode value names and UTF-16 data are supported. It rejects
creation of new non-ASCII keys rather than emitting a hash incompatible with
Windows. No unsafe/corrupt-hive heuristics are enabled.
