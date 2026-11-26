package pl.rufusportable;

import android.hardware.usb.*;
import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** USB Mass Storage Bulk-Only Transport, transparent SCSI, logical unit zero. */
final class UsbDisk implements Closeable {
    interface Progress { void moved(boolean verifying, long bytes) throws IOException; }
    private final UsbDeviceConnection connection;
    private final UsbInterface iface;
    private UsbEndpoint in, out;
    private int tag;
    final int blockSize;
    final long capacity;

    static UsbInterface storage(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 8 && f.getInterfaceSubclass() == 6 && f.getInterfaceProtocol() == 80) return f;
        }
        return null;
    }

    UsbDisk(UsbManager manager, UsbDevice device) throws IOException {
        iface = storage(device);
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Brak dostępu do USB.");
        try {
            if (iface == null || !connection.claimInterface(iface, true)) throw new IOException("Nośnik jest zajęty.");
            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint e = iface.getEndpoint(i);
                if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e;
                else out = e;
            }
            if (in == null || out == null) throw new IOException("Brak kanałów USB.");
            byte[] geometry = new byte[8];
            byte[] readCapacity = new byte[10];
            readCapacity[0] = 0x25;
            try {
                command(readCapacity, geometry, 8, true);
            } catch (ScsiError e) {
                command(new byte[]{3, 0, 0, 0, 18, 0}, new byte[18], 18, true);
                command(readCapacity, geometry, 8, true);
            }
            ByteBuffer info = ByteBuffer.wrap(geometry);
            long last = Integer.toUnsignedLong(info.getInt());
            blockSize = info.getInt();
            if (last == 0xffffffffL || blockSize < 512 || blockSize > 16384 || Integer.bitCount(blockSize) != 1)
                throw new IOException("Nieobsługiwany rozmiar lub geometria nośnika.");
            capacity = (last + 1) * blockSize;
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private static final class ScsiError extends IOException {
        ScsiError() { super("Nośnik odrzucił polecenie. Sprawdź ochronę zapisu i połączenie."); }
    }

    private void command(byte[] cdb, byte[] data, int count, boolean reading) throws IOException {
        ByteBuffer wrapper = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
        wrapper.putInt(0x43425355).putInt(++tag).putInt(count).put((byte)(reading ? 128 : 0))
                .put((byte)0).put((byte)cdb.length).put(cdb);
        if (transfer(out, wrapper.array(), 0, 31) != 31) throw new IOException("Nie udało się wysłać polecenia USB.");
        int position = 0;
        while (position < count) {
            int size = Math.min(16384, count - position);
            int n = transfer(reading ? in : out, data, position, size);
            if (n < 0 || n > size) throw new IOException("Błąd transmisji USB.");
            position += n;
            if (n < size) break;
        }
        ByteBuffer status = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
        if (transfer(in, status.array(), 0, 13) != 13 || status.getInt() != 0x53425355 || status.getInt() != tag)
            throw new IOException("Niepoprawne potwierdzenie USB.");
        int residue = status.getInt();
        byte result = status.get();
        if (result == 1) throw new ScsiError();
        if (result != 0 || residue != 0 || position != count) throw new IOException("Niepełna transmisja USB.");
    }

    private int transfer(UsbEndpoint endpoint, byte[] data, int offset, int count) {
        return connection.bulkTransfer(endpoint, data, offset, count, 10000);
    }

    void flash(File image, Progress progress) throws Exception {
        long length = image.length();
        if (length <= 0 || length > capacity) throw new IOException("Obraz nie mieści się na nośniku.");
        MessageDigest original = MessageDigest.getInstance("SHA-256");
        MessageDigest actual = MessageDigest.getInstance("SHA-256");
        byte[] data = new byte[131072];
        try (DataInputStream source = new DataInputStream(new BufferedInputStream(new FileInputStream(image)))) {
            for (boolean verify : new boolean[]{false, true}) {
                for (long offset = 0; offset < length;) {
                    progress.moved(verify, offset);
                    int bytes = (int)Math.min(data.length, length - offset);
                    int blocks = (bytes + blockSize - 1) / blockSize;
                    if (!verify) {
                        source.readFully(data, 0, bytes);
                        Arrays.fill(data, bytes, blocks * blockSize, (byte)0);
                        original.update(data, 0, bytes);
                    }
                    ByteBuffer cdb = ByteBuffer.allocate(10);
                    cdb.put((byte)(verify ? 0x28 : 0x2A)).put((byte)0).putInt((int)(offset / blockSize))
                            .put((byte)0).putShort((short)blocks).put((byte)0);
                    command(cdb.array(), data, blocks * blockSize, verify);
                    if (verify) actual.update(data, 0, bytes);
                    offset += bytes;
                }
                if (!verify) command(new byte[]{0x35,0,0,0,0,0,0,0,0,0}, data, 0, false);
            }
        }
        if (!MessageDigest.isEqual(original.digest(), actual.digest())) throw new IOException("Weryfikacja SHA-256 wykryła różnicę.");
    }

    public void close() {
        if (iface != null) connection.releaseInterface(iface);
        connection.close();
    }
}
