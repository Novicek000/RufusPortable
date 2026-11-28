package pl.rufusportable;

import java.io.IOException;
import java.nio.*;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.Test;
import static org.junit.Assert.*;

public class WindowsToGoLayoutTest {
    private static byte[] at(WindowsToGoLayout disk,long offset) throws Exception {
        return disk.metadata.ranges.stream().filter(r->r.offset==offset).findFirst().orElseThrow().source.open().readAllBytes();
    }
    private static ByteBuffer le(byte[] bytes){return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static UUID guid(byte[] bytes,int offset) {
        ByteBuffer b=le(bytes);return new UUID(((long)b.getInt(offset)<<32)|((b.getShort(offset+4)&65535L)<<16)|(b.getShort(offset+6)&65535L),
                ByteBuffer.wrap(bytes).getLong(offset+8));
    }
    private static int crc(byte[] bytes,int offset,int length){CRC32 c=new CRC32();c.update(bytes,offset,length);return (int)c.getValue();}
    private static void header(byte[] bytes,int offset,long current,long backup,long table,long sectors,UUID id) {
        ByteBuffer b=le(bytes);assertEquals(current,b.getLong(offset+24));assertEquals(backup,b.getLong(offset+32));
        assertEquals(34,b.getLong(offset+40));assertEquals(sectors-34,b.getLong(offset+48));
        assertEquals(id,guid(bytes,offset+56));assertEquals(table,b.getLong(offset+72));
        int expected=b.getInt(offset+16);byte[] copy=Arrays.copyOfRange(bytes,offset,offset+92);le(copy).putInt(16,0);assertEquals(expected,crc(copy,0,92));
    }
    @Test public void gptMatchesRufusEspMsrAndTrackPlacementAtEverySectorSize() throws Exception {
        for(int sector:new int[]{512,1024,2048,4096})for(int cluster:new int[]{4096,8192,32768,65536}) {
            WindowsToGoLayout disk=WindowsToGoLayout.create(64L<<30,sector,cluster,true,new byte[0],null);
            assertEquals(1048576,disk.esp.offset);assertEquals(260L<<20,disk.esp.length);assertEquals(128L<<20,disk.reserved.length);
            long track=63L*sector;
            assertEquals(((disk.esp.offset+disk.esp.length+track-1)/track*track)/cluster*cluster,disk.reserved.offset);
            assertEquals(((disk.reserved.offset+disk.reserved.length+track-1)/track*track)/cluster*cluster,disk.main.offset);
            assertEquals((((64L<<30)-33L*sector-disk.main.offset)/track*track)/cluster*cluster,disk.main.length);
            byte[] first=at(disk,0),backup=at(disk,(64L<<30)-33L*sector);ByteBuffer b=le(first);
            assertEquals(0xee,first[450]&255);assertEquals(1,b.getInt(454));assertEquals(0xaa55,b.getShort(510)&65535);
            long sectors=(64L<<30)/sector;header(first,sector,1,sectors-1,2,sectors,disk.diskId);
            header(backup,32*sector,sectors-1,1,sectors-1-16384/sector,sectors,disk.diskId);
            assertEquals(crc(first,2*sector,16384),b.getInt(sector+88));
            assertArrayEquals(Arrays.copyOfRange(first,2*sector,2*sector+16384),Arrays.copyOfRange(backup,32*sector-16384,32*sector));
            for(int i=0;i<3;i++) {
                WindowsToGoLayout.Partition p=disk.partitions.get(i);int off=2*sector+128*i;
                assertEquals(p.type,guid(first,off));assertEquals(p.id,guid(first,off+16));
                assertEquals(p.offset/sector,b.getLong(off+32));assertEquals((p.offset+p.length)/sector-1,b.getLong(off+40));
                assertEquals(0,b.getLong(off+48));
            }
        }
    }
    @Test public void mbrContainsBiosCodeAndOptionalOriginalUefiNtfs() throws Exception {
        byte[] mbr=new byte[440],uefi=new byte[1048576];new Random(2).nextBytes(mbr);new Random(3).nextBytes(uefi);
        for(boolean efi:new boolean[]{false,true}) {
            WindowsToGoLayout disk=WindowsToGoLayout.create(32L<<30,512,4096,false,mbr,efi?uefi:null);byte[] first=at(disk,0);ByteBuffer b=le(first);
            assertArrayEquals(mbr,Arrays.copyOf(first,440));assertEquals(128,first[446]&255);assertEquals(7,first[450]&255);
            assertEquals(2048,b.getInt(454));assertEquals(disk.main.length/512,Integer.toUnsignedLong(b.getInt(458)));
            assertEquals(disk.diskSignature,b.getInt(440));assertNotEquals(0,disk.diskSignature);
            if(efi) {
                WindowsToGoLayout.Partition aux=disk.partitions.get(1);assertEquals(0,aux.offset%(63*512));assertEquals(0xef,first[466]&255);
                assertArrayEquals(uefi,Arrays.copyOf(at(disk,aux.offset),uefi.length));
            }else assertEquals(1,disk.partitions.size());
        }
    }
    @Test public void rejectsImpossibleGeometryWithoutWriting() throws Exception {
        for(int sector:new int[]{0,1,511,513,8192})assertThrows(IOException.class,()->WindowsToGoLayout.create(64L<<30,sector,4096,true,new byte[0],null));
        assertThrows(IOException.class,()->WindowsToGoLayout.create(256L<<20,512,4096,true,new byte[0],null));
        assertThrows(IOException.class,()->WindowsToGoLayout.create(4L<<40,512,4096,false,new byte[0],null));
        assertThrows(IOException.class,()->WindowsToGoLayout.create(64L<<30,4096,4096,false,new byte[0],null));
    }
    @Test public void bcdQualifiedDeviceUsesBootEnvironmentLayout() throws Exception {
        for(boolean gpt:new boolean[]{false,true}) {
            WindowsToGoLayout disk=WindowsToGoLayout.create(64L<<30,512,4096,gpt,new byte[0],null);
            byte[] bytes=WindowsBootConfiguration.device(disk,disk.main);ByteBuffer b=le(bytes);
            assertEquals(88,bytes.length);assertArrayEquals(new byte[16],Arrays.copyOf(bytes,16));
            assertEquals(6,b.getInt(16));assertEquals(72,b.getInt(24));assertEquals(gpt?0:1,b.getInt(52));
            if(gpt){assertEquals(disk.main.id,guid(bytes,32));assertEquals(disk.diskId,guid(bytes,56));}
            else {assertEquals(disk.main.offset,b.getLong(32));assertEquals(disk.diskSignature,b.getInt(56));}
            WindowsToGoLayout other=WindowsToGoLayout.create(64L<<30,512,4096,true,new byte[0],null);
            assertThrows(IOException.class,()->WindowsBootConfiguration.device(disk,other.main));
        }
    }
    @Test public void capacityChecksExpandedBytesClustersAndNtfsReserveBeforeFormatting() throws Exception {
        Map<String,String> values=new HashMap<>();values.put("TOTALBYTES",Long.toString(20L<<30));values.put("FILECOUNT","150000");values.put("DIRCOUNT","30000");
        WimMetadata.Image image=new WimMetadata.Image(1,values);
        WindowsToGoInstaller.requireCapacity(image,32L<<30,4096);
        assertThrows(IOException.class,()->WindowsToGoInstaller.requireCapacity(image,16L<<30,4096));
        assertThrows(IOException.class,()->WindowsToGoInstaller.requireCapacity(image,32L<<30,2097152));
        values.put("TOTALBYTES",Long.toString(Long.MAX_VALUE));
        assertThrows(IOException.class,()->WindowsToGoInstaller.requireCapacity(new WimMetadata.Image(1,values),Long.MAX_VALUE,4096));
        values.put("TOTALBYTES","-1");
        assertThrows(IOException.class,()->WindowsToGoInstaller.requireCapacity(new WimMetadata.Image(1,values),32L<<30,4096));
        values.remove("TOTALBYTES");
        assertThrows(IOException.class,()->WindowsToGoInstaller.requireCapacity(new WimMetadata.Image(1,values),32L<<30,4096));
    }
    @Test public void legacyOnlyImagesDoNotAcquireAnUnusableUefiTarget() throws Exception {
        assertEquals(1,WindowsToGoInstaller.selectBoot(1,false,0));
        assertEquals(2,WindowsToGoInstaller.selectBoot(2,false,0));
        assertEquals(3,WindowsToGoInstaller.selectBoot(3,false,0));
        assertEquals(2,WindowsToGoInstaller.selectBoot(3,true,0));
        assertEquals(2,WindowsToGoInstaller.selectBoot(3,false,1));
        assertEquals(1,WindowsToGoInstaller.selectBoot(3,false,2));
        assertThrows(IOException.class,()->WindowsToGoInstaller.selectBoot(1,true,0));
        assertThrows(IOException.class,()->WindowsToGoInstaller.selectBoot(1,false,1));
        assertThrows(IOException.class,()->WindowsToGoInstaller.selectBoot(2,false,2));
        assertThrows(IOException.class,()->WindowsToGoInstaller.selectBoot(3,true,2));
        assertThrows(IOException.class,()->WindowsToGoInstaller.selectBoot(0,false,0));
    }
    @Test public void uefiOnlyMbrUsesRufusMarkerAndZeroBootCode() throws Exception {
        byte[] mbr=new byte[440];Arrays.fill(mbr,(byte)0x7f);
        WindowsToGoLayout disk=WindowsToGoLayout.create(32L<<30,512,4096,false,mbr,new byte[1048576],true);
        byte[] first=at(disk,0);assertArrayEquals(new byte[440],Arrays.copyOf(first,440));
        assertEquals(0x49464555,le(first).getInt(440));assertEquals(disk.diskSignature,le(WindowsBootConfiguration.device(disk,disk.main)).getInt(56));
        assertThrows(IOException.class,()->WindowsToGoLayout.create(32L<<30,512,4096,false,mbr,null,true));
    }
}
