package pl.rufusportable;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BadBlocksTest {
    private static class Memory implements BlockDevice {
        final byte[] data;
        long advertised;
        boolean corrupt, transientWrite, writeError, readError;
        int writes;
        Memory(int blocks) {data=new byte[blocks*BadBlocks.BLOCK_SIZE];advertised=data.length;}
        public long size() {return advertised;}
        public int sectorSize() {return 512;}
        public void sync() {}
        public void write(long offset,byte[] bytes) throws IOException {
            writes++;
            if (transientWrite && bytes.length>BadBlocks.BLOCK_SIZE) throw new IOException("bulk retry");
            if (writeError && offset+bytes.length>BadBlocks.BLOCK_SIZE) throw new IOException("write error");
            for(int i=0;i<bytes.length;i++) data[(int)((offset+i)%data.length)]=bytes[i];
        }
        public void read(long offset,byte[] bytes) throws IOException {
            if (readError && offset+bytes.length>BadBlocks.BLOCK_SIZE) throw new IOException("read error");
            for(int i=0;i<bytes.length;i++) bytes[i]=data[(int)((offset+i)%data.length)];
            if(corrupt) bytes[0]^=1;
        }
    }
    private BadBlocks.Report run(Memory disk,int option,boolean ids) throws IOException {
        return BadBlocks.run(disk,option,ids,(p,n,r,d,t)->{});
    }
    @Test public void allOriginalPassOptionsCompleteOnHealthyMedia() throws Exception {
        int[] passes={1,2,4,4,4};
        for(int option=0;option<5;option++) {
            Memory disk=new Memory(2);
            BadBlocks.Report report=run(disk,option,true);
            assertTrue(report.failures.isEmpty());assertTrue(report.complete);
            assertEquals(disk.size(),report.rangeBytes);assertEquals(passes[option],disk.writes);
        }
    }
    @Test public void tlcUsesThreeBytePatternsInReferenceOrder() throws Exception {
        Memory disk=new Memory(1);
        run(disk,4,false);
        assertArrayEquals(new byte[]{(byte)0xe3,(byte)0x8e,0x38,(byte)0xe3,(byte)0x8e,0x38},Arrays.copyOf(disk.data,6));
    }
    @Test public void blockIdsRevealFakeCapacityThatRepeatingPatternsMiss() throws Exception {
        Memory disk=new Memory(2);disk.advertised=disk.size()*2;
        assertTrue(run(disk,0,false).failures.isEmpty());
        BadBlocks.Report report=run(disk,0,true);
        assertEquals(2,report.failures.size());
        assertEquals(BadBlocks.Failure.CORRUPTION,report.failures.get(0L));
        assertEquals(BadBlocks.Failure.CORRUPTION,report.failures.get(1L));
    }
    @Test public void transientBulkFailureRecoversWithIndividualTestBlocks() throws Exception {
        Memory disk=new Memory(2);disk.transientWrite=true;
        assertTrue(run(disk,1,true).failures.isEmpty());
        assertEquals(6,disk.writes);
    }
    @Test public void persistentFailuresAreClassifiedAndDeduplicatedAcrossPasses() throws Exception {
        Memory disk=new Memory(2);disk.writeError=true;disk.readError=true;
        BadBlocks.Report report=run(disk,2,true);
        assertEquals(1,report.failures.size());
        assertEquals(1,report.count(BadBlocks.Failure.WRITE));
        assertEquals(0,report.count(BadBlocks.Failure.READ));
    }
    @Test public void readAndCorruptionFailuresHaveSeparateCounts() throws Exception {
        Memory unreadable=new Memory(2);unreadable.readError=true;
        assertEquals(1,run(unreadable,0,true).count(BadBlocks.Failure.READ));
        Memory corrupt=new Memory(2);corrupt.corrupt=true;
        BadBlocks.Report report=run(corrupt,2,true);
        assertEquals(1,report.failures.size());
        assertEquals(1,report.count(BadBlocks.Failure.CORRUPTION));
    }
    @Test public void stopsAtOriginalBadBlockThreshold() throws Exception {
        BlockDevice broken=new BlockDevice() {
            public long size(){return 258L*BadBlocks.BLOCK_SIZE;}
            public int sectorSize(){return 512;}
            public void sync(){}
            public void write(long offset,byte[] data){}
            public void read(long offset,byte[] data)throws IOException{throw new IOException("read error");}
        };
        BadBlocks.Report report=BadBlocks.run(broken,0,true,(p,n,r,d,t)->{});
        assertFalse(report.complete);
        assertEquals(256,report.count(BadBlocks.Failure.READ));
    }
    @Test public void cancellationAndInvalidOptionsStopBeforeWriting() {
        Memory disk=new Memory(1);
        assertThrows(IOException.class,()->run(disk,-1,true));
        assertThrows(InterruptedIOException.class,()->BadBlocks.run(disk,0,true,(p,n,r,d,t)->{throw new InterruptedIOException();}));
        assertEquals(0,disk.writes);
    }
}
