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

    private byte[] bytes(InputStream input) throws IOException {
        try(InputStream source=input;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;while((count=source.read(buffer))!=-1)out.write(buffer,0,count);return out.toByteArray();
        }
    }
    private void removeTree(File file) {
        if(file.isDirectory())for(File child:Objects.requireNonNull(file.listFiles()))removeTree(child);
        assertTrue(file.delete());
    }
    @Test public void extractsCa2023ResourcesFromIsoInstallsAndCompactsTargetWim() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source=File.createTempFile("ca-source-", ".iso",context.getCacheDir());
        File target=File.createTempFile("ca-target-", ".img",context.getCacheDir());
        File extracted=new File(context.getCacheDir(),"ca-files-"+System.nanoTime());assertTrue(extracted.mkdir());
        try {
            try(OutputStream out=new FileOutputStream(source)) {
                out.write(bytes(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("windows-test.iso")));
            }
            ImageFile image=ImageFile.describe(context,Uri.fromFile(source));
            List<IsoTree.Entry> entries=image.contents(context);
            IsoTree.Entry boot=entries.stream().filter(e->e.path.equalsIgnoreCase("sources/boot.wim")).findFirst().get();
            try(ProxyImageFile proxy=new ProxyImageFile(context,image,boot)) {
                WimMetadata.requireEditable(proxy.path());
                WimMetadata.extractFile(proxy.path(),2,"Windows/Boot/EFI_EX",extracted.getAbsolutePath());
                WimMetadata.extractFile(proxy.path(),2,"Windows/Boot/Fonts_EX",extracted.getAbsolutePath());
            }
            Map<String,File> files=WindowsBootFiles.plan(entries,extracted);
            WindowsUnattend.Options options=new WindowsUnattend.Options();options.flags=WindowsUnattend.USE_MS2023_BOOTLOADERS;
            WindowsCustomization plan=new WindowsCustomization(entries,options.flags,26300,WindowsUnattend.create(options),
                    entry->image.openEntry(context,entry),name->{throw new AssertionError();},files);
            assertTrue(plan.compactBootWim);assertFalse(plan.answerInBootWim);
            try(FileDisk disk=new FileDisk(target)) {
                var installer=new WindowsInstaller(plan.entries,disk.size(),4096);
                installer.install(disk,entry->plan.open(e->image.openEntry(context,e),entry),"CA2023",4096,true,
                        bytes(context.getAssets().open("rufus/br_ntfs_0x0.bin")),bytes(context.getAssets().open("rufus/br_ntfs_0x54.bin")),
                        true,(verify,path,done,total)->{});
                try(NtfsVolume volume=new NtfsVolume(disk);ProxyNtfsFile proxy=new ProxyNtfsFile(context,volume.openRandom(plan.bootWimPath))) {
                    long size=WimMetadata.compact(proxy.path(),2);assertTrue(size>0);proxy.completeUpdate(size);
                }
                disk.sync();
                try(NtfsVolume volume=new NtfsVolume(disk);ProxyNtfsFile proxy=new ProxyNtfsFile(context,volume.openRandom(plan.bootWimPath))) {
                    WimMetadata.verify(proxy.path());assertEquals(2,WimMetadata.read(proxy.path()).size());
                    for(var entry:files.entrySet())assertArrayEquals(bytes(new FileInputStream(entry.getValue())),bytes(volume.open(entry.getKey())));
                    assertArrayEquals(plan.answer,bytes(volume.open("sources/$OEM$/$$/Panther/unattend.xml")));
                }
            }
        } finally {removeTree(extracted);assertTrue(source.delete());assertTrue(target.delete());}
    }

    private byte[] applyFixture() throws IOException {
        return bytes(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("native-apply.wim"));
    }
    @Test public void appliesFragmentedWimToNativeNtfsWith64BitOffsets() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source=File.createTempFile("apply-source-", ".bin",context.getCacheDir());
        File target=File.createTempFile("apply-target-", ".img",context.getCacheDir());
        byte[] wim=applyFixture();long second=0x100004000L;
        try {
            try(RandomAccessFile file=new RandomAccessFile(source,"rw")) {
                file.seek(2048);file.write(wim,0,73);file.seek(second);file.write(wim,73,wim.length-73);
            }
            ImageFile image=ImageFile.describe(context,Uri.fromFile(source));
            var entry=new IsoTree.Entry("sources/install.wim",false,wim.length,Arrays.asList(
                    new IsoTree.Extent(2048,73),new IsoTree.Extent(second,wim.length-73)));
            try(FileDisk disk=new FileDisk(target,64L<<30);ProxyImageFile proxy=new ProxyImageFile(context,image,entry)) {
                NtfsVolume.format(disk,"WIM_APPLY",4096,2048,true);disk.highestWrite=0;
                long[] last={0,0};int[] callbacks={0};
                WimMetadata.apply(proxy.path(),1,disk,(done,total,files,totalFiles)->{
                    assertTrue(done>=0 && done<=total);assertTrue(files>=0 && files<=totalFiles);
                    callbacks[0]++;last[0]=done;last[1]=total;
                });
                assertNull(proxy.sourceFailure());assertTrue(callbacks[0]>1);assertEquals(last[1],last[0]);assertTrue(last[1]>0);
                assertTrue(disk.highestWrite>0xffffffffL);
                try(NtfsVolume volume=new NtfsVolume(disk)) {
                    for(String path:new String[]{"rich/data.bin","rich/alias.bin"}) {
                        byte[] data=bytes(volume.open(path));assertEquals(170003,data.length);
                        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(data);
                        StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(Locale.ROOT,"%02x",value&255));
                        assertEquals("a9b244bdfd17275039f1e97732c7f3618c6055f15edff3eec85795e5fb31fab1",hex.toString());
                    }
                    assertEquals("Original żółć 日本語 😀 data.\n",new String(bytes(volume.open("żółć/日本語.txt")),java.nio.charset.StandardCharsets.UTF_8));
                    assertEquals(0,bytes(volume.open("empty.txt")).length);
                }
            }
        } finally {assertTrue(source.delete());assertTrue(target.delete());}
    }
    @Test public void wimApplyPreservesCancellationAndAllowsRetry() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source=File.createTempFile("apply-cancel-source-", ".wim",context.getCacheDir());
        File target=File.createTempFile("apply-cancel-target-", ".img",context.getCacheDir());
        byte[] wim=applyFixture();
        try {
            try(OutputStream out=new FileOutputStream(source)){out.write(wim);}
            try(FileDisk disk=new FileDisk(target)) {
                NtfsVolume.format(disk,"WIM_CANCEL",4096,2048,true);
                try {WimMetadata.apply(source.getAbsolutePath(),1,disk,(a,b,c,d)->{throw new InterruptedIOException("cancel WIM apply");});
                    fail("Cancellation ignored");}
                catch(InterruptedIOException expected){assertEquals("cancel WIM apply",expected.getMessage());}
                WimMetadata.apply(source.getAbsolutePath(),1,disk,(a,b,c,d)->{});
                try(NtfsVolume volume=new NtfsVolume(disk)){assertEquals(170003,bytes(volume.open("rich/data.bin")).length);}
            }
        } finally {assertTrue(source.delete());assertTrue(target.delete());}
    }

}
