package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.zip.CRC32;
import org.junit.Test;
import static org.junit.Assert.*;

public class LayoutTest {
    private final Path assets=Path.of("src/main/assets");
    private DiskLayout.Range at(DiskLayout disk,long offset) {
        return disk.ranges.stream().filter(r->r.offset==offset).findFirst().orElseThrow();
    }
    @Test public void createsLargeIsoWithoutReadingSourceDuringPreparation() throws Exception {
        long length=(5L<<30)+197,capacity=31_000_000_000L/512*512;
        for (boolean gpt:new boolean[]{true,false}) {
            DiskLayout layout=VentoyLayout.create(n->Files.newInputStream(assets.resolve("ventoy").resolve(n)),capacity,
                    512,gpt,"Windows",256,"Windows 11.iso",length,()->{throw new AssertionError("eager ISO read");});
            assertEquals(length,layout.ranges.get(0).length);
            ByteBuffer boot=VentoyLayout.little(at(layout,2048L*512).source.open().readAllBytes());
            assertEquals(2048,boot.getLong(64));
            byte[] start=at(layout,0).source.open().readAllBytes();
            assertEquals(gpt?238:7,start[450]&255);
            if (gpt) {
                ByteBuffer header=VentoyLayout.little(start);
                int expected=header.getInt(528); header.putInt(528,0);
                CRC32 crc=new CRC32();crc.update(start,512,92);
                assertEquals(expected,(int)crc.getValue());
                assertEquals(512,at(layout,capacity-512).length);
            }
        }
    }
    @Test public void rejectsIsoThatLeavesNoRoomForBootPartition() {
        assertThrows(IOException.class,()->VentoyLayout.create(n->Files.newInputStream(assets.resolve("ventoy").resolve(n)),
                512L<<20,512,false,"Ventoy",256,"test.iso",512L<<20,()->null));
    }
    @Test public void formatsFat32WithAndWithoutDos() throws Exception {
        for (boolean dos:new boolean[]{true,false}) {
            DiskLayout layout=Fat32.create(n->Files.newInputStream(assets.resolve("freedos").resolve(n)),512L<<20,512,dos,"FREEDOS");
            byte[] mbr=at(layout,0).source.open().readAllBytes();
            assertEquals(dos?128:0,mbr[446]&255);
            assertEquals(12,mbr[450]);
            assertEquals(dos,layout.ranges.stream().anyMatch(r->r.length==46256));
        }
    }
}
