package pl.rufusportable;

import java.io.*;
import java.util.*;

/** Native WIM -> NTFS -> Windows boot configuration; no installer or third-party boot manager. */
final class WindowsToGoInstaller {
    interface Progress {void update(String phase,String path,long done,long total) throws IOException;}
    /** firmware: 0=available image boot files, 1=UEFI, 2=BIOS. Result: BIOS bit 0, UEFI bit 1. */
    static int selectBoot(int capabilities,boolean gpt,int firmware) throws IOException {
        if(capabilities<0 || capabilities>3 || firmware<0 || firmware>2)throw new IOException("Nieprawidłowy wybór rozruchu Windows To Go.");
        if(gpt && firmware==2)throw new IOException("Rozruch BIOS wymaga schematu MBR.");
        int requested=gpt || firmware==1?2:firmware==2?1:capabilities;
        if(requested==0 || (capabilities&requested)!=requested)
            throw new IOException(gpt?"Wybrany Windows nie obsługuje UEFI. Wybierz MBR i rozruch BIOS.":"Obraz nie zawiera plików dla wybranego rodzaju rozruchu.");
        return requested;
    }
    static BlockDevice partition(BlockDevice disk,WindowsToGoLayout.Partition part) throws IOException {
        if(part==null || part.offset<0 || part.length<=0 || part.offset%disk.sectorSize()!=0 ||
                part.length%disk.sectorSize()!=0 || part.offset>disk.size() || part.length>disk.size()-part.offset)
            throw new IOException("Nieprawidłowy zakres partycji Windows To Go.");
        return new BlockDevice() {
            public long size(){return part.length;}public int sectorSize(){return disk.sectorSize();}
            private void bounds(long offset,int length) throws IOException {
                if(offset<0 || length>part.length-offset)throw new IOException("Operacja poza partycją Windows To Go.");
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            }
            public void read(long offset,byte[] bytes) throws IOException {bounds(offset,bytes.length);disk.read(part.offset+offset,bytes);}
            public void write(long offset,byte[] bytes) throws IOException {bounds(offset,bytes.length);disk.write(part.offset+offset,bytes);}
            public void sync() throws IOException {disk.sync();}
        };
    }
    static void install(BlockDevice disk,WindowsToGoLayout layout,String wim,int index,String label,int cluster,boolean quick,
            boolean bios,boolean efi,File cache,WindowsUnattend.Options options,byte[] bootPrefix,byte[] bootCode,Progress progress) throws IOException {
        if(disk.size()!=layout.capacity || disk.sectorSize()!=layout.sectorSize || !WindowsInstaller.validLabel(label) || (!bios && !efi) ||
                (layout.gpt && (bios || !efi)) || (!layout.gpt && efi && layout.partitions.size()!=2) ||
                (bios && (bootPrefix.length!=11 || bootCode.length!=4052)) || !cache.isDirectory())
            throw new IOException("Nieprawidłowe parametry Windows To Go.");
        layout.metadata.validate(disk.size(),disk.sectorSize());WindowsUnattend.validate(options);
        if((options.flags&WindowsUnattend.WINDOWS_TO_GO)==0 || (options.flags&WindowsUnattend.SILENT_INSTALL)!=0)
            throw new IOException("Opcje instalatora dysku wewnętrznego nie pasują do Windows To Go.");
        WimMetadata.Image image=WimMetadata.read(wim).stream().filter(i->i.index==index).findFirst()
                .orElseThrow(()->new IOException("Nie ma wybranej edycji w obrazie Windows."));
        int architecture;
        switch(image.platform()){case "x86":architecture=1;break;case "x64":architecture=2;break;case "ARM":architecture=3;break;case "ARM64":architecture=4;break;default:throw new IOException("Nieobsługiwana architektura Windows To Go.");}
        if(options.architecture!=architecture)throw new IOException("Ustawienia odpowiedzi nie pasują do architektury Windows.");
        if(bios && architecture>2)throw new IOException("Obraz ARM wymaga rozruchu UEFI.");
        requireCapacity(image,layout.main.length,cluster);
        byte[] answer=WindowsUnattend.create(options);BlockDevice main=partition(disk,layout.main);
        BlockDevice esp=layout.gpt?partition(disk,layout.esp):null;
        progress.update("format","NTFS",0,1);
        for(DiskLayout.Range range:layout.metadata.ranges) {
            byte[] buffer=new byte[131072];long offset=range.offset;
            try(InputStream input=range.source.open()) {
                long remaining=range.length;
                while(remaining>0) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    int count=(int)Math.min(buffer.length,remaining),read=0;
                    while(read<count){int n=input.read(buffer,read,count-read);if(n<=0)throw new EOFException("Niepełne metadane partycji.");read+=n;}
                    disk.write(offset,count==buffer.length?buffer:Arrays.copyOf(buffer,count));offset+=count;remaining-=count;
                }
            }
        }
        disk.sync();NtfsVolume.format(main,label,cluster,layout.main.offset/layout.sectorSize,quick);
        if(bios)WindowsInstaller.installBootCode(main,bootPrefix,bootCode,true);
        WimMetadata.apply(wim,index,main,(done,total,files,totalFiles)->progress.update("apply","Windows · "+files+" / "+totalFiles,done,total));
        try(NtfsVolume volume=new NtfsVolume(main)) {
            new WindowsToGoBootFiles(volume).install(layout,esp,cache,bios,efi,(options.flags&WindowsUnattend.USE_MS2023_BOOTLOADERS)!=0,
                    image.platform(),"en-US",image.displayName(),(options.flags&WindowsUnattend.OFFLINE_INTERNAL_DRIVES)!=0,
                    (options.flags&WindowsUnattend.FORCE_S_MODE)!=0,answer,
                    (phase,path,done,total)->progress.update(phase,path,done,total));
        }
        disk.sync();progress.update("complete","Windows To Go",1,1);
    }
    static void requireCapacity(WimMetadata.Image image,long capacity,int cluster) throws IOException {
        if(capacity<=0 || cluster<512 || cluster>2097152 || Integer.bitCount(cluster)!=1)throw new IOException("Nieprawidłowa geometria Windows To Go.");
        try {
            long bytes=Long.parseLong(image.property("TOTALBYTES")),files=Long.parseLong(image.property("FILECOUNT")),directories=Long.parseLong(image.property("DIRCOUNT"));
            if(bytes<0 || files<0 || directories<0)throw new NumberFormatException();
            long required=Math.addExact(Math.addExact(bytes,Math.multiplyExact(Math.addExact(files,directories),cluster)),128L<<20);
            if(required>capacity)throw new IOException("Rozpakowany Windows i struktury NTFS nie mieszczą się na nośniku.");
        }catch(NumberFormatException|ArithmeticException error){throw new IOException("Nieprawidłowa informacja o rozmiarze obrazu Windows.",error);}
    }
    private WindowsToGoInstaller(){}
}
