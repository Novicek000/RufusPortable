package pl.rufusportable;

import android.hardware.usb.*;
import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** USB Mass Storage Bulk-Only Transport, transparent SCSI with 64-bit block addresses and explicit logical units. */
final class UsbDisk implements Closeable {
    interface Progress { void moved(boolean verifying, long bytes) throws IOException; }
    private final UsbDeviceConnection connection;
    private final UsbInterface iface;
    private UsbEndpoint in, out;
    private int tag;
    private final int lun;
    final int blockSize;
    final long capacity;

    static UsbInterface storage(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 8 && f.getInterfaceSubclass() == 6 && f.getInterfaceProtocol() == 80) return f;
        }
        return null;
    }

    static int logicalUnits(UsbManager manager,UsbDevice device) throws IOException {
        UsbInterface iface=storage(device);
        UsbDeviceConnection connection=manager.openDevice(device);
        if (connection==null) throw new IOException("Brak dostępu do USB.");
        boolean claimed=false;
        try {
            if (iface==null || !(claimed=connection.claimInterface(iface,true)))
                throw new IOException("Nośnik jest zajęty.");
            return maxLun(connection,iface)+1;
        } finally {
            if (claimed) connection.releaseInterface(iface);
            connection.close();
        }
    }

    private static int maxLun(UsbDeviceConnection connection,UsbInterface iface) throws IOException {
        byte[] response=new byte[1];
        int result=connection.controlTransfer(0xa1,0xfe,0,iface.getId(),response,1,5000);
        // Bulk-Only devices with a single unit may stall GET_MAX_LUN.
        if (result<=0) return 0;
        int maximum=response[0]&255;
        if (result!=1 || maximum>15) throw new IOException("Nieprawidłowa liczba jednostek USB.");
        return maximum;
    }

    UsbDisk(UsbManager manager, UsbDevice device) throws IOException {
        this(manager,device,0);
    }

    UsbDisk(UsbManager manager, UsbDevice device,int logicalUnit) throws IOException {
        if (logicalUnit<0 || logicalUnit>15) throw new IOException("Nieprawidłowa jednostka USB.");
        lun=logicalUnit;
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
            if (lun>0 && lun>maxLun(connection,iface))
                throw new IOException("Wybrana jednostka USB nie jest już dostępna.");
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
            int sector = info.getInt();
            if (last == 0xffffffffL) {
                byte[] extended = new byte[32];
                byte[] capacity16 = new byte[16];
                capacity16[0] = (byte)0x9e;
                capacity16[1] = 0x10;
                ByteBuffer.wrap(capacity16).putInt(10, extended.length);
                command(capacity16, extended, extended.length, true);
                info = ByteBuffer.wrap(extended);
                last = info.getLong();
                sector = info.getInt();
            }
            if (last < 0 || sector < 512 || sector > 16384 || Integer.bitCount(sector) != 1
                    || last >= Long.MAX_VALUE / sector)
                throw new IOException("Nieobsługiwany rozmiar lub geometria nośnika.");
            blockSize = sector;
            capacity = (last + 1) * blockSize;
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private static final class ScsiError extends IOException {
        ScsiError() { super("Nośnik odrzucił polecenie. Sprawdź ochronę zapisu i połączenie."); }
    }

    private synchronized void command(byte[] cdb, byte[] data, int count, boolean reading) throws IOException {
        ByteBuffer wrapper = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
        wrapper.putInt(0x43425355).putInt(++tag).putInt(count).put((byte)(reading ? 128 : 0))
                .put((byte)lun).put((byte)cdb.length).put(cdb);
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

    void flash(DiskLayout layout, Progress progress, boolean verifyAfterWrite) throws Exception {
        layout.validate(capacity,blockSize);
        MessageDigest original=MessageDigest.getInstance("SHA-256");
        MessageDigest actual=MessageDigest.getInstance("SHA-256");
        byte[] data=new byte[131072];
        for (boolean verify : verifyAfterWrite ? new boolean[]{false,true} : new boolean[]{false}) {
            long done=0;
            for (DiskLayout.Range range:layout.ranges) {
                try (DataInputStream source=verify ? null : new DataInputStream(range.source.open())) {
                    for (long pos=0;pos<range.length;) {
                        progress.moved(verify, done+pos);
                        int bytes=(int)Math.min(data.length,range.length-pos);
                        int blocks=(bytes+blockSize-1)/blockSize;
                        if (!verify) {
                            source.readFully(data,0,bytes);
                            Arrays.fill(data,bytes,blocks*blockSize,(byte)0);
                            if (verifyAfterWrite) original.update(data,0,blocks*blockSize);
                        }
                        command(blockCommand(verify,(range.offset+pos)/blockSize,blocks),
                                data,blocks*blockSize,verify);
                        if (verify) actual.update(data,0,blocks*blockSize);
                        pos+=bytes;
                    }
                    if (source!=null && source.read()!=-1) throw new IOException("Rozmiar źródła zmienił się podczas zapisu.");
                }
                done+=range.length;
            }
            if (!verify) command(new byte[]{0x35,0,0,0,0,0,0,0,0,0},data,0,false);
        }
        if (verifyAfterWrite && !MessageDigest.isEqual(original.digest(),actual.digest()))
            throw new IOException("Weryfikacja SHA-256 wykryła różnicę.");
    }

    BlockDevice partition(long start,long length) throws IOException {
        if (start<0 || length<=0 || start>capacity || length>capacity-start
                || start%blockSize!=0 || length%blockSize!=0)
            throw new IOException("Nieprawidłowy zakres partycji.");
        return new BlockDevice() {
            public long size() { return length; }
            public int sectorSize() { return blockSize; }
            private void check(long offset,int count) throws IOException {
                if (offset<0 || offset>length || count>length-offset)
                    throw new IOException("Operacja wykracza poza partycję.");
            }
            public void read(long offset,byte[] data) throws IOException {
                check(offset,data.length); access(start+offset,data,false);
            }
            public void write(long offset,byte[] data) throws IOException {
                check(offset,data.length); access(start+offset,data,true);
            }
            public void sync() throws IOException {
                synchronized (UsbDisk.this) {
                    command(new byte[]{0x35,0,0,0,0,0,0,0,0,0},new byte[0],0,false);
                }
            }
        };
    }

    private synchronized void access(long offset,byte[] bytes,boolean writing) throws IOException {
        int done=0;
        while (done<bytes.length) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            long aligned=offset/blockSize*blockSize;
            int prefix=(int)(offset-aligned);
            int count=Math.min(131072-prefix,bytes.length-done);
            int blocks=(prefix+count+blockSize-1)/blockSize;
            byte[] transfer=new byte[blocks*blockSize];
            if (!writing || prefix!=0 || count!=transfer.length) {
                command(blockCommand(true,aligned/blockSize,blocks),transfer,transfer.length,true);
            }
            if (writing) {
                System.arraycopy(bytes,done,transfer,prefix,count);
                command(blockCommand(false,aligned/blockSize,blocks),transfer,transfer.length,false);
            } else System.arraycopy(transfer,prefix,bytes,done,count);
            done+=count; offset+=count;
        }
    }

    private static byte[] blockCommand(boolean reading,long lba,int blocks) throws IOException {
        if (lba < 0 || blocks <= 0 || lba > Long.MAX_VALUE - (blocks - 1))
            throw new IOException("Nieprawidłowy zakres bloków SCSI.");
        if (blocks <= 0xffff && lba + blocks - 1 <= 0xffffffffL) {
            ByteBuffer command=ByteBuffer.allocate(10);
            command.put((byte)(reading?0x28:0x2a)).put((byte)0)
                    .putInt((int)lba).put((byte)0).putShort((short)blocks);
            return command.array();
        }
        ByteBuffer command=ByteBuffer.allocate(16);
        command.put((byte)(reading?0x88:0x8a)).put((byte)0).putLong(lba).putInt(blocks);
        return command.array();
    }

    public synchronized void close() {
        if (iface != null) connection.releaseInterface(iface);
        connection.close();
    }
}
