package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Single-file exFAT volume with a contiguous ISO extent and standard up-case table. */
final class ExFat {
    private static final int FAT=128, SECTOR=512;
    private static int ceil(long a,int b) { return (int)((a+b-1)/b); }
    private static int checksum(byte[] bytes) {
        int sum=0;
        for (byte value:bytes) sum=Integer.rotateRight(sum,1)+(value&255);
        return sum;
    }
    private static int sum16(int previous,int value) {
        return (((previous>>>1)|((previous&1)<<15))+(value&255))&65535;
    }
    static boolean validLabel(String volume) {
        return !volume.isEmpty() && volume.length()<=11 && !volume.matches(".*[\\p{Cntrl}/:*?\"<>|\\\\].*");
    }
    static void add(DiskLayout disk,VentoyLayout.Assets assets,long sectors,int spc,String label,String name,
                    long length,DiskLayout.Source source) throws IOException {
        if (spc!=64 && spc!=256 && spc!=512) throw new IOException("Nieobsługiwany rozmiar klastra.");
        int cluster=spc*SECTOR;
        int fatSectors=ceil((sectors/spc+2)*4,SECTOR);
        int heap=ceil(FAT+fatSectors,spc)*spc;
        int clusters=(int)((sectors-heap)/spc);
        if (clusters<4 || length>sectors*SECTOR) throw new IOException("Brak miejsca na obraz ISO.");
        int bitmapBytes=ceil(clusters,8), bitmapClusters=ceil(bitmapBytes,cluster);
        int upcase=2+bitmapClusters, root=upcase+1, file=root+1;
        int used=file-2+ceil(length,cluster);
        if (used>clusters) throw new IOException("Brak miejsca na ISO, strukturę exFAT i partycję rozruchową.");
        byte[] boot=new byte[12*SECTOR];
        ByteBuffer b=VentoyLayout.little(boot);
        b.put((byte)0xeb).put((byte)0x76).put((byte)0x90).put("EXFAT   ".getBytes(StandardCharsets.US_ASCII));
        b.position(64); b.putLong(VentoyLayout.FIRST).putLong(sectors).putInt(FAT).putInt(fatSectors)
         .putInt(heap).putInt(clusters).putInt(root).putInt(UUID.randomUUID().hashCode()).putShort((short)256)
         .putShort((short)0).put((byte)9).put((byte)Integer.numberOfTrailingZeros(spc))
         .put((byte)1).put((byte)128).put((byte)(100L*used/clusters));
        b.putShort(510,(short)0xaa55);
        for (int i=1;i<=8;i++) b.putInt((i+1)*SECTOR-4,0xaa550000);
        int sum=0;
        for (int i=0;i<11*SECTOR;i++) if (i!=106 && i!=107 && i!=112) sum=Integer.rotateRight(sum,1)+(boot[i]&255);
        for (int i=11*SECTOR;i<boot.length;i+=4) b.putInt(i,sum);
        long base=(long)VentoyLayout.FIRST*SECTOR;
        disk.add(base+(long)heap*SECTOR+(long)(file-2)*cluster,length,source);
        disk.add(base,boot); disk.add(base+boot.length,boot);
        byte[] fat=new byte[4*(root+1)];
        ByteBuffer f=VentoyLayout.little(fat);
        f.putInt(0xfffffff8).putInt(-1);
        for (int i=2;i<upcase;i++) f.putInt(i+1==upcase?-1:i+1);
        f.putInt(-1).putInt(-1);
        disk.add(base+FAT*SECTOR,(long)fatSectors*SECTOR,()->new Padded(fat,(long)fatSectors*SECTOR));
        long data=base+(long)heap*SECTOR;
        byte[] bitmap=new byte[bitmapClusters*cluster];
        Arrays.fill(bitmap,0,used/8,(byte)255);
        if (used%8!=0) bitmap[used/8]=(byte)((1<<(used%8))-1);
        disk.add(data,bitmap);
        byte[] table=VentoyLayout.read(assets,"upcase.bin",5836,false);
        disk.add(data+(long)(upcase-2)*cluster,table);
        byte[] directory=new byte[cluster];
        ByteBuffer d=VentoyLayout.little(directory);
        String volume=label.trim();
        if (!validLabel(volume))
            throw new IOException("Etykieta exFAT musi mieć od 1 do 11 dozwolonych znaków.");
        d.put((byte)0x83).put((byte)volume.length()).put(volume.getBytes(StandardCharsets.UTF_16LE));
        d.position(32); d.put((byte)0x81); d.position(52); d.putInt(2).putLong(bitmapBytes);
        d.position(64); d.put((byte)0x82); d.putInt(68,checksum(table));
        d.position(84); d.putInt(upcase).putLong(table.length);
        // ASCII names keep the filesystem name hash independent of platform Unicode versions.
        String leaf=name.substring(0,name.length()-4).replaceAll("[^A-Za-z0-9._-]","_");
        leaf=leaf.substring(0,Math.min(leaf.length(),200))+".iso";
        byte[] filename=leaf.getBytes(StandardCharsets.UTF_16LE);
        int names=ceil(leaf.length(),15), start=96;
        d.position(start); d.put((byte)0x85).put((byte)(1+names)).putShort((short)0).putShort((short)32);
        Calendar now=Calendar.getInstance();
        int stamp=((now.get(Calendar.YEAR)-1980)<<25)|((now.get(Calendar.MONTH)+1)<<21)|
                (now.get(Calendar.DAY_OF_MONTH)<<16)|(now.get(Calendar.HOUR_OF_DAY)<<11)|
                (now.get(Calendar.MINUTE)<<5)|(now.get(Calendar.SECOND)/2);
        for (int i=8;i<=16;i+=4) d.putInt(start+i,stamp);
        int hash=0;
        for (byte value:leaf.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_16LE)) hash=sum16(hash,value);
        int stream=start+32;
        d.position(stream); d.put((byte)0xc0).put((byte)3).put((byte)0).put((byte)leaf.length()).putShort((short)hash);
        d.putLong(stream+8,length); d.putInt(stream+20,file); d.putLong(stream+24,length);
        for (int i=0;i<names;i++) {
            d.position(stream+32*(i+1)); d.put((byte)0xc1).put((byte)0);
            d.put(filename,i*30,Math.min(30,filename.length-i*30));
        }
        int total=0;
        for (int i=start;i<start+(2+names)*32;i++) if (i!=start+2 && i!=start+3) total=sum16(total,directory[i]);
        d.putShort(start+2,(short)total);
        disk.add(data+(long)(root-2)*cluster,directory);

    }

    static final class Padded extends InputStream {
        final byte[] prefix; final long size; long pos;
        Padded(byte[] prefix,long length) { this.prefix=prefix; size=length; }
        public int read() {
            if (pos==size) return -1;
            return pos++<prefix.length ? prefix[(int)pos-1]&255 : 0;
        }
        public int read(byte[] data,int offset,int count) {
            if (count==0) return 0;
            if (pos==size) return -1;
            int n=(int)Math.min(count,size-pos), copy=(int)Math.max(0,Math.min(n,prefix.length-pos));
            if (copy>0) System.arraycopy(prefix,(int)pos,data,offset,copy);
            Arrays.fill(data,offset+copy,offset+n,(byte)0); pos+=n; return n;
        }
    }
}
