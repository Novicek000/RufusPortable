package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ISO 9660 / Joliet directory reader for the extraction pipeline.
 * Extents address the selected source directly. No image staging is performed.
 */
final class IsoTree {
    interface Reader { void read(long offset, byte[] destination) throws IOException; }
    static final class Entry {
        final String path;
        final boolean directory;
        final long offset, length;
        Entry(String path, boolean directory, long offset, long length) {
            this.path=path; this.directory=directory; this.offset=offset; this.length=length;
        }
    }
    private static final int BLOCK=2048, MAX_DIRECTORY=16*1024*1024, MAX_ENTRIES=200000;
    private final Reader reader;
    private final long sourceLength;
    private final List<Entry> entries=new ArrayList<>();
    private final Set<Long> directories=new HashSet<>();
    private boolean joliet;

    private IsoTree(Reader reader,long length) {
        this.reader=reader; sourceLength=length;
    }
    static List<Entry> scan(Reader reader,long length) throws IOException {
        IsoTree tree=new IsoTree(reader,length);
        byte[] root=null;
        for (int i=16;i<80;i++) {
            byte[] descriptor=tree.bytes((long)i*BLOCK,BLOCK);
            String identifier=new String(descriptor,1,5,StandardCharsets.US_ASCII);
            if (!identifier.equals("CD001")) continue;
            int type=descriptor[0]&255;
            if (type==255) break;
            if ((type==1 && root==null) || (type==2 && descriptor[88]==0x25 && descriptor[89]==0x2f
                    && (descriptor[90]==0x40 || descriptor[90]==0x43 || descriptor[90]==0x45))) {
                if (unsigned16(descriptor,128)!=BLOCK) throw new IOException("Nieobsługiwany blok ISO.");
                root=Arrays.copyOfRange(descriptor,156,190);
                tree.joliet=type==2;
            }
        }
        if (root==null) throw new IOException("Brak drzewa ISO 9660. Ten obraz wymaga czytnika UDF.");
        tree.walk(tree.record(root,0,""),0);
        return Collections.unmodifiableList(tree.entries);
    }
    private byte[] bytes(long offset,int length) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        if (offset<0 || length<0 || offset>sourceLength || length>sourceLength-offset)
            throw new IOException("Zakres pliku wykracza poza obraz ISO.");
        byte[] data=new byte[length]; reader.read(offset,data); return data;
    }
    private static long unsigned32(byte[] data,int pos) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(data,pos,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }
    private static int unsigned16(byte[] data,int pos) {
        return (data[pos]&255)|((data[pos+1]&255)<<8);
    }
    private Entry record(byte[] data,int pos,String parent) throws IOException {
        int size=data[pos]&255;
        if (size<34 || pos+size>data.length) throw new IOException("Uszkodzony rekord katalogu ISO.");
        int nameLength=data[pos+32]&255;
        if (nameLength>size-33 || (data[pos+26]&255)!=0 || (data[pos+27]&255)!=0)
            throw new IOException("Nieobsługiwany rekord ISO.");
        if ((data[pos+25]&128)!=0) throw new IOException("Wieloczęściowy plik ISO wymaga odczytu wielu ekstentów.");
        long offset=(unsigned32(data,pos+2)+(data[pos+1]&255))*BLOCK;
        long length=unsigned32(data,pos+10);
        if (offset>sourceLength || length>sourceLength-offset) throw new IOException("Uszkodzony zakres ISO.");
        String name="";
        if (nameLength>1 || (data[pos+33]!=0 && data[pos+33]!=1)) {
            if (joliet && nameLength%2!=0) throw new IOException("Niepoprawna nazwa Joliet.");
            name=new String(data,pos+33,nameLength,joliet?StandardCharsets.UTF_16BE:StandardCharsets.US_ASCII);
            name=name.replaceFirst(";[0-9]+$","");
            if (name.endsWith(".")) name=name.substring(0,name.length()-1);
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/')>=0
                    || name.indexOf('\\')>=0 || name.indexOf(0)>=0)
                throw new IOException("Niebezpieczna nazwa ścieżki ISO.");
        }
        return new Entry(parent.isEmpty()?name:parent+"/"+name,(data[pos+25]&2)!=0,offset,length);
    }
    private void walk(Entry directory,int depth) throws IOException {
        if (depth>64 || !directories.add(directory.offset) || directory.length>MAX_DIRECTORY)
            throw new IOException("Zbyt duże lub cykliczne drzewo ISO.");
        byte[] data=bytes(directory.offset,(int)directory.length);
        Set<String> names=new HashSet<>();
        for (int pos=0;pos<data.length;) {
            int size=data[pos]&255;
            if (size==0) { pos=((pos/BLOCK)+1)*BLOCK; continue; }
            if (size>BLOCK-pos%BLOCK) throw new IOException("Rekord ISO przecina granicę sektora.");
            Entry child=record(data,pos,directory.path);
            int nameLength=data[pos+32]&255;
            boolean special=nameLength==1 && (data[pos+33]==0 || data[pos+33]==1);
            pos+=size;
            if (special) continue;
            if (!names.add(child.path.toUpperCase(Locale.ROOT))) throw new IOException("Powtórzona ścieżka w ISO.");
            if (entries.size()>=MAX_ENTRIES) throw new IOException("Zbyt wiele plików w ISO.");
            entries.add(child);
            if (child.directory) walk(child,depth+1);
        }
    }
}
