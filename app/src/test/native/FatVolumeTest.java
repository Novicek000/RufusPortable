package pl.rufusportable;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class FatVolumeTest {
    private static final class Disk implements BlockDevice,Closeable {
        final RandomAccessFile file;
        final long size;
        final int sector;
        long writes;
        boolean corrupt;
        byte[] corruptPrefix;
        Disk(Path path,long size,int sector) throws IOException {this.size=size;this.sector=sector;file=new RandomAccessFile(path.toFile(),"rw");file.setLength(0);file.setLength(size);}
        public long size(){return size;}
        public int sectorSize(){return sector;}
        public void read(long offset,byte[] bytes) throws IOException {if(offset<0 || bytes.length>size-offset)throw new IOException("Out of bounds");file.seek(offset);file.readFully(bytes);}
        public void write(long offset,byte[] bytes) throws IOException {
            if(offset<0 || bytes.length>size-offset)throw new IOException("Out of bounds");
            writes++;file.seek(offset);file.write(bytes);if((corrupt && bytes.length>0) || (corruptPrefix!=null && bytes.length>=corruptPrefix.length &&
                    Arrays.equals(corruptPrefix,Arrays.copyOf(bytes,corruptPrefix.length)))){file.seek(offset);file.write(bytes[0]^1);}
        }
        public void sync() throws IOException {file.getFD().sync();}
        public void close() throws IOException {file.close();}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void invalid(List<IsoTree.Entry> entries) throws Exception {
        try {new FatVolume(entries,128L<<20,512,32,512,"TEST",2048);throw new AssertionError("Invalid FAT tree accepted");}
        catch(IOException expected) {}
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);Files.createDirectories(directory);
        List<IsoTree.Entry> entries=new ArrayList<>();Map<String,byte[]> data=new LinkedHashMap<>();
        for(String path:new String[]{"EFI","EFI/Boot","żółć","wide"})entries.add(new IsoTree.Entry(path,true,0,0));
        data.put("EFI/Boot/bootx64.efi","Original dummy EFI bytes.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        data.put("żółć/日本語😀.txt","Original Unicode data żółć 日本語 😀.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        data.put("mixedCase.TXT",new byte[]{1,2,3});data.put("EMPTY",new byte[0]);
        data.put("thirteenchars",new byte[]{4,5});data.put("abcdefghijkl😀.txt",new byte[]{7,8});
        data.put("straße.txt",new byte[]{9});data.put("STRASSE.txt",new byte[]{10});data.put("x".repeat(251)+".txt",new byte[]{6});
        byte[] payload=new byte[170003];new Random(40).nextBytes(payload);data.put("wide/payload.bin",payload);
        for(int i=0;i<1700;i++)data.put("wide/common-long-prefix-"+i+".txt",new byte[]{(byte)i});
        for(var entry:data.entrySet())entries.add(new IsoTree.Entry(entry.getKey(),false,0,entry.getValue().length));
        Path expected=directory.resolve("expected");Files.createDirectories(expected);
        for(var entry:data.entrySet()) {Path path=expected.resolve(entry.getKey());Files.createDirectories(path.getParent());Files.write(path,entry.getValue());}
        long[][] geometries={{16,32L<<20,512,4096},{16,128L<<20,4096,4096},{32,128L<<20,512,512},{32,512L<<20,4096,4096}};
        for(long[] geometry:geometries) {
            int bits=(int)geometry[0],sector=(int)geometry[2],cluster=(int)geometry[3];long size=geometry[1];
            Path image=directory.resolve("fat"+bits+"-"+sector+".img");
            FatVolume plan=new FatVolume(entries,size,sector,bits,cluster,"RUFUS_TEST",2048);
            try(Disk disk=new Disk(image,size,sector)) {
                byte[] marker=new byte[sector];Arrays.fill(marker,(byte)0x5a);disk.write(size-sector,marker);
                plan.install(disk,e->new ByteArrayInputStream(data.get(e.path)),true,true,(phase,path,done,total)->check(done>=0 && done<=total,"Invalid FAT progress"));
                byte[] actual=new byte[sector];disk.read(size-sector,actual);check(Arrays.equals(marker,actual),"Quick format overwrote unused tail");
            }
        }
        Path empty=directory.resolve("fat32-empty.img");
        try(Disk disk=new Disk(empty,128L<<20,512)) {
            new FatVolume(Collections.emptyList(),disk.size(),512,32,512,"",2048).install(disk,e->{throw new AssertionError();},true,true,(a,b,c,d)->{});
        }
        Path full=directory.resolve("fat16-full.img");
        try(Disk disk=new Disk(full,32L<<20,512)) {
            byte[] marker=new byte[512];Arrays.fill(marker,(byte)0x77);disk.write(disk.size()-512,marker);
            new FatVolume(Collections.emptyList(),disk.size(),512,16,4096,"FULL",2048).install(disk,e->{throw new AssertionError();},false,true,(a,b,c,d)->{});
            byte[] actual=new byte[512];disk.read(disk.size()-512,actual);check(Arrays.equals(new byte[512],actual),"Full format did not zero unused tail");
        }
        Locale previous=Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH"));
            try(Disk disk=new Disk(directory.resolve("fat16-thai.img"),32L<<20,512)) {
                new FatVolume(Collections.emptyList(),disk.size(),512,16,4096,"THAI",2048).install(disk,e->{throw new AssertionError();},true,true,(a,b,c,d)->{});
                byte[] boot=new byte[512];disk.read(0,boot);
                var b=java.nio.ByteBuffer.wrap(boot).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                long rootOffset=(Short.toUnsignedInt(b.getShort(14))+2L*Short.toUnsignedInt(b.getShort(22)))*512;
                byte[] entry=new byte[32];disk.read(rootOffset,entry);
                int date=Short.toUnsignedInt(java.nio.ByteBuffer.wrap(entry).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(16));
                check(1980+(date>>>9)==new GregorianCalendar().get(Calendar.YEAR),"FAT date used a Buddhist year");
            }
        } finally {Locale.setDefault(previous);}
        invalid(Arrays.asList(new IsoTree.Entry("a",false,0,0),new IsoTree.Entry("A",false,0,0)));
        invalid(Collections.singletonList(new IsoTree.Entry("missing/file",false,0,0)));
        invalid(Arrays.asList(new IsoTree.Entry("a",false,0,0),new IsoTree.Entry("a/file",false,0,0)));
        for(String path:new String[]{"../file","a//b","/file","trailing.","x".repeat(256),"bad\\name","bad\u0000name","bad\ud800","bad\uffff"})
            invalid(Collections.singletonList(new IsoTree.Entry(path,false,0,0)));
        invalid(Collections.singletonList(new IsoTree.Entry("large.wim",false,0,0x100000000L)));
        List<IsoTree.Entry> tooMany=new ArrayList<>();for(int i=0;i<512;i++)tooMany.add(new IsoTree.Entry("long-root-name-"+i,false,0,0));
        try {new FatVolume(tooMany,32L<<20,512,16,4096,"TEST",2048);throw new AssertionError("FAT16 root overflow accepted");}catch(IOException expectedError){}
        try(Disk disk=new Disk(directory.resolve("fault.img"),128L<<20,512)) {
            FatVolume plan=new FatVolume(entries,disk.size(),512,32,512,"TEST",2048);disk.writes=0;
            try {plan.install(disk,e->new ByteArrayInputStream(data.get(e.path)),true,true,(a,b,c,d)->{throw new InterruptedIOException("cancel FAT");});throw new AssertionError("Cancellation ignored");}
            catch(InterruptedIOException expectedError) {check(disk.writes==0,"Cancelled before format still wrote data");}
            disk.corrupt=true;
            try {plan.install(disk,e->new ByteArrayInputStream(data.get(e.path)),true,true,(a,b,c,d)->{});throw new AssertionError("Corrupted metadata accepted");}
            catch(IOException expectedError) {check(expectedError.getMessage().contains("Weryfikacja FAT"),"Wrong verification failure: "+expectedError);}
            disk.corrupt=false;disk.corruptPrefix=Arrays.copyOf(payload,16);
            try {plan.install(disk,e->new ByteArrayInputStream(data.get(e.path)),true,true,(a,b,c,d)->{});throw new AssertionError("Corrupted data accepted");}
            catch(IOException expectedError) {check(expectedError.getMessage().contains("wide/payload.bin"),"Wrong data verification failure: "+expectedError);}
            disk.corruptPrefix=null;
            try {plan.install(disk,e->new ByteArrayInputStream(e.path.equals("wide/payload.bin")?new byte[0]:data.get(e.path)),true,true,(a,b,c,d)->{});
                throw new AssertionError("Truncated source accepted");}
            catch(EOFException expectedError) {check(expectedError.getMessage().contains("wide/payload.bin"),"Lost source filename");}
        }
        Files.delete(directory.resolve("fault.img"));
        System.out.println("FAT16/32 tree, LFN, Unicode, aliases, sector sizes, full/quick format, validation and corruption tests passed");
    }
}
