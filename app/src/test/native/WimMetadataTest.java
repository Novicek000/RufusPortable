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
        System.out.println("WIM image count, Unicode, architecture and invalid-header tests passed");
    }
}
