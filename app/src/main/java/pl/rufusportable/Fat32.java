package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** FAT32 with two FATs and backup boot sectors. Optional FreeDOS BIOS boot code from Rufus. */
final class Fat32 {
    static DiskLayout create(VolumeResources.Assets assets,long size,int sector,boolean dos,String label) throws IOException {
        if (sector!=512) throw new IOException("Formatowanie FAT32 wymaga sektorów 512 B.");
        long total=size/sector, sectors=total-2049;
        int spc=64;
        while (spc>1 && sectors/spc<70000) spc/=2;
        int cluster=spc*sector;
        int fatSectors=(int)(((sectors-32+2L*spc)*4+cluster+7)/(cluster+8));
        int clusters=(int)((sectors-32-2L*fatSectors)/spc);
        if (clusters<65525 || clusters>0xffffff5) throw new IOException("Ten nośnik ma nieobsługiwaną pojemność dla FAT32.");
        byte[] mbr=new byte[2048*512];
        if (dos) System.arraycopy(VolumeResources.read(assets,"mbr.bin",440),0,mbr,0,440);
        ByteBuffer mb=VolumeResources.little(mbr);
        mb.putInt(440,UUID.randomUUID().hashCode());
        mb.position(446); mb.put((byte)(dos?128:0)).put(new byte[]{32,33,0}).put((byte)12)
                .put(new byte[]{-2,-1,-1}).putInt(2048).putInt((int)sectors).putShort(510,(short)0xaa55);
        byte[] reserved=new byte[32*512]; ByteBuffer boot=VolumeResources.little(reserved);
        boot.put(new byte[]{(byte)0xeb,0x58,(byte)0x90}).put("MSWIN4.1".getBytes(StandardCharsets.US_ASCII))
                .putShort((short)512).put((byte)spc).putShort((short)32).put((byte)2).putShort((short)0)
                .putShort((short)0).put((byte)0xf8).putShort((short)0).putShort((short)63).putShort((short)255)
                .putInt(2048).putInt((int)sectors).putInt(fatSectors).putShort((short)0).putShort((short)0)
                .putInt(2).putShort((short)1).putShort((short)6);
        if (!label.matches("[a-zA-Z0-9 _-]{1,11}")) throw new IOException("Etykieta FAT32 może zawierać do 11 liter ASCII, cyfr, spacji, znaków - i _.");
        byte[] volume=String.format(Locale.ROOT,"%-11s",label.toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.US_ASCII);
        boot.position(64); boot.put((byte)128).put((byte)0).put((byte)0x29).putInt(UUID.randomUUID().hashCode())
                .put(volume).put("FAT32   ".getBytes(StandardCharsets.US_ASCII));
        if (dos) {
            System.arraycopy(VolumeResources.read(assets,"pbr.bin",918),0,reserved,0x52,918);
            System.arraycopy(VolumeResources.read(assets,"tail.bin",528),0,reserved,0x3f0,528);
        }
        boot.putShort(510,(short)0xaa55); boot.putInt(512,0x41615252); boot.putInt(996,0x61417272);
        boot.putInt(1020,0xaa550000);
        byte[] kernel=dos?VolumeResources.read(assets,"KERNEL.SYS",46256):new byte[0];
        byte[] command=dos?VolumeResources.read(assets,"COMMAND.COM",87772):new byte[0];
        int commandCl=3+(kernel.length+cluster-1)/cluster;
        int next=commandCl+(command.length+cluster-1)/cluster;
        boot.putInt(1000,clusters-(next-2)); boot.putInt(1004,next);
        System.arraycopy(reserved,0,reserved,6*512,3*512);
        byte[] fat=new byte[next*4]; ByteBuffer chain=VolumeResources.little(fat);
        chain.putInt(0x0ffffff8).putInt(0x0fffffff).putInt(0x0fffffff);
        for (int i=3;i<next;i++) chain.putInt(i+1==commandCl || i+1==next?0x0fffffff:i+1);
        byte[] root=new byte[cluster]; ByteBuffer dir=VolumeResources.little(root);
        dir.put(volume).put((byte)8);
        DiskLayout layout=new DiskLayout();
        long base=2048L*512, fats=base+32L*512, fatBytes=(long)fatSectors*512;
        for (int i=0;i<2;i++) layout.add(fats+i*fatBytes,fatBytes,()->new ExFat.Padded(fat,fatBytes));
        long heap=fats+2*fatBytes;
        if (dos) {
            entry(dir,32,"KERNEL  SYS",3,kernel.length);
            entry(dir,64,"COMMAND COM",commandCl,command.length);
            layout.add(heap+cluster,kernel); layout.add(heap+(long)(commandCl-2)*cluster,command);
        }
        layout.add(heap,root); layout.add(base,reserved);
        layout.add(size-512,new byte[512]); layout.add(0,mbr);
        layout.validate(size,sector); return layout;
    }
    private static void entry(ByteBuffer dir,int pos,String name,int cluster,int length) {
        dir.position(pos); dir.put(name.getBytes(StandardCharsets.US_ASCII)).put((byte)32);
        dir.putShort(pos+20,(short)(cluster>>>16)); dir.putShort(pos+26,(short)cluster); dir.putInt(pos+28,length);
    }
}
