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
                assertTrue(hive.valueNames("Options").containsAll(Arrays.asList("Nazwa 😀","Device","Counter")));
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
    @Test public void bcdRuntimeStoreClonesTemplateWithQualifiedPartitionDevices() throws Exception {
        File source=fixture(),template=new File(source.getParentFile(),"bcd-template-test.hive"),result=new File(source.getParentFile(),"bcd-runtime-test.hive");
        String original="{b012b84d-c47c-4ed5-b722-c0c42163e569}";
        try {
            try(RegistryHive hive=new RegistryHive(source,true)) {
                hive.putDword("Objects\\"+WindowsBootConfiguration.BOOTMGR+"\\Description","Type",0x10100002);
                hive.putDword("Objects\\"+original+"\\Description","Type",0x10200003);
                hive.putString("Objects\\"+original+"\\Elements\\12000002","Element","\\Windows\\system32\\winload.efi");
                hive.put("Objects\\"+original+"\\Elements\\26000022","Element",RegistryHive.BINARY,new byte[]{1});hive.commit(template);
            }
            WindowsToGoLayout disk=WindowsToGoLayout.create(64L<<30,4096,4096,true,new byte[0],null);
            String loader=WindowsBootConfiguration.create(template,result,disk,true,"pl-PL","Windows 11 żółć 😀");
            try(RegistryHive hive=new RegistryHive(result,false)) {
                assertEquals(loader,hive.get("Objects\\"+WindowsBootConfiguration.BOOTMGR+"\\Elements\\23000003","Element").string());
                assertEquals("Windows 11 żółć 😀",hive.get("Objects\\"+loader+"\\Elements\\12000004","Element").string());
                assertArrayEquals(WindowsBootConfiguration.device(disk,disk.main),hive.get("Objects\\"+loader+"\\Elements\\21000001","Element").data);
                assertNull(hive.get("Objects\\"+loader+"\\Elements\\26000022","Element"));
                assertEquals(RegistryHive.MULTI_STRING,hive.get("Objects\\"+WindowsBootConfiguration.BOOTMGR+"\\Elements\\24000001","Element").type);
            }
        } finally {assertTrue(source.delete());if(template.exists())assertTrue(template.delete());if(result.exists())assertTrue(result.delete());}
    }
}
