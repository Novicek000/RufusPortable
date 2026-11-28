package pl.rufusportable;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class SyslinuxVolumeTest {
    private static final class Disk implements BlockDevice,Closeable {
        final RandomAccessFile file;final long length;
        Disk(Path path,long length) throws IOException {file=new RandomAccessFile(path.toFile(),"rw");file.setLength(0);file.setLength(length);this.length=length;}
        public long size(){return length;}public int sectorSize(){return 512;}
        public void read(long offset,byte[] data) throws IOException {if(offset<0 || data.length>length-offset)throw new IOException("Bounds");file.seek(offset);file.readFully(data);}
        public void write(long offset,byte[] data) throws IOException {if(offset<0 || data.length>length-offset)throw new IOException("Bounds");file.seek(offset);file.write(data);}
        public void sync() throws IOException {file.getFD().sync();}public void close() throws IOException {file.close();}
    }
    private static byte[] read(Path assets,String name) throws IOException {return Files.readAllBytes(assets.resolve(name));}
    public static void main(String[] args) throws Exception {
        Path assets=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        for(int version:new int[]{4,6})for(int bits:new int[]{16,32}) {
            String name="syslinux"+version+"-fat"+bits;int cluster=bits==16?4096:512;long size=bits==16?64L<<20:128L<<20;
            MbrLayout layout=MbrLayout.create(size,512,cluster,bits==16?0x0e:0x0c,true,read(assets,"mbr.bin"));
            byte[] config=("SERIAL 0 115200\nPROMPT 1\nTIMEOUT 0\nSAY RUFUS_NATIVE_"+name.toUpperCase(Locale.ROOT)+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            List<IsoTree.Entry> entries=Collections.singletonList(new IsoTree.Entry("syslinux.cfg",false,0,config.length));
            byte[] system=read(assets,"ldlinux_v"+version+".sys"),bss=read(assets,"ldlinux_v"+version+".bss");
            byte[] module=version==6?read(assets,"ldlinux_v6.c32"):null,signature=version==6?read(assets,"ldlinux_v6.c32.sig"):null;
            SyslinuxVolume plan=new SyslinuxVolume(entries,layout.length,512,bits,cluster,"RUFUS_SYS",layout.start/512,system,bss,module,signature,"",false,false);
            Path partition=output.resolve(name+"-partition.img");
            try(Disk disk=new Disk(partition,layout.length)) {
                plan.install(disk,e->{if(!e.path.equals("syslinux.cfg"))throw new AssertionError("Unexpected source "+e.path);return new ByteArrayInputStream(config);},true,true,(phase,path,done,total)->{
                    if(done<0 || done>total)throw new AssertionError("Progress bounds");
                });
            }
            try(Disk disk=new Disk(output.resolve(name+".img"),size)) {
                for(DiskLayout.Range range:layout.metadata.ranges) {
                    try(InputStream input=range.source.open()) {byte[] bytes=new byte[(int)range.length];new DataInputStream(input).readFully(bytes);disk.write(range.offset,bytes);}
                }
                try(InputStream input=Files.newInputStream(partition)) {
                    byte[] bytes=new byte[131072];long position=layout.start;int count;
                    while((count=input.read(bytes))!=-1) {
                        boolean zero=true;for(int i=0;i<count;i++)if(bytes[i]!=0){zero=false;break;}
                        if(!zero)disk.write(position,count==bytes.length?bytes:Arrays.copyOf(bytes,count));position+=count;
                    }
                }
                disk.sync();
            }
            System.out.println("Prepared and SHA-256 verified "+name+" with the original Rufus MBR and libinstaller");
        }
    }
}
