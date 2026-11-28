package pl.rufusportable;

import java.io.IOException;
import java.util.Collections;

/** Rufus default allocation sizes; final cluster count is checked against the actual MBR partition. */
final class FatGeometry {
    private static final long MB=1L<<20,GB=1L<<30;
    static int cluster(long diskSize,int bits,int requested) throws IOException {
        if((bits!=16 && bits!=32) || diskSize<=1048576 || diskSize%512!=0 ||
                (bits==16 && diskSize>=4*GB) || (bits==32 && (diskSize<32*MB || diskSize>=2048*GB)))
            throw new IOException("Pojemność nośnika nie pasuje do FAT"+bits+". Wybierz inny system plików.");
        int cluster=requested;
        if(cluster==0) {
            if(bits==16) {
                for(int i=32;i<=4096;i*=2)if(diskSize<i*MB){cluster=16*i;break;}
            } else {
                for(int i=32;i<=32768;i*=2)if((float)diskSize<i*MB*1.011f){cluster=8*i;break;}
                if(diskSize>=256*MB && diskSize<32*GB)
                    for(int i=8;i<=32;i*=2)if((float)diskSize<i*GB*1.011f){cluster=i/2*1024;break;}
                if(diskSize>=32*GB)cluster=32768;
                cluster=Math.max(512,cluster);
            }
        }
        if(cluster<512 || cluster>65536 || Integer.bitCount(cluster)!=1)
            throw new IOException("Nieprawidłowy rozmiar klastra FAT.");
        while(true) {
            long count=(diskSize-1048576)/512/63*63;count=count/(cluster/512)*(cluster/512);
            try {new FatVolume(Collections.emptyList(),count*512,512,bits,cluster,"",2048);return cluster;}
            catch(IOException invalid) {
                // At a capacity boundary the 1 MiB partition offset can make the default
                // too large for the FAT type. A user-selected size must never be changed.
                if(requested!=0 || cluster==512)throw invalid;
                cluster/=2;
            }
        }
    }
    private FatGeometry(){}
}
