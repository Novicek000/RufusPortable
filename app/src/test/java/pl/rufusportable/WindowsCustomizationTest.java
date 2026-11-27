package pl.rufusportable;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WindowsCustomizationTest {
    private List<IsoTree.Entry> tree() {
        return new ArrayList<>(Arrays.asList(new IsoTree.Entry("SOURCES",true,0,0),
                new IsoTree.Entry("SOURCES/BOOT.WIM",false,4096,100),
                new IsoTree.Entry("SOURCES/APPRAISERRES.DLL",false,8192,300),
                new IsoTree.Entry("SETUP.EXE",false,12288,256)));
    }
    private byte[] pe(int machine) {
        byte[] bytes=new byte[256];bytes[0]='M';bytes[1]='Z';bytes[60]=64;
        bytes[64]='P';bytes[65]='E';bytes[68]=(byte)machine;bytes[69]=(byte)(machine>>>8);return bytes;
    }
    private IsoTree.Entry entry(WindowsCustomization plan,String path) {
        return plan.entries.stream().filter(e->e.path.equalsIgnoreCase(path)).findFirst().orElseThrow();
    }
    @Test public void oobeAnswerUsesOemPantherAndPreservesOriginalCaseAndSource() throws Exception {
        List<IsoTree.Entry> original=tree();byte[] answer={1,2,3};
        WindowsCustomization plan=new WindowsCustomization(original,WindowsUnattend.SET_USER,26100,answer,
                e->{throw new AssertionError("OOBE should not read setup.exe");},n->{throw new AssertionError();});
        IsoTree.Entry extra=entry(plan,"sources/$OEM$/$$/Panther/unattend.xml");
        assertEquals("SOURCES/$OEM$/$$/Panther/unattend.xml",extra.path);
        assertArrayEquals(answer,plan.open(e->{throw new AssertionError();},extra).readAllBytes());
        assertEquals(4,original.size());assertFalse(plan.answerInBootWim);
    }
    @Test public void bypassBacksUpAppraiserAndInstallsMatchingOriginalSetupWrapper() throws Exception {
        byte[] originalSetup=pe(0x8664),wrapper=pe(0x8664);wrapper[200]=99;
        WindowsCustomization plan=new WindowsCustomization(tree(),WindowsUnattend.BYPASS_REQUIREMENTS,26100,
                new byte[]{1},e->new ByteArrayInputStream(originalSetup),n->{assertEquals("setup_x64.exe",n);return wrapper;});
        assertTrue(plan.answerInBootWim);assertEquals("SOURCES/BOOT.WIM",plan.bootWimPath);
        assertEquals(8192,entry(plan,"sources/appraiserres.bak").offset);
        assertEquals(0,entry(plan,"sources/appraiserres.dll").length);
        assertEquals(12288,entry(plan,"setup.dll").offset);
        assertArrayEquals(wrapper,plan.open(e->{throw new AssertionError();},entry(plan,"setup.exe")).readAllBytes());
    }
    @Test public void arm64SelectsArmWrapperAndOlderBuildKeepsSetup() throws Exception {
        WindowsCustomization arm=new WindowsCustomization(tree(),1,26100,new byte[]{1},
                e->new ByteArrayInputStream(pe(0xaa64)),n->{assertEquals("setup_arm64.exe",n);return pe(0xaa64);});
        assertNotNull(entry(arm,"setup.dll"));
        WindowsCustomization old=new WindowsCustomization(tree(),1,22000,new byte[]{1},
                e->{throw new AssertionError();},n->{throw new AssertionError();});
        assertEquals(12288,entry(old,"setup.exe").offset);
    }
    @Test public void existingAnswerAndSetupBackupAreRejectedBeforeTargetWrite() {
        List<IsoTree.Entry> original=tree();
        original.add(new IsoTree.Entry("sources/$OEM$",true,0,0));
        original.add(new IsoTree.Entry("sources/$OEM$/$$",true,0,0));
        original.add(new IsoTree.Entry("sources/$OEM$/$$/Panther",true,0,0));
        original.add(new IsoTree.Entry("sources/$OEM$/$$/Panther/unattend.xml",false,16000,10));
        assertThrows(IOException.class,()->new WindowsCustomization(original,WindowsUnattend.SET_USER,26100,new byte[]{1},null,null));
        List<IsoTree.Entry> backup=tree();backup.add(new IsoTree.Entry("setup.dll",false,16000,10));
        assertThrows(IOException.class,()->new WindowsCustomization(backup,1,26100,new byte[]{1},
                e->new ByteArrayInputStream(pe(0x8664)),n->pe(0x8664)));
    }
}
