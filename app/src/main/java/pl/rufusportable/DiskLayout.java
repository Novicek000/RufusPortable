package pl.rufusportable;

import java.io.*;
import java.util.*;

/** Non-overlapping byte ranges. Only allocated ranges are transferred to the device. */
final class DiskLayout {
    interface Source { InputStream open() throws IOException; }
    static final class Range {
        final long offset, length;
        final Source source;
        Range(long offset,long length,Source source) {
            this.offset=offset; this.length=length; this.source=source;
        }
    }
    final List<Range> ranges=new ArrayList<>();
    long bytes;
    void add(long offset,long length,Source source) {
        ranges.add(new Range(offset,length,source)); bytes+=length;
    }
    void add(long offset,byte[] data) {
        add(offset,data.length,()->new ByteArrayInputStream(data));
    }
    void validate(long capacity,int sector) throws IOException {
        List<Range> sorted=new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingLong(r->r.offset));
        long end=0;
        for (Range r:sorted) {
            if (r.offset<end || r.offset%sector!=0 || r.length<=0 || r.length>capacity-r.offset)
                throw new IOException("Obraz nie mieści się na nośniku lub jego układ jest nieprawidłowy.");
            end=r.offset+((r.length+sector-1)/sector)*sector;
        }
    }
}
