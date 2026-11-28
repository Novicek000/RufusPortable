package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class SyslinuxJavaTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static byte[] boot() {
        byte[] bytes=new byte[512];ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{(byte)0xeb,0x58,(byte)0x90}).put("MSWIN4.1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putShort(11,(short)512).put(13,(byte)1).putShort(14,(short)32).put(16,(byte)2).put(21,(byte)0xf8);
        b.putInt(28,2048).putInt(32,262144).putInt(36,2033).putInt(44,2).putShort(48,(short)1).putShort(50,(short)6).put(66,(byte)0x29);
        b.position(82);b.put("FAT32   ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putShort(510,(short)0xaa55);return bytes;
    }
    private static void verify(byte[] system,SyslinuxPatch.Result patch) {
        check(patch.file.length==system.length+1024 && patch.bootSector.length==512,"result sizes");
        ByteBuffer b=ByteBuffer.wrap(patch.file).order(ByteOrder.LITTLE_ENDIAN);int sum=0;for(int i=0;i<system.length/4;i++)sum+=b.getInt(4*i);
        check(sum==0x3eb202fe,"ldlinux checksum");check(Arrays.equals(Arrays.copyOfRange(patch.file,system.length,system.length+512),Arrays.copyOfRange(patch.file,system.length+512,system.length+1024)),"ADV copies");
    }
    public static void main(String[] args) throws Exception {
        Path assets=Path.of(args[0]);
        byte[] module=Files.readAllBytes(assets.resolve("ldlinux_v6.c32")),signature=Files.readAllBytes(assets.resolve("ldlinux_v6.c32.sig"));
        RufusSignature.verify(module,signature);
        for(int version:new int[]{4,6}) {
            byte[] system=Files.readAllBytes(assets.resolve("ldlinux_v"+version+".sys")),bss=Files.readAllBytes(assets.resolve("ldlinux_v"+version+".bss"));
            byte[] before=system.clone(),beforeBss=bss.clone(),vbr=boot(),beforeVbr=vbr.clone();long[] sectors=new long[(system.length+511)/512+2];
            for(int i=0;i<sectors.length;i++)sectors[i]=3000+i;
            verify(system,SyslinuxPatch.patch(system,bss,vbr,sectors,"/boot/syslinux",false,false));
            check(Arrays.equals(before,system) && Arrays.equals(beforeBss,bss) && Arrays.equals(beforeVbr,vbr),"JNI mutated caller buffers");
            sectors[0]=0;try{SyslinuxPatch.patch(system,bss,vbr,sectors,"",false,false);throw new AssertionError("invalid LBA accepted");}catch(IOException expected){}sectors[0]=3000;
            try{SyslinuxPatch.patch(system,bss,vbr,sectors,"../escape",false,false);throw new AssertionError("escaping directory accepted");}catch(IOException expected){}
            try{SyslinuxPatch.patch(null,bss,vbr,sectors,"",false,false);throw new AssertionError("null image accepted");}catch(IOException expected){}
            ExecutorService executor=Executors.newFixedThreadPool(4);List<Future<?>> work=new ArrayList<>();
            try {
                for(int i=0;i<8;i++)work.add(executor.submit(()->{try{verify(system,SyslinuxPatch.patch(system,bss,vbr,sectors,"",false,false));}catch(IOException e){throw new UncheckedIOException(e);}}));
                for(Future<?> result:work)result.get();
            } finally {executor.shutdownNow();}
        }
        System.out.println("Syslinux JNI checksum, ADV, input ownership, bad arguments, thread isolation and original RSA signature passed");
    }
}
