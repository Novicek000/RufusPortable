package pl.rufusportable;

import java.io.IOException;
import java.nio.*;

/** One ordinary MBR partition, with Rufus track/cluster placement. Offsets are bytes. */
final class MbrLayout {
    final long start=1048576,length;
    final DiskLayout metadata=new DiskLayout();
    private MbrLayout(long capacity,int sector,int cluster,int type,boolean active,byte[] code) throws IOException {
        if(sector<512 || sector>4096 || Integer.bitCount(sector)!=1 || capacity<=start || capacity%sector!=0 ||
                capacity/sector>0x100000000L || cluster<sector || cluster>2097152 || Integer.bitCount(cluster)!=1 ||
                type<1 || type>255 || code==null || code.length>440)throw new IOException("Nieobsługiwana geometria MBR.");
        long count=(capacity-start)/sector/63*63;count=count/(cluster/sector)*(cluster/sector);
        if(count<=0 || count>0xffffffffL)throw new IOException("Nośnik jest za mały lub za duży dla MBR.");
        length=count*sector;byte[] first=new byte[(int)start];System.arraycopy(code,0,first,0,code.length);
        ByteBuffer b=ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN);b.putInt(440,new java.security.SecureRandom().nextInt());
        b.position(446);b.put((byte)(active?128:0)).put(chs(start/sector)).put((byte)type).put(chs(start/sector+count-1));
        b.putInt((int)(start/sector)).putInt((int)count);b.putShort(510,(short)0xaa55);
        metadata.add(0,first);metadata.add(capacity-sector,new byte[sector]);metadata.validate(capacity,sector);
    }
    static MbrLayout create(long capacity,int sector,int cluster,int type,boolean active,byte[] code) throws IOException {
        return new MbrLayout(capacity,sector,cluster,type,active,code);
    }
    private static byte[] chs(long lba) {
        long cylinder=lba/(255*63),head=lba/63%255,sector=lba%63+1;
        return cylinder>1023?new byte[]{-2,-1,-1}:new byte[]{(byte)head,(byte)(sector|((cylinder>>2)&0xc0)),(byte)cylinder};
    }
}
