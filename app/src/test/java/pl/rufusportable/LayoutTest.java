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
