package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/** Rufus drive.c partition placement for NTFS installation media and UEFI:NTFS. */
final class WindowsLayout {
    static final long MAIN_START = 2048;
    final long mainLength;
    final DiskLayout metadata;
    private static final int SECTOR = 512;
    private static final String BASIC_DATA = "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7";

    private WindowsLayout(long mainLength, DiskLayout metadata) {
        this.mainLength = mainLength;
        this.metadata = metadata;
    }

    static WindowsLayout create(long capacity, int sectorSize, boolean gpt, int clusterSize,
            byte[] uefiNtfs, byte[] biosMbr) throws IOException {
        if (sectorSize != SECTOR || capacity % SECTOR != 0 || clusterSize < SECTOR ||
                clusterSize > 2097152 || Integer.bitCount(clusterSize) != 1 ||
                uefiNtfs.length != 1048576 || biosMbr.length > 440) {
            throw new IOException("Nieobsługiwana geometria instalatora Windows.");
        }
        long sectors = capacity / SECTOR;
        if (!gpt && sectors > 0x100000000L) {
            throw new IOException("Dla tego nośnika wybierz GPT.");
        }
        long extraLength = ((uefiNtfs.length / SECTOR + 62) / 63) * 63;
        long extraStart = ((sectors - (gpt ? 33 : 0) - extraLength) / 63) * 63;
        long mainSectors = ((extraStart - MAIN_START) / 63) * 63;
        mainSectors = mainSectors / (clusterSize / SECTOR) * (clusterSize / SECTOR);
        if (mainSectors < 32768 || extraStart <= MAIN_START) {
            throw new IOException("Nośnik jest za mały na instalator Windows.");
        }
        DiskLayout metadata = new DiskLayout();
        byte[] first = new byte[2048 * SECTOR];
        ByteBuffer mbr = little(first);
        mbr.putInt(440, new java.security.SecureRandom().nextInt());
        mbr.putShort(510, (short)0xaa55);
        if (gpt) {
            partition(mbr, 446, 0, 0xee, 1, Math.min(0xffffffffL, sectors - 1));
            byte[] entries = new byte[16384];
            entry(entries, 0, MAIN_START, MAIN_START + mainSectors - 1, 0, "Main Data Partition");
            // Rufus uses Basic Data and suppresses a drive letter for this partition.
            entry(entries, 128, extraStart, extraStart + extraLength - 1,
                    Long.MIN_VALUE, "UEFI:NTFS");
            UUID disk = UUID.randomUUID();
            System.arraycopy(header(sectors, 1, sectors - 1, 2, disk, entries), 0, first, SECTOR, SECTOR);
            System.arraycopy(entries, 0, first, 2 * SECTOR, entries.length);
            metadata.add((sectors - 33) * SECTOR, entries);
            metadata.add((sectors - 1) * SECTOR,
                    header(sectors, sectors - 1, 1, sectors - 33, disk, entries));
        } else {
            System.arraycopy(biosMbr, 0, first, 0, biosMbr.length);
            partition(mbr, 446, 128, 7, MAIN_START, mainSectors);
            partition(mbr, 462, 0, 0xef, extraStart, extraLength);
            metadata.add((sectors - 1) * SECTOR, new byte[SECTOR]);
        }
        byte[] bootPartition = new byte[(int)(extraLength * SECTOR)];
        System.arraycopy(uefiNtfs, 0, bootPartition, 0, uefiNtfs.length);
        metadata.add(extraStart * SECTOR, bootPartition);
        metadata.add(0, first);
        metadata.validate(capacity, sectorSize);
        return new WindowsLayout(mainSectors * SECTOR, metadata);
    }

    private static ByteBuffer little(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void partition(ByteBuffer table, int offset, int active, int type, long start, long count) {
        table.position(offset);
        table.put((byte)active).put(new byte[]{-2, -1, -1}).put((byte)type)
                .put(new byte[]{-2, -1, -1}).putInt((int)start).putInt((int)count);
    }

    private static void guid(ByteBuffer data, UUID id) {
        long high = id.getMostSignificantBits();
        data.putInt((int)(high >>> 32)).putShort((short)(high >>> 16)).putShort((short)high);
        data.order(ByteOrder.BIG_ENDIAN).putLong(id.getLeastSignificantBits()).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void entry(byte[] entries, int offset, long start, long end, long attributes, String name) {
        ByteBuffer data = little(entries);
        data.position(offset);
        guid(data, UUID.fromString(BASIC_DATA));
        guid(data, UUID.randomUUID());
        data.putLong(start).putLong(end).putLong(attributes).put(name.getBytes(StandardCharsets.UTF_16LE));
    }

    private static int crc(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int)crc.getValue();
    }

    private static byte[] header(long sectors, long current, long backup, long table, UUID disk, byte[] entries) {
        byte[] result = new byte[SECTOR];
        ByteBuffer data = little(result);
        data.put("EFI PART".getBytes(StandardCharsets.US_ASCII)).putInt(65536).putInt(92).putLong(0)
                .putLong(current).putLong(backup).putLong(34).putLong(sectors - 34);
        guid(data, disk);
        data.putLong(table).putInt(128).putInt(128).putInt(crc(entries, 0, entries.length));
        data.putInt(16, crc(result, 0, 92));
        return result;
    }
}
