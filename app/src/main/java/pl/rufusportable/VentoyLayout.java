package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Boot layout from Ventoy2Disk 1.1.18. Offsets in this class are 512-byte sectors. */
final class VentoyLayout {
    interface Assets { InputStream open(String name) throws IOException; }
    static final int SECTOR=512, FIRST=2048, EFI=65536;
    static ByteBuffer little(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    static byte[] read(Assets assets,String name,int size,boolean compressed) throws IOException {
        byte[] result=new byte[size];
        try (InputStream file=assets.open(name);
             DataInputStream in=new DataInputStream(compressed?new GZIPInputStream(file):file)) {
            in.readFully(result);
            if (in.read()!=-1) throw new IOException("Niepoprawny rozmiar składnika "+name);
        }
        return result;
    }

    static DiskLayout create(Assets assets,long capacity,int sector,boolean gpt,String label,int sectorsPerCluster,
                             String name,long length,DiskLayout.Source source) throws IOException {
        if (sector!=SECTOR) throw new IOException("Tryb ISO wymaga nośnika o sektorach logicznych 512 B.");
        long sectors=capacity/SECTOR;
        long esp=(sectors-EFI-(gpt?33:1))&~7L;
        if (esp<FIRST+8192 || length<1) throw new IOException("Za mało miejsca na obraz i partycję rozruchową.");
        DiskLayout layout=new DiskLayout();
        ExFat.add(layout,assets,esp-FIRST,sectorsPerCluster,label,name,length,source);
        layout.add(esp*SECTOR,(long)EFI*SECTOR,()->new GZIPInputStream(assets.open("efi.gz")));
        byte[] first=new byte[FIRST*SECTOR];
        byte[] boot=read(assets,"boot.bin",512,false);
        System.arraycopy(boot,0,first,0,446);
        byte[] core=read(assets,"core.gz",2047*512,true);
        int start=gpt?34:1;
        System.arraycopy(core,0,first,start*SECTOR,(FIRST-start)*SECTOR);
        byte[] id=new byte[16]; new java.security.SecureRandom().nextBytes(id);
        System.arraycopy(id,0,first,384,16);
        System.arraycopy(id,12,first,440,4);
        first[510]=0x55; first[511]=(byte)0xaa;
        if (gpt) {
            first[92]=0x22; first[17908]=0x23;
            mbrEntry(first,446,0,0xee,1,sectors-1);
            byte[] entries=new byte[16384];
            gptEntry(entries,0,"EBD0A0A2-B9E5-4433-87C0-68B6B72699C7",FIRST,esp-1,"Ventoy");
            gptEntry(entries,128,"C12A7328-F81F-11D2-BA4B-00A0C93EC93B",esp,esp+EFI-1,"VTOYEFI");
            UUID disk=UUID.randomUUID();
            byte[] primary=header(sectors,1,sectors-1,2,disk,entries);
            System.arraycopy(primary,0,first,512,512);
            System.arraycopy(entries,0,first,1024,entries.length);
            layout.add((sectors-33)*SECTOR,entries);
            layout.add((sectors-1)*SECTOR,header(sectors,sectors-1,1,sectors-33,disk,entries));
        } else {
            mbrEntry(first,446,128,7,FIRST,esp-FIRST);
            mbrEntry(first,462,0,0xef,esp,EFI);
            layout.add((sectors-1)*SECTOR,new byte[512]); // Clear previous backup GPT header.
        }
        layout.add(0,first);
        layout.validate(capacity,sector);
        return layout;
    }
    private static void mbrEntry(byte[] table,int pos,int active,int type,long start,long count) {
        ByteBuffer b=little(table); b.position(pos);
        b.put((byte)active).put(new byte[]{-2,-1,-1}).put((byte)type).put(new byte[]{-2,-1,-1})
         .putInt((int)start).putInt((int)count);
    }
    private static void guid(ByteBuffer b,UUID uuid) {
        long hi=uuid.getMostSignificantBits();
        b.putInt((int)(hi>>>32)).putShort((short)(hi>>>16)).putShort((short)hi);
        b.order(ByteOrder.BIG_ENDIAN).putLong(uuid.getLeastSignificantBits()).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void gptEntry(byte[] entries,int pos,String type,long first,long last,String name) {
        ByteBuffer b=little(entries); b.position(pos);
        guid(b,UUID.fromString(type)); guid(b,UUID.randomUUID());
        b.putLong(first).putLong(last).putLong(0).put(name.getBytes(StandardCharsets.UTF_16LE));
    }
    private static int crc(byte[] data,int size) {
        CRC32 crc=new CRC32(); crc.update(data,0,size); return (int)crc.getValue();
    }
    private static byte[] header(long sectors,long self,long other,long partitionLba,UUID disk,byte[] entries) {
        byte[] result=new byte[512]; ByteBuffer b=little(result);
        b.put("EFI PART".getBytes(StandardCharsets.US_ASCII)).putInt(65536).putInt(92).putLong(0)
         .putLong(self).putLong(other).putLong(34).putLong(sectors-34);
        guid(b,disk); b.putLong(partitionLba).putInt(128).putInt(128).putInt(crc(entries,entries.length));
        b.putInt(16,crc(result,92)); return result;
    }
}
