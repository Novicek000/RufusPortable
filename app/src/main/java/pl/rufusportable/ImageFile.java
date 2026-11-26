package pl.rufusportable;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;

final class ImageFile {
    final Uri uri;
    final String name;
    long length;
    boolean iso, hybrid;
    String partition="Obraz sektorowy";
    private ImageFile(Uri uri,String name,long length) {
        this.uri=uri; this.name=name; this.length=length;
    }
    static ImageFile volume(boolean dos) {
        return new ImageFile(null,dos?"FreeDOS 1.4":"Pusty wolumin FAT32",0);
    }
    static ImageFile describe(Context ctx,Uri uri) {
        String name=uri.getLastPathSegment(); long length=-1;
        try (Cursor c=ctx.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
            if (c!=null && c.moveToFirst()) {
                if (!c.isNull(0)) name=c.getString(0);
                if (!c.isNull(1)) length=c.getLong(1);
            }
        }
        return new ImageFile(uri,name==null?"Obraz dysku":name,length);
    }
    InputStream open(Context ctx) throws IOException {
        InputStream stream=ctx.getContentResolver().openInputStream(uri);
        if (stream==null) throw new IOException("Nie można otworzyć wybranego obrazu.");
        return stream;
    }
    long measure(Context ctx) throws IOException {
        long count=0;
        try (InputStream in=open(ctx)) {
            byte[] buffer=new byte[131072]; int n;
            while ((n=in.read(buffer))!=-1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                count+=n;
            }
        }
        return count;
    }
    void inspect(Context ctx) throws IOException {
        if (length<0) {
            try (ParcelFileDescriptor fd=ctx.getContentResolver().openFileDescriptor(uri,"r")) {
                if (fd!=null) length=fd.getStatSize();
            }
        }
        if (length>=0 && length<512) throw new IOException("Plik jest za mały na obraz dysku.");
        String lower=name.toLowerCase(Locale.ROOT);
        iso=lower.endsWith(".iso");
        if (!lower.matches(".*\\.(img|iso|raw|bin|dd)$")) throw new IOException("Wybierz rozpakowany obraz IMG, ISO, RAW, BIN lub DD.");
        byte[] sector=new byte[512];
        try (DataInputStream in=new DataInputStream(new BufferedInputStream(open(ctx),2048))) {
            in.readFully(sector);
            if (iso) {
                byte[] descriptor=new byte[2048];
                int remaining=16*2048-sector.length;
                while (remaining>0) {
                    int n=in.skipBytes(remaining);
                    if (n==0) { in.readByte(); n=1; }
                    remaining-=n;
                }
                boolean recognized=false;
                for (int i=0;i<16 && (length<0 || 2048L*(17+i)<=length);i++) {
                    in.readFully(descriptor);
                    String id=new String(descriptor,1,5,java.nio.charset.StandardCharsets.US_ASCII);
                    if (id.equals("CD001") || id.equals("BEA01") || id.equals("NSR02") || id.equals("NSR03")) {
                        recognized=true; break;
                    }
                }
                if (!recognized) throw new IOException("Plik nie zawiera nagłówka ISO 9660 lub UDF.");
            }
        } catch (EOFException e) { throw new IOException("Nagłówek obrazu jest niekompletny.",e); }
        hybrid=sector[510]==0x55 && sector[511]==(byte)0xaa;
        if (iso && !hybrid) { partition="ISO · tryb instalacyjny Ventoy"; return; }
        if (hybrid) {
            Properties types=new Properties();
            try (InputStream in=ctx.getResources().openRawResource(R.raw.mbr_types)) { types.load(in); }
            for (int i=450;i<510;i+=16) {
                if (sector[i]==0) continue;
                String code=String.format(Locale.ROOT,"%02x",sector[i]&255);
                partition="MBR · "+types.getProperty(code,"0x"+code); break;
            }
        }
    }
}
