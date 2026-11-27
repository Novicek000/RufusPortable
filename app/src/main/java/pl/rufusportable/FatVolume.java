package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Callback-backed FAT16/FAT32 formatter and VFAT tree writer, including EFI partitions. */
final class FatVolume {
    interface Source {InputStream open(IsoTree.Entry entry) throws IOException;}
    interface Progress {void update(String phase,String path,long done,long total) throws IOException;}
    private static final long MAX_FILE=0xffffffffL;
    private static final int RESERVED=32, ROOT_ENTRIES=512, BUFFER=131072;
    private final int bits,sector,cluster,spc,fatSectors,dataClusters,rootSectors,reserved;
    private final long capacity,hidden,fatOffset,fatBytes,rootOffset,dataOffset,totalBytes;
    private final byte[] label;
    private final Node root=new Node(null,"",null);
    private final List<Node> nodes=new ArrayList<>(),allocated=new ArrayList<>();
    private final long usedClusters;
    private final int date,time,tenths;
    private static final class Node {
        final Node parent;
        final String name;
        final IsoTree.Entry entry;
        final List<Node> children=new ArrayList<>();
        byte[] alias;
        boolean longName;
        int start,count;
        Node(Node parent,String name,IsoTree.Entry entry) {this.parent=parent;this.name=name;this.entry=entry;}
        boolean directory(){return entry==null || entry.directory;}
        String path(){return entry==null?"":entry.path;}
    }
    private static final class Checksum {
        final long offset,length;
        final byte[] digest;
        final String path;
        Checksum(long offset,long length,byte[] digest,String path) {this.offset=offset;this.length=length;this.digest=digest;this.path=path;}
    }
    FatVolume(List<IsoTree.Entry> entries,long capacity,int sector,int bits,int cluster,String label,long hidden) throws IOException {
        if((bits!=16 && bits!=32) || sector<512 || sector>4096 || Integer.bitCount(sector)!=1 ||
                cluster<sector || cluster>65536 || Integer.bitCount(cluster)!=1 || cluster/sector>128 ||
                capacity<=0 || capacity%sector!=0 || capacity/sector>MAX_FILE || hidden<0 || hidden>MAX_FILE)
            throw new IOException("Nieobsługiwana geometria FAT.");
        if(label==null || !label.matches("[a-zA-Z0-9 _-]{0,11}"))throw new IOException("Etykieta FAT może zawierać do 11 liter ASCII, cyfr, spacji oraz znaków - i _.");
        this.capacity=capacity;this.sector=sector;this.bits=bits;this.cluster=cluster;this.hidden=hidden;
        this.label=String.format(Locale.ROOT,"%-11s",label.toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.US_ASCII);
        spc=cluster/sector;reserved=bits==32?RESERVED:1;rootSectors=bits==16?(ROOT_ENTRIES*32+sector-1)/sector:0;
        long total=capacity/sector,entrySize=bits/8;
        long numerator=(total-reserved-rootSectors+2L*spc)*entrySize;
        long denominator=cluster+2*entrySize;
        long fats=(numerator+denominator-1)/denominator;
        long clusters=(total-reserved-rootSectors-2*fats)/spc;
        if(fats<1 || fats>Integer.MAX_VALUE || (bits==16 && (fats>65535 || clusters<4085 || clusters>65518)) ||
                (bits==32 && (clusters<65525 || clusters>0x0fffffedL)))
            throw new IOException("Liczba klastrów nie pasuje do FAT"+bits+". Wybierz inny rozmiar klastra.");
        fatSectors=(int)fats;dataClusters=(int)clusters;
        fatOffset=(long)reserved*sector;fatBytes=fats*sector;rootOffset=fatOffset+2*fatBytes;
        dataOffset=rootOffset+(long)rootSectors*sector;
        if((clusters+2)*entrySize>fatBytes)throw new IOException("Tablica FAT jest za mała.");
        Calendar now=new GregorianCalendar();int year=Math.max(1980,Math.min(2107,now.get(Calendar.YEAR)));
        date=((year-1980)<<9)|((now.get(Calendar.MONTH)+1)<<5)|now.get(Calendar.DAY_OF_MONTH);
        time=(now.get(Calendar.HOUR_OF_DAY)<<11)|(now.get(Calendar.MINUTE)<<5)|(now.get(Calendar.SECOND)/2);
        tenths=(now.get(Calendar.SECOND)%2)*100+now.get(Calendar.MILLISECOND)/10;
        Map<String,Node> paths=new HashMap<>();paths.put("",root);nodes.add(root);
        List<IsoTree.Entry> sorted=new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(e->e.path.split("/",-1).length));
        long bytes=0;
        for(IsoTree.Entry entry:sorted) {
            if(entry.path.isEmpty() || entry.path.length()>32767 || entry.length<0 || (!entry.directory && entry.length>MAX_FILE))
                throw new IOException("Plik nie mieści się w FAT: "+entry.path);
            for(String part:entry.path.split("/",-1))if(!validName(part))throw new IOException("Nieprawidłowa nazwa FAT: "+entry.path);
            int slash=entry.path.lastIndexOf('/');String parentPath=slash<0?"":entry.path.substring(0,slash);
            Node parent=paths.get(key(parentPath));
            if(parent==null || !parent.directory())throw new IOException("Brak katalogu nadrzędnego FAT: "+entry.path);
            Node node=new Node(parent,entry.path.substring(slash+1),entry);
            if(paths.put(key(entry.path),node)!=null)throw new IOException("Powtórzona nazwa FAT: "+entry.path);
            parent.children.add(node);nodes.add(node);
            if(!entry.directory) {
                if(entry.length>Long.MAX_VALUE-bytes)throw new IOException("Zbyt dużo danych FAT.");bytes+=entry.length;
            }
        }
        totalBytes=bytes;
        for(Node node:nodes)if(node.directory()) {
            Set<String> aliases=new HashSet<>();Map<String,Integer> suffixes=new HashMap<>();
            for(Node child:node.children) {
                child.alias=alias(child.name,aliases,suffixes);
                child.longName=!shortText(child.alias).equals(child.name);
            }
        }
        long next=2;
        for(Node node:nodes) {
            long length;
            if(node.directory()) {
                length=directoryLength(node);
                if(node==root && bits==16) {
                    if(length>ROOT_ENTRIES*32L)throw new IOException("Za dużo wpisów w katalogu głównym FAT16.");continue;
                }
            } else length=node.entry.length;
            long count=(length+cluster-1)/cluster;
            if(node.directory())count=Math.max(1,count);
            if(count>dataClusters || count>dataClusters-(next-2))throw new IOException("Pliki nie mieszczą się na woluminie FAT.");
            if(count>0){node.start=(int)next;node.count=(int)count;allocated.add(node);next+=count;}
        }
        usedClusters=next-2;
    }
    private static String key(String text) {
        char[] key=text.toCharArray();for(int i=0;i<key.length;i++)key[i]=Character.toUpperCase(key[i]);return new String(key);
    }
    private static boolean validName(String name) {
        if(name.isEmpty() || name.length()>255 || name.equals(".") || name.equals("..") || name.endsWith(" ") || name.endsWith("."))return false;
        for(int i=0;i<name.length();i++) {
            char value=name.charAt(i);
            if(value<32 || value==0xffff || value==0xfffe || "\"*/:<>?\\|".indexOf(value)>=0)return false;
            if(Character.isHighSurrogate(value)) {if(i+1==name.length() || !Character.isLowSurrogate(name.charAt(++i)))return false;}
            else if(Character.isLowSurrogate(value))return false;
        }
        return true;
    }
    private static String clean(String text) {
        StringBuilder result=new StringBuilder();
        for(char value:text.toUpperCase(Locale.ROOT).toCharArray()) {
            if(value==' ' || value=='.')continue;
            result.append(value>32 && value<127 && "\"*+,/:;<=>?[\\]|".indexOf(value)<0?value:'_');
        }
        return result.toString();
    }
    private static byte[] alias(String name,Set<String> used,Map<String,Integer> suffixes) throws IOException {
        int dot=name.lastIndexOf('.');String base=dot>0?name.substring(0,dot):name,extension=dot>0?name.substring(dot+1):"";
        String stem=clean(base),suffix=clean(extension);
        if(stem.isEmpty())stem="_";
        boolean simple=stem.length()<=8 && suffix.length()<=3 && stem.equalsIgnoreCase(base) && suffix.equalsIgnoreCase(extension);
        suffix=suffix.substring(0,Math.min(3,suffix.length()));
        if(simple) {
            String packed=String.format(Locale.ROOT,"%-8s%-3s",stem,suffix);
            if(used.add(packed))return packed.getBytes(StandardCharsets.US_ASCII);
        }
        String bucket=stem.substring(0,Math.min(6,stem.length()))+"."+suffix;
        for(int number=suffixes.getOrDefault(bucket,1);number<1000000;number++) {
            String tail="~"+number;
            String candidate=stem.substring(0,Math.min(stem.length(),8-tail.length()))+tail;
            String packed=String.format(Locale.ROOT,"%-8s%-3s",candidate,suffix);
            if(used.add(packed)){suffixes.put(bucket,number+1);return packed.getBytes(StandardCharsets.US_ASCII);}
        }
        throw new IOException("Zbyt wiele kolidujących nazw FAT.");
    }
    private static String shortText(byte[] alias) {
        String base=new String(alias,0,8,StandardCharsets.US_ASCII).trim(),extension=new String(alias,8,3,StandardCharsets.US_ASCII).trim();
        return base+(extension.isEmpty()?"":"."+extension);
    }
    private long directoryLength(Node node) {
        long entries=node==root?(new String(label,StandardCharsets.US_ASCII).trim().isEmpty()?0:1):2;
        for(Node child:node.children)entries+=1+(child.longName?(child.name.length()+12)/13:0);
        return entries*32;
    }
    private byte[] boot() {
        byte[] bytes=new byte[sector];ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{(byte)0xeb,(byte)(bits==32?0x58:0x3c),(byte)0x90}).put("MSWIN4.1".getBytes(StandardCharsets.US_ASCII));
        b.putShort((short)sector).put((byte)spc).putShort((short)reserved).put((byte)2).putShort((short)(bits==16?ROOT_ENTRIES:0));
        long sectors=capacity/sector;
        b.putShort((short)(bits==16 && sectors<65536?sectors:0)).put((byte)0xf8).putShort((short)(bits==16?fatSectors:0));
        b.putShort((short)63).putShort((short)255).putInt((int)hidden).putInt((int)(bits==16 && sectors<65536?0:sectors));
        if(bits==32)b.putInt(fatSectors).putShort((short)0).putShort((short)0).putInt(root.start).putShort((short)1).putShort((short)6);
        b.position(bits==32?64:36);b.put((byte)128).put((byte)0).put((byte)0x29).putInt(new java.security.SecureRandom().nextInt());
        b.put(new String(label,StandardCharsets.US_ASCII).trim().isEmpty()?"NO NAME    ".getBytes(StandardCharsets.US_ASCII):label).put((bits==32?"FAT32   ":"FAT16   ").getBytes(StandardCharsets.US_ASCII));b.putShort(510,(short)0xaa55);
        return bytes;
    }
    private byte[] shortEntry(byte[] name,int attributes,int start,long length) {
        byte[] bytes=new byte[32];ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put(name).put((byte)attributes);b.put(13,(byte)tenths);b.putShort(14,(short)time).putShort(16,(short)date).putShort(18,(short)date);
        b.putShort(20,(short)(bits==32?start>>>16:0)).putShort(22,(short)time).putShort(24,(short)date).putShort(26,(short)start).putInt(28,(int)length);
        return bytes;
    }
    private byte[] longEntry(Node node,int order,int count) {
        byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)0xff);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes[0]=(byte)(order|(order==count?0x40:0));bytes[11]=0x0f;bytes[12]=0;bytes[26]=bytes[27]=0;
        int checksum=0;for(byte value:node.alias)checksum=(((checksum&1)<<7)+(checksum>>>1)+(value&255))&255;bytes[13]=(byte)checksum;
        int[] positions={1,3,5,7,9,14,16,18,20,22,24,28,30};
        for(int i=0;i<13;i++){int at=(order-1)*13+i;b.putShort(positions[i],(short)(at<node.name.length()?node.name.charAt(at):at==node.name.length()?0:0xffff));}
        return bytes;
    }
    private long offset(Node node){return node==root && bits==16?rootOffset:node.start==0?0:dataOffset+(long)(node.start-2)*cluster;}
    private static MessageDigest digest() {
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException error){throw new AssertionError(error);}
    }
    private static void metadata(BlockDevice disk,long offset,byte[] data,List<Checksum> checks) throws IOException {
        disk.write(offset,data);if(checks!=null)checks.add(new Checksum(offset,data.length,digest().digest(data),"Struktury FAT"));
    }
    void install(BlockDevice disk,Source source,boolean quick,boolean verify,Progress progress) throws IOException {
        if(disk.size()!=capacity || disk.sectorSize()!=sector)throw new IOException("Zmieniła się geometria woluminu FAT.");
        List<Checksum> checks=verify?new ArrayList<>():null;
        byte[] buffer=new byte[BUFFER];
        if(!quick)for(long at=0;at<capacity;) {
            int size=(int)Math.min(buffer.length,capacity-at);progress.update("Zerowanie FAT"+bits,"",at,capacity);
            disk.write(at,size==buffer.length?buffer:Arrays.copyOf(buffer,size));at+=size;
        }
        progress.update("Formatowanie FAT"+bits,"",0,2*fatBytes);
        byte[] reservedBytes=new byte[reserved*sector];byte[] primary=boot();System.arraycopy(primary,0,reservedBytes,0,sector);
        if(bits==32) {
            ByteBuffer info=ByteBuffer.wrap(reservedBytes).order(ByteOrder.LITTLE_ENDIAN);
            info.putInt(sector,0x41615252).putInt(sector+484,0x61417272).putInt(sector+488,(int)(dataClusters-usedClusters));
            info.putInt(sector+492,usedClusters<dataClusters?(int)(usedClusters+2):0xffffffff).putInt(sector+508,0xaa550000);
            System.arraycopy(reservedBytes,0,reservedBytes,6*sector,2*sector);
        }
        metadata(disk,0,reservedBytes,checks);
        int width=bits/8;
        for(int copy=0;copy<2;copy++) {
            int nodeIndex=0;
            for(long at=0;at<fatBytes;) {
                int size=(int)Math.min(BUFFER,fatBytes-at);byte[] page=new byte[size];ByteBuffer values=ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<size;i+=width) {
                    long clusterNumber=(at+i)/width;int value=0;
                    if(clusterNumber<2)value=clusterNumber==0?(bits==32?0x0ffffff8:0xfff8):(bits==32?0x0fffffff:0xffff);
                    else {
                        while(nodeIndex<allocated.size() && clusterNumber>=(long)allocated.get(nodeIndex).start+allocated.get(nodeIndex).count)nodeIndex++;
                        if(nodeIndex<allocated.size()) {
                            Node node=allocated.get(nodeIndex);
                            if(clusterNumber>=node.start)value=clusterNumber+1<(long)node.start+node.count?(int)(clusterNumber+1):(bits==32?0x0fffffff:0xffff);
                        }
                    }
                    if(bits==32)values.putInt(value);else values.putShort((short)value);
                }
                metadata(disk,fatOffset+copy*fatBytes+at,page,checks);at+=size;
                progress.update("Formatowanie FAT"+bits,"",copy*fatBytes+at,2*fatBytes);
            }
        }
        for(Node directory:nodes)if(directory.directory())writeDirectory(disk,directory,checks);
        long done=0;
        for(Node file:nodes)if(!file.directory()) {
            MessageDigest hash=verify?digest():null;long copied=0;
            try(InputStream input=source.open(file.entry)) {
                while(copied<file.entry.length) {
                    progress.update("Zapisywanie",file.path(),done+copied,totalBytes);
                    int count=input.read(buffer,0,(int)Math.min(buffer.length,file.entry.length-copied));
                    if(count<0)throw new EOFException("Niepełny plik źródłowy: "+file.path());
                    if(count==0)throw new IOException("Źródło nie zwróciło danych: "+file.path());
                    disk.write(offset(file)+copied,count==buffer.length?buffer:Arrays.copyOf(buffer,count));
                    if(hash!=null)hash.update(buffer,0,count);copied+=count;
                }
                if(input.read()!=-1)throw new IOException("Zmieniono rozmiar źródła: "+file.path());
            }
            if(file.count>0 && copied%cluster!=0)disk.write(offset(file)+copied,new byte[(int)(cluster-copied%cluster)]);
            if(checks!=null)checks.add(new Checksum(offset(file),file.entry.length,hash.digest(),file.path()));
            done+=copied;
        }
        disk.sync();progress.update("Zapisywanie","",totalBytes,totalBytes);
        if(checks!=null) {
            long checked=0,total=0;for(Checksum check:checks)total+=check.length;
            for(Checksum check:checks) {
                MessageDigest hash=digest();long read=0;
                while(read<check.length) {
                    int size=(int)Math.min(buffer.length,check.length-read);byte[] data=size==buffer.length?buffer:new byte[size];
                    progress.update("Weryfikacja",check.path,checked+read,total);disk.read(check.offset+read,data);hash.update(data);read+=size;
                }
                if(!MessageDigest.isEqual(check.digest,hash.digest()))throw new IOException("Weryfikacja FAT nie powiodła się: "+check.path);
                checked+=read;
            }
            progress.update("Weryfikacja","",total,total);
        }
    }
    private void writeDirectory(BlockDevice disk,Node directory,List<Checksum> checks) throws IOException {
        long length=directory==root && bits==16?(long)rootSectors*sector:(long)directory.count*cluster;
        DirectoryWriter writer=new DirectoryWriter(disk,offset(directory),length,checks);
        if(directory!=root) {
            writer.add(shortEntry(".          ".getBytes(StandardCharsets.US_ASCII),0x10,directory.start,0));
            writer.add(shortEntry("..         ".getBytes(StandardCharsets.US_ASCII),0x10,directory.parent==root?0:directory.parent.start,0));
        } else if(!new String(label,StandardCharsets.US_ASCII).trim().isEmpty())writer.add(shortEntry(label,0x08,0,0));
        for(Node child:directory.children) {
            int count=child.longName?(child.name.length()+12)/13:0;
            for(int i=count;i>0;i--)writer.add(longEntry(child,i,count));
            writer.add(shortEntry(child.alias,child.directory()?0x10:0x20,child.start,child.directory()?0:child.entry.length));
        }
        writer.finish();
    }
    private static final class DirectoryWriter {
        final BlockDevice disk;
        final long start,length;
        final List<Checksum> checks;
        byte[] page;
        int position;
        long written;
        DirectoryWriter(BlockDevice disk,long start,long length,List<Checksum> checks){this.disk=disk;this.start=start;this.length=length;this.checks=checks;page=new byte[(int)Math.min(BUFFER,length)];}
        void add(byte[] entry) throws IOException {
            if(written+position+entry.length>length)throw new IOException("Przepełnienie katalogu FAT.");
            if(position==page.length)flush();System.arraycopy(entry,0,page,position,entry.length);position+=entry.length;
        }
        void flush() throws IOException {
            metadata(disk,start+written,page,checks);written+=page.length;
            page=new byte[(int)Math.min(BUFFER,length-written)];position=0;
        }
        void finish() throws IOException {while(written<length)flush();}
    }
}
