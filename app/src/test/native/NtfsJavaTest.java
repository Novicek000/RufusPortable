package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;

public final class NtfsJavaTest {
    private static final class FileDevice implements BlockDevice,Closeable {
        final RandomAccessFile file;
        boolean failRead, failWrite, failSync;
        FileDevice(String name) throws IOException {file=new RandomAccessFile(name,"rw");}
        public long size() {try{return file.length();}catch(IOException e){throw new UncheckedIOException(e);}}
        public int sectorSize() {return 512;}
        public void read(long offset,byte[] data) throws IOException {
            if(failRead)throw new IOException("injected block read failure");
            file.seek(offset);file.readFully(data);
        }
        public void write(long offset,byte[] data) throws IOException {
            if(failWrite)throw new IOException("injected block write failure");
            file.seek(offset);file.write(data);
        }
        public void sync() throws IOException {
            if(failSync)throw new IOException("injected block sync failure");
            file.getFD().sync();
        }
        public void close() throws IOException {file.close();}
    }
    public static void main(String[] args) throws Exception {
        try(FileDevice device=new FileDevice(args[0])) {
            device.failWrite=true;
            try {
                NtfsVolume.format(device,"FAILED",4096,2048,true);
                throw new AssertionError("format succeeded after callback failure");
            } catch(IOException expected) {
                if(!"injected block write failure".equals(expected.getMessage()))throw expected;
            }
            device.failWrite=false;
            device.failSync=true;
            try {
                NtfsVolume.format(device,"FAILED_SYNC",4096,2048,true);
                throw new AssertionError("format succeeded after synchronization failure");
            } catch(IOException expected) {
                if(!"injected block sync failure".equals(expected.getMessage()))throw expected;
            }
            device.failSync=false;
            NtfsVolume.format(device,"RUFUS_żółć",4096,2048,true);
            device.failRead=true;
            try {
                new NtfsVolume(device);
                throw new AssertionError("mount succeeded after callback failure");
            } catch(IOException expected) {
                if(!"injected block read failure".equals(expected.getMessage()))throw expected;
            }
            device.failRead=false;
            OutputStream afterClose;
            NtfsVolume.RandomFile randomAfterClose;
            try(NtfsVolume volume=new NtfsVolume(device)) {
                volume.mkdir("efi");volume.mkdir("efi/boot");volume.mkdir("żółć");
                try(OutputStream file=volume.create("efi/boot/payload.bin")) {
                    byte[] data=new byte[170001];
                    for(int i=0;i<data.length;i++)data[i]=(byte)(i*17+9);
                    file.write(data,5,169993);
                }
                try(InputStream in=volume.open("efi/boot/payload.bin")) {
                    byte[] actual=in.readAllBytes();
                    byte[] expected=new byte[169993];
                    for(int i=0;i<expected.length;i++)expected[i]=(byte)((i+5)*17+9);
                    if(!java.util.Arrays.equals(actual,expected))throw new AssertionError("native read mismatch");
                    if(in.read()!=-1 || in.read(new byte[1],0,0)!=0)throw new AssertionError("EOF handling");
                }
                try(OutputStream file=volume.create("random.bin")) {file.write(new byte[200003]);}
                randomAfterClose=volume.openRandom("random.bin");
                byte[] patch=new byte[170007];
                for(int i=0;i<patch.length;i++)patch[i]=(byte)(i*13+7);
                randomAfterClose.write(103,patch,3,170001);
                byte[] actual=new byte[170001];
                if(randomAfterClose.read(103,actual,0,actual.length)!=actual.length ||
                        !java.util.Arrays.equals(actual,java.util.Arrays.copyOfRange(patch,3,170004)))
                    throw new AssertionError("random read/write mismatch");
                randomAfterClose.truncate(70001);
                if(randomAfterClose.size()!=70001)throw new AssertionError("truncate size mismatch");
                randomAfterClose.truncate(140003);
                byte[] extension=new byte[70002];
                if(randomAfterClose.read(70001,extension,0,extension.length)!=extension.length ||
                        !java.util.Arrays.equals(extension,new byte[extension.length]))
                    throw new AssertionError("extended bytes were not zeroed");
                if(randomAfterClose.read(140003,new byte[8],0,8)!=0)throw new AssertionError("random EOF mismatch");
                try {randomAfterClose.truncate(-1);throw new AssertionError("negative truncate accepted");}
                catch(IOException expected) {}
                device.failSync=true;
                try {randomAfterClose.sync();throw new AssertionError("file sync failure lost");}
                catch(IOException expected) {
                    if(!"injected block sync failure".equals(expected.getMessage()))throw expected;
                }
                device.failSync=false;randomAfterClose.sync();
                afterClose=volume.create("unfinished.bin");
                afterClose.write(new byte[]{2,4,6,8});
                // Closing the owning volume must close its remaining files as well.
            }
            try {randomAfterClose.size();throw new AssertionError("random file remained open after volume close");}
            catch(IOException expected) {}
            try {afterClose.write(1);throw new AssertionError("write after close");}
            catch(IOException expected) {}
        }
        System.out.println("JNI callbacks, exception propagation and Java handle lifetime passed");
    }
}
