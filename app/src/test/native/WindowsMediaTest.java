package pl.rufusportable;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Full Windows ISO extraction smoke test for CI with bounded HTTP range caching. */
public final class WindowsMediaTest {
    private static final class HttpImage implements IsoTree.Reader {
        private static final int CHUNK = 1048576;
        private final URL url;
        final long length;
        private final LinkedHashMap<Long, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);

        HttpImage(String address) throws IOException {
            HttpURLConnection probe = (HttpURLConnection)new URL(address).openConnection();
            probe.setRequestProperty("Range", "bytes=0-0");
            probe.setConnectTimeout(30000);
            probe.setReadTimeout(60000);
            try {
                if (probe.getResponseCode() != 206) {
                    throw new IOException("ISO server does not support range reads");
                }
                String range = probe.getHeaderField("Content-Range");
                if (range == null || !range.matches("bytes 0-0/[0-9]+")) {
                    throw new IOException("Invalid ISO size response");
                }
                length = Long.parseLong(range.substring(range.indexOf('/') + 1));
                url = probe.getURL();
            } finally {
                probe.disconnect();
            }
        }

        @Override
        public void read(long offset, byte[] data) throws IOException {
            int done = 0;
            while (done < data.length) {
                long index = (offset + done) / CHUNK;
                int within = (int)((offset + done) % CHUNK);
                byte[] part = cache.get(index);
                if (part == null) {
                    long start = index * CHUNK;
                    long end = Math.min(length, start + CHUNK) - 1;
                    IOException failure = null;
                    for (int attempt = 0; attempt < 3; attempt++) {
                        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
                        connection.setConnectTimeout(30000);
                        connection.setReadTimeout(60000);
                        try {
                            if (connection.getResponseCode() != 206 ||
                                    !("bytes " + start + "-" + end + "/" + length)
                                            .equals(connection.getHeaderField("Content-Range"))) {
                                throw new IOException("Unexpected ISO range response");
                            }
                            try (InputStream input = connection.getInputStream()) {
                                part = input.readAllBytes();
                            }
                            if (part.length != end - start + 1) {
                                throw new IOException("Incomplete ISO range");
                            }
                            break;
                        } catch (IOException error) {
                            failure = error;
                            part = null;
                        } finally {
                            connection.disconnect();
                        }
                    }
                    if (part == null) {
                        throw failure;
                    }
                    cache.put(index, part);
                    if (cache.size() > 8) {
                        cache.remove(cache.keySet().iterator().next());
                    }
                }
                int count = Math.min(data.length - done, part.length - within);
                System.arraycopy(part, within, data, done, count);
                done += count;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        HttpImage image = new HttpImage(args[0]);
        List<IsoTree.Entry> entries = UdfTree.scan(image, image.length);
        IsoTree.Entry wim = entries.stream().filter(e -> e.path.equalsIgnoreCase("sources/install.wim"))
                .findFirst().orElseThrow();
        if (wim.length <= 0xffffffffL) {
            throw new AssertionError("The large-file regression requires install.wim larger than 4 GiB");
        }
        Path assets = Path.of(args[2]);
        long capacity = 32_000_000_000L;
        WindowsLayout layout = WindowsLayout.create(capacity, 512, true, 4096,
                Files.readAllBytes(assets.resolve("uefi-ntfs.img")),
                Files.readAllBytes(assets.resolve("mbr_win7.bin")));
        WindowsInstaller plan = new WindowsInstaller(entries, layout.mainLength, 4096);
        System.out.println("ISO bytes=" + image.length + " entries=" + entries.size());
        System.out.println("install.wim bytes=" + wim.length + " extents=" + wim.extents.size());
        System.out.println("Total extracted file bytes=" + plan.bytes);
        try (RandomAccessFile disk = new RandomAccessFile(args[1], "rw")) {
            disk.setLength(0);
            disk.setLength(capacity);
            for (DiskLayout.Range range : layout.metadata.ranges) {
                disk.seek(range.offset);
                try (InputStream input = range.source.open()) {
                    byte[] buffer = new byte[131072];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        disk.write(buffer, 0, count);
                    }
                }
            }
            BlockDevice partition = new BlockDevice() {
                public long size() { return layout.mainLength; }
                public int sectorSize() { return 512; }
                public void read(long offset, byte[] data) throws IOException {
                    disk.seek(WindowsLayout.MAIN_START * 512 + offset);
                    disk.readFully(data);
                }
                public void write(long offset, byte[] data) throws IOException {
                    disk.seek(WindowsLayout.MAIN_START * 512 + offset);
                    disk.write(data);
                }
                public void sync() throws IOException { disk.getFD().sync(); }
            };
            int[] last = {-1};
            plan.install(partition, e -> new ExtentInputStream(image, e), "WINDOWS_TEST", 4096, true,
                    Files.readAllBytes(assets.resolve("br_ntfs_0x0.bin")),
                    Files.readAllBytes(assets.resolve("br_ntfs_0x54.bin")), true,
                    (verifying, path, done, total) -> {
                        int percent = (int)(100.0 * done / total);
                        int step = (verifying ? 100 : 0) + percent;
                        if (last[0] != step && percent % 10 == 0) {
                            last[0] = step;
                            System.out.println((verifying ? "Verify " : "Copy ") + percent + "% " + path);
                        }
                    });
            for (DiskLayout.Range range : layout.metadata.ranges) {
                disk.seek(range.offset);
                try (InputStream expected = range.source.open()) {
                    byte[] buffer = new byte[(int)range.length];
                    disk.readFully(buffer);
                    if (!Arrays.equals(expected.readAllBytes(), buffer)) {
                        throw new AssertionError("Partition metadata changed");
                    }
                }
            }
        }
        System.out.println("Full Windows ISO extraction and all file SHA-256 checks passed");
    }
}
