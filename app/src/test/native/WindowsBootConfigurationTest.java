package pl.rufusportable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class WindowsBootConfigurationTest {
    private static final String EXE="{a1943bbc-ea85-487c-97c7-c9ede908a38a}",EFI="{b012b84d-c47c-4ed5-b722-c0c42163e569}";
    private static String object(String id){return "Objects\\"+id;}
    private static String element(String id,String number){return object(id)+"\\Elements\\"+number;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void template(File minimal,File output) throws Exception {
        try(RegistryHive hive=new RegistryHive(minimal,true)) {
            hive.putDword(object(WindowsBootConfiguration.BOOTMGR)+"\\Description","Type",0x10100002);
            hive.putDword(object(WindowsBootConfiguration.MEMDIAG)+"\\Description","Type",0x10200005);
            for(String id:new String[]{EXE,EFI}) {
                hive.putDword(object(id)+"\\Description","Type",0x10200003);
                hive.putString(element(id,"12000002"),"Element",id.equals(EFI)?"\\windows\\system32\\winload.efi":"\\windows\\system32\\winload.exe");
                hive.put(element(id,"14000006"),"Element",RegistryHive.MULTI_STRING,
                        "{6efb52bf-1766-41db-a6b3-0ee5eff72bd7}\0\0".getBytes(StandardCharsets.UTF_16LE));
                for(String number:new String[]{"26000022","42000002","45000001","14000008","23000003","24000002","26000005"})
                    hive.put(element(id,number),"Element",RegistryHive.BINARY,new byte[]{1});
            }
            hive.putString("Inherited\\Existing","Preserve","żółć 😀");hive.commit(output);
        }
    }
    public static void main(String[] args) throws Exception {
        File minimal=new File(args[0]);Path output=Path.of(args[1]);Files.createDirectories(output);
        Path source=output.resolve("bcd-template.hive");template(minimal,source.toFile());
        byte[] original=Files.readAllBytes(source);
        for(boolean gpt:new boolean[]{false,true})for(boolean efi:new boolean[]{false,true}) {
            WindowsToGoLayout disk=WindowsToGoLayout.create(64L<<30,512,4096,gpt,new byte[0],!gpt && efi?new byte[1048576]:null);
            File result=output.resolve("bcd-"+(gpt?"gpt":"mbr")+"-"+(efi?"efi":"bios")+".hive").toFile();
            String loader=WindowsBootConfiguration.create(source.toFile(),result,disk,efi,"pl-PL","Windows 11 żółć 😀");
            try(RegistryHive hive=new RegistryHive(result,false)) {
                check(hive.get(object(loader)+"\\Description","Type").dword()==0x10200003,"loader type");
                check(hive.get(element(loader,"12000002"),"Element").string().endsWith(efi?"winload.efi":"winload.exe"),"winload path");
                check(hive.get(element(loader,"12000004"),"Element").string().equals("Windows 11 żółć 😀"),"description Unicode");
                check(hive.get(element(loader,"12000005"),"Element").string().equals("pl-PL"),"locale");
                check(hive.get(element(loader,"22000002"),"Element").string().equals("\\Windows"),"system root");
                check(Arrays.equals(hive.get(element(loader,"11000001"),"Element").data,WindowsBootConfiguration.device(disk,disk.main)),"loader device");
                check(Arrays.equals(hive.get(element(loader,"21000001"),"Element").data,WindowsBootConfiguration.device(disk,disk.main)),"OS device");
                check(Arrays.equals(hive.get(element(loader,"16000009"),"Element").data,new byte[]{0}),"disable recovery");
                check(hive.get(element(loader,"26000022"),"Element")==null,"WinPE mode removed");
                check(hive.get(element(loader,"42000002"),"Element")==null && hive.get(element(loader,"45000001"),"Element")==null,"template instructions removed");
                check(hive.get(element(loader,"23000003"),"Element")==null,"resume reference removed");
                check(hive.get(element(loader,"14000006"),"Element").type==RegistryHive.MULTI_STRING,"inheritance retained");
                check(hive.get("Inherited\\Existing","Preserve").string().equals("żółć 😀"),"other objects preserved");
                check(hive.get(element(WindowsBootConfiguration.BOOTMGR,"23000003"),"Element").string().equals(loader),"default object GUID is REG_SZ");
                RegistryHive.Value order=hive.get(element(WindowsBootConfiguration.BOOTMGR,"24000001"),"Element");
                check(order.type==RegistryHive.MULTI_STRING && new String(order.data,StandardCharsets.UTF_16LE).equals(loader+"\0\0"),"display order is REG_MULTI_SZ");
                check(hive.get("Description","KeyName").string().equals("BCD00000000"),"root key name");
            }
            check(Arrays.equals(original,Files.readAllBytes(source)),"source changed");
        }
        Path missing=output.resolve("incomplete.hive");
        try{WindowsBootConfiguration.create(minimal,missing.toFile(),WindowsToGoLayout.create(64L<<30,512,4096,true,new byte[0],null),true,"en-US","Windows");throw new AssertionError("Incomplete template accepted");}catch(IOException expected){}
        check(!Files.exists(missing),"failed validation created output");
        if(args.length>2) {
            File actual=new File(args[2]);WindowsToGoLayout disk=WindowsToGoLayout.create(64L<<30,512,4096,true,new byte[0],null);
            WindowsBootConfiguration.create(actual,output.resolve("bcd-microsoft-template.hive").toFile(),disk,true,"en-US","Windows 11");
            System.out.println("Microsoft BCD template was configured; a Windows boot test is still required");
        }
        System.out.println("Offline BCD template cloning, runtime references, GPT/MBR devices and recovery tests passed");
    }
}
