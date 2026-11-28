package pl.rufusportable;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class FatGeometryTest {
    @Test public void matchesRufusDefaultsAcrossCapacityThresholds() throws Exception {
        long mb=1L<<20,gb=1L<<30;
        assertEquals(512,FatGeometry.cluster(16*mb,16,0));
        assertEquals(1024,FatGeometry.cluster(32*mb,16,0));
        assertEquals(4096,FatGeometry.cluster(128*mb,16,0));
        assertEquals(65536,FatGeometry.cluster(4095*mb,16,0));
        assertEquals(512,FatGeometry.cluster(64*mb,32,0));
        assertEquals(2048,FatGeometry.cluster(256*mb,32,0));
        assertEquals(4096,FatGeometry.cluster(260*mb,32,0));
        assertEquals(4096,FatGeometry.cluster(8*gb,32,0));
        assertEquals(8192,FatGeometry.cluster(9*gb,32,0));
        assertEquals(16384,FatGeometry.cluster(17*gb,32,0));
        assertEquals(32768,FatGeometry.cluster(32*gb,32,0));
        assertEquals(32768,FatGeometry.cluster(1024*gb,32,0));
    }
    @Test public void rejectsImpossibleGeometryBeforeAnyDeviceWrites() throws Exception {
        assertThrows(IOException.class,()->FatGeometry.cluster(32L<<30,16,0));
        assertThrows(IOException.class,()->FatGeometry.cluster(2048L<<30,32,0));
        assertThrows(IOException.class,()->FatGeometry.cluster(16L<<20,32,0));
        assertThrows(IOException.class,()->FatGeometry.cluster(128L<<20,32,65536));
        assertThrows(IOException.class,()->FatGeometry.cluster(128L<<20,16,512));
        assertThrows(IOException.class,()->FatGeometry.cluster(128L<<20,32,1234));
        assertEquals(2048,FatGeometry.cluster(128L<<20,16,2048));
    }
}
