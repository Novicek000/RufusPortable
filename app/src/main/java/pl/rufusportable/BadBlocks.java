package pl.rufusportable;

import java.io.*;
import java.util.*;

/** Destructive media test adapted from Rufus badblocks.c and rufus.h (GPL-3.0+).
 * Copyright 1992-1994 Remy Card; 1995-1999 Theodore Ts'o; 1999 David Beattie;
 * 2011-2024 Pete Batard. Original badblocks derives from Linus Torvalds' fsck/mkfs.
 * Tests 512 KiB blocks, including block identifiers to reveal aliased capacity. */
final class BadBlocks {
    static final int BLOCK_SIZE=512*1024, BATCH_BLOCKS=64, MAX_BAD_BLOCKS=256;
    static final String[] OPTIONS={"1 przebieg", "2 przebiegi", "4 przebiegi · SLC",
            "4 przebiegi · MLC", "4 przebiegi · TLC"};
    private static final int[][] PATTERNS={{0x55},{0x55,0xaa},{0,0xff,0x55,0xaa},
            {0,0xff,0x33,0xcc},{0,0xff,0x1c71c7,0xe38e38}};
    enum Failure { READ, WRITE, CORRUPTION }
    interface Progress {
        void update(int pass,int passes,boolean reading,long done,long total) throws IOException;
    }
    static final class Report {
        final SortedMap<Long,Failure> failures;
        final long rangeBytes;
        final boolean complete;
        Report(SortedMap<Long,Failure> failures,long rangeBytes,boolean complete) {
            this.failures=Collections.unmodifiableSortedMap(new TreeMap<>(failures));
            this.rangeBytes=rangeBytes;this.complete=complete;
        }
        int count(Failure type) {
            int count=0;
            for (Failure failure:failures.values()) if (failure==type) count++;
            return count;
        }
    }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano test bloków.");
    }
    private static boolean record(SortedMap<Long,Failure> failures,long block,Failure type) {
        if (!failures.containsKey(block)) failures.put(block,type);
        return failures.size()>=MAX_BAD_BLOCKS;
    }
    private static void fill(byte[] buffer,int pattern) {
        int width=pattern>0xffff?3:pattern>0xff?2:1;
        for(int i=0;i<buffer.length;i++) buffer[i]=(byte)(pattern>>>(8*(width-1-i%width)));
    }
    private static void identifiers(byte[] buffer,int count,int offset,long first) {
        for(int i=0;i<count;i++) {
            long id=first+i;
            for(int b=0;b<8;b++) buffer[i*BLOCK_SIZE+offset+b]=(byte)(id>>>(b*8));
        }
    }
    private static boolean different(byte[] expected,int start,byte[] actual,int offset) {
        for(int i=0;i<BLOCK_SIZE;i++) if (expected[start+i]!=actual[offset+i]) return true;
        return false;
    }
    static Report run(BlockDevice device,int option,boolean detectFakes,Progress progress) throws IOException {
        if (option<0 || option>=PATTERNS.length || device.sectorSize()<512
                || BLOCK_SIZE%device.sectorSize()!=0 || device.size()<BLOCK_SIZE
                || device.size()>(1L<<50)) throw new IOException("Nieprawidłowe parametry testu bloków.");
        long blocks=device.size()/BLOCK_SIZE, total=blocks*BLOCK_SIZE;
        // Keep the two buffers below one eighth of the Android heap budget.
        int memoryBatch=(int)Math.max(1,Math.min(BATCH_BLOCKS,Runtime.getRuntime().maxMemory()/(16L*BLOCK_SIZE)));
        int batch=(int)Math.min(memoryBatch,blocks);
        byte[] expected=new byte[batch*BLOCK_SIZE],actual=new byte[expected.length];
        byte[] oneExpected=new byte[BLOCK_SIZE],oneActual=new byte[BLOCK_SIZE];
        int idOffset=new Random().nextInt(BLOCK_SIZE-8+1);
        SortedMap<Long,Failure> failures=new TreeMap<>();
        int[] patterns=PATTERNS[option];
        for(int pass=0;pass<patterns.length;pass++) {
            fill(expected,patterns[pass]);
            for(boolean reading:new boolean[]{false,true}) {
                for(long first=0;first<blocks;first+=batch) {
                    checkCancelled();
                    progress.update(pass+1,patterns.length,reading,first*BLOCK_SIZE,total);
                    int count=(int)Math.min(batch,blocks-first);
                    if (detectFakes && pass==0) identifiers(expected,count,idOffset,first);
                    int bytes=count*BLOCK_SIZE;
                    byte[] write=bytes==expected.length?expected:Arrays.copyOf(expected,bytes);
                    byte[] read=bytes==actual.length?actual:new byte[bytes];
                    boolean bulkSucceeded;
                    try {
                        if(reading) device.read(first*BLOCK_SIZE,read);
                        else device.write(first*BLOCK_SIZE,write);
                        bulkSucceeded=true;
                    } catch(InterruptedIOException cancelled) {throw cancelled;}
                    catch(IOException error) {bulkSucceeded=false;}
                    for(int index=0;index<count;index++) {
                        checkCancelled();
                        long block=first+index;
                        if (!bulkSucceeded) {
                            // Retry individual test blocks to locate the failing region.
                            progress.update(pass+1,patterns.length,reading,block*BLOCK_SIZE,total);
                            System.arraycopy(expected,index*BLOCK_SIZE,oneExpected,0,BLOCK_SIZE);
                            try {
                                if(reading) device.read(block*BLOCK_SIZE,oneActual);
                                else device.write(block*BLOCK_SIZE,oneExpected);
                            } catch(InterruptedIOException cancelled) {throw cancelled;}
                            catch(IOException error) {
                                if(record(failures,block,reading?Failure.READ:Failure.WRITE))
                                    return new Report(failures,total,false);
                                continue;
                            }
                        }
                        if (reading && (bulkSucceeded?different(expected,index*BLOCK_SIZE,read,index*BLOCK_SIZE):
                                different(oneExpected,0,oneActual,0))) {
                            if(record(failures,block,Failure.CORRUPTION)) return new Report(failures,total,false);
                        }
                    }
                }
                if (!reading) device.sync();
                progress.update(pass+1,patterns.length,reading,total,total);
            }
        }
        return new Report(failures,total,true);
    }
}
