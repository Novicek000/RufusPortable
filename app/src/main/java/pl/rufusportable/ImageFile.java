package pl.rufusportable;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;

final class ImageFile {
    final File file;
    final String name, partition;
    private ImageFile(File file, String name, String partition) {
        this.file = file;
        this.name = name;
        this.partition = partition;
    }

    static ImageFile prepare(Context context, Uri uri, File directory) throws IOException {
        String name = "Obraz dysku";
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        }
        File copy = File.createTempFile("image-", ".raw", directory);
        boolean ready = false;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(copy))) {
                if (in == null) throw new IOException("Nie można otworzyć obrazu.");
                byte[] buffer = new byte[131072];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    out.write(buffer, 0, n);
                }
            }
            if (copy.length() < 512) throw new IOException("Plik jest za mały na obraz dysku.");
            byte[] sector = new byte[512];
            try (DataInputStream input = new DataInputStream(new FileInputStream(copy))) { input.readFully(sector); }
            boolean mbr = sector[510] == 0x55 && sector[511] == (byte)0xaa;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".iso") && !mbr) throw new IOException("Ten plik ISO nie zawiera hybrydowego sektora rozruchowego. Wybierz obraz przeznaczony do zapisu na USB.");
            if (!lower.matches(".*\\.(img|iso|raw|bin|dd)$")) throw new IOException("Wybierz rozpakowany obraz IMG, ISO, RAW, BIN lub DD.");
            Properties types = new Properties();
            try (InputStream in = context.getResources().openRawResource(R.raw.mbr_types)) { types.load(in); }
            String layout = "Obraz sektorowy";
            if (mbr) {
                for (int i=450; i<510; i+=16) {
                    if (sector[i] == 0) continue;
                    String code = String.format(Locale.ROOT, "%02x", sector[i] & 255);
                    layout = "MBR · " + types.getProperty(code, "0x" + code);
                    break;
                }
            }
            ready = true;
            return new ImageFile(copy, name, layout);
        } finally { if (!ready) copy.delete(); }
    }
}
