package pl.rufusportable;

import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WindowsInstallerPlanTest {
    private List<IsoTree.Entry> tree(long wimLength) {
        return List.of(
                new IsoTree.Entry("sources",true,0,0),
                new IsoTree.Entry("sources/boot.wim",false,100,600000000),
                new IsoTree.Entry("bootmgr",false,200,800000),
                new IsoTree.Entry("sources/install.wim",false,300,wimLength));
    }
    @Test public void plansWimLargerThanFourGiBWithoutReadingPayload() throws Exception {
        WindowsInstaller plan=new WindowsInstaller(tree(7333902541L),32_000_000_000L,4096);
        assertEquals(7934702541L,plan.bytes);
    }
    @Test public void rejectsTooSmallTargetAndMissingParentsBeforeFormatting() {
        assertThrows(IOException.class,()->new WindowsInstaller(tree(7333902541L),7_000_000_000L,4096));
        assertThrows(IOException.class,()->new WindowsInstaller(tree(1).subList(1,4),1L<<30,4096));
    }
    @Test public void rejectsTraversalAndCaseCollisionsBeforeFormatting() {
        ArrayList<IsoTree.Entry> unsafe=new ArrayList<>(tree(1));
        unsafe.add(new IsoTree.Entry("../evil",false,0,1));
        assertThrows(IOException.class,()->new WindowsInstaller(unsafe,1L<<30,4096));
        unsafe.remove(unsafe.size()-1);
        unsafe.add(new IsoTree.Entry("BOOTMGR",false,0,1));
        assertThrows(IOException.class,()->new WindowsInstaller(unsafe,1L<<30,4096));
    }
}
