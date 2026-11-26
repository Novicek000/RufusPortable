package pl.rufusportable;
import org.junit.Test;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class IsoTreeTest {
    private byte[] record(String name,int block,int length,boolean directory) {
        byte[] n=name.getBytes(StandardCharsets.US_ASCII);
        byte[] r=new byte[33+n.length+(n.length%2==0?1:0)];
        r[0]=(byte)r.length;
        ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).putInt(2,block).putInt(10,length);
        r[25]=(byte)(directory?2:0);r[32]=(byte)n.length;
        System.arraycopy(n,0,r,33,n.length);return r;
    }
    private byte[] image(String filename) {
        byte[] image=new byte[24*2048];
        int p=16*2048;image[p]=1;
        System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII),0,image,p+1,5);
        image[p+128]=0; image[p+129]=8;
        byte[] root=record("\0",20,2048,true);
        System.arraycopy(root,0,image,p+156,root.length);
        image[17*2048]=(byte)255;
        System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII),0,image,17*2048+1,5);
        byte[] file=record(filename,21,5,false);
        System.arraycopy(file,0,image,20*2048,file.length);
        return image;
    }
    private List<IsoTree.Entry> scan(byte[] image) throws IOException {
        return IsoTree.scan((offset,destination)->System.arraycopy(image,(int)offset,destination,0,destination.length),image.length);
    }
    @Test public void locatesSourceExtentWithoutReadingFileContents() throws Exception {
        byte[] image=image("BOOTMGR.;1");
        var entries=scan(image);
        assertEquals(1,entries.size());assertEquals("BOOTMGR",entries.get(0).path);
        assertEquals(21*2048,entries.get(0).offset);assertEquals(5,entries.get(0).length);
    }
    @Test public void rejectsTraversalName() { assertThrows(IOException.class,()->scan(image("../bad"))); }
    @Test public void rejectsExtentOutsideSource() {
        byte[] image=image("INSTALL.WIM;1");
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(20*2048+2,10000);
        assertThrows(IOException.class,()->scan(image));
    }
    @Test public void rejectsDirectoryCycle() {
        byte[] image=image("BOOT"); image[20*2048+25]=2;
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(20*2048+2,20);
        assertThrows(IOException.class,()->scan(image));
    }
}
