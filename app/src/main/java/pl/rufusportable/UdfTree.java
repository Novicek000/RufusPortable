package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** ECMA-167 physical partitions with short, long and embedded allocation descriptors. */
final class UdfTree {
    private static final int BLOCK=2048, MAX_DIRECTORY=16*1024*1024;
    private final IsoTree.Reader reader;
    private final long length;
    private final Map<Integer,Long> partitions=new HashMap<>();
    private final List<Integer> maps=new ArrayList<>();
    private final List<IsoTree.Entry> entries=new ArrayList<>();
    private final Set<Long> visited=new HashSet<>();
    private final Map<Integer,Long> partitionSizes=new HashMap<>();

    private UdfTree(IsoTree.Reader reader,long length) {this.reader=reader;this.length=length;}
    private static int u16(byte[] b,int p) {return (b[p]&255)|((b[p+1]&255)<<8);}
    private static long u32(byte[] b,int p) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }
    private static long u64(byte[] b,int p) throws IOException {
        long n=ByteBuffer.wrap(b,p,8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if(n<0)throw new IOException("Zbyt duży rozmiar UDF.");return n;
    }
    private byte[] read(long offset,int size) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
        if(offset<0 || size<0 || offset>length || size>length-offset)throw new IOException("Zakres UDF poza obrazem.");
        byte[] b=new byte[size];reader.read(offset,b);return b;
    }
    private static int tag(byte[] b,int pos) throws IOException {
        if(pos<0 || b.length-pos<16)throw new IOException("Niepełny deskryptor UDF.");
        int sum=0;for(int i=0;i<16;i++)if(i!=4)sum+=b[pos+i]&255;
        if((sum&255)!=(b[pos+4]&255))throw new IOException("Błędna suma znacznika UDF.");
        int size=u16(b,pos+10);
        if(size>b.length-pos-16)throw new IOException("Niepełna treść deskryptora UDF.");
        int crc=0;
        for(int i=pos+16;i<pos+16+size;i++){
            crc^=(b[i]&255)<<8;
            for(int j=0;j<8;j++)crc=((crc<<1)^((crc&0x8000)!=0?0x1021:0))&65535;
        }
        if(crc!=u16(b,pos+8))throw new IOException("Błędna suma treści UDF.");
        return u16(b,pos);
    }
    static List<IsoTree.Entry> scan(IsoTree.Reader reader,long length) throws IOException {
        UdfTree tree=new UdfTree(reader,length);
        byte[] anchor=tree.read(256L*BLOCK,BLOCK);
        if(tag(anchor,0)!=2)throw new IOException("Brak kotwicy UDF.");
        long sequence=u32(anchor,20), size=u32(anchor,16);
        if(size>16*1024*1024 || size%BLOCK!=0)throw new IOException("Nieprawidłowa sekwencja woluminu UDF.");
        byte[] logical=null;
        for(long n=0;n<size;n+=BLOCK){
            byte[] b=tree.read(sequence*BLOCK+n,BLOCK);
            int type=tag(b,0);
            if(type==8)break;
            if(type==5){
                if(u16(b,20)!=1)throw new IOException("UDF wymaga obsługi wielu woluminów.");
                int number=u16(b,22);
                long first=u32(b,188),count=u32(b,192);
                if(first>length/BLOCK || count>length/BLOCK-first)throw new IOException("Partycja UDF poza obrazem.");
                tree.partitions.put(number,first);
                tree.partitionSizes.put(number,count);
            }
            if(type==6)logical=b;
        }
        if(logical==null || u32(logical,212)!=BLOCK)throw new IOException("Nieobsługiwany wolumin UDF.");
        long mapLength=u32(logical,264), count=u32(logical,268);
        if(mapLength>BLOCK-440 || count>64)throw new IOException("Nieprawidłowa mapa partycji UDF.");
        int p=440;
        for(int i=0;i<count;i++){
            if(p+6>440+mapLength || logical[p]!=1 || logical[p+1]!=6)
                throw new IOException("Obraz UDF wymaga mapy wirtualnej lub metadanych.");
            if(u16(logical,p+2)!=1)throw new IOException("Nieobsługiwana sekwencja woluminu UDF.");
            tree.maps.add(u16(logical,p+4));p+=6;
        }
        long fsd=tree.address(u32(logical,252),u16(logical,256));
        byte[] fileset=tree.read(fsd,BLOCK);
        if(tag(fileset,0)!=256)throw new IOException("Brak zbioru plików UDF.");
        tree.walk("",tree.address(u32(fileset,404),u16(fileset,408)),u16(fileset,408),0);
        return Collections.unmodifiableList(tree.entries);
    }
    private long address(long block,int map) throws IOException {
        if(map>=maps.size() || !partitions.containsKey(maps.get(map)))throw new IOException("Nieznana partycja UDF.");
        if(block>=partitionSizes.get(maps.get(map)))throw new IOException("Adres UDF poza partycją.");
        long pos=(partitions.get(maps.get(map))+block)*BLOCK;
        if(pos>=length)throw new IOException("Adres UDF poza partycją.");
        return pos;
    }
    private IsoTree.Entry file(String path,long offset,int currentMap) throws IOException {
        byte[] b=read(offset,BLOCK);int type=tag(b,0);
        if(type!=261 && type!=266)throw new IOException("Nieobsługiwany wpis pliku UDF.");
        int kind=b[27]&255;
        if(kind!=4 && kind!=5)throw new IOException("Nieobsługiwany typ pliku UDF.");
        long size=u64(b,56);
        int attrPosition=type==261?168:208, start=type==261?176:216;
        long attributes=u32(b,attrPosition),allocations=u32(b,attrPosition+4);
        if(attributes>BLOCK-start || allocations>BLOCK-start-attributes)throw new IOException("Zbyt duży wpis UDF.");
        int pos=start+(int)attributes, end=pos+(int)allocations,format=u16(b,34)&7;
        List<IsoTree.Extent> extents=new ArrayList<>();
        long remaining=size;
        if(format==3){
            if(size>allocations)throw new IOException("Niepełny plik osadzony UDF.");
            extents.add(new IsoTree.Extent(offset+pos,size));remaining=0;
        }else{
            int step=format==0?8:format==1?16:0;
            if(step==0 || allocations%step!=0)throw new IOException("Nieobsługiwany format alokacji UDF.");
            for(;pos<end && remaining>0;pos+=step){
                long allocated=u32(b,pos);
                if((allocated>>>30)!=0)throw new IOException("UDF wymaga obsługi kontynuacji lub plików rzadkich.");
                long bytes=Math.min(allocated,remaining);
                int map=format==1?u16(b,pos+8):currentMap;
                long location=address(u32(b,pos+4),map);
                long partitionLimit=(partitions.get(maps.get(map))+partitionSizes.get(maps.get(map)))*BLOCK;
                if(bytes>partitionLimit-location || bytes>length-location)throw new IOException("Ekstent UDF poza obrazem.");
                if(bytes>0)extents.add(new IsoTree.Extent(location,bytes));
                remaining-=bytes;
            }
        }
        if(remaining!=0)throw new IOException("Niepełne ekstenty pliku UDF.");
        return new IsoTree.Entry(path,kind==4,size,extents);
    }
    private void walk(String path,long offset,int currentMap,int depth) throws IOException {
        if(depth>64 || !visited.add(offset))throw new IOException("Cykliczne lub zbyt głębokie drzewo UDF.");
        IsoTree.Entry directory=file(path,offset,currentMap);
        if(!directory.directory || directory.length>MAX_DIRECTORY)throw new IOException("Nieprawidłowy katalog UDF.");
        if(!path.isEmpty())entries.add(directory);
        byte[] data=new byte[(int)directory.length];int filled=0;
        for(IsoTree.Extent e:directory.extents){
            byte[] part=read(e.offset,(int)e.length);System.arraycopy(part,0,data,filled,part.length);filled+=part.length;
        }
        Set<String> names=new HashSet<>();
        for(int pos=0;pos<data.length;){
            if(data.length-pos<38 || tag(data,pos)!=257)throw new IOException("Uszkodzony wpis katalogu UDF.");
            int flags=data[pos+18]&255,nameLength=data[pos+19]&255,implementation=u16(data,pos+36);
            int record=(38+implementation+nameLength+3)&~3;
            if(record>data.length-pos)throw new IOException("Niepełna nazwa UDF.");
            if((flags&12)==0){
                String name=decode(data,pos+38+implementation,nameLength);
                if(name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/')>=0
                        || name.indexOf('\\')>=0 || name.indexOf(0)>=0 || !names.add(name.toUpperCase(Locale.ROOT)))
                    throw new IOException("Nieprawidłowa lub powtórzona nazwa UDF.");
                if(entries.size()>=200000)throw new IOException("Zbyt wiele plików UDF.");
                String child=path.isEmpty()?name:path+"/"+name;
                long location=address(u32(data,pos+24),u16(data,pos+28));
                IsoTree.Entry entry=file(child,location,u16(data,pos+28));
                if(entry.directory)walk(child,location,u16(data,pos+28),depth+1);else entries.add(entry);
            }
            pos+=record;
        }
    }
    private static String decode(byte[] b,int p,int length)throws IOException{
        if(length<1)throw new IOException("Brak nazwy UDF.");
        int compression=b[p]&255;
        if(compression==8)return new String(b,p+1,length-1,StandardCharsets.ISO_8859_1);
        if(compression==16 && length%2==1)return new String(b,p+1,length-1,StandardCharsets.UTF_16BE);
        throw new IOException("Nieobsługiwane kodowanie nazwy UDF.");
    }
}
