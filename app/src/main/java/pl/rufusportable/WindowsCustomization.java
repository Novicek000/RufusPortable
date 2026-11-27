package pl.rufusportable;

import java.io.*;
import java.util.*;

/** Plans Rufus wue.c installation-media changes before the first target write. */
final class WindowsCustomization {
    interface Resources {byte[] read(String name) throws IOException;}
    final List<IsoTree.Entry> entries;
    final byte[] answer;
    final String bootWimPath;
    final boolean answerInBootWim;
    private final Map<String,byte[]> generated=new HashMap<>();
    private final List<IsoTree.Entry> working;
    private final Map<String,IsoTree.Entry> paths=new HashMap<>();
    private static String key(String path){return path.toUpperCase(Locale.ROOT);}

    WindowsCustomization(List<IsoTree.Entry> original,int flags,int build,byte[] xml,
            WindowsInstaller.Source source,Resources resources) throws IOException {
        working=new ArrayList<>(original);
        answer=xml.clone();
        for(IsoTree.Entry entry:working) {
            if(paths.put(key(entry.path),entry)!=null)throw new IOException("Powtórzona ścieżka instalatora.");
        }
        if((flags&(WindowsUnattend.WINDOWS_TO_GO|WindowsUnattend.USE_MS2023_BOOTLOADERS|
                WindowsUnattend.OFFLINE_INTERNAL_DRIVES|WindowsUnattend.FORCE_S_MODE))!=0)
            throw new IOException("Te opcje wymagają osobnego trybu przygotowania Windows.");
        answerInBootWim=(flags&(WindowsUnattend.BYPASS_REQUIREMENTS|WindowsUnattend.SILENT_INSTALL))!=0;
        IsoTree.Entry boot=paths.get(key("sources/boot.wim"));
        bootWimPath=boot==null?null:boot.path;
        if(flags!=0) {
            if(answer.length==0)throw new IOException("Brak pliku odpowiedzi Windows.");
            if(answerInBootWim) {
                if(boot==null || boot.directory)throw new IOException("Brak sources/boot.wim.");
                prepareUpgrade(source,resources,build);
            } else {
                String directory=directory("sources/$OEM$/$$/Panther");
                addFile(directory+"/unattend.xml",answer,false);
            }
        }
        entries=Collections.unmodifiableList(new ArrayList<>(working));
    }
    InputStream open(WindowsInstaller.Source source,IsoTree.Entry entry) throws IOException {
        byte[] bytes=generated.get(entry.path);
        return bytes==null?source.open(entry):new ByteArrayInputStream(bytes);
    }
    private String directory(String requested) throws IOException {
        String parent="";
        for(String part:requested.split("/")) {
            String path=parent.isEmpty()?part:parent+"/"+part;
            IsoTree.Entry existing=paths.get(key(path));
            if(existing!=null) {
                if(!existing.directory)throw new IOException("Plik zajmuje ścieżkę katalogu personalizacji: "+path);
                parent=existing.path;
            } else {
                IsoTree.Entry created=new IsoTree.Entry(path,true,0,Collections.emptyList());
                working.add(created);paths.put(key(path),created);parent=path;
            }
        }
        return parent;
    }
    private void addFile(String path,byte[] data,boolean replace) throws IOException {
        IsoTree.Entry existing=paths.get(key(path));
        if(existing!=null) {
            if(!replace || existing.directory)throw new IOException("Obraz zawiera już plik personalizacji: "+path);
            working.remove(existing);generated.remove(existing.path);path=existing.path;
        }
        IsoTree.Entry entry=new IsoTree.Entry(path,false,data.length,Collections.emptyList());
        working.add(entry);paths.put(key(path),entry);generated.put(path,data.clone());
    }
    private void move(IsoTree.Entry entry,String destination,boolean replace) throws IOException {
        IsoTree.Entry existing=paths.get(key(destination));
        if(existing!=null) {
            if(!replace || existing.directory)throw new IOException("Nie można utworzyć kopii instalatora: "+destination);
            working.remove(existing);paths.remove(key(existing.path));
        }
        working.remove(entry);paths.remove(key(entry.path));
        IsoTree.Entry moved=new IsoTree.Entry(destination,false,entry.length,entry.extents);
        working.add(moved);paths.put(key(destination),moved);
    }
    private void prepareUpgrade(WindowsInstaller.Source source,Resources resources,int build) throws IOException {
        String sources=directory("sources");
        IsoTree.Entry appraiser=paths.get(key(sources+"/appraiserres.dll"));
        if(appraiser!=null) {
            if(appraiser.directory)throw new IOException("Nieprawidłowy appraiserres.dll w obrazie.");
            move(appraiser,sources+"/appraiserres.bak",true);
        }
        addFile(sources+"/appraiserres.dll",new byte[0],false);
        if(build<26000)return;
        IsoTree.Entry setup=paths.get(key("setup.exe"));
        if(setup==null || setup.directory)return;
        int machine;
        try(InputStream input=source.open(setup)){machine=peMachine(input,setup.length);}
        if(machine!=0x8664 && machine!=0xaa64)return;
        byte[] wrapper=resources.read(machine==0x8664?"setup_x64.exe":"setup_arm64.exe");
        if(peMachine(new ByteArrayInputStream(wrapper),wrapper.length)!=machine)
            throw new IOException("Nieprawidłowy wrapper setup.exe.");
        move(setup,"setup.dll",false);
        addFile("setup.exe",wrapper,false);
    }
    static int peMachine(InputStream input,long length) throws IOException {
        if(length<64)return 0;
        byte[] dos=new byte[64];new DataInputStream(input).readFully(dos);
        if(dos[0]!='M' || dos[1]!='Z')return 0;
        long position=0;
        for(int i=0;i<4;i++)position|=(long)(dos[60+i]&255)<<(8*i);
        if(position<64 || position>length-6 || position>(16L<<20))return 0;
        for(long remaining=position-64;remaining>0;) {
            long skipped=input.skip(remaining);
            if(skipped==0){if(input.read()<0)throw new EOFException("Niepełny setup.exe.");skipped=1;}
            remaining-=skipped;
        }
        byte[] pe=new byte[6];new DataInputStream(input).readFully(pe);
        return pe[0]=='P' && pe[1]=='E' && pe[2]==0 && pe[3]==0 ? (pe[4]&255)|((pe[5]&255)<<8):0;
    }
}
