package pl.rufusportable;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class WindowsToGoBootFilesTest {
    private static final class Disk implements BlockDevice,Closeable {
        final RandomAccessFile file;final long length;final int sector;
        Disk(Path path,long length,int sector) throws IOException {file=new RandomAccessFile(path.toFile(),"rw");file.setLength(0);file.setLength(length);this.length=length;this.sector=sector;}
        public long size(){return length;}public int sectorSize(){return sector;}
        public void read(long offset,byte[] bytes) throws IOException {if(offset<0 || bytes.length>length-offset)throw new IOException("Bounds");file.seek(offset);file.readFully(bytes);}
        public void write(long offset,byte[] bytes) throws IOException {if(offset<0 || bytes.length>length-offset)throw new IOException("Bounds");file.seek(offset);file.write(bytes);}
        public void sync() throws IOException {file.getFD().sync();}public void close() throws IOException {file.close();}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void put(NtfsVolume volume,String path,byte[] bytes) throws Exception {
        String parent=path.substring(0,path.lastIndexOf('/'));String done="";
        for(String part:parent.split("/")) {
            String directory=done.isEmpty()?part:done+"/"+part;
            if(volume.list(done).stream().noneMatch(e->e.name.equals(part)))volume.mkdir(directory);done=directory;
        }
        try(OutputStream output=volume.create(path)){output.write(bytes);}
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[1]);Files.createDirectories(directory);File minimal=new File(args[0]);
        File template=directory.resolve("BCD-Template").toFile(),system=directory.resolve("SYSTEM").toFile();
        WindowsBootConfigurationTest.template(minimal,template);
        try(RegistryHive hive=new RegistryHive(minimal,true)) {
            hive.putDword("Select","Default",2);hive.putDword("ControlSet001\\Services\\partmgr\\Parameters","SanPolicy",2);
            hive.putDword("ControlSet002\\Services\\partmgr\\Parameters","SanPolicy",1);hive.commit(system);
        }
        Map<String,byte[]> payloads=new LinkedHashMap<>();
        for(String path:new String[]{"EFI/bootmgfw.efi","EFI/memtest.efi","EFI/en-US/bootmgfw.efi.mui","EFI_EX/bootmgfw.efi","EFI_EX/memtest.efi",
                "Fonts/wgl4_boot.ttf","Fonts_EX/wgl4_boot.ttf","PCAT/bootmgr","PCAT/memtest.exe","Resources/bootres.dll"})
            payloads.put("Windows/Boot/"+path,("Original test bytes: "+path+" żółć 😀").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(int sector:new int[]{512,4096})for(boolean gpt:new boolean[]{false,true}) {
            if(!gpt && sector==4096)continue;
            String name=(gpt?"gpt":"mbr")+"-"+sector;Path main=directory.resolve("main.img"),esp=directory.resolve(name+"-esp.img");
            WindowsToGoLayout layout=WindowsToGoLayout.create(32L<<30,sector,4096,gpt,new byte[0],gpt?null:new byte[1048576]);
            byte[] answer="<unattend>Original test answer</unattend>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] expectedAnswer=sector==4096?"<unattend>Image supplied answer</unattend>".getBytes(java.nio.charset.StandardCharsets.UTF_8):answer;
            try(Disk disk=new Disk(main,layout.main.length,sector);Disk efi=new Disk(esp,gpt?layout.esp.length:512,sector)) {
                NtfsVolume.format(disk,"WINT0GO",4096,layout.main.offset/sector,true);
                try(NtfsVolume volume=new NtfsVolume(disk)) {
                    for(var entry:payloads.entrySet())put(volume,entry.getKey(),entry.getValue());
                    put(volume,"Windows/System32/config/BCD-Template",Files.readAllBytes(template.toPath()));
                    put(volume,"Windows/System32/config/SYSTEM",Files.readAllBytes(system.toPath()));
                    if(sector==4096)put(volume,"Windows/Panther/unattend.xml",expectedAnswer);
                    if(gpt && sector==512) {
                        try {new WindowsToGoBootFiles(volume).install(layout,efi,directory.toFile(),false,true,true,"x64","en-US","Windows test",true,answer,
                                (phase,path,done,total)->{throw new InterruptedIOException("cancel boot files");});throw new AssertionError("Cancellation ignored");}
                        catch(InterruptedIOException expected){check(expected.getMessage().equals("cancel boot files"),"Cancellation exception changed");}
                    }
                    new WindowsToGoBootFiles(volume).install(layout,gpt?efi:null,directory.toFile(),!gpt,true,true,"x64","en-US","Windows 11 test",true,answer,
                            (phase,path,done,total)->check(done>=0 && done<=total,"Progress bounds"));
                    try(InputStream input=volume.open("Windows/Panther/unattend.xml")){check(Arrays.equals(expectedAnswer,input.readAllBytes()),"Unattend write or image supplied answer preservation");}
                    Path savedSystem=directory.resolve(name+"-SYSTEM.hive");try(InputStream input=volume.open("Windows/System32/config/SYSTEM")){Files.copy(input,savedSystem,StandardCopyOption.REPLACE_EXISTING);}
                    try(RegistryHive hive=new RegistryHive(savedSystem.toFile(),false)) {
                        check(hive.get("ControlSet002\\Services\\partmgr\\Parameters","SanPolicy").dword()==4,"Offline default control set");
                        check(hive.get("ControlSet001\\Services\\partmgr\\Parameters","SanPolicy").dword()==2,"Other control set changed");
                    }
                    if(!gpt) {
                        try(InputStream input=volume.open("bootmgr")){check(Arrays.equals(payloads.get("Windows/Boot/PCAT/bootmgr"),input.readAllBytes()),"BIOS bootmgr");}
                        try(InputStream input=volume.open("EFI/Boot/bootx64.efi")){check(Arrays.equals(payloads.get("Windows/Boot/EFI_EX/bootmgfw.efi"),input.readAllBytes()),"CA2023 fallback");}
                        for(String path:new String[]{"Boot/BCD","EFI/Microsoft/Boot/BCD"})try(InputStream input=volume.open(path)){Files.copy(input,directory.resolve(name+"-"+path.replace('/','-')),StandardCopyOption.REPLACE_EXISTING);}
                    }
                }
                try(NtfsVolume volume=new NtfsVolume(disk)) {
                    try(InputStream input=volume.open("Windows/Panther/unattend.xml")){check(Arrays.equals(expectedAnswer,input.readAllBytes()),"Answer after remount");}
                }
            }
            Files.delete(main);if(!gpt)Files.delete(esp);
            try(var paths=Files.list(directory)){check(paths.noneMatch(p->p.getFileName().toString().startsWith("wintogo-registry-")),"Registry scratch leaked");}
        }
        Path expected=directory.resolve("expected");Files.createDirectories(expected);
        for(String[] mapping:new String[][]{{"EFI_EX/bootmgfw.efi","EFI/Boot/bootx64.efi"},{"EFI_EX/bootmgfw.efi","EFI/Microsoft/Boot/bootmgfw.efi"},
                {"EFI_EX/memtest.efi","EFI/Microsoft/Boot/memtest.efi"},{"Fonts_EX/wgl4_boot.ttf","EFI/Microsoft/Boot/Fonts/wgl4_boot.ttf"},
                {"Resources/bootres.dll","EFI/Microsoft/Boot/Resources/bootres.dll"}}) {
            Path path=expected.resolve(mapping[1]);Files.createDirectories(path.getParent());Files.write(path,payloads.get("Windows/Boot/"+mapping[0]));
        }
        System.out.println("Windows To Go boot files, CA2023, BIOS+UEFI, 512/4096 sectors, SAN policy, answer and scratch cleanup passed");
    }
}
