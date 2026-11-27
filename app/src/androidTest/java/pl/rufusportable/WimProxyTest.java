package pl.rufusportable;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WimProxyTest {
    private byte[] fixture() throws Exception {
        try (InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("metadata.wim");
             ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];
            int count;
            while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            return bytes.toByteArray();
        }
    }

    @Test public void nativeWimReadsAcrossIsoExtentsThroughUnprivilegedAndroidProxy() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] wim=fixture();
        long secondExtent=0x100002000L; // Beyond 4 GiB, sparse source: no ISO staging.
        File container=File.createTempFile("wim-source-", ".iso",context.getCacheDir());
        try {
            try(RandomAccessFile output=new RandomAccessFile(container,"rw")) {
                output.seek(2048);output.write(wim,0,73);
                output.seek(secondExtent);output.write(wim,73,wim.length-73);
            }
            ImageFile image=ImageFile.describe(context,Uri.fromFile(container));
            IsoTree.Entry entry=new IsoTree.Entry("sources/install.wim",false,wim.length,
                    Arrays.asList(new IsoTree.Extent(2048,73),new IsoTree.Extent(secondExtent,wim.length-73)));
            try(ProxyImageFile proxy=new ProxyImageFile(context,image,entry)) {
                List<WimMetadata.Image> images=WimMetadata.read(proxy.path());
                assertEquals(2,images.size());
                assertEquals("Windows Test żółć",images.get(0).name);
                assertEquals("x64",images.get(0).platform());
                assertEquals("Professional",images.get(0).edition);
                assertEquals("26100",images.get(0).build);
                assertEquals("Windows Test 😀",images.get(1).name);
                assertEquals("ARM64",images.get(1).platform());
                assertNull(proxy.sourceFailure());
                assertEquals(2,WimMetadata.read(proxy.path()).size());
            }
        } finally {
            assertTrue(container.delete());
        }
    }

    @Test public void invalidSourceExtentReturnsIoFailureAndReleasesDescriptor() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File container=File.createTempFile("wim-invalid-", ".iso",context.getCacheDir());
        try {
            try(FileOutputStream output=new FileOutputStream(container)) {
                output.write(new byte[64]);
            }
            ImageFile image=ImageFile.describe(context,Uri.fromFile(container));
            IsoTree.Entry entry=new IsoTree.Entry("sources/install.wim",false,2048,4096);
            try(ProxyImageFile proxy=new ProxyImageFile(context,image,entry)) {
                try {
                    WimMetadata.read(proxy.path());
                    fail("Incomplete source was accepted");
                } catch(IOException expected) {
                    assertNotNull(proxy.sourceFailure());
                }
            }
        } finally {
            assertTrue(container.delete());
        }
    }
    private static final class FileDisk implements BlockDevice,Closeable {
        final RandomAccessFile file;
        boolean failSync;
        final long capacity;
        long highestWrite;
        FileDisk(File path) throws IOException {this(path,128L<<20);}
        FileDisk(File path,long capacity) throws IOException {this.capacity=capacity;file=new RandomAccessFile(path,"rw");file.setLength(capacity);}
        public long size(){return capacity;}
        public int sectorSize(){return 512;}
        public synchronized void read(long offset,byte[] data)throws IOException{file.seek(offset);file.readFully(data);}
        public synchronized void write(long offset,byte[] data)throws IOException{highestWrite=Math.max(highestWrite,offset+data.length);file.seek(offset);file.write(data);}
        public void sync()throws IOException{if(failSync)throw new IOException("injected target sync failure");file.getFD().sync();}
        public void close()throws IOException{file.close();}
    }
    private void updateTargetWim(boolean failSync) throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target=File.createTempFile("wim-target-", ".img",context.getCacheDir());
        File answer=File.createTempFile("answer-", ".xml",context.getCacheDir());
        File extracted=new File(context.getCacheDir(),"wim-extract-"+System.nanoTime());
        assertTrue(extracted.mkdir());
        WindowsUnattend.Options options=new WindowsUnattend.Options();
        options.architecture=4;options.username="żółć/Jan";
        options.flags=WindowsUnattend.BYPASS_REQUIREMENTS|WindowsUnattend.NO_ONLINE_ACCOUNT|
                WindowsUnattend.NO_DATA_COLLECTION|WindowsUnattend.SET_USER|WindowsUnattend.DISABLE_BITLOCKER;
        byte[] contents=WindowsUnattend.create(options);
        String xml=new String(contents,java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(xml.contains("processorArchitecture=\"arm64\""));
        assertTrue(xml.contains("<Name>żółć_Jan</Name>"));
        assertTrue(xml.contains("BypassTPMCheck"));
        try {
            try(FileOutputStream out=new FileOutputStream(answer)){out.write(contents);}
            try(FileDisk disk=new FileDisk(target)) {
                NtfsVolume.format(disk,"WIMTEST",4096,2048,true);
                try(NtfsVolume volume=new NtfsVolume(disk)) {
                    volume.mkdir("sources");
                    try(OutputStream out=volume.create("sources/boot.wim")){out.write(fixture());}
                    try(ProxyNtfsFile proxy=new ProxyNtfsFile(context,volume.openRandom("sources/boot.wim"))) {
                        disk.failSync=failSync;
                        try {
                            long updatedLength=WimMetadata.addFile(proxy.path(),2,answer.getAbsolutePath(),"Autounattend.xml");
                            proxy.completeUpdate(updatedLength);
                            if(failSync)fail("Target sync failure was ignored");
                            assertNull(proxy.failure());
                        } catch(IOException error) {
                            if(!failSync)throw error;
                            assertNotNull(proxy.failure());
                            assertEquals("injected target sync failure",proxy.failure().getMessage());
                        } finally {disk.failSync=false;}
                    }
                }
                if(!failSync) {
                    try(NtfsVolume volume=new NtfsVolume(disk);
                        ProxyNtfsFile proxy=new ProxyNtfsFile(context,volume.openRandom("sources/boot.wim"))) {
                        assertEquals(2,WimMetadata.read(proxy.path()).size());
                        WimMetadata.verify(proxy.path());
                        WimMetadata.extractFile(proxy.path(),2,"Autounattend.xml",extracted.getAbsolutePath());
                        File result=new File(extracted,"Autounattend.xml");
                        byte[] actual=new byte[contents.length];
                        try(DataInputStream input=new DataInputStream(new FileInputStream(result))) {
                            input.readFully(actual);assertEquals(-1,input.read());
                        }
                        assertArrayEquals(contents,actual);assertTrue(result.delete());
                        try {
                            WimMetadata.extractFile(proxy.path(),1,"Autounattend.xml",extracted.getAbsolutePath());
                            fail("Unselected WIM image was modified");
                        } catch(IOException expected) {}
                    }
                }
            }
        } finally {
            for(File child:Objects.requireNonNull(extracted.listFiles()))assertTrue(child.delete());
            assertTrue(extracted.delete());assertTrue(answer.delete());assertTrue(target.delete());
        }
    }
    @Test public void updatesWimDirectlyOnNativeNtfsAndExtractsAfterRemount() throws Exception {
        updateTargetWim(false);
    }
    @Test public void wimUpdatePropagatesTargetSyncFailure() throws Exception {
        updateTargetWim(true);
    }

    @Test public void nativeNtfsUsesHighOffsetsOnLargeSparseTarget() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File target=File.createTempFile("ntfs-large-", ".img",context.getCacheDir());
        try(FileDisk disk=new FileDisk(target,64L<<30)) {
            NtfsVolume.format(disk,"LARGE_TEST",4096,2048,true);
            disk.highestWrite=0;
            byte[] contents=new byte[170003];new Random(19).nextBytes(contents);
            try(NtfsVolume volume=new NtfsVolume(disk);OutputStream file=volume.create("high-offset.bin")) {file.write(contents);}
            assertTrue("NTFS data allocation did not exercise a 64-bit byte offset",disk.highestWrite>0xffffffffL);
            try(NtfsVolume volume=new NtfsVolume(disk);InputStream file=volume.open("high-offset.bin")) {
                byte[] actual=new byte[contents.length];new DataInputStream(file).readFully(actual);
                assertEquals(-1,file.read());assertArrayEquals(contents,actual);
            }
        } finally {assertTrue(target.delete());}
    }

}
