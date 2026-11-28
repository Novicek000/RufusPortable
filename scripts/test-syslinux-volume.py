#!/usr/bin/env python3
"""Independent filesystem and BIOS boot checks of self-generated test images only."""
import os
import json
import pathlib
import selectors
import socket
import struct
import subprocess
import sys
import time
from pyfatfs.PyFatFS import PyFatFS


def verify(directory):
    for version in (4, 6):
        for bits in (16, 32):
            name = f'syslinux{version}-fat{bits}'
            path = directory / f'{name}-partition.img'
            subprocess.run([os.environ.get('FSCK_FAT', 'fsck.fat'), '-n', str(path)], check=True)
            with PyFatFS(str(path), read_only=True) as fs:
                with fs.openbin('syslinux.cfg') as file:
                    assert f'RUFUS_NATIVE_{name.upper()}'.encode() in file.read()
                with fs.openbin('ldlinux.sys') as file:
                    data = file.read()
                if version == 6:
                    with fs.openbin('ldlinux.c32') as file:
                        assert file.read()[:4] == b'\x7fELF'
            # Decode the physical FAT map separately from the Java allocator.
            with path.open('rb') as image:
                boot = image.read(512)
                u16 = lambda off: struct.unpack_from('<H', boot, off)[0]
                u32 = lambda off: struct.unpack_from('<I', boot, off)[0]
                sector, cluster, reserved, fats = u16(11), boot[13], u16(14), boot[16]
                root_count, fat_size = u16(17), u16(22) or u32(36)
                root_sectors = (root_count * 32 + sector - 1) // sector
                data_sector = reserved + fats * fat_size + root_sectors
                image.seek(reserved * sector)
                fat = image.read(fat_size * sector)

                def chain(first):
                    seen = set()
                    while first < (0xfff8 if bits == 16 else 0x0ffffff8):
                        assert first >= 2 and first not in seen
                        seen.add(first)
                        yield first
                        first = struct.unpack_from('<H' if bits == 16 else '<I', fat, first * (bits // 8))[0] & (0xffff if bits == 16 else 0x0fffffff)

                if bits == 16:
                    image.seek((reserved + fats * fat_size) * sector)
                    root = image.read(root_sectors * sector)
                else:
                    root = bytearray()
                    for item in chain(u32(44)):
                        image.seek((data_sector + (item - 2) * cluster) * sector)
                        root += image.read(cluster * sector)
                entries = [root[i:i+32] for i in range(0, len(root), 32)]
                entry = next(e for e in entries if e[:11] == b'LDLINUX SYS' and e[11] != 0x0f)
                assert entry[11] & 7 == 7
                first = struct.unpack_from('<H', entry, 26)[0]
                if bits == 32:
                    first |= struct.unpack_from('<H', entry, 20)[0] << 16
                length = struct.unpack_from('<I', entry, 28)[0]
                assert length == len(data)
                sectors = []
                for item in chain(first):
                    sectors.extend(range(data_sector + (item - 2) * cluster, data_sector + (item - 1) * cluster))
                sectors = sectors[:(length + 511) // 512]
                patch = data.index(bytes.fromhex('fe02b23e'))
                epa = struct.unpack_from('<H', data, patch + 22)[0]
                adv, _, _, _, _, table, slots, low, high, _ = struct.unpack_from('<10H', data, epa)
                assert (u32(low) | (u32(high) << 32)) == sectors[0]
                expected = (length - 1024 + 511) // 512 - 1
                decoded = []
                for i in range(slots):
                    lba, count = struct.unpack_from('<QH', data, table + 10 * i)
                    if not count:
                        break
                    decoded.extend(range(lba, lba + count))
                assert decoded == sectors[1:1+expected]
                assert list(struct.unpack_from('<QQ', data, adv)) == sectors[1+expected:3+expected]
                original_length = length - 1024
                assert sum(struct.unpack_from('<' + 'I' * (original_length // 4), data)) & 0xffffffff == 0x3eb202fe
            print('Independent FAT and sector-map verification passed:', name, flush=True)


def boot_images(directory):
    executable = os.environ.get('QEMU_SYSTEM_X86_64', 'qemu-system-x86_64')
    for version in (4, 6):
        for bits in (16, 32):
            name = f'syslinux{version}-fat{bits}'
            image = directory / f'{name}.img'
            assert image.is_file()
            qmp_path = directory / f'{name}-qmp.sock'
            qmp_path.unlink(missing_ok=True)
            command = [executable, '-machine', 'pc,accel=tcg', '-m', '64', '-drive', f'file={image},format=raw,if=ide,snapshot=on',
                       '-display', 'none', '-serial', 'stdio', '-monitor', 'none', '-net', 'none', '-no-reboot',
                       '-qmp', f'unix:{qmp_path},server=on,wait=off']
            if os.environ.get('QEMU_FIRMWARE_DIR'):
                command += ['-L', os.environ['QEMU_FIRMWARE_DIR']]
            if os.environ.get('QEMU_BIOS'):
                command += ['-bios', os.environ['QEMU_BIOS']]
            marker = f'RUFUS_NATIVE_{name.upper()}'.encode()
            output = bytearray()
            vga_text = ''
            connection = None
            stream = None
            sequence = 0

            def execute(name, arguments=None):
                nonlocal sequence
                sequence += 1
                request = {'execute': name, 'id': sequence}
                if arguments is not None:
                    request['arguments'] = arguments
                connection.sendall(json.dumps(request).encode() + b'\n')
                while True:
                    line = stream.readline()
                    if not line:
                        raise EOFError('QEMU closed QMP')
                    result = json.loads(line)
                    if result.get('id') == sequence:
                        if 'error' in result:
                            raise RuntimeError(result['error'])
                        return result.get('return')
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env={**os.environ, 'TMPDIR': str(directory)})
            selector = selectors.DefaultSelector()
            selector.register(process.stdout, selectors.EVENT_READ)
            deadline = time.monotonic() + 30
            try:
                while marker not in output and marker.decode() not in vga_text and time.monotonic() < deadline:
                    for key, _ in selector.select(timeout=.2):
                        data = os.read(key.fd, 4096)
                        if not data:
                            break
                        output.extend(data)
                    if process.poll() is not None:
                        break
                    if connection is None and qmp_path.exists():
                        connection = socket.socket(socket.AF_UNIX)
                        connection.settimeout(5)
                        connection.connect(str(qmp_path))
                        stream = connection.makefile('rb')
                        assert 'QMP' in json.loads(stream.readline())
                        execute('qmp_capabilities')
                    if connection is not None:
                        # Syslinux 6 applies SERIAL after parsing SAY, unlike version 4.
                        # Read the actual VGA text buffer to verify that the configuration ran.
                        vga_path = directory / f'{name}-vga.bin'
                        execute('pmemsave', {'val': 0xb8000, 'size': 4000, 'filename': str(vga_path)})
                        vga_text = vga_path.read_bytes()[::2].decode('cp437', errors='replace')
            finally:
                selector.close()
                if stream:
                    stream.close()
                if connection:
                    connection.close()
                if process.poll() is None:
                    process.terminate()
                remaining, _ = process.communicate(timeout=5)
                output.extend(remaining)
            (directory / f'{name}-qemu.log').write_bytes(output)
            (directory / f'{name}-vga.txt').write_text(vga_text)
            assert marker in output or marker.decode() in vga_text, f'{name} did not reach its configuration: {output.decode(errors="replace")}\n{vga_text}'
            print('BIOS executed original Syslinux and its configuration:', name, flush=True)


if __name__ == '__main__':
    directory = pathlib.Path(sys.argv[1]).resolve()
    verify(directory)
    boot_images(directory)
