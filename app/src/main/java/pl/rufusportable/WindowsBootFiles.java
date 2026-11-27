package pl.rufusportable;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Maps the EFI_EX / Fonts_EX resources used by Rufus ApplyWindowsCustomization. */
final class WindowsBootFiles {
    private static File child(File directory,String name,boolean required) throws IOException {
        File[] children=directory.listFiles();
        if(children==null)throw new IOException("Nie można odczytać katalogu bootloaderów Windows.");
        File found=null;
        for(File file:children)if(file.getName().equalsIgnoreCase(name)) {
            if(found!=null)throw new IOException("Powtórzona nazwa pliku bootloadera Windows.");found=file;
        }
        if(found==null && required)throw new IOException("Obraz nie zawiera bootloaderów Windows CA 2023: "+name);
        return found;
    }
    static Map<String,File> plan(List<IsoTree.Entry> entries,File extracted) throws IOException {
        File efi=child(extracted,"EFI_EX",true),fonts=child(extracted,"Fonts_EX",true);
        if(Files.isSymbolicLink(efi.toPath()) || Files.isSymbolicLink(fonts.toPath()) || !efi.isDirectory() || !fonts.isDirectory())throw new IOException("Nieprawidłowe katalogi bootloaderów Windows.");
        Map<String,File> result=new LinkedHashMap<>();
        try(var paths=Files.walk(fonts.toPath())) {
            for(Path path:(Iterable<Path>)paths.sorted()::iterator) {
                if(Files.isSymbolicLink(path))throw new IOException("Dowiązanie w zasobach bootloadera Windows.");
                if(!Files.isRegularFile(path))continue;
                String relative=fonts.toPath().relativize(path).toString().replace(File.separatorChar,'/');
                put(result,"efi/microsoft/boot/fonts/"+relative.replace("_EX",""),path.toFile());
            }
        }
        File boot=child(efi,"bootmgfw_EX.efi",true);
        if(!boot.isFile() || Files.isSymbolicLink(boot.toPath()))throw new IOException("Brak bootmgfw_EX.efi.");
        boolean replaced=false;
        for(String architecture:new String[]{"ia32","x64","arm","aa64","ia64","riscv64","loongarch64","ebc"}) {
            String target="efi/boot/boot"+architecture+".efi";
            IsoTree.Entry entry=entries.stream().filter(e->!e.directory && e.path.equalsIgnoreCase(target)).findFirst().orElse(null);
            if(entry!=null){put(result,entry.path,boot);replaced=true;break;}
        }
        if(!replaced)throw new IOException("Brak pliku rozruchowego EFI do zastąpienia.");
        File manager=child(efi,"bootmgr_EX.efi",false);
        if(manager!=null) {
            if(!manager.isFile() || Files.isSymbolicLink(manager.toPath()))throw new IOException("Nieprawidłowy bootmgr_EX.efi.");
            put(result,"bootmgr.efi",manager);
        }
        return Collections.unmodifiableMap(result);
    }
    private static void put(Map<String,File> result,String path,File source) throws IOException {
        for(String existing:result.keySet())if(existing.equalsIgnoreCase(path))throw new IOException("Powtórzona ścieżka bootloadera Windows.");
        result.put(path,source);
    }
}
