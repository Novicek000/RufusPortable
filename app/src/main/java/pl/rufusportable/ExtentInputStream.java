package pl.rufusportable;

import java.io.*;
import java.util.*;

/** Reads a file's logical bytes across ISO/UDF extents without reading unrelated sectors. */
final class ExtentInputStream extends InputStream {
    private final IsoTree.Reader reader;
    private final List<IsoTree.Extent> extents;
    private final long length;
    private long position,within;
    private int index;
    ExtentInputStream(IsoTree.Reader reader,IsoTree.Entry entry) throws IOException {
        this.reader=reader;extents=entry.extents;length=entry.length;
        long total=0;
        for(IsoTree.Extent e:extents){
            if(e.length<0 || e.offset<0 || e.length>Long.MAX_VALUE-total)throw new IOException("Niepoprawne ekstenty pliku.");
            total+=e.length;
        }
        if(total!=length)throw new IOException("Niepełne ekstenty pliku.");
    }
    @Override public int read() throws IOException {
        byte[] data=new byte[1];return read(data,0,1)<0?-1:data[0]&255;
    }
    @Override public int read(byte[] data,int offset,int count) throws IOException {
        if(offset<0 || count<0 || offset>data.length-count)throw new IndexOutOfBoundsException();
        if(count==0)return 0;
        if(position==length)return -1;
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
        int n=(int)Math.min(Math.min(count,131072),length-position),done=0;
        while(done<n){
            IsoTree.Extent extent=extents.get(index);
            if(within==extent.length){index++;within=0;continue;}
            int size=(int)Math.min(n-done,extent.length-within);
            byte[] chunk=new byte[size];reader.read(extent.offset+within,chunk);
            System.arraycopy(chunk,0,data,offset+done,size);
            within+=size;position+=size;done+=size;
        }
        return n;
    }
}
