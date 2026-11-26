package pl.rufusportable;

import android.hardware.usb.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DiskTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private final byte[] storage = new byte[512 * 512];
    private int op, cursor, remaining, tag, writes;
    private boolean corrupt;
    private UsbDisk disk;

    @Before public void setup() throws Exception {
        UsbManager manager = mock(UsbManager.class);
        UsbDevice device = mock(UsbDevice.class);
        UsbInterface face = mock(UsbInterface.class);
        UsbDeviceConnection link = mock(UsbDeviceConnection.class);
        UsbEndpoint in = mock(UsbEndpoint.class), out = mock(UsbEndpoint.class);
        when(device.getInterfaceCount()).thenReturn(1);
        when(device.getInterface(0)).thenReturn(face);
        when(face.getInterfaceClass()).thenReturn(8);
        when(face.getInterfaceSubclass()).thenReturn(6);
        when(face.getInterfaceProtocol()).thenReturn(80);
        when(face.getEndpointCount()).thenReturn(2);
        when(face.getEndpoint(0)).thenReturn(in);
        when(face.getEndpoint(1)).thenReturn(out);
        when(in.getType()).thenReturn(2);
        when(out.getType()).thenReturn(2);
        when(in.getDirection()).thenReturn(128);
        when(manager.openDevice(device)).thenReturn(link);
        when(link.claimInterface(face,true)).thenReturn(true);
        when(link.bulkTransfer(any(),any(),anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            UsbEndpoint endpoint = call.getArgument(0);
            byte[] payload = call.getArgument(1);
            int offset = call.getArgument(2), count = call.getArgument(3);
            ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            if (endpoint == out && remaining == 0) {
                assertEquals(31,count);
                assertEquals(0x43425355,b.getInt());
                tag=b.getInt(); remaining=b.getInt(); op=payload[15];
                if (op==0x28 || op==0x2a) {
                    b.order(ByteOrder.BIG_ENDIAN);
                    cursor=b.getInt(17)*512;
                    assertEquals(remaining,b.getShort(22)*512);
                    if (op==0x2a) writes++;
                }
            } else if (remaining>0) {
                if (op==0x25) b.order(ByteOrder.BIG_ENDIAN).putInt(storage.length/512-1).putInt(512);
                else if (op==0x2a) System.arraycopy(payload,offset,storage,cursor,count);
                else if (op==0x28) {
                    System.arraycopy(storage,cursor,payload,offset,count);
                    if (corrupt) payload[offset]^=1;
                } else fail("unexpected data command");
                cursor+=count; remaining-=count;
            } else {
                assertEquals(13,count);
                b.putInt(0x53425355).putInt(tag).putInt(0).put((byte)0);
            }
            return count;
        });
        disk=new UsbDisk(manager,device);
    }
    private DiskLayout image(int length) throws IOException {
        byte[] data=new byte[length]; new Random(41).nextBytes(data);
        File f=files.newFile(); Files.write(f.toPath(),data);
        DiskLayout layout=new DiskLayout(); layout.add(0,length,()->new FileInputStream(f)); return layout;
    }
    @Test public void writesMultipleChunksPadsSectorAndReadsBack() throws Exception {
        Arrays.fill(storage,(byte)87);
        DiskLayout f=image(150131);
        disk.flash(f,(v,n)->{},true);
        assertEquals(storage.length,disk.capacity);
        assertArrayEquals(f.ranges.get(0).source.open().readAllBytes(),Arrays.copyOf(storage,(int)f.bytes));
        assertEquals(0,storage[(int)f.bytes]);
        assertEquals(87,storage[150528]); assertEquals(2,writes);
    }
    @Test public void detectsCorruptedReadback() throws Exception {
        corrupt=true;
        assertTrue(assertThrows(IOException.class,()->disk.flash(image(513),(v,n)->{},true)).getMessage().contains("SHA-256"));
    }
    @Test public void refusesOversizedImageBeforeWriting() {
        assertThrows(IOException.class,()->disk.flash(image(storage.length+1),(v,n)->{},true));
        assertEquals(0,writes);
    }
    @Test public void cancellationStopsNextWrite() {
        assertThrows(InterruptedIOException.class,()->disk.flash(image(140000),(v,n)->{
            if (n>0) throw new InterruptedIOException();
        },true));
        assertEquals(1,writes);
    }
    @Test public void comparesReleaseComponentsNumerically() {
        assertTrue(Updates.newer("v1.10.0","1.9.9"));
        assertFalse(Updates.newer("1.0.0","1.0.0"));
        assertFalse(Updates.newer("v1.10.0","2.0.0"));
    }
}
