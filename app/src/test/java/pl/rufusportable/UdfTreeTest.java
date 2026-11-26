package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class UdfTreeTest {
    private final byte[] image=new byte[400*2048];
    private ByteBuffer at(int sector) {
        return ByteBuffer.wrap(image,sector*2048,2048).slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private void seal(int block,int kind,int size) {
        ByteBuffer b=at(block);b.putShort(0,(short)kind).putShort(2,(short)2).putInt(12,block);
        b.putShort(10,(short)size);
        int crc=0,base=block*2048;
        for(int i=16;i<16+size;i++){
            crc^=(image[base+i]&255)<<8;
            for(int j=0;j<8;j++)crc=((crc<<1)^((crc&32768)!=0?0x1021:0))&65535;
        }
        b.putShort(8,(short)crc);
        int sum=0;for(int i=0;i<16;i++)if(i!=4)sum+=image[base+i]&255;
        b.put(4,(byte)sum);
    }
    private void prepare() {
        at(256).putInt(16,4*2048).putInt(20,257);seal(256,2,496);
        at(257).putShort(20,(short)1).putShort(22,(short)7).putInt(188,270).putInt(192,100);seal(257,5,496);
        ByteBuffer logical=at(258);
        logical.putInt(212,2048).putInt(252,0).putShort(256,(short)0).putInt(264,6).putInt(268,1);
        logical.put(440,(byte)1).put(441,(byte)6).putShort(442,(short)1).putShort(444,(short)7);seal(258,6,430);
        seal(259,8,496);
        at(270).putInt(404,1).putShort(408,(short)0);seal(270,256,496);
        at(271).put(27,(byte)4).putLong(56,52).putInt(172,8).putInt(176,52).putInt(180,2);seal(271,261,168);
        ByteBuffer fid=at(272);
        fid.put(19,(byte)12).putInt(24,3).putShort(28,(short)0).put(38,(byte)8);
        fid.position(39);fid.put("install.wim".getBytes(StandardCharsets.ISO_8859_1));seal(272,257,36);
        at(273).put(27,(byte)5).putLong(56,9).putInt(172,16)
                .putInt(176,4).putInt(180,4).putInt(184,5).putInt(188,6);seal(273,261,176);
        System.arraycopy(new byte[]{1,2,3,4},0,image,274*2048,4);
        System.arraycopy(new byte[]{5,6,7,8,9},0,image,276*2048,5);
    }
    private List<IsoTree.Entry> scan() throws IOException {
        return UdfTree.scan((offset,data)->System.arraycopy(image,(int)offset,data,0,data.length),image.length);
    }
    @Test public void readsFileAcrossExtentsInNonzeroPartition() throws Exception {
        prepare();var files=scan();
        assertEquals(1,files.size());assertEquals("install.wim",files.get(0).path);
        assertEquals(2,files.get(0).extents.size());
        try(InputStream in=new ExtentInputStream((offset,data)->System.arraycopy(image,(int)offset,data,0,data.length),files.get(0))){
            assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8,9},in.readAllBytes());
            assertEquals(-1,in.read());
        }
    }
    @Test public void rejectsCorruptDescriptorBeforeReturningFileList() {
        prepare();image[273*2048+56]^=1;assertThrows(IOException.class,this::scan);
    }
    @Test public void rejectsAllocationOutsidePartition() {
        prepare();at(273).putInt(188,140);seal(273,261,176);
        assertThrows(IOException.class,this::scan);
    }
}
