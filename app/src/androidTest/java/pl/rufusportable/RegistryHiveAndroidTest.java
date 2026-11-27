package pl.rufusportable;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RegistryHiveAndroidTest {
    private File fixture() throws IOException {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file=File.createTempFile("registry-source-", ".hive",context.getCacheDir());
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("registry-minimal.hive");OutputStream out=new FileOutputStream(file)) {
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)out.write(buffer,0,count);
        }
        return file;
    }
    @Test public void nativeHivePreservesUnicodeAndBinaryWithCommitAndReopen() throws Exception {
        File source=fixture();File destination=new File(source.getParentFile(),"registry-żółć-😀-test.hive");
        byte[] data={0,1,(byte)255,0,17};
        try {
            try(RegistryHive hive=new RegistryHive(source,true)) {
                hive.putDword("ControlSet001\\Services\\partmgr\\Parameters","SanPolicy",4);
                hive.putString("Options","Nazwa 😀","żółć 日本語 😀");
                hive.put("Options","Device",RegistryHive.BINARY,data);
                hive.put("Options","Counter",RegistryHive.QWORD,ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0xfedcba9876543210L).array());
                hive.putDword("Temporary\\Subkey","Value",1);assertTrue(hive.deleteKey("Temporary"));
                hive.commit(destination);
            }
            try(RegistryHive hive=new RegistryHive(destination,false)) {
                assertEquals(4,hive.get("ControlSet001\\Services\\partmgr\\Parameters","SanPolicy").dword());
                assertEquals("żółć 日本語 😀",hive.get("Options","Nazwa 😀").string());
                assertArrayEquals(data,hive.get("Options","Device").data);
                assertEquals(0xfedcba9876543210L,ByteBuffer.wrap(hive.get("Options","Counter").data).order(ByteOrder.LITTLE_ENDIAN).getLong());
                assertTrue(hive.children("").contains("ControlSet001"));assertNull(hive.get("Temporary\\Subkey","Value"));
            }
            try(RegistryHive hive=new RegistryHive(source,false)){assertNull(hive.get("Options","Nazwa 😀"));}
        } finally {assertTrue(source.delete());assertTrue(destination.delete());}
    }
    @Test public void readonlyAndClosedHandlesRejectMutationWithoutWritingDestination() throws Exception {
        File source=fixture();File destination=new File(source.getParentFile(),"registry-readonly-test.hive");
        try {
            RegistryHive hive=new RegistryHive(source,false);
            try {
                try {hive.putDword("Options","Value",1);fail("Readonly mutation accepted");}catch(IOException expected){}
                try {hive.commit(destination);fail("Readonly commit accepted");}catch(IOException expected){}
                assertFalse(destination.exists());
            } finally {hive.close();hive.close();}
            try {hive.children("");fail("Closed hive used");}catch(IOException expected){}
        } finally {assertTrue(source.delete());if(destination.exists())assertTrue(destination.delete());}
    }
}
