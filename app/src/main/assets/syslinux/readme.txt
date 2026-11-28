Syslinux BIOS resources for the native Rufus Android port.

ldlinux_v4.sys / .bss: exact Rufus res/syslinux resources, Syslinux 4.07.
ldlinux_v6.sys / .bss: exact Rufus res/syslinux resources, Syslinux 6.04-pre1.
Reference Rufus revision: 8337c3e7e534b5d5b5045dd8ecb9a7ea60e9232c.
Upstream source: https://www.kernel.org/pub/linux/utils/boot/syslinux/
License: GPL-2.0-or-later; the source reference and installer notices are in third_party/rufus.

ldlinux_v6.c32 and .sig downloaded from the same location used by Rufus:
https://rufus.ie/files/syslinux-6.04/ldlinux.c32
https://rufus.ie/files/syslinux-6.04/ldlinux.c32.sig
The detached RSA-2048 / SHA-256 signature was verified with the public modulus
in the reference Rufus src/pki.c using OpenSSL. The Java verifier uses the same
public key and the standard big-endian signature encoding.
SHA-256 ldlinux_v6.c32:
d3472c02263acf9cd1da5db51e263c5484bad13ea68618c403d9cb01ca070aee
SHA-256 ldlinux_v6.c32.sig:
a5658ef94a26c23173e19ebce57f02074ca86f3781550f2e17da242516ee7a2a

These files are not a complete installer. Boot and filesystem tests determine
which combinations are supported before release.

mbr.bin is the exact 440-byte mbr_syslinux_0x0 array from
reference Rufus src/ms-sys/inc/mbr_syslinux.h (Syslinux 6.02 MBR).
SHA-256: 4746f74bc9b9d3d579c41988a4a29bb7ac932ad1c70470ea779ea161eb799b64
