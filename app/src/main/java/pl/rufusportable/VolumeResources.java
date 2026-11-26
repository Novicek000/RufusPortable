package pl.rufusportable;

import java.io.*;
import java.nio.*;

/** Shared binary resource access for filesystem formatters. */
final class VolumeResources {
    interface Assets {
        InputStream open(String name) throws IOException;
    }

    static ByteBuffer little(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    static byte[] read(Assets assets, String name, int length) throws IOException {
        byte[] data = new byte[length];
        try (DataInputStream input = new DataInputStream(assets.open(name))) {
            input.readFully(data);
            if (input.read() != -1) {
                throw new IOException("Niepoprawny rozmiar składnika " + name);
            }
        }
        return data;
    }
}
