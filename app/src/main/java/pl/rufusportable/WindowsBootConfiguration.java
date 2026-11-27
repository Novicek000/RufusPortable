package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline BCD configuration. Uses the BCD-Template supplied by the selected Windows image. */
final class WindowsBootConfiguration {
    static final String BOOTMGR="{9dea862c-5cdd-4e70-acc1-f32b344d4795}";
    static final String MEMDIAG="{b2721d73-1db4-4c62-bf78-c548a880142d}";
    private static final int OS_LOADER=0x10200003;
    private static String object(String id){return "Objects\\"+id;}
    private static String element(String id,String number){return object(id)+"\\Elements\\"+number;}
    private static void binary(RegistryHive hive,String id,String number,byte[] bytes) throws IOException {
        hive.put(element(id,number),"Element",RegistryHive.BINARY,bytes);
    }
    private static void string(RegistryHive hive,String id,String number,String value) throws IOException {
        hive.putString(element(id,number),"Element",value);
    }
    private static void list(RegistryHive hive,String id,String number,String... values) throws IOException {
        StringBuilder text=new StringBuilder();for(String value:values)text.append(value).append('\0');text.append('\0');
        hive.put(element(id,number),"Element",RegistryHive.MULTI_STRING,text.toString().getBytes(StandardCharsets.UTF_16LE));
    }
    private static void copy(RegistryHive hive,String source,String target,int depth) throws IOException {
        if(depth>32)throw new IOException("Zbyt głęboka struktura szablonu BCD.");
        for(String name:hive.valueNames(source)) {
            RegistryHive.Value value=hive.get(source,name);hive.put(target,name,value.type,value.data);
        }
        for(String child:hive.children(source))copy(hive,source+"\\"+child,target+"\\"+child,depth+1);
    }
    /** REG_BINARY device format, distinct from the public WMI / native API device structures.
     * 16-byte additional-options GUID, followed by the 72-byte qualified partition descriptor.
     * Partition style is 0=GPT, 1=MBR in the serialized boot environment format.
     */
    static byte[] device(WindowsToGoLayout disk,WindowsToGoLayout.Partition partition) throws IOException {
        if(!disk.partitions.contains(partition))throw new IOException("Partycja nie należy do tego nośnika.");
        byte[] bytes=new byte[88];ByteBuffer b=WindowsToGoLayout.le(bytes);
        b.putInt(16,6).putInt(24,72);b.position(32);
        if(disk.gpt)WindowsToGoLayout.guid(b,partition.id);else b.putLong(partition.offset);
        b.putInt(52,disk.gpt?0:1);b.position(56);
        if(disk.gpt)WindowsToGoLayout.guid(b,disk.diskId);else b.putInt(disk.diskSignature);
        return bytes;
    }
    static String create(File template,File destination,WindowsToGoLayout disk,boolean efi,String locale,String title) throws IOException {
        if(template.getCanonicalFile().equals(destination.getCanonicalFile()) || locale==null || !locale.matches("[A-Za-z0-9-]{1,85}") ||
                title==null || title.isEmpty() || title.indexOf(0)>=0 || (efi && !disk.gpt && disk.partitions.size()<2))
            throw new IOException("Nieprawidłowa konfiguracja rozruchu Windows To Go.");
        String selected=null;int best=-1;
        try(RegistryHive hive=new RegistryHive(template,true)) {
            RegistryHive.Value manager=hive.get(object(BOOTMGR)+"\\Description","Type");
            if(manager==null || manager.dword()!=0x10100002)throw new IOException("Szablon BCD nie zawiera menedżera rozruchu Windows.");
            for(String id:hive.children("Objects")) {
                RegistryHive.Value type=hive.get(object(id)+"\\Description","Type");
                if(type==null || type.type!=RegistryHive.DWORD || type.dword()!=OS_LOADER)continue;
                RegistryHive.Value path=hive.get(element(id,"12000002"),"Element");
                if(path==null)path=hive.get(element(id,"42000002"),"Element");
                if(path==null || !path.string().toLowerCase(Locale.ROOT).endsWith(efi?"winload.efi":"winload.exe"))continue;
                int score=hive.get(element(id,"42000003"),"Element")==null?1:0;
                if(score>best){selected=id;best=score;}
            }
            if(selected==null)throw new IOException("Brak odpowiedniego programu winload w szablonie BCD.");
            String loader="{"+UUID.randomUUID()+"}";
            copy(hive,object(selected),object(loader),0);
            // Class 4 elements are bcdboot template instructions, not runtime settings.
            for(String id:new String[]{loader,BOOTMGR})for(String number:hive.children(object(id)+"\\Elements"))
                if(number.startsWith("4"))hive.deleteKey(element(id,number));
            // Remove template references to WinPE, recovery images and resume applications.
            for(String number:new String[]{"26000022","14000008","23000003","24000002","26000005"})hive.deleteKey(element(loader,number));
            hive.putString("Description","KeyName","BCD00000000");
            byte[] main=device(disk,disk.main);
            binary(hive,loader,"11000001",main);binary(hive,loader,"21000001",main);
            string(hive,loader,"12000002",efi?"\\Windows\\system32\\winload.efi":"\\Windows\\system32\\winload.exe");
            string(hive,loader,"12000004",title);string(hive,loader,"12000005",locale);
            string(hive,loader,"22000002","\\Windows");binary(hive,loader,"16000009",new byte[]{0});
            binary(hive,BOOTMGR,"11000001",device(disk,efi && disk.gpt?disk.esp:disk.main));
            string(hive,BOOTMGR,"12000002",efi?"\\EFI\\Microsoft\\Boot\\bootmgfw.efi":"\\bootmgr");
            string(hive,BOOTMGR,"12000005",locale);string(hive,BOOTMGR,"23000003",loader);
            list(hive,BOOTMGR,"24000001",loader);
            binary(hive,BOOTMGR,"25000004",ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(30).array());
            hive.deleteKey(element(BOOTMGR,"24000002"));
            if(hive.get(object(MEMDIAG)+"\\Description","Type")!=null) {
                binary(hive,MEMDIAG,"11000001",device(disk,efi && disk.gpt?disk.esp:disk.main));
                string(hive,MEMDIAG,"12000002",efi?"\\EFI\\Microsoft\\Boot\\memtest.efi":"\\boot\\memtest.exe");
                string(hive,MEMDIAG,"12000005",locale);list(hive,BOOTMGR,"24000010",MEMDIAG);
            }
            hive.commit(destination);return loader;
        }
    }
    private WindowsBootConfiguration(){}
}
