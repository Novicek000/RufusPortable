package pl.rufusportable;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class WindowsInstallerTest {
    private static final class FileDevice implements BlockDevice, Closeable {
        final RandomAccessFile file;
        final long start, length;
        boolean corruptPayload;
        FileDevice(String name,long start,long length) throws IOException {
            file=new RandomAccessFile(name,"rw");
            file.setLength(start+length);
            this.start=start;this.length=length;
        }
        public long size() {return length;}
        public int sectorSize() {return 512;}
        public void read(long offset,byte[] data) throws IOException {
            file.seek(start+offset);file.readFully(data);
            if(corruptPayload && data.length>=32768) {data[0]^=1;corruptPayload=false;}
        }
        public void write(long offset,byte[] data) throws IOException {
            file.seek(start+offset);file.write(data);
        }
        public void sync() throws IOException {file.getFD().sync();}
        public void close() throws IOException {file.close();}
    }
    public static void main(String[] args) throws Exception {
        Path assets=Path.of(args[1]);
        List<IsoTree.Entry> entries=new ArrayList<>();
        Map<String,byte[]> contents=new LinkedHashMap<>();
        for(String directory:new String[]{"sources","efi","efi/boot"})
            entries.add(new IsoTree.Entry(directory,true,0,0));
        contents.put("sources/boot.wim",new byte[170003]);
        contents.put("efi/boot/bootx64.efi",new byte[513]);
        contents.put("sources/install.wim",new byte[390001]);
        Random random=new Random(173);
        for(var file:contents.entrySet()) {
            random.nextBytes(file.getValue());
            entries.add(new IsoTree.Entry(file.getKey(),false,0,file.getValue().length));
        }
        try(FileDevice partition=new FileDevice(args[0],1048576,256L<<20)) {
            WindowsInstaller plan=new WindowsInstaller(entries,partition.size(),4096);
            byte[] prefix=Files.readAllBytes(assets.resolve("br_ntfs_0x0.bin"));
            byte[] boot=Files.readAllBytes(assets.resolve("br_ntfs_0x54.bin"));
            plan.install(partition,e->new ByteArrayInputStream(contents.get(e.path)),
                    "WINDOWS",4096,true,prefix,boot,true,(verify,path,done,total)->{});
            try(NtfsVolume volume=new NtfsVolume(partition)) {
                for(var file:contents.entrySet()) {
                    try(InputStream in=volume.open(file.getKey())) {
                        if(!Arrays.equals(file.getValue(),in.readAllBytes()))
                            throw new AssertionError("extracted data mismatch "+file.getKey());
                    }
                }
            }
            boolean[] injected={false};
            try {
                plan.install(partition,e->new ByteArrayInputStream(contents.get(e.path)),
                        "WINDOWS",4096,true,prefix,boot,true,(verify,path,done,total)->{
                            if(verify && !injected[0]) {partition.corruptPayload=true;injected[0]=true;}
                        });
                throw new AssertionError("corrupt NTFS readback accepted");
            } catch(IOException expected) {
                if(!expected.getMessage().contains("Weryfikacja pliku NTFS"))throw expected;
            }
            byte[] primary=new byte[512],backup=new byte[512];
            partition.read(0,primary);partition.read(partition.size()-512,backup);
            if(!Arrays.equals(primary,backup))throw new AssertionError("backup NTFS boot sector mismatch");
        }
        try {
            new WindowsInstaller(List.of(new IsoTree.Entry("../bad",false,0,1)),256L<<20,4096);
            throw new AssertionError("unsafe image accepted");
        } catch(IOException expected) {}
        System.out.println("Windows extraction, NTFS boot code and SHA-256 verification passed");
    }
}
