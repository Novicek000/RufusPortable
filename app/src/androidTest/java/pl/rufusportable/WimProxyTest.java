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
}
