package pl.rufusportable;

import java.io.*;
import java.security.*;
import java.util.*;

/** Copies the installation tree to NTFS and installs Rufus's NTFS boot code. */
final class WindowsInstaller {
    interface Source {
        InputStream open(IsoTree.Entry entry) throws IOException;
    }
    interface Progress {
        void update(boolean verifying, String path, long done, long total) throws IOException;
    }
    private final List<IsoTree.Entry> entries;
    final long bytes;
    private final long expectedCapacity;
    private final int expectedCluster;

    static boolean validLabel(String label) {
        return label.length()<=32 && !label.matches(".*[\\p{Cntrl}:*?\"<>|\\\\].*");
    }

    WindowsInstaller(List<IsoTree.Entry> source, long capacity, int clusterSize) throws IOException {
        if (capacity < (16L << 20) || clusterSize < 512 || clusterSize > 2097152 || Integer.bitCount(clusterSize) != 1) {
            throw new IOException("Nieprawidłowy klaster NTFS.");
        }
        Set<String> paths = new HashSet<>();
        Set<String> directories = new HashSet<>();
        long total = 0;
        long allocated = 0;
        boolean setup = false;
        boolean boot = false;
        for (IsoTree.Entry entry : source) {
            if (entry.path.isEmpty() || entry.path.length() > 32767 ||
                    !paths.add(entry.path.toUpperCase(Locale.ROOT))) {
                throw new IOException("Nieprawidłowa lub powtórzona ścieżka instalatora.");
            }
            for (String component : entry.path.split("/", -1)) {
                if (component.isEmpty() || component.length() > 255 || component.equals(".") ||
                        component.equals("..") || component.matches(".*[\\p{Cntrl}:*?\"<>|\\\\].*")) {
                    throw new IOException("Nieobsługiwana nazwa pliku Windows: " + entry.path);
                }
            }
            if (entry.length < 0 || entry.length > capacity || entry.length > Long.MAX_VALUE - total) {
                throw new IOException("Nieprawidłowy rozmiar pliku instalatora.");
            }
            if (entry.directory) directories.add(entry.path);
            if (!entry.directory) {
                String path = entry.path.toLowerCase(Locale.ROOT);
                setup |= path.equals("sources/boot.wim");
                boot |= path.equals("bootmgr") || path.matches("efi/boot/boot[a-z0-9]+\\.efi");
                total += entry.length;
            }
            long padding = (clusterSize - entry.length % clusterSize) % clusterSize;
            if (entry.length > capacity - allocated || padding + 4096 > capacity - allocated - entry.length) {
                throw new IOException("Pliki instalatora nie mieszczą się na nośniku.");
            }
            long required = entry.length + padding + 4096;
            if (required > capacity - allocated) {
                throw new IOException("Pliki instalatora nie mieszczą się na nośniku.");
            }
            allocated += required;
        }
        // Space for the journal, bitmap, MFT and volume metadata created by mkntfs.
        long metadataReserve = Math.min(128L << 20, capacity / 8);
        if (allocated > capacity - metadataReserve) {
            throw new IOException("Brak miejsca na pliki i struktury NTFS.");
        }
        if (!setup || !boot) {
            throw new IOException("Obraz nie zawiera rozpoznanego instalatora Windows.");
        }
        for (IsoTree.Entry entry : source) {
            int slash = entry.path.lastIndexOf('/');
            if (slash > 0) {
                String parent = entry.path.substring(0, slash);
                boolean present = directories.contains(parent);
                if (!present) {
                    throw new IOException("Brak katalogu nadrzędnego w obrazie.");
                }
            }
        }
        entries = new ArrayList<>(source);
        entries.sort(Comparator.comparingInt(e -> e.path.split("/").length));
        bytes = total;
        expectedCapacity = capacity;
        expectedCluster = clusterSize;
    }

    void install(BlockDevice partition, Source source, String label, int clusterSize, boolean quick,
            byte[] bootPrefix, byte[] bootCode, boolean verify, Progress progress) throws Exception {
        if (partition.size() != expectedCapacity || partition.sectorSize() != 512 || clusterSize != expectedCluster || !validLabel(label) || bootPrefix.length != 11 ||
                bootCode.length != 4052) {
            throw new IOException("Nieprawidłowe zasoby lub etykieta instalatora.");
        }
        progress.update(false, "Formatowanie NTFS", 0, bytes);
        NtfsVolume.format(partition, label, clusterSize, WindowsLayout.MAIN_START, quick);
        installBootCode(partition, bootPrefix, bootCode, verify);
        Map<String, byte[]> checksums = new LinkedHashMap<>();
        byte[] buffer = new byte[131072];
        long done = 0;
        try (NtfsVolume volume = new NtfsVolume(partition)) {
            for (IsoTree.Entry entry : entries) {
                progress.update(false, entry.path, done, bytes);
                if (entry.directory) {
                    volume.mkdir(entry.path);
                    continue;
                }
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                try (InputStream input = source.open(entry);
                     OutputStream output = volume.create(entry.path)) {
                    long copied = 0;
                    while (copied < entry.length) {
                        progress.update(false, entry.path, done + copied, bytes);
                        int count = input.read(buffer, 0, (int)Math.min(buffer.length, entry.length - copied));
                        if (count < 0) {
                            throw new EOFException("Niepełny plik źródłowy: " + entry.path);
                        }
                        if (count == 0) {
                            throw new IOException("Nie udało się odczytać pliku: " + entry.path);
                        }
                        output.write(buffer, 0, count);
                        if (verify) {
                            hash.update(buffer, 0, count);
                        }
                        copied += count;
                    }
                    if (input.read() != -1) {
                        throw new IOException("Rozmiar pliku zmienił się: " + entry.path);
                    }
                }
                if (verify) {
                    checksums.put(entry.path, hash.digest());
                }
                done += entry.length;
            }
        }
        partition.sync();
        if (verify) {
            verify(partition, checksums, progress, buffer);
        }
    }

    private void verify(BlockDevice partition, Map<String, byte[]> checksums,
            Progress progress, byte[] buffer) throws Exception {
        long done = 0;
        try (NtfsVolume volume = new NtfsVolume(partition)) {
            for (IsoTree.Entry entry : entries) {
                if (entry.directory) {
                    continue;
                }
                MessageDigest actual = MessageDigest.getInstance("SHA-256");
                long read = 0;
                try (InputStream input = volume.open(entry.path)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        progress.update(true, entry.path, done + read, bytes);
                        if (count > entry.length - read) {
                            throw new IOException("Zmieniony rozmiar pliku NTFS: " + entry.path);
                        }
                        actual.update(buffer, 0, count);
                        read += count;
                    }
                }
                if (read != entry.length ||
                        !MessageDigest.isEqual(checksums.get(entry.path), actual.digest())) {
                    throw new IOException("Weryfikacja pliku NTFS nie powiodła się: " + entry.path);
                }
                done += read;
            }
        }
    }

    static void installBootCode(BlockDevice partition, byte[] prefix, byte[] code,
            boolean verify) throws IOException {
        byte[] boot = new byte[4608];
        partition.read(0, boot);
        System.arraycopy(prefix, 0, boot, 0, prefix.length);
        System.arraycopy(code, 0, boot, 84, code.length);
        partition.write(0, boot);
        byte[] backup = Arrays.copyOf(boot, 512);
        partition.write(partition.size() - 512, backup);
        partition.sync();
        if (verify) {
            byte[] actual = new byte[boot.length];
            partition.read(0, actual);
            byte[] actualBackup = new byte[512];
            partition.read(partition.size() - 512, actualBackup);
            if (!Arrays.equals(boot, actual) || !Arrays.equals(backup, actualBackup)) {
                throw new IOException("Błąd weryfikacji sektora rozruchowego NTFS.");
            }
        }
    }
}
