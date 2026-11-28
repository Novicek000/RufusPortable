package pl.rufusportable;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Applies self-generated WIMs through the same callback-backed NTFS backend as Android. */
public final class WimApplyTest {
    private static final class Disk implements BlockDevice,Closeable {
        final RandomAccessFile file;
        final long size;
        long writes,high;
        boolean failWrite,failSync;
        Disk(Path path,long size) throws IOException {this.size=size;file=new RandomAccessFile(path.toFile(),"rw");file.setLength(0);file.setLength(size);}
        public long size(){return size;}
        public int sectorSize(){return 512;}
        public void read(long offset,byte[] data) throws IOException {file.seek(offset);file.readFully(data);}
        public void write(long offset,byte[] data) throws IOException {
            if(failWrite)throw new IOException("injected WIM target write failure");
            writes++;high=Math.max(high,offset+data.length);file.seek(offset);file.write(data);
        }
        public void sync() throws IOException {if(failSync)throw new IOException("injected WIM target sync failure");file.getFD().sync();}
        public void close() throws IOException {file.close();}
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    private static void verify(Disk disk,Path original) throws Exception {
        try(NtfsVolume volume=new NtfsVolume(disk)) {
            for(String path:new String[]{"rich/data.bin","rich/alias.bin","żółć/日本語.txt","empty.txt"}) {
                try(InputStream input=volume.open(path)) {
                    check(Arrays.equals(Files.readAllBytes(original.resolve(path)),input.readAllBytes()),"WIM apply content: "+path);
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        check(Arrays.equals(new boolean[]{false,true,false,true},WimMetadata.containsFiles(args[0],1,"rich","RICH/DATA.BIN","unicode-link","żółć/日本語.txt")),
                "WIM probe directory, case, reparse and Unicode");
        Path target=Path.of(args[1]),source=Path.of(args[2]);
        try(Disk disk=new Disk(target,128L<<20)) {
            NtfsVolume.format(disk,"WIM_APPLY",4096,2048,true);disk.writes=0;
            try {WimMetadata.apply(args[0],2,disk,(a,b,c,d)->{});throw new AssertionError("Invalid image accepted");}
            catch(IOException expected) {}
            check(disk.writes==0,"Invalid image wrote target");
            int[] callbacks={0};long[] last={0,0};
            WimMetadata.apply(args[0],1,disk,(done,total,files,totalFiles)->{
                check(done>=0 && done<=total && files>=0 && files<=totalFiles,"Invalid extraction progress");
                callbacks[0]++;last[0]=done;last[1]=total;
            });
            check(callbacks[0]>1 && last[0]==last[1] && last[1]>0,"Incomplete extraction progress");
            verify(disk,source);
            // A cancelled callback and a failed device must unwind the TLS lease and JNI references.
            NtfsVolume.format(disk,"WIM_CANCEL",4096,2048,true);disk.writes=0;
            try {WimMetadata.apply(args[0],1,disk,(a,b,c,d)->{throw new InterruptedIOException("injected WIM cancellation");});
                throw new AssertionError("Cancellation ignored");}
            catch(InterruptedIOException expected) {check(expected.getMessage().equals("injected WIM cancellation"),"Lost cancellation");}
            check(disk.writes==0,"Initial progress cancellation wrote target");
            disk.failWrite=true;
            try {WimMetadata.apply(args[0],1,disk,(a,b,c,d)->{});throw new AssertionError("Target write failure ignored");}
            catch(IOException expected) {check(expected.getMessage().equals("injected WIM target write failure"),"Lost write failure: "+expected);}
            finally {disk.failWrite=false;}
            NtfsVolume.format(disk,"WIM_SYNC",4096,2048,true);disk.failSync=true;
            try {WimMetadata.apply(args[0],1,disk,(a,b,c,d)->{});throw new AssertionError("Target sync failure ignored");}
            catch(IOException expected) {check(expected.getMessage().equals("injected WIM target sync failure"),"Lost sync failure: "+expected);}
            finally {disk.failSync=false;}
            NtfsVolume.format(disk,"WIM_APPLY",4096,2048,true);
            WimMetadata.apply(args[0],1,disk,(a,b,c,d)->{});verify(disk,source);
        }
        Path large=target.resolveSibling("apply-large.img");
        try(Disk disk=new Disk(large,64L<<30)) {
            NtfsVolume.format(disk,"WIM_LARGE",4096,2048,true);disk.high=0;
            WimMetadata.apply(args[0],1,disk,(a,b,c,d)->{});
            check(disk.high>0xffffffffL,"NTFS apply did not exercise 64-bit target offsets");verify(disk,source);
        } finally {Files.delete(large);}
        System.out.println("Native WIM apply, Unicode, contents, high offsets, progress, cancellation and device failures passed");
    }
}
