package pl.rufusportable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** CI-only full applied Windows USB image. The host WIM file substitutes Android's AppFuse descriptor. */
public final class WindowsToGoMediaTest {
    public static void main(String[] args) throws Exception {
        WindowsMediaTest.HttpImage source=new WindowsMediaTest.HttpImage(args[0]);
        List<IsoTree.Entry> entries=UdfTree.scan(source,source.length);
        IsoTree.Entry entry=entries.stream().filter(e->e.path.equalsIgnoreCase("sources/install.wim")).findFirst().orElseThrow();
        Path target=Path.of(args[1]),cache=Path.of(args[2]);Files.createDirectories(cache);
        Path wim=cache.resolve("install-reference.wim");
        try {
            try(InputStream input=new ExtentInputStream(source,entry);OutputStream output=Files.newOutputStream(wim,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[131072];long done=0;int count,last=-1;
                while((count=input.read(buffer))!=-1) {
                    output.write(buffer,0,count);done+=count;int step=(int)(done*10/entry.length);
                    if(step!=last){last=step;System.out.println("CI reference WIM "+step*10+"%");}
                }
            }
            WimMetadata.Image image=WimMetadata.read(wim.toString()).get(0);
            if(!image.platform().equals("x64"))throw new IOException("The VM probe requires x64 Windows");
            WindowsToGoLayout layout=WindowsToGoLayout.create(64L<<30,512,4096,true,new byte[0],null);
            WindowsUnattend.Options options=new WindowsUnattend.Options();options.username="RufusTest";
            options.flags=WindowsUnattend.WINDOWS_TO_GO|WindowsUnattend.SET_USER|WindowsUnattend.NO_ONLINE_ACCOUNT|
                    WindowsUnattend.NO_DATA_COLLECTION|WindowsUnattend.DUPLICATE_LOCALE|WindowsUnattend.DISABLE_BITLOCKER|
                    WindowsUnattend.QOL_ENHANCEMENTS|WindowsUnattend.OFFLINE_INTERNAL_DRIVES;
            options.setupLanguage=image.language;options.fallbackLanguage=image.language;
            byte[] answer=WindowsInstallProbe.firstLogonProbe(new String(WindowsUnattend.create(options),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            try(RandomAccessFile file=new RandomAccessFile(target.toFile(),"rw")) {
                file.setLength(0);file.setLength(layout.capacity);
                BlockDevice disk=new BlockDevice() {
                    public long size(){return layout.capacity;}public int sectorSize(){return 512;}
                    public void read(long offset,byte[] bytes) throws IOException {file.seek(offset);file.readFully(bytes);}
                    public void write(long offset,byte[] bytes) throws IOException {file.seek(offset);file.write(bytes);}
                    public void sync() throws IOException {file.getFD().sync();}
                };
                for(DiskLayout.Range range:layout.metadata.ranges)try(InputStream input=range.source.open()) {
                    byte[] buffer=new byte[131072];file.seek(range.offset);int count;while((count=input.read(buffer))!=-1)file.write(buffer,0,count);
                }
                BlockDevice main=WindowsToGoInstaller.partition(disk,layout.main),esp=WindowsToGoInstaller.partition(disk,layout.esp);
                NtfsVolume.format(main,"WINDOWS_TO_GO",4096,layout.main.offset/512,true);int[] last={-1};
                WimMetadata.apply(wim.toString(),1,main,(done,total,files,totalFiles)->{
                    int percent=total==0?0:(int)(done*100/total);if(percent!=last[0]){last[0]=percent;System.out.println("Apply Windows "+percent+"% files="+files+"/"+totalFiles);}
                });
                try(NtfsVolume volume=new NtfsVolume(main)) {
                    String directory="";
                    for(String part:new String[]{"Windows","Setup","Scripts"}) {
                        String parent=directory;directory=directory.isEmpty()?part:directory+"/"+part;
                        if(volume.list(parent).stream().noneMatch(e->e.name.equalsIgnoreCase(part)))volume.mkdir(directory);
                    }
                    try(OutputStream output=volume.create("Windows/Setup/Scripts/rufus-ci-probe.ps1")){output.write(WindowsInstallProbe.script().getBytes(StandardCharsets.UTF_8));}
                    new WindowsToGoBootFiles(volume).install(layout,esp,cache.toFile(),false,true,false,"x64","en-US",image.displayName(),true,false,answer,
                            (phase,path,done,total)->{});
                }
                disk.sync();System.out.println("Prepared applied Windows To Go image with native BCD, ESP and offline SAN policy");
            }
        } finally {Files.deleteIfExists(wim);}
    }
}
