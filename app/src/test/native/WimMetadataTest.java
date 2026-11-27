package pl.rufusportable;

import java.io.*;
import java.nio.file.*;

public final class WimMetadataTest {
    public static void main(String[] args) throws Exception {
        var images=WimMetadata.read(args[0]);
        if(images.size()!=2)throw new AssertionError("image count");
        var first=images.get(0);
        if(first.index!=1 || !first.name.equals("Windows Test żółć") ||
                !first.architecture.equals("9") || !first.edition.equals("Professional") ||
                !first.build.equals("26100") || !first.platform().equals("x64"))
            throw new AssertionError("first image metadata");
        var second=images.get(1);
        if(second.index!=2 || !second.name.equals("Windows Test 😀") ||
                !second.platform().equals("ARM64") || !second.description.equals("Second test image"))
            throw new AssertionError("UTF-8 metadata");
        Path invalid=Files.createTempFile(Path.of(args[0]).getParent(),"invalid-wim",".bin");
        try {
            Files.writeString(invalid,"invalid WIM header");
            try {
                WimMetadata.read(invalid.toString());
                throw new AssertionError("invalid WIM accepted");
            } catch(IOException expected) {}
        } finally { Files.delete(invalid); }
        Path source=Path.of(args[0]).getParent().resolve("żółć.xml");
        String text="<?xml version=\"1.0\" encoding=\"UTF-8\"?><test>żółć 😀</test>";
        Files.writeString(source,text);
        try {
            WimMetadata.addFile(args[0],2,source.toString(),"Autounattend.xml");
            Path extracted=Files.createTempDirectory(source.getParent(),"extract-wim-");
            try {
                WimMetadata.extractFile(args[0],2,"Autounattend.xml",extracted.toString());
                if(!text.equals(Files.readString(extracted.resolve("Autounattend.xml"))))
                    throw new AssertionError("WIM update/extract mismatch");
                Files.delete(extracted.resolve("Autounattend.xml"));
                byte[] before=Files.readAllBytes(Path.of(args[0]));
                try {WimMetadata.addFile(args[0],3,source.toString(),"Autounattend.xml");
                    throw new AssertionError("invalid image update succeeded");}
                catch(IOException expected) {}
                if(!java.util.Arrays.equals(before,Files.readAllBytes(Path.of(args[0]))))
                    throw new AssertionError("failed update changed WIM");
                if(WimMetadata.read(args[0]).size()!=2)throw new AssertionError("update changed image count");
                WimMetadata.verify(args[0]);
                byte[] valid=Files.readAllBytes(Path.of(args[0]));
                byte[] payload="A small original WIM test fixture.".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                byte[] damaged=valid.clone();int found=-1;
                search:for(int i=208;i<=damaged.length-payload.length;i++) {
                    for(int j=0;j<payload.length;j++)if(damaged[i+j]!=payload[j])continue search;
                    found=i;break;
                }
                if(found<0)throw new AssertionError("fixture payload not found");
                damaged[found]^=1;Files.write(Path.of(args[0]),damaged);
                try {WimMetadata.verify(args[0]);throw new AssertionError("WIM payload corruption missed");}
                catch(IOException expected){}
                finally {Files.write(Path.of(args[0]),valid);}
            } finally {Files.delete(extracted);}
        } finally {Files.delete(source);}
        System.out.println("WIM metadata, Unicode, update/extract, invalid image and invalid-header tests passed");
    }
}
