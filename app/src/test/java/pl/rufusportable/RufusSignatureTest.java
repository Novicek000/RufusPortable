package pl.rufusportable;

import java.io.IOException;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RufusSignatureTest {
    @Test public void verifiesOfficialSyslinuxModuleWithoutChangingInputs() throws Exception {
        byte[] content=Files.readAllBytes(Path.of("src/main/assets/syslinux/ldlinux_v6.c32"));
        byte[] signature=Files.readAllBytes(Path.of("src/main/assets/syslinux/ldlinux_v6.c32.sig"));
        byte[] copy=signature.clone(),original=content.clone();RufusSignature.verify(content,signature);RufusSignature.verify(content,signature);
        assertArrayEquals(copy,signature);assertArrayEquals(original,content);
        content[700]^=1;assertThrows(IOException.class,()->RufusSignature.verify(content,signature));
        for(int i=0;i<signature.length/2;i++){byte value=signature[i];signature[i]=signature[signature.length-1-i];signature[signature.length-1-i]=value;}
        assertThrows(IOException.class,()->RufusSignature.verify(original,signature));
        assertThrows(IOException.class,()->RufusSignature.verify(original,new byte[255]));
        assertThrows(IOException.class,()->RufusSignature.verify(new byte[0],copy));
    }
}
