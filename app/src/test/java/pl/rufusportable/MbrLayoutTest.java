package pl.rufusportable;

import java.io.IOException;
import java.nio.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MbrLayoutTest {
    @Test public void alignsTrackClusterAndChsAndKeepsTheOriginalBootCode() throws Exception {
        byte[] code=new byte[440];new Random(3).nextBytes(code);
        for(int sector:new int[]{512,4096}) {
            MbrLayout disk=MbrLayout.create(128L<<20,sector,4096,0x0c,true,code);
            byte[] first=disk.metadata.ranges.get(0).source.open().readAllBytes();ByteBuffer b=ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN);
            assertArrayEquals(code,Arrays.copyOf(first,440));assertEquals(128,first[446]&255);assertEquals(0x0c,first[450]&255);
            assertEquals(1048576/sector,b.getInt(454));assertEquals(disk.length/sector,Integer.toUnsignedLong(b.getInt(458)));
            assertEquals(0,disk.length%4096);assertTrue(disk.start+disk.length<=128L<<20);assertEquals(0xaa55,b.getShort(510)&65535);
            long start=disk.start/sector;assertEquals(start/63%255,first[447]&255);assertEquals(start%63+1,first[448]&63);
        }
        assertThrows(IOException.class,()->MbrLayout.create(4L<<40,512,4096,0x0c,true,code));
        assertThrows(IOException.class,()->MbrLayout.create(128L<<20,0,4096,0x0c,true,code));
    }
    @Test public void bootCodeCannotReplaceFatGeometry() throws Exception {
        FatVolume fat=new FatVolume(Collections.emptyList(),128L<<20,512,32,512,"TEST",2048);
        byte[] boot=fat.bootSector(),again=fat.bootSector();assertArrayEquals(boot,again);
        boot[90]=42;fat.useBootSector(boot);assertArrayEquals(boot,fat.bootSector());
        byte[] bad=boot.clone();bad[13]=2;assertThrows(IOException.class,()->fat.useBootSector(bad));assertArrayEquals(boot,fat.bootSector());
        bad[13]=boot[13];bad[510]=0;assertThrows(IOException.class,()->fat.useBootSector(bad));
    }
}
