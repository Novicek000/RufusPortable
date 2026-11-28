package pl.rufusportable;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SyslinuxOptionsUiTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return null;
        for(AccessibilityNodeInfo item:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(item.getText()))return item;
        return null;
    }
    private AccessibilityNodeInfo await(String text) throws Exception {
        long end=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo found=node(text);if(found!=null)return found;Thread.sleep(25);}
        throw new AssertionError("Missing UI element: "+text);
    }
    private void click(String text) throws Exception {
        AccessibilityNodeInfo target=await(text);target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        for(int i=0;i<6 && target!=null;i++,target=target.getParent())
            if(target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;
        fail("Cannot click "+text);
    }
    private View find(View view,String text) {
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;
        }
        return null;
    }
    private void button(MainActivity activity,String text) {
        instrumentation.runOnMainSync(()->{View found=find(activity.getWindow().getDecorView(),text);assertNotNull(found);assertTrue(found.performClick());});
    }
    private void choose(String caption,String value) throws Exception {
        AccessibilityNodeInfo spinner=await(caption).getLabelFor();assertNotNull(spinner);
        spinner.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        assertTrue(spinner.performAction(AccessibilityNodeInfo.ACTION_CLICK));click(value);
    }
    @Test public void selectsBothSyslinuxVersionsAndRetainsFatOptionsAcrossActivityRecreation() throws Exception {
        Context context=instrumentation.getTargetContext();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Instrumentation.ActivityMonitor monitor=null;
        try {
            button(activity,"Inne opcje rozruchu");click("Syslinux 6.04 · BIOS");await("Syslinux 6.04");await("MBR · FAT32 · BIOS");
            button(activity,"Opcje zapisu");choose("System plików","FAT16");choose("Klaster FAT","2 KiB");
            click("Szybkie formatowanie FAT");click("Zastosuj");await("MBR · FAT16 · BIOS");
            button(activity,"Inne opcje rozruchu");click("Syslinux 4.07 · BIOS");await("Syslinux 4.07");
            monitor=instrumentation.addMonitor(MainActivity.class.getName(),null,false);
            MainActivity previous=activity;instrumentation.runOnMainSync(previous::recreate);
            Activity replacement=instrumentation.waitForMonitorWithTimeout(monitor,30000);assertTrue(replacement instanceof MainActivity);activity=(MainActivity)replacement;
            await("Syslinux 4.07");await("MBR · FAT16 · BIOS");button(activity,"Opcje zapisu");
            await("FAT16");await("2 KiB");assertFalse(await("Szybkie formatowanie FAT").isChecked());click("Anuluj");
            button(activity,"Inne opcje rozruchu");click("FreeDOS 1.4 · BIOS");await("FreeDOS 1.4");await("MBR · FAT32 · BIOS");
            button(activity,"Opcje zapisu");await("FreeDOS 1.4");assertNull(node("System plików"));click("Anuluj");
        } finally {
            if(monitor!=null)instrumentation.removeMonitor(monitor);
            MainActivity last=activity;instrumentation.runOnMainSync(last::finish);instrumentation.waitForIdleSync();
        }
    }
}
