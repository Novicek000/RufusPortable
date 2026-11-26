package pl.rufusportable;

import android.content.Intent;
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
