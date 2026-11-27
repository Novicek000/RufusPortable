package pl.rufusportable;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WindowsBootFilesTest {
    private static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("windows-ca2023-");
        Fixture() throws IOException {
            Files.createDirectories(root.resolve("EFI_EX"));Files.createDirectories(root.resolve("Fonts_EX/sub"));
            Files.write(root.resolve("EFI_EX/bootmgfw_EX.efi"),new byte[]{1,2,3});
            Files.write(root.resolve("Fonts_EX/font_EX.ttf"),new byte[]{4});
            Files.write(root.resolve("Fonts_EX/sub/other.ttf"),new byte[]{5});
        }
        public void close() throws IOException {
            try(var paths=Files.walk(root)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}
        }
    }
    private List<IsoTree.Entry> entries() {
        return Arrays.asList(new IsoTree.Entry("EFI",true,0,0),new IsoTree.Entry("EFI/Boot",true,0,0),
                new IsoTree.Entry("EFI/Boot/bootaa64.efi",false,9000,20),
                new IsoTree.Entry("sources",true,0,0),new IsoTree.Entry("sources/boot.wim",false,1000,100));
    }
    @Test public void mapsFontsAndFirstMatchingBootloaderAndHandlesOptionalBootManager() throws Exception {
        try(Fixture fixture=new Fixture()) {
            var mapped=WindowsBootFiles.plan(entries(),fixture.root.toFile());
            assertEquals(3,mapped.size());
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(mapped.get("EFI/Boot/bootaa64.efi").toPath()));
            assertTrue(mapped.containsKey("efi/microsoft/boot/fonts/font.ttf"));
            assertTrue(mapped.containsKey("efi/microsoft/boot/fonts/sub/other.ttf"));
            Files.write(fixture.root.resolve("EFI_EX/bootmgr_EX.efi"),new byte[]{6});
            mapped=WindowsBootFiles.plan(entries(),fixture.root.toFile());assertTrue(mapped.containsKey("bootmgr.efi"));
            var plan=new WindowsCustomization(entries(),WindowsUnattend.USE_MS2023_BOOTLOADERS,26300,new byte[]{9},
                    e->{throw new AssertionError();},n->{throw new AssertionError();},mapped);
            assertTrue(plan.compactBootWim);assertFalse(plan.answerInBootWim);
            IsoTree.Entry boot=plan.entries.stream().filter(e->e.path.equals("EFI/Boot/bootaa64.efi")).findFirst().orElseThrow();
            assertEquals(3,boot.length);
            try(InputStream input=plan.open(e->{throw new AssertionError();},boot)){assertArrayEquals(new byte[]{1,2,3},input.readAllBytes());}
            Files.write(mapped.get(boot.path).toPath(),new byte[2]);
            assertThrows(IOException.class,()->plan.open(null,boot));
        }
    }
    @Test public void rejectsMissingResourcesAndAmbiguousNamesBeforeWrite() throws Exception {
        try(Fixture fixture=new Fixture()) {
            Files.delete(fixture.root.resolve("EFI_EX/bootmgfw_EX.efi"));
            assertThrows(IOException.class,()->WindowsBootFiles.plan(entries(),fixture.root.toFile()));
        }
        try(Fixture fixture=new Fixture()) {
            Files.write(fixture.root.resolve("Fonts_EX/font.ttf"),new byte[]{7});
            assertThrows(IOException.class,()->WindowsBootFiles.plan(entries(),fixture.root.toFile()));
        }
        try(Fixture fixture=new Fixture()) {
            Files.createSymbolicLink(fixture.root.resolve("Fonts_EX/link.ttf"),fixture.root.resolve("Fonts_EX/font_EX.ttf"));
            assertThrows(IOException.class,()->WindowsBootFiles.plan(entries(),fixture.root.toFile()));
        }
        assertThrows(IOException.class,()->new WindowsCustomization(entries(),WindowsUnattend.USE_MS2023_BOOTLOADERS,
                26300,new byte[]{1},null,null));
    }
    @Test public void choosesOriginalRufusArchitectureOrderForMultiArchitectureMedia() throws Exception {
        try(Fixture fixture=new Fixture()) {
            var entries=new ArrayList<>(entries());entries.add(new IsoTree.Entry("EFI/Boot/bootx64.efi",false,8000,30));
            var mapped=WindowsBootFiles.plan(entries,fixture.root.toFile());
            assertTrue(mapped.containsKey("EFI/Boot/bootx64.efi"));assertFalse(mapped.containsKey("EFI/Boot/bootaa64.efi"));
        }
    }
}
