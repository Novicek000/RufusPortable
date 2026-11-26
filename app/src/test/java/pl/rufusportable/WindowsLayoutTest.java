package pl.rufusportable;

import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.Test;
import static org.junit.Assert.*;

public class WindowsLayoutTest {
    private byte[] uefi() throws Exception {
        return Files.readAllBytes(Path.of("src/main/assets/rufus/uefi-ntfs.img"));
    }
    private byte[] mbr() throws Exception {
        return Files.readAllBytes(Path.of("src/main/assets/rufus/mbr_win7.bin"));
    }
    private byte[] at(WindowsLayout plan,long offset) throws Exception {
        return plan.metadata.ranges.stream().filter(r->r.offset==offset).findFirst().orElseThrow().source.open().readAllBytes();
    }
    private void checkCrc(byte[] descriptor) {
        ByteBuffer header=ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN);
        int expected=header.getInt(16);
        header.putInt(16,0);
        CRC32 crc=new CRC32();crc.update(descriptor,0,92);
        assertEquals(expected,(int)crc.getValue());
    }
    @Test public void gptUsesRufusBasicDataTypeAndNoDriveLetterForUefiNtfs() throws Exception {
        long capacity=32_000_000_000L;
        WindowsLayout plan=WindowsLayout.create(capacity,512,true,4096,uefi(),mbr());
        byte[] first=at(plan,0);
        ByteBuffer data=ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0xee,first[450]&255);
        assertEquals(WindowsLayout.MAIN_START,data.getLong(1024+32));
        assertEquals(Long.MIN_VALUE,data.getLong(1024+128+48));
        assertArrayEquals(Arrays.copyOfRange(first,1024,1040),Arrays.copyOfRange(first,1152,1168));
        long extraStart=data.getLong(1024+128+32);
        assertEquals(0,extraStart%63);
        assertEquals(0,plan.mainLength%4096);
        assertTrue(WindowsLayout.MAIN_START*512+plan.mainLength<=extraStart*512);
        byte[] bootPartition=at(plan,extraStart*512);
        assertArrayEquals(uefi(),Arrays.copyOf(bootPartition,uefi().length));
        checkCrc(Arrays.copyOfRange(first,512,1024));
        checkCrc(at(plan,capacity-512));
    }
    @Test public void mbrContainsOriginalBootCodeAndActiveNtfsPartition() throws Exception {
        WindowsLayout plan=WindowsLayout.create(256L<<20,512,false,4096,uefi(),mbr());
        byte[] first=at(plan,0);
        assertArrayEquals(mbr(),Arrays.copyOf(first,440));
        assertEquals(128,first[446]&255);
        assertEquals(7,first[450]&255);
        assertEquals(0xef,first[466]&255);
    }
    @Test public void invalidGeometryIsRejectedBeforeAnyWrites() throws Exception {
        assertThrows(IOException.class,()->WindowsLayout.create(128L<<30,4096,true,4096,uefi(),mbr()));
        assertThrows(IOException.class,()->WindowsLayout.create(4L<<40,512,false,4096,uefi(),mbr()));
        assertThrows(IOException.class,()->WindowsLayout.create(1L<<20,512,true,4096,uefi(),mbr()));
    }
}
