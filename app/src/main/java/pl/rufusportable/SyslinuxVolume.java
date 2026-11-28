package pl.rufusportable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Plans a FAT tree and original Syslinux boot code before the first target write. */
final class SyslinuxVolume {
    private final FatVolume fat;
    private final Map<String,byte[]> generated=new HashMap<>();
    private static final String SYSTEM="ldlinux.sys",MODULE="ldlinux.c32";
    private static String key(String path){return path.toUpperCase(Locale.ROOT);}
    private static void replace(List<IsoTree.Entry> entries,String name,long size) throws IOException {
        for(IsoTree.Entry entry:entries)if(entry.path.equalsIgnoreCase(name) && entry.directory)
            throw new IOException("Katalog blokuje plik Syslinux: "+name);
        entries.removeIf(entry->entry.path.equalsIgnoreCase(name));entries.add(new IsoTree.Entry(name,false,0,size));
    }
    SyslinuxVolume(List<IsoTree.Entry> entries,long capacity,int sector,int bits,int cluster,String label,long hidden,
            byte[] system,byte[] bss,byte[] module,byte[] moduleSignature,String directory,boolean stupid,boolean raid) throws IOException {
        if(sector!=512 || system==null || system.length<24 || system.length>1048576 || bss==null || bss.length!=512 ||
                ((module==null)!=(moduleSignature==null)))throw new IOException("Nieobsługiwane zasoby lub geometria Syslinux.");
        List<IsoTree.Entry> tree=new ArrayList<>(entries);replace(tree,SYSTEM,system.length+1024L);
        if(module!=null) {
            RufusSignature.verify(module,moduleSignature);replace(tree,MODULE,module.length);generated.put(key(MODULE),module.clone());
        }
        String header=new String(system,0,Math.min(512,system.length),StandardCharsets.ISO_8859_1);
        var version=java.util.regex.Pattern.compile("SYSLINUX ([0-9]{1,3})\\.").matcher(header);
        if(module==null && version.find() && Integer.parseInt(version.group(1))>=5 &&
                tree.stream().noneMatch(e->!e.directory && (e.path.equalsIgnoreCase(MODULE) || e.path.toLowerCase(Locale.ROOT).endsWith("/"+MODULE))))
            throw new IOException("Brak wymaganego modułu ldlinux.c32.");
        fat=new FatVolume(tree,capacity,sector,bits,cluster,label,hidden);
        SyslinuxPatch.Result patched=SyslinuxPatch.patch(system,bss,fat.bootSector(),fat.fileSectors(SYSTEM,(system.length+1024+511)/512),directory,stupid,raid);
        if(patched.file.length!=system.length+1024)throw new IOException("Instalator Syslinux zmienił rozmiar pliku.");
        generated.put(key(SYSTEM),patched.file);fat.attributes(SYSTEM,0x07);fat.useBootSector(patched.bootSector);
    }
    void install(BlockDevice target,FatVolume.Source source,boolean quick,boolean verify,FatVolume.Progress progress) throws IOException {
        fat.install(target,entry->{byte[] bytes=generated.get(key(entry.path));return bytes==null?source.open(entry):new ByteArrayInputStream(bytes);},quick,verify,progress);
    }
}
