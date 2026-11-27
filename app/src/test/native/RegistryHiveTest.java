package pl.rufusportable;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class RegistryHiveTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        Path original=Path.of(args[0]),directory=Path.of(args[1]);Files.createDirectories(directory);
        byte[] before=Files.readAllBytes(original);
        Path source=directory.resolve("żółć 😀 source.hive"),result=directory.resolve("registry-updated.hive");Files.write(source,before);
        try(RegistryHive hive=new RegistryHive(source.toFile(),true)) {
            check(hive.get("missing","value")==null,"missing key");check(hive.children("missing").isEmpty(),"missing children");
            hive.putDword("ControlSet001\\Services\\partmgr\\Parameters","SanPolicy",4);
            check(hive.get("ControlSet001\\Services\\partmgr\\Parameters","SanPolicy").dword()==4,"DWORD roundtrip");
            hive.putString("Options","Nazwa 😀","żółć 日本語 😀");
            check(hive.get("Options","Nazwa 😀").string().equals("żółć 日本語 😀"),"UTF-8 key and UTF-16 value");
            hive.put("Options","Binary",RegistryHive.BINARY,new byte[]{0,1,0,(byte)255});
            hive.put("Options","Empty",RegistryHive.BINARY,new byte[0]);
            hive.putString("","","Root default");
            hive.putDword("Options","Preserved",123);
            hive.putDword("Options","Change",1);hive.putDword("Options","Change",2);
            hive.putDword("Remove\\Subkey","Value",8);check(hive.deleteKey("Remove"),"delete key");check(!hive.deleteKey("Remove"),"delete absent");
            for(int i=0;i<1000;i++)hive.putDword("Many\\Key"+i,"Value",i);
            check(hive.children("Many").size()==1000,"expanded hive subkeys");
            try {hive.commit(directory.toFile());throw new AssertionError("Commit to a directory succeeded");}catch(IOException expected){}
            check(hive.get("Options","Change").dword()==2,"Failed commit lost in-memory state");
            hive.commit(result.toFile());
            check(Arrays.equals(before,Files.readAllBytes(source)),"Editing changed source before explicit commit target");
        }
        try(RegistryHive hive=new RegistryHive(result.toFile(),false)) {
            check(hive.get("ControlSet001\\Services\\partmgr\\Parameters","SanPolicy").dword()==4,"saved DWORD");
            check(hive.get("Options","Nazwa 😀").string().equals("żółć 日本語 😀"),"saved Unicode");
            check(Arrays.equals(new byte[]{0,1,0,(byte)255},hive.get("Options","Binary").data),"binary bytes");
            check(hive.get("Options","Empty").data.length==0,"empty binary");
            check(hive.get(""," ")==null && hive.get("","").string().equals("Root default"),"default value");
            check(hive.get("Options","Change").dword()==2 && hive.get("Options","Preserved").dword()==123,"replace retained other values");
            check(hive.get("Remove\\Subkey","Value")==null,"recursive deletion");
            check(hive.children("Many").size()==1000 && hive.get("Many\\Key999","Value").dword()==999,"large saved hive");
            try {hive.putDword("Readonly","Value",1);throw new AssertionError("Readonly write accepted");}catch(IOException expected){}
            try {hive.commit(directory.resolve("readonly.hive").toFile());throw new AssertionError("Readonly commit accepted");}catch(IOException expected){}
            check(!Files.exists(directory.resolve("readonly.hive")),"Readonly commit touched destination");
            try {hive.get("bad\\\\path","Value");throw new AssertionError("Empty path component accepted");}catch(IOException expected){}
        }
        RegistryHive closed=new RegistryHive(source.toFile(),true);closed.close();closed.close();
        try {closed.children("");throw new AssertionError("Closed handle used");}catch(IOException expected){}
        try(RegistryHive hive=new RegistryHive(source.toFile(),true)) {
            try {hive.putDword("New\\żółć","Value",1);throw new AssertionError("Unsafe Unicode lh hash accepted");}catch(IOException expected){}
            try {hive.putString("Options","Value","bad\ud800");throw new AssertionError("Unpaired surrogate accepted");}catch(IOException expected){}
        }
        Path invalid=directory.resolve("invalid.hive");Files.writeString(invalid,"not a registry hive");
        try {new RegistryHive(invalid.toFile(),true);throw new AssertionError("Invalid hive accepted");}catch(IOException expected){}
        check(Arrays.equals(before,Files.readAllBytes(original)),"Reference fixture changed");
        Files.delete(invalid);Files.delete(source);
        System.out.println("Registry hive DWORD, Unicode values, binary, replacement, growth, deletion, readonly and lifetime tests passed");
    }
}
