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
