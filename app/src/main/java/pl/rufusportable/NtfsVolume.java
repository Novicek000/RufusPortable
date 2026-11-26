package pl.rufusportable;

import java.io.*;
import java.util.*;

/** Serializes native handles and releases file handles before their owning volume. */
final class NtfsVolume implements Closeable {
    static {
        System.loadLibrary("rufus_ntfs");
    }

    private long handle;
    private final Set<NtfsFile> files = new HashSet<>();

    NtfsVolume(BlockDevice partition) throws IOException {
        handle = mount(partition, partition.size(), partition.sectorSize());
    }

    static void format(BlockDevice partition, String label, int clusterSize,
            long partitionStartSector, boolean quick) throws IOException {
        format(partition, partition.size(), partition.sectorSize(),
                label.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                clusterSize, partitionStartSector, quick);
    }

    private void requireOpen() throws IOException {
        if (handle == 0) {
            throw new IOException("Wolumin NTFS jest zamknięty.");
        }
    }

    synchronized void mkdir(String path) throws IOException {
        requireOpen();
        mkdir(handle, path);
    }

    synchronized OutputStream create(String path) throws IOException {
        requireOpen();
        NtfsFile file = new NtfsFile(create(handle, path));
        files.add(file);
        return file;
    }

    @Override
    public synchronized void close() throws IOException {
        if (handle == 0) {
            return;
        }
        IOException failure = null;
        for (NtfsFile file : new ArrayList<>(files)) {
            try {
                file.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        long closing = handle;
        handle = 0;
        try {
            unmount(closing);
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private final class NtfsFile extends OutputStream {
        private long fileHandle;
        private long position;

        NtfsFile(long handle) {
            fileHandle = handle;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value});
        }

        @Override
        public void write(byte[] data, int offset, int count) throws IOException {
            if (offset < 0 || count < 0 || offset > data.length - count) {
                throw new IndexOutOfBoundsException();
            }
            synchronized (NtfsVolume.this) {
                requireOpen();
                if (fileHandle == 0) {
                    throw new IOException("Plik NTFS jest zamknięty.");
                }
                if (count > Long.MAX_VALUE - position) {
                    throw new IOException("Plik NTFS jest zbyt duży.");
                }
                int done = 0;
                while (done < count) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException();
                    }
                    int bytes = Math.min(131072, count - done);
                    NtfsVolume.write(fileHandle, position, data, offset + done, bytes);
                    position += bytes;
                    done += bytes;
                }
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (NtfsVolume.this) {
                if (fileHandle == 0) {
                    return;
                }
                long closing = fileHandle;
                fileHandle = 0;
                files.remove(this);
                closeFile(closing);
            }
        }
    }

    private static native void format(BlockDevice device, long size, int sectorSize,
            byte[] label, int clusterSize, long partitionStartSector, boolean quick) throws IOException;
    private static native long mount(BlockDevice device, long size, int sectorSize) throws IOException;
    private static native void mkdir(long volume, String path) throws IOException;
    private static native long create(long volume, String path) throws IOException;
    private static native void write(long file, long offset, byte[] data, int start, int length) throws IOException;
    private static native void closeFile(long file) throws IOException;
    private static native void unmount(long volume) throws IOException;
}
