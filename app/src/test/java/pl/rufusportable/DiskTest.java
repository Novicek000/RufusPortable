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
    private int op, remaining, tag, writes;
    private long cursor, windowStart, lastLba=storage.length/512-1;
    private UsbManager manager;
    private UsbDevice device;
    private UsbDeviceConnection link;
    private int maximumLun, selectedLun;
    private UsbInterface face;
    private int resets,writePackets,stallWritePacket=-1,scsiFailures,senseKey=7,senseAsc,cswResidue;
    private boolean transportStalled,alwaysStalled,phaseError,commandFailed,shortSense,shortCapacity;
    private final List<Integer> clearedEndpoints=new ArrayList<>();
    private final List<Integer> commands=new ArrayList<>();
    private boolean corrupt;
    private UsbDisk disk;

    @Before public void setup() throws Exception {
        manager = mock(UsbManager.class);
        device = mock(UsbDevice.class);
        face = mock(UsbInterface.class);
        link = mock(UsbDeviceConnection.class);
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
        when(in.getAddress()).thenReturn(0x81);when(out.getAddress()).thenReturn(0x02);
        when(link.setInterface(face)).thenReturn(true);
        when(manager.openDevice(device)).thenReturn(link);
        when(link.claimInterface(face,true)).thenReturn(true);
        when(link.controlTransfer(eq(0xa1),eq(0xfe),eq(0),eq(0),any(),eq(1),eq(5000)))
                .thenAnswer(call->{byte[] response=call.getArgument(4);response[0]=(byte)maximumLun;return 1;});
        when(link.controlTransfer(eq(0x21),eq(0xff),eq(0),eq(0),any(),eq(0),eq(5000)))
                .thenAnswer(call->{resets++;transportStalled=false;remaining=0;cswResidue=0;return 0;});
        when(link.controlTransfer(eq(0x02),eq(1),eq(0),anyInt(),any(),eq(0),eq(5000)))
                .thenAnswer(call->{clearedEndpoints.add(call.getArgument(3));return 0;});
        when(link.bulkTransfer(any(),any(),anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            if(transportStalled || alwaysStalled)return -1;
            UsbEndpoint endpoint = call.getArgument(0);
            byte[] payload = call.getArgument(1);
            int offset = call.getArgument(2), count = call.getArgument(3);
            ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            if (endpoint == out && remaining == 0) {
                assertEquals(31,count);
                assertEquals(0x43425355,b.getInt());
                assertEquals(selectedLun,payload[13]);
                tag=b.getInt(); remaining=b.getInt(); op=payload[15]&255; commands.add(op);
                commandFailed=op!=3 && scsiFailures>0;
                if(commandFailed)scsiFailures--;
                cswResidue=0;
                if (op==0x28 || op==0x2a || op==0x88 || op==0x8a) {
                    b.order(ByteOrder.BIG_ENDIAN);
                    boolean extended=op==0x88 || op==0x8a;
                    assertEquals(extended?16:10,payload[14]);
                    cursor=(extended?b.getLong(17):Integer.toUnsignedLong(b.getInt(17)))*512;
                    assertEquals(remaining,(extended?b.getInt(25):Short.toUnsignedInt(b.getShort(22)))*512);
                    if (op==0x2a || op==0x8a) writes++;
                }
                if (op==0x9e) {
                    assertEquals(16,payload[14]);assertEquals(0x10,payload[16]);
                    assertEquals(32,b.order(ByteOrder.BIG_ENDIAN).getInt(25));
                }
            } else if (remaining>0) {
                if((op==0x2a || op==0x8a) && ++writePackets==stallWritePacket) {
                    transportStalled=true;stallWritePacket=-1;return -1;
                }
                if(op==3) {
                    payload[offset]=(byte)(shortSense?0x72:0x70);
                    if(shortSense) {
                        payload[offset+1]=(byte)senseKey;payload[offset+2]=(byte)senseAsc;
                        cswResidue=remaining-8;remaining=0;return 8;
                    }
                    payload[offset+2]=(byte)senseKey;payload[offset+7]=10;payload[offset+12]=(byte)senseAsc;
                } else if(commandFailed) { /* failed command consumes data but does not change media */ }
                else if (op==0x25) b.order(ByteOrder.BIG_ENDIAN).putInt((int)(lastLba<0?0xffffffffL:Math.min(0xffffffffL,lastLba))).putInt(512);
                else if (op==0x9e) {
                    b.order(ByteOrder.BIG_ENDIAN).putLong(lastLba).putInt(512);
                    if(shortCapacity){cswResidue=remaining-12;remaining=0;return 12;}
                }
                else if (op==0x2a || op==0x8a) System.arraycopy(payload,offset,storage,Math.toIntExact(cursor-windowStart),count);
                else if (op==0x28 || op==0x88) {
                    System.arraycopy(storage,Math.toIntExact(cursor-windowStart),payload,offset,count);
                    if (corrupt) payload[offset]^=1;
                } else fail("unexpected data command");
                cursor+=count; remaining-=count;
            } else {
                assertEquals(13,count);
                int result=commandFailed?1:phaseError?2:0;
                if(phaseError){phaseError=false;transportStalled=true;}
                b.putInt(0x53425355).putInt(tag).putInt(cswResidue).put((byte)result);
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
    @Test public void randomPartitionWritePreservesBytesOutsideUnalignedRange() throws Exception {
        Arrays.fill(storage,(byte)87);
        BlockDevice partition=disk.partition(512,400*512);
        byte[] source=new byte[180007];new Random(71).nextBytes(source);
        partition.write(513,source);
        byte[] actual=new byte[source.length];partition.read(513,actual);
        assertArrayEquals(source,actual);
        for (int i=0;i<1025;i++) assertEquals(87,storage[i]);
        for (int i=1025+source.length;i<storage.length;i++) assertEquals(87,storage[i]);
        partition.sync();
    }
    @Test public void randomPartitionIoRejectsInvalidRangesBeforeWriting() throws Exception {
        BlockDevice partition=disk.partition(512,512);
        assertThrows(IOException.class,()->partition.write(511,new byte[2]));
        assertThrows(IOException.class,()->partition.write(-1,new byte[2]));
        assertThrows(IOException.class,()->partition.read(Long.MAX_VALUE,new byte[2]));
        assertThrows(IOException.class,()->disk.partition(Long.MAX_VALUE,512));
        assertEquals(0,writes);
    }
    private void largeDisk(long start) throws Exception {
        lastLba=0x100001000L;
        windowStart=start*512;
        disk.close();
        disk=new UsbDisk(manager,device);
        assertEquals((lastLba+1)*512,disk.capacity);
        assertTrue(commands.contains(0x9e));
    }
    @Test public void largeDeviceUses64BitAddressesAndPreservesUnalignedNeighbours() throws Exception {
        largeDisk(0x100000000L);
        Arrays.fill(storage,(byte)87);
        BlockDevice partition=disk.partition(windowStart,storage.length);
        byte[] data=new byte[30007];new Random(3).nextBytes(data);
        partition.write(513,data);
        byte[] actual=new byte[data.length];partition.read(513,actual);
        assertArrayEquals(data,actual);
        assertEquals(87,storage[512]);assertEquals(87,storage[513+data.length]);
        assertTrue(commands.contains(0x88));assertTrue(commands.contains(0x8a));
    }
    @Test public void ddVerificationCrosses32BitLbaBoundaryWithoutWrapping() throws Exception {
        largeDisk(0xffffffffL);
        byte[] data=new byte[1024];new Random(7).nextBytes(data);
        DiskLayout layout=new DiskLayout();layout.add(windowStart,data);
        disk.flash(layout,(v,n)->{},true);
        assertArrayEquals(data,Arrays.copyOf(storage,data.length));
        assertTrue(commands.contains(0x88));assertTrue(commands.contains(0x8a));
    }
    @Test public void capacityOverflowIsRejectedBeforeAnyWrite() {
        lastLba=Long.MAX_VALUE;
        assertThrows(IOException.class,()->new UsbDisk(manager,device));
        lastLba=-1; // Unsigned READ CAPACITY(16) result outside Java address range.
        assertThrows(IOException.class,()->new UsbDisk(manager,device));
        assertEquals(0,writes);
    }
    @Test public void multiLunReaderSendsAllCommandsOnlyToSelectedUnit() throws Exception {
        maximumLun=3;
        assertEquals(4,UsbDisk.logicalUnits(manager,device));
        selectedLun=2;
        disk=new UsbDisk(manager,device,selectedLun);
        disk.flash(image(513),(v,n)->{},true);
        assertEquals(1,writes);
    }
    @Test public void removedOrInvalidLogicalUnitIsRejectedBeforeScsiCommands() {
        maximumLun=3;
        int before=commands.size();
        assertThrows(IOException.class,()->new UsbDisk(manager,device,4));
        assertThrows(IOException.class,()->new UsbDisk(manager,device,-1));
        maximumLun=16;
        assertThrows(IOException.class,()->UsbDisk.logicalUnits(manager,device));
        assertEquals(before,commands.size());assertEquals(0,writes);
    }
    @Test public void stalledGetMaxLunUsesSingleUnitFallback() throws Exception {
        when(link.controlTransfer(eq(0xa1),eq(0xfe),eq(0),eq(0),any(),eq(1),eq(5000))).thenReturn(-1);
        assertEquals(1,UsbDisk.logicalUnits(manager,device));
        assertThrows(IOException.class,()->new UsbDisk(manager,device,1));
    }
    @Test public void partialWriteStallResetsTransportAndRetriesWithoutChangingNeighbours() throws Exception {
        Arrays.fill(storage,(byte)87);stallWritePacket=2;
        byte[] bytes=new byte[40000];new Random(23).nextBytes(bytes);
        BlockDevice partition=disk.partition(0,storage.length);
        partition.write(0,bytes);
        byte[] read=new byte[bytes.length];partition.read(0,read);
        assertArrayEquals(bytes,read);assertEquals(87,storage[bytes.length]);
        assertEquals(1,resets);assertEquals(Arrays.asList(0x81,0x02),clearedEndpoints);
    }
    @Test public void phaseErrorRecoversAndRepeatedTransportFailureIsBounded() throws Exception {
        phaseError=true;
        disk.partition(0,storage.length).write(0,new byte[512]);
        assertEquals(1,resets);
        alwaysStalled=true;
        assertThrows(IOException.class,()->disk.partition(0,storage.length).read(0,new byte[512]));
        assertEquals(3,resets);
    }
    @Test public void unitAttentionIsClearedAndRetriedButWriteProtectionIsNot() throws Exception {
        scsiFailures=1;senseKey=6;
        byte[] data=new byte[512];Arrays.fill(data,(byte)13);
        disk.partition(0,storage.length).write(0,data);
        assertArrayEquals(data,Arrays.copyOf(storage,512));assertTrue(commands.contains(3));assertEquals(0,resets);
        scsiFailures=1;senseKey=7;
        IOException failure=assertThrows(IOException.class,()->disk.partition(0,storage.length).write(0,new byte[512]));
        assertTrue(failure.getMessage().contains("zabezpieczony"));
        assertArrayEquals(data,Arrays.copyOf(storage,512));assertEquals(0,resets);
    }
    @Test public void shortDescriptorSenseExplainsMissingMedium() {
        scsiFailures=1;senseKey=2;senseAsc=0x3a;shortSense=true;
        IOException failure=assertThrows(IOException.class,()->disk.partition(0,storage.length).read(0,new byte[512]));
        assertTrue(failure.getMessage().contains("nie ma nośnika"));assertEquals(0,resets);
    }
    @Test public void botAlternateSettingIsActivatedBeforeScsiAndLogicalUnitQuery() throws Exception {
        when(face.getAlternateSetting()).thenReturn(1);
        new UsbDisk(manager,device).close();UsbDisk.logicalUnits(manager,device);
        verify(link,times(2)).setInterface(face);
        when(link.setInterface(face)).thenReturn(false);
        int before=commands.size();
        assertThrows(IOException.class,()->new UsbDisk(manager,device));
        assertEquals(before,commands.size());
    }
    @Test public void capacity16AcceptsMandatoryGeometryWithCorrectResidue() throws Exception {
        lastLba=0x100001000L;shortCapacity=true;
        try(UsbDisk large=new UsbDisk(manager,device)){assertEquals((lastLba+1)*512,large.capacity);}
        assertEquals(0,resets);
    }
    @Test public void failedResetStopsWithoutReissuingTheDataCommand() {
        alwaysStalled=true;
        when(link.controlTransfer(eq(0x21),eq(0xff),eq(0),eq(0),any(),eq(0),eq(5000))).thenReturn(-1);
        clearInvocations(link);
        IOException failed=assertThrows(IOException.class,()->disk.partition(0,storage.length).read(0,new byte[512]));
        assertTrue(failed.getSuppressed().length>0);
        verify(link,times(1)).bulkTransfer(any(),any(),anyInt(),anyInt(),anyInt());
        assertEquals(0,writes);
    }
    @Test public void comparesReleaseComponentsNumerically() {
        assertTrue(Updates.newer("v1.10.0","1.9.9"));
        assertFalse(Updates.newer("1.0.0","1.0.0"));
        assertFalse(Updates.newer("v1.10.0","2.0.0"));
    }
}
