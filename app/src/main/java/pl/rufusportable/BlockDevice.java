package pl.rufusportable;

import java.io.IOException;

/** Byte-addressable partition I/O used by native filesystem implementations. */
interface BlockDevice {
    long size();
    int sectorSize();
    void read(long offset,byte[] destination) throws IOException;
    void write(long offset,byte[] source) throws IOException;
    void sync() throws IOException;
}
