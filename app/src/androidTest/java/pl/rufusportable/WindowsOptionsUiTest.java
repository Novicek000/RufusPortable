package pl.rufusportable;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WindowsOptionsUiTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    interface Check {boolean ready();}
    private void await(Check check) throws Exception {
        long end=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<end) {
            if(check.ready())return;
            Thread.sleep(25);
        }
        fail("Android UI did not reach the expected state");
    }
    private View find(View view,String text) {
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),text);if(found!=null)return found;}
        }
        return null;
    }
    private AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
        if(root==null)return null;
        for(AccessibilityNodeInfo item:root.findAccessibilityNodeInfosByText(text)) {
            if(item.getText()!=null && text.contentEquals(item.getText()))return item;
        }
        return null;
    }
    private void click(String text) throws Exception {
        await(()->node(text)!=null);
        AccessibilityNodeInfo target=node(text);
        target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        assertTrue("Cannot activate "+text,target.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
    @Test public void opensRealIsoReadsNativeWimAndAppliesAccessibleWindowsOptions() throws Exception {
        Context context=instrumentation.getTargetContext();
        File source=File.createTempFile("windows-source-", ".iso",context.getCacheDir());
        try(InputStream input=instrumentation.getContext().getAssets().open("windows-test.iso");OutputStream output=new FileOutputStream(source)) {
            byte[] bytes=new byte[16384];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);
        }
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->activity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(source))));
            await(()->{
                AtomicBoolean ready=new AtomicBoolean();
                instrumentation.runOnMainSync(()->{
                    View root=activity.getWindow().getDecorView();
                    View button=find(root,"Instalacja Windows");
                    ready.set(button!=null && button.isEnabled() && button.getVisibility()==View.VISIBLE && find(root,source.getName())!=null);
                });
                return ready.get();
            });
            instrumentation.runOnMainSync(()->find(activity.getWindow().getDecorView(),"Instalacja Windows").performClick());
            await(()->node("Pomiń wymagania TPM, Secure Boot i RAM")!=null);
            assertTrue(node("Pomiń wymagania TPM, Secure Boot i RAM").isChecked());
            click("Utwórz konto lokalne");
            await(()->node("Nazwa konta lokalnego")!=null && node("Nazwa konta lokalnego").getLabelFor()!=null);
            AccessibilityNodeInfo account=node("Nazwa konta lokalnego").getLabelFor();
            account.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Test User");
            assertTrue(account.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value));
            click("Pomiń pytania o zbieranie danych");
            Thread.sleep(400);
            Bitmap screenshot=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(screenshot);
            try(OutputStream out=new FileOutputStream(new File(context.getCacheDir(),"windows-options-test.png"))) {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,out));
            } finally {screenshot.recycle();}
            click("Zastosuj");
            await(()->node("Utwórz konto lokalne")==null);
            instrumentation.runOnMainSync(()->find(activity.getWindow().getDecorView(),"Instalacja Windows").performClick());
            await(()->node("Utwórz konto lokalne")!=null);
            assertTrue(node("Utwórz konto lokalne").isChecked());
            assertTrue(node("Pomiń pytania o zbieranie danych").isChecked());
            assertEquals("Test User",node("Nazwa konta lokalnego").getLabelFor().getText().toString());
            click("Ustaw język, region i strefę czasową");
            String silent="Instalacja bezobsługowa — wymaż pierwszy dysk komputera";
            click(silent);
            AccessibilityNodeInfo edition=node("Edycja Windows").getLabelFor();assertNotNull(edition);
            edition.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            assertTrue(edition.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            click("Windows Test Education · ARM64 · indeks 2");
            click("Zastosuj");
            await(()->node("Akceptuję i stosuję")!=null);
            assertFalse(node("Akceptuję i stosuję").isEnabled());
            click("Odłączę wszystkie dyski oprócz tego, na którym chcę zainstalować Windows.");
            click("Nie pozostawię tego nośnika w komputerze, którego nie zamierzam wymazać.");
            assertFalse(node("Akceptuję i stosuję").isEnabled());
            click("Przyjmuję pełną odpowiedzialność za utratę danych wynikającą z użycia tej opcji.");
            await(()->node("Akceptuję i stosuję").isEnabled());click("Akceptuję i stosuję");
            await(()->node(silent)==null);
            instrumentation.runOnMainSync(()->find(activity.getWindow().getDecorView(),"Instalacja Windows").performClick());
            await(()->node(silent)!=null);assertTrue(node(silent).isChecked());
            assertNotNull(node("Windows Test Education · ARM64 · indeks 2"));

        } finally {
            instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();assertTrue(source.delete());
        }
    }
    @Test public void selectsWindowsToGoReadsInstallWimAndKeepsEditionWithoutDiskWipeMode() throws Exception {
        Context context=instrumentation.getTargetContext();File source=File.createTempFile("wintogo-source-", ".iso",context.getCacheDir());
        try(InputStream input=instrumentation.getContext().getAssets().open("windows-test.iso");OutputStream output=new FileOutputStream(source)) {
            byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
        }
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->activity.onActivityResult(1,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(source))));
            await(()->{AtomicBoolean ready=new AtomicBoolean();instrumentation.runOnMainSync(()->{
                View view=find(activity.getWindow().getDecorView(),"Opcje zapisu");ready.set(view!=null && view.isEnabled());});return ready.get();});
            instrumentation.runOnMainSync(()->find(activity.getWindow().getDecorView(),"Opcje zapisu").performClick());
            await(()->node("Tryb Windows")!=null);
            AccessibilityNodeInfo mode=node("Tryb Windows").getLabelFor();assertNotNull(mode);
            assertTrue(mode.performAction(AccessibilityNodeInfo.ACTION_CLICK));click("Windows To Go — system uruchamiany z USB");
            await(()->node("Rozruch Windows To Go")!=null);
            AccessibilityNodeInfo firmware=node("Rozruch Windows To Go").getLabelFor();assertNotNull(firmware);
            firmware.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            assertTrue(firmware.performAction(AccessibilityNodeInfo.ACTION_CLICK));click("Tylko UEFI");click("Zastosuj");
            await(()->node("Tryb Windows")==null);
            instrumentation.runOnMainSync(()->find(activity.getWindow().getDecorView(),"Instalacja Windows").performClick());
            await(()->node("Pozostaw wewnętrzne dyski komputera offline")!=null);
            assertTrue(node("Pozostaw wewnętrzne dyski komputera offline").isChecked());
            assertNull(node("Instalacja bezobsługowa — wymaż pierwszy dysk komputera"));
            AccessibilityNodeInfo edition=node("Edycja Windows").getLabelFor();assertNotNull(edition);assertTrue(edition.isEnabled());
            edition.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            assertTrue(edition.performAction(AccessibilityNodeInfo.ACTION_CLICK));click("Windows Test Education · ARM64 · indeks 2");
            click("Zastosuj");await(()->node("Pozostaw wewnętrzne dyski komputera offline")==null);
            instrumentation.runOnMainSync(()->find(activity.getWindow().getDecorView(),"Instalacja Windows").performClick());
            await(()->node("Windows Test Education · ARM64 · indeks 2")!=null);
            assertTrue(node("Pozostaw wewnętrzne dyski komputera offline").isChecked());
            click("Anuluj");
            instrumentation.runOnMainSync(()->find(activity.getWindow().getDecorView(),"Opcje zapisu").performClick());
            await(()->node("Tylko UEFI")!=null);click("Anuluj");
        } finally {instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();assertTrue(source.delete());}
    }
}
