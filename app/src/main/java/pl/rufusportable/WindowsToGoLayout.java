package pl.rufusportable;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.CRC32;

/** Windows To Go placement from Rufus drive.c, with the ESP first. Offsets are bytes. */
final class WindowsToGoLayout {
    static final UUID ESP=UUID.fromString("C12A7328-F81F-11D2-BA4B-00A0C93EC93B");
    static final UUID MSR=UUID.fromString("E3C9E316-0B5C-4DB8-817D-F92DF00215AE");
    static final UUID DATA=UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7");
    static final class Partition {
        final long offset,length;
        final UUID type,id;
        final String name;
        Partition(long offset,long length,UUID type,String name) {
            this.offset=offset;this.length=length;this.type=type;this.name=name;id=UUID.randomUUID();
        }
    }
    final boolean gpt;
    final long capacity;
    final int sectorSize,diskSignature;
    final UUID diskId;
    final Partition main,esp,reserved;
    final List<Partition> partitions;
    final DiskLayout metadata;

    private WindowsToGoLayout(long capacity,int sector,int cluster,boolean gpt,byte[] mbr,byte[] uefi,boolean uefiOnly) throws IOException {
        if(capacity<=0 || capacity%sector!=0 || sector<512 || sector>4096 || Integer.bitCount(sector)!=1 ||
                cluster<sector || cluster>2097152 || Integer.bitCount(cluster)!=1 ||
                (!gpt && (sector!=512 || capacity/sector>0x100000000L)) ||
                mbr==null || mbr.length>440 || (!gpt && uefi!=null && uefi.length!=1048576))
            throw new IOException("Nieobsługiwana geometria Windows To Go.");
        this.gpt=gpt;this.capacity=capacity;sectorSize=sector;diskId=UUID.randomUUID();
        int signature;do {signature=new SecureRandom().nextInt();}while(signature==0);
        diskSignature=uefiOnly && !gpt?0x49464555:signature;
        long track=63L*sector,start=1048576,last=capacity-(gpt?33L*sector:0);
        List<Partition> parts=new ArrayList<>();
        if(gpt) {
            esp=new Partition(start,260L<<20,ESP,"EFI System Partition");parts.add(esp);
            start=next(esp,track,cluster);
            reserved=new Partition(start,128L<<20,MSR,"Microsoft Reserved Partition");parts.add(reserved);
            start=next(reserved,track,cluster);
        } else {esp=null;reserved=null;}
        Partition auxiliary=null;
        if(!gpt && uefi!=null) {
            long length=ceil(uefi.length,track);last=floor(last-length,track);
            auxiliary=new Partition(last,length,ESP,"UEFI:NTFS");
        }
        long length=floor(floor(last-start,track),cluster);
        if(length<32L<<20)throw new IOException("Nośnik jest za mały na Windows To Go.");
        main=new Partition(start,length,DATA,"Main Data Partition");parts.add(main);
        if(auxiliary!=null)parts.add(auxiliary);
        partitions=Collections.unmodifiableList(parts);metadata=new DiskLayout();
        byte[] first=new byte[1048576];ByteBuffer b=le(first);
        b.putInt(440,diskSignature);b.putShort(510,(short)0xaa55);
        if(gpt) {
            mbrEntry(b,446,0,0xee,1,Math.min(0xffffffffL,capacity/sector-1));
            byte[] entries=new byte[16384];ByteBuffer table=le(entries);
            for(int i=0;i<parts.size();i++) {
                Partition p=parts.get(i);table.position(i*128);guid(table,p.type);guid(table,p.id);
                table.putLong(p.offset/sector).putLong((p.offset+p.length)/sector-1).putLong(0)
                        .put(p.name.getBytes(StandardCharsets.UTF_16LE));
            }
            long sectors=capacity/sector,backupTable=sectors-1-entries.length/sector;
            System.arraycopy(header(sectors,1,sectors-1,2,entries),0,first,sector,sector);
            System.arraycopy(entries,0,first,2*sector,entries.length);
            // Clear stale backup metadata in the same reserved 33-sector area Rufus uses.
            byte[] backup=new byte[33*sector];
            System.arraycopy(entries,0,backup,(int)((backupTable-(sectors-33))*sector),entries.length);
            System.arraycopy(header(sectors,sectors-1,1,backupTable,entries),0,backup,32*sector,sector);
            metadata.add(capacity-backup.length,backup);
        } else {
            if(!uefiOnly)System.arraycopy(mbr,0,first,0,mbr.length);
            mbrEntry(b,446,128,7,main.offset/sector,main.length/sector);
            if(auxiliary!=null) {
                mbrEntry(b,462,0,0xef,auxiliary.offset/sector,auxiliary.length/sector);
                metadata.add(auxiliary.offset,Arrays.copyOf(uefi,(int)auxiliary.length));
            }
            if(auxiliary==null || auxiliary.offset+auxiliary.length<=capacity-sector)
                metadata.add(capacity-sector,new byte[sector]);
        }
        metadata.add(0,first);metadata.validate(capacity,sector);
    }
    static WindowsToGoLayout create(long capacity,int sector,int cluster,boolean gpt,byte[] mbr,byte[] uefi) throws IOException {
        return create(capacity,sector,cluster,gpt,mbr,uefi,false);
    }
    static WindowsToGoLayout create(long capacity,int sector,int cluster,boolean gpt,byte[] mbr,byte[] uefi,boolean uefiOnly) throws IOException {
        // Validate before division or allocations in the constructor.
        if(sector<512 || sector>4096 || Integer.bitCount(sector)!=1)throw new IOException("Nieprawidłowy sektor nośnika.");
        if(uefiOnly && !gpt && uefi==null)throw new IOException("Rozruch UEFI z MBR wymaga partycji UEFI:NTFS.");
        return new WindowsToGoLayout(capacity,sector,cluster,gpt,mbr,uefi,uefiOnly);
    }
    private static long floor(long value,long unit){return Math.floorDiv(value,unit)*unit;}
    private static long ceil(long value,long unit){return floor(value+unit-1,unit);}
    private static long next(Partition p,long track,int cluster) throws IOException {
        long end=p.offset+p.length,next=floor(ceil(end,track),cluster);
        if(next<end)throw new IOException("Klaster powoduje nakładanie się partycji Windows To Go.");
        return next;
    }
    static ByteBuffer le(byte[] bytes){return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    static void guid(ByteBuffer b,UUID id) {
        long high=id.getMostSignificantBits();b.putInt((int)(high>>>32)).putShort((short)(high>>>16)).putShort((short)high);
        b.order(ByteOrder.BIG_ENDIAN).putLong(id.getLeastSignificantBits()).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void mbrEntry(ByteBuffer b,int offset,int active,int type,long start,long count) {
        b.position(offset);b.put((byte)active).put(new byte[]{-2,-1,-1}).put((byte)type)
                .put(new byte[]{-2,-1,-1}).putInt((int)start).putInt((int)count);
    }
    private static int crc(byte[] bytes,int length){CRC32 crc=new CRC32();crc.update(bytes,0,length);return (int)crc.getValue();}
    private byte[] header(long sectors,long current,long backup,long table,byte[] entries) {
        byte[] bytes=new byte[sectorSize];ByteBuffer b=le(bytes);
        b.put("EFI PART".getBytes(StandardCharsets.US_ASCII)).putInt(65536).putInt(92).putLong(0)
                .putLong(current).putLong(backup).putLong(34).putLong(sectors-34);
        guid(b,diskId);b.putLong(table).putInt(128).putInt(128).putInt(crc(entries,entries.length));
        b.putInt(16,crc(bytes,92));return bytes;
    }
}
