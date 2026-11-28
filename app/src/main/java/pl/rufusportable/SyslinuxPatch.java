package pl.rufusportable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Calls the original Rufus Syslinux libinstaller with a validated volume sector map. */
final class SyslinuxPatch {
    static {System.loadLibrary("rufus_boot");}
    static final class Result {
        final byte[] file,bootSector;
        Result(byte[][] fields) throws IOException {
            if(fields==null || fields.length!=2 || fields[0]==null || fields[1]==null || fields[1].length!=512)
                throw new IOException("Niepełny wynik instalatora Syslinux.");
            file=fields[0];bootSector=fields[1];
        }
    }
    static Result patch(byte[] system,byte[] bss,byte[] boot,long[] sectors,String directory,boolean stupid,boolean raid) throws IOException {
        if(directory==null || directory.indexOf(0)>=0 || directory.indexOf('\\')>=0 || directory.length()>255)
            throw new IOException("Nieprawidłowy katalog Syslinux.");
        for(String part:directory.split("/",-1))if(part.equals(".") || part.equals(".."))throw new IOException("Nieprawidłowy katalog Syslinux.");
        return new Result(prepare(system,bss,boot,sectors,directory.getBytes(StandardCharsets.UTF_8),stupid,raid));
    }
    private static native byte[][] prepare(byte[] system,byte[] bss,byte[] boot,long[] sectors,byte[] directory,boolean stupid,boolean raid) throws IOException;
    private SyslinuxPatch(){}
}
