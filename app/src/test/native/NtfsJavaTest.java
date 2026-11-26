package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;

public final class NtfsJavaTest {
    private static final class FileDevice implements BlockDevice,Closeable {
        final RandomAccessFile file;
        boolean failRead;
        FileDevice(String name) throws IOException {file=new RandomAccessFile(name,"rw");}
        public long size() {try{return file.length();}catch(IOException e){throw new UncheckedIOException(e);}}
        public int sectorSize() {return 512;}
        public void read(long offset,byte[] data) throws IOException {
            if(failRead)throw new IOException("injected block read failure");
            file.seek(offset);file.readFully(data);
        }
        public void write(long offset,byte[] data) throws IOException {file.seek(offset);file.write(data);}
        public void sync() throws IOException {file.getFD().sync();}
        public void close() throws IOException {file.close();}
    }
    public static void main(String[] args) throws Exception {
        try(FileDevice device=new FileDevice(args[0])) {
            device.failRead=true;
            try {
                new NtfsVolume(device);
                throw new AssertionError("mount succeeded after callback failure");
            } catch(IOException expected) {
                if(!"injected block read failure".equals(expected.getMessage()))throw expected;
            }
            device.failRead=false;
            OutputStream afterClose;
            try(NtfsVolume volume=new NtfsVolume(device)) {
                volume.mkdir("efi");volume.mkdir("efi/boot");volume.mkdir("żółć");
                try(OutputStream file=volume.create("efi/boot/payload.bin")) {
                    byte[] data=new byte[170001];
                    for(int i=0;i<data.length;i++)data[i]=(byte)(i*17+9);
                    file.write(data,5,169993);
                }
                afterClose=volume.create("unfinished.bin");
                afterClose.write(new byte[]{2,4,6,8});
                // Closing the owning volume must close its remaining files as well.
            }
            try {afterClose.write(1);throw new AssertionError("write after close");}
            catch(IOException expected) {}
        }
        System.out.println("JNI callbacks, exception propagation and Java handle lifetime passed");
    }
}
