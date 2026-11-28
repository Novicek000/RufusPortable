package pl.rufusportable;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SyslinuxAndroidTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private byte[] resource(String name) throws IOException {
        try(InputStream input=context.getAssets().open("syslinux/"+name);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);return output.toByteArray();
        }
    }
    private static final class Disk implements BlockDevice,Closeable {
        final RandomAccessFile file;final long length;
        Disk(File path,long length) throws IOException {file=new RandomAccessFile(path,"rw");file.setLength(length);this.length=length;}
        public long size(){return length;}public int sectorSize(){return 512;}
        public void read(long offset,byte[] data) throws IOException {if(offset<0 || data.length>length-offset)throw new IOException("Bounds");file.seek(offset);file.readFully(data);}
        public void write(long offset,byte[] data) throws IOException {if(offset<0 || data.length>length-offset)throw new IOException("Bounds");file.seek(offset);file.write(data);}
        public void sync() throws IOException {file.getFD().sync();}public void close() throws IOException {file.close();}
    }
    @Test public void originalSyslinuxInstallsAndVerifiesFat16AndFat32WithTheSignedModule() throws Exception {
        byte[] module=resource("ldlinux_v6.c32"),signature=resource("ldlinux_v6.c32.sig");RufusSignature.verify(module,signature);
        for(int version:new int[]{4,6})for(int bits:new int[]{16,32}) {
            File target=File.createTempFile("syslinux-target-", ".img",context.getCacheDir());
            try(Disk disk=new Disk(target,bits==16?32L<<20:128L<<20)) {
                byte[] system=resource("ldlinux_v"+version+".sys"),bss=resource("ldlinux_v"+version+".bss"),config="PROMPT 1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                SyslinuxVolume volume=new SyslinuxVolume(Collections.singletonList(new IsoTree.Entry("syslinux.cfg",false,0,config.length)),
                        disk.size(),512,bits,bits==16?4096:512,"RUFUS_SYS",2048,system,bss,version==6?module:null,version==6?signature:null,"",false,false);
                volume.install(disk,e->{assertEquals("syslinux.cfg",e.path);return new ByteArrayInputStream(config);},true,true,(phase,path,done,total)->{assertTrue(done>=0 && done<=total);});
                byte[] boot=new byte[512];disk.read(0,boot);assertEquals(0xaa55,ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN).getShort(510)&65535);
                assertEquals(0x3eb202fe,ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN).getInt(504));
                if(bits==32){byte[] backup=new byte[512];disk.read(6*512,backup);assertArrayEquals(boot,backup);}
            } finally {assertTrue(target.delete());}
        }
    }
    @Test public void nativeSyslinuxKeepsHighSectorBitsAndDoesNotMutateInputs() throws Exception {
        byte[] system=resource("ldlinux_v6.sys"),bss=resource("ldlinux_v6.bss"),before=system.clone();
        byte[] vbr=new byte[512];ByteBuffer b=ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{(byte)0xeb,0x52,(byte)0x90}).put("NTFS    ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putShort(11,(short)512).put(13,(byte)8).put(21,(byte)0xf8).putLong(40,1L<<36).putShort(510,(short)0xaa55);
        long[] sectors=new long[(system.length+511)/512+2];for(int i=0;i<sectors.length;i++)sectors[i]=0x100000123L+i;
        SyslinuxPatch.Result result=SyslinuxPatch.patch(system,bss,vbr,sectors,"",false,false);assertArrayEquals(before,system);
        ByteBuffer original=ByteBuffer.wrap(system).order(ByteOrder.LITTLE_ENDIAN);int epa=original.getShort(46)&65535;
        int low=original.getShort(epa+14)&65535,high=original.getShort(epa+16)&65535;ByteBuffer boot=ByteBuffer.wrap(result.bootSector).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(sectors[0],Integer.toUnsignedLong(boot.getInt(low))|(Integer.toUnsignedLong(boot.getInt(high))<<32));
        sectors[0]=-1;try{SyslinuxPatch.patch(system,bss,vbr,sectors,"",false,false);fail("Invalid LBA accepted");}catch(IOException expected){}
        byte[] module=resource("ldlinux_v6.c32");module[50]^=1;
        try{RufusSignature.verify(module,resource("ldlinux_v6.c32.sig"));fail("Modified module accepted");}catch(IOException expected){}
    }
}
