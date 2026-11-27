package pl.rufusportable;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.robolectric.shadows.ShadowContentResolver;
import android.view.*;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
public class StartScreenTest {
    private View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView)root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for (int i=0;i<group.getChildCount();i++) {
                View v=find(group.getChildAt(i),text);
                if (v!=null) return v;
            }
        }
        return null;
    }
    @Test public void selectedNameSurvivesValidationAndWindowsIsoNeedsOnlyHeaders() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "pl.rufusportable.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        Uri uri=Uri.parse("content://test-image/document/42");
        ShadowContentResolver.registerProviderInternal("test-image",new ContentProvider() {
            public boolean onCreate() { return true; }
            public Cursor query(Uri u,String[] p,String s,String[] args,String order) {
                MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
                c.addRow(new Object[]{"Windows 11.iso",8L<<30}); return c;
            }
            public Uri insert(Uri u,ContentValues v) {throw new UnsupportedOperationException();}
            public int delete(Uri u,String s,String[] a) {throw new UnsupportedOperationException();}
            public int update(Uri u,ContentValues v,String s,String[] a) {throw new UnsupportedOperationException();}
            public String getType(Uri u) {return "application/octet-stream";}
        });
        try (var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            byte[] header=new byte[17*2048];
            Shadows.shadowOf(activity.getContentResolver()).registerInputStreamSupplier(uri,()->new ByteArrayInputStream(header));
            for (boolean valid:new boolean[]{false,true}) {
                if (valid) System.arraycopy(new byte[]{'C','D','0','0','1'},0,header,16*2048+1,5);
                activity.onActivityResult(1,-1,new Intent().setData(uri));
                View root=activity.getWindow().getDecorView();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while (!find(root,"Wybierz plik").isEnabled() && System.nanoTime()<deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
                }
                assertNotNull(find(root,"Windows 11.iso"));
                assertEquals(valid,find(root,"Opcje zapisu").isEnabled());
            }
        }
    }
    @Test public void badBlockOptionsRemainSelectedAfterActivityRecreation() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "pl.rufusportable.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        try (var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            View root=controller.get().getWindow().getDecorView();
            find(root,"Inne opcje rozruchu").performClick();
            androidx.appcompat.app.AlertDialog boot=(androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            boot.getListView().performItemClick(null,1,1);
            find(root,"Opcje zapisu").performClick();
            androidx.appcompat.app.AlertDialog options=(androidx.appcompat.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            View dialogRoot=options.getWindow().getDecorView();
            ((android.widget.CheckBox)find(dialogRoot,"Test uszkodzonych bloków przed zapisem")).performClick();
            TextView caption=(TextView)find(dialogRoot,"Przebiegi testu");
            android.widget.Spinner patterns=dialogRoot.findViewById(caption.getLabelFor());
            assertEquals(View.VISIBLE,((View)caption.getParent()).getVisibility());
            patterns.setSelection(4);
            ((android.widget.CheckBox)find(dialogRoot,"Wykrywaj fałszywą pojemność")).setChecked(false);
            options.getButton(-1).performClick();
            controller.recreate();
            find(controller.get().getWindow().getDecorView(),"Opcje zapisu").performClick();
            options=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            dialogRoot=options.getWindow().getDecorView();
            assertTrue(((android.widget.CheckBox)find(dialogRoot,"Test uszkodzonych bloków przed zapisem")).isChecked());
            assertFalse(((android.widget.CheckBox)find(dialogRoot,"Wykrywaj fałszywą pojemność")).isChecked());
            caption=(TextView)find(dialogRoot,"Przebiegi testu");
            assertEquals(4,((android.widget.Spinner)dialogRoot.findViewById(caption.getLabelFor())).getSelectedItemPosition());
        }
    }
    private void windowsProfile(MainActivity activity) throws Exception {
        byte[][] values={"Windows Setup".getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[0],
                "9".getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[0],
                "26100".getBytes(java.nio.charset.StandardCharsets.UTF_8),"en-US".getBytes(java.nio.charset.StandardCharsets.UTF_8)};
        Class<?> type=Class.forName("pl.rufusportable.MainActivity$WindowsProfile");
        var constructor=type.getDeclaredConstructor(java.util.List.class,int.class,WimMetadata.Image.class);
        constructor.setAccessible(true);
        Object profile=constructor.newInstance(java.util.Collections.emptyList(),2,new WimMetadata.Image(2,values,0));
        var field=MainActivity.class.getDeclaredField("windowsProfile");field.setAccessible(true);field.set(activity,profile);
        var timezone=MainActivity.class.getDeclaredField("windowsTimezone");timezone.setAccessible(true);timezone.set(activity,"UTC");
    }
    private androidx.appcompat.app.AlertDialog windowsDialog(MainActivity activity) throws Exception {
        var show=MainActivity.class.getDeclaredMethod("showWindowsOptions",Runnable.class);show.setAccessible(true);show.invoke(activity,(Object)null);
        return (androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
    }
    @Test public void windowsOptionsValidateAccountsAndPersistAcrossRecreation() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "pl.rufusportable.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            windowsProfile(controller.get());
            androidx.appcompat.app.AlertDialog dialog=windowsDialog(controller.get());
            View root=dialog.getWindow().getDecorView();
            ((android.widget.CheckBox)find(root,"Utwórz konto lokalne")).setChecked(true);
            TextView nameCaption=(TextView)find(root,"Nazwa konta lokalnego");
            android.widget.EditText account=root.findViewById(nameCaption.getLabelFor());
            account.setText("Administrator");dialog.getButton(-1).performClick();
            assertNotNull(account.getError());assertTrue(dialog.isShowing());
            account.setText("żółć/Jan");
            ((android.widget.CheckBox)find(root,"Nie wymagaj konta Microsoft")).setChecked(true);
            ((android.widget.CheckBox)find(root,"Ustaw język, region i strefę czasową")).setChecked(true);
            TextView regionCaption=(TextView)find(root,"Język i region (np. pl-PL)");
            ((android.widget.EditText)root.findViewById(regionCaption.getLabelFor())).setText("pl-PL");
            dialog.getButton(-1).performClick();assertFalse(dialog.isShowing());
            controller.recreate();windowsProfile(controller.get());dialog=windowsDialog(controller.get());
            root=dialog.getWindow().getDecorView();
            assertTrue(((android.widget.CheckBox)find(root,"Utwórz konto lokalne")).isChecked());
            assertTrue(((android.widget.CheckBox)find(root,"Nie wymagaj konta Microsoft")).isChecked());
            nameCaption=(TextView)find(root,"Nazwa konta lokalnego");
            assertEquals("żółć_Jan",((android.widget.EditText)root.findViewById(nameCaption.getLabelFor())).getText().toString());
            regionCaption=(TextView)find(root,"Język i region (np. pl-PL)");
            assertEquals("pl-PL",((android.widget.EditText)root.findViewById(regionCaption.getLabelFor())).getText().toString());
        }
    }
    @Test public void existingOemAnswerSkipsCustomizationControls() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "pl.rufusportable.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            Class<?> profileType=Class.forName("pl.rufusportable.MainActivity$WindowsProfile");
            var constructor=profileType.getDeclaredConstructor(java.util.List.class);constructor.setAccessible(true);
            var profile=MainActivity.class.getDeclaredField("windowsProfile");profile.setAccessible(true);
            profile.set(controller.get(),constructor.newInstance(java.util.Collections.singletonList(
                    new IsoTree.Entry("sources/$OEM$/$$/Panther/unattend.xml",false,0,10))));
            var dialog=windowsDialog(controller.get());
            assertEquals("Zamknij",dialog.getButton(-1).getText().toString());
            assertNull(find(dialog.getWindow().getDecorView(),"Utwórz konto lokalne"));
        }
    }
    @Test public void windowsTimezoneDataMapsAndroidIanaZones() throws Exception {
        WindowsTimeZones zones=new WindowsTimeZones(RuntimeEnvironment.getApplication().getAssets().open("windows/timezones.properties"));
        assertEquals("Central European Standard Time",zones.windowsZone("Europe/Warsaw"));
        assertEquals("Pacific Standard Time",zones.windowsZone("America/Los_Angeles"));
        assertEquals("UTC",zones.windowsZone(null));
        assertTrue(zones.windows.contains("UTC"));
    }
    @Test public void startsWithWriteDisabledAndOpensDocumentPicker() {
        // Android grants the app its own manifest-declared signature permission at install.
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "pl.rufusportable.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        try (var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            View root=activity.getWindow().getDecorView();
            assertFalse(find(root,"Zapisz obraz").isEnabled());
            find(root,"Wybierz plik").performClick();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,Shadows.shadowOf(activity).getNextStartedActivity().getAction());
        }
    }
}
