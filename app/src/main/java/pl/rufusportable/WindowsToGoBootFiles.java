package pl.rufusportable;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Installs Windows boot files from the applied NTFS image. Only small registry hives use scratch storage. */
final class WindowsToGoBootFiles {
    interface Progress {void update(String phase,String path,long done,long total) throws IOException;}
    private static final class FileSource {
        final String path;final File local;
        FileSource(String path,File local){this.path=path;this.local=local;}
    }
    private final NtfsVolume windows;
    private final LinkedHashMap<String,IsoTree.Entry> entries=new LinkedHashMap<>();
    private final Map<String,FileSource> sources=new HashMap<>();
    WindowsToGoBootFiles(NtfsVolume windows){this.windows=windows;}
    private static String key(String path){return path.toUpperCase(Locale.ROOT);}
    private void add(String target,boolean directory,long length,FileSource source) throws IOException {
        String id=key(target);IsoTree.Entry previous=entries.get(id);
        if(previous!=null && previous.directory!=directory)throw new IOException("Konflikt ścieżek rozruchowych Windows: "+target);
        entries.put(id,new IsoTree.Entry(target,directory,0,length));if(source!=null)sources.put(id,source);
    }
    private void directory(String target) throws IOException {
        int slash=target.lastIndexOf('/');if(slash>=0)directory(target.substring(0,slash));add(target,true,0,null);
    }
    private void tree(String source,String target,int depth) throws IOException {
        if(depth>32)throw new IOException("Zbyt głęboka struktura plików rozruchowych Windows.");
        directory(target);
        for(NtfsVolume.Entry item:windows.list(source)) {
            if(item.reparse)throw new IOException("Nieoczekiwany punkt ponownej analizy w plikach rozruchowych: "+source+"/"+item.name);
            String from=source+"/"+item.name,to=target+"/"+item.name;
            if(item.directory)tree(from,to,depth+1);else add(to,false,item.size,new FileSource(from,null));
        }
    }
    private NtfsVolume.Entry child(String directory,String name) throws IOException {
        for(NtfsVolume.Entry entry:windows.list(directory))if(entry.name.equalsIgnoreCase(name))return entry;return null;
    }
    private void copyFile(String from,String to) throws IOException {
        int slash=from.lastIndexOf('/');NtfsVolume.Entry entry=child(from.substring(0,slash),from.substring(slash+1));
        if(entry==null || entry.directory || entry.reparse)throw new IOException("Brak pliku rozruchowego Windows: "+from);
        add(to,false,entry.size,new FileSource(from,null));
    }
    private InputStream open(IsoTree.Entry entry) throws IOException {
        FileSource source=sources.get(key(entry.path));
        if(source==null)throw new IOException("Brak źródła pliku rozruchowego: "+entry.path);
        return source.local==null?windows.open(source.path):new FileInputStream(source.local);
    }
    private void extractHive(String source,File target) throws IOException {
        try(InputStream input=windows.open(source);OutputStream output=new FileOutputStream(target)) {
            byte[] buffer=new byte[131072];long done=0;int count;
            while((count=input.read(buffer))!=-1) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if((done+=count)>64L<<20)throw new IOException("Plik rejestru Windows przekracza limit roboczy.");output.write(buffer,0,count);
            }
        }
    }
    private void ensureDirectory(String path) throws IOException {
        int slash=path.lastIndexOf('/');String parent=slash<0?"":path.substring(0,slash),name=path.substring(slash+1);
        if(!parent.isEmpty())ensureDirectory(parent);
        NtfsVolume.Entry existing=child(parent,name);
        if(existing==null)windows.mkdir(path);else if(!existing.directory || existing.reparse)throw new IOException("Nieprawidłowy katalog Windows: "+path);
    }
    private void replaceHive(String path,File source) throws IOException {
        try(NtfsVolume.RandomFile file=windows.openRandom(path);InputStream input=new FileInputStream(source)) {
            byte[] buffer=new byte[131072];long offset=0;int count;
            while((count=input.read(buffer))!=-1){file.write(offset,buffer,0,count);offset+=count;}
            file.truncate(offset);file.sync();
        }
    }
    private static byte[] digest(InputStream input) throws IOException {
        try(InputStream stream=input) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[131072];int count;
            while((count=stream.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();digest.update(buffer,0,count);}return digest.digest();
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private void offline(File scratch,boolean offlineInternal,boolean forceSMode) throws IOException {
        File source=new File(scratch,"SYSTEM-source"),result=new File(scratch,"SYSTEM-offline");
        extractHive("Windows/System32/config/SYSTEM",source);
        try(RegistryHive hive=new RegistryHive(source,true)) {
            RegistryHive.Value selected=hive.get("Select","Default");
            if(selected==null || selected.dword()<1 || selected.dword()>999)throw new IOException("Nieprawidłowy domyślny zestaw sterowania Windows.");
            String control=String.format(Locale.ROOT,"ControlSet%03d",selected.dword());
            if(!hive.children("").contains(control))throw new IOException("Brak domyślnego zestawu sterowania Windows.");
            if(offlineInternal)hive.putDword(control+"\\Services\\partmgr\\Parameters","SanPolicy",4);
            if(forceSMode)hive.putDword(control+"\\Control\\CI\\Policy","SkuPolicyRequired",1);
            hive.commit(result);
        }
        replaceHive("Windows/System32/config/SYSTEM",result);
        if(!Arrays.equals(digest(new FileInputStream(result)),digest(windows.open("Windows/System32/config/SYSTEM"))))
            throw new IOException("Błąd weryfikacji konfiguracji dysków offline Windows To Go.");
    }
    void install(WindowsToGoLayout layout,BlockDevice esp,File cache,boolean bios,boolean efi,boolean bootex,
            String architecture,String locale,String title,boolean offlineInternal,boolean forceSMode,byte[] unattend,Progress progress) throws IOException {
        if((!bios && !efi) || (layout.gpt && (bios || !efi || esp==null)) || (!layout.gpt && esp!=null))
            throw new IOException("Nieobsługiwana konfiguracja plików Windows To Go.");
        String fallback;
        switch(architecture){case "x64":fallback="bootx64.efi";break;case "ARM64":fallback="bootaa64.efi";break;case "x86":fallback="bootia32.efi";break;case "ARM":fallback="bootarm.efi";break;default:throw new IOException("Nieznana architektura Windows.");}
        entries.clear();sources.clear();Path scratch=Files.createTempDirectory(cache.toPath(),"wintogo-registry-");
        try {
            File template=scratch.resolve("BCD-Template").toFile();extractHive("Windows/System32/config/BCD-Template",template);
            String suffix=bootex && child("Windows/Boot","EFI_EX")!=null?"_EX":"";
            if(efi) {
                tree("Windows/Boot/EFI"+suffix,"EFI/Microsoft/Boot",0);
                tree("Windows/Boot/Fonts"+suffix,"EFI/Microsoft/Boot/Fonts",0);
                if(child("Windows/Boot","Resources")!=null)tree("Windows/Boot/Resources","EFI/Microsoft/Boot/Resources",0);
                directory("EFI/Boot");copyFile("Windows/Boot/EFI"+suffix+"/bootmgfw.efi","EFI/Boot/"+fallback);
                File bcd=scratch.resolve("BCD-UEFI").toFile();WindowsBootConfiguration.create(template,bcd,layout,true,locale,title);
                add("EFI/Microsoft/Boot/BCD",false,bcd.length(),new FileSource(null,bcd));
            }
            if(bios) {
                tree("Windows/Boot/PCAT","Boot",0);entries.remove(key("Boot/bootmgr"));sources.remove(key("Boot/bootmgr"));
                copyFile("Windows/Boot/PCAT/bootmgr","bootmgr");tree("Windows/Boot/Fonts","Boot/Fonts",0);
                if(child("Windows/Boot","Resources")!=null)tree("Windows/Boot/Resources","Boot/Resources",0);
                File bcd=scratch.resolve("BCD-BIOS").toFile();WindowsBootConfiguration.create(template,bcd,layout,false,locale,title);
                add("Boot/BCD",false,bcd.length(),new FileSource(null,bcd));
            }
            List<IsoTree.Entry> files=new ArrayList<>(entries.values());
            if(layout.gpt) {
                int cluster=layout.sectorSize<=1024?1024:4096;
                FatVolume fat=new FatVolume(files,esp.size(),esp.sectorSize(),32,cluster,"",layout.esp.offset/layout.sectorSize);
                fat.install(esp,this::open,true,true,(phase,path,done,total)->progress.update(phase,path,done,total));
            }else {
                files.sort(Comparator.comparingInt(e->e.path.split("/").length));
                Map<String,byte[]> hashes=new LinkedHashMap<>();long total=files.stream().filter(e->!e.directory).mapToLong(e->e.length).sum(),done=0;
                byte[] buffer=new byte[131072];
                for(IsoTree.Entry entry:files) {
                    if(entry.directory){ensureDirectory(entry.path);continue;}
                    hashes.put(entry.path,digest(open(entry)));
                    try(InputStream input=open(entry);OutputStream output=windows.create(entry.path)) {
                        long copied=0;int count;while((count=input.read(buffer))!=-1){output.write(buffer,0,count);copied+=count;done+=count;progress.update("copy",entry.path,done,total);}
                        if(copied!=entry.length)throw new IOException("Zmieniła się długość pliku Windows: "+entry.path);
                    }
                }
                for(Map.Entry<String,byte[]> hash:hashes.entrySet())if(!Arrays.equals(hash.getValue(),digest(windows.open(hash.getKey()))))
                    throw new IOException("Błąd weryfikacji pliku rozruchowego Windows: "+hash.getKey());
            }
            if(offlineInternal || forceSMode)offline(scratch.toFile(),offlineInternal,forceSMode);
            if(unattend!=null) {
                ensureDirectory("Windows/Panther");
                NtfsVolume.Entry existing=child("Windows/Panther","unattend.xml");
                if(existing==null) {
                    try(OutputStream out=windows.create("Windows/Panther/unattend.xml")){out.write(unattend);}
                    if(!Arrays.equals(digest(new ByteArrayInputStream(unattend)),digest(windows.open("Windows/Panther/unattend.xml"))))
                        throw new IOException("Błąd weryfikacji odpowiedzi Windows To Go.");
                }
                // An image's existing answer file takes precedence, matching Rufus.
            }
        } finally {
            // No links are followed when removing our registry-only working directory.
            try(java.util.stream.Stream<Path> paths=Files.walk(scratch)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
}
