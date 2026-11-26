package pl.rufusportable;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.text.format.Formatter;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService diskWork = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private UsbManager usb;
    private UsbDevice target, pending;
    private ImageFile image;
    private long capacity;
    private boolean busy, checking, flashing;
    private volatile boolean cancel;
    private static final String PERMISSION = "pl.rufusportable.USB";
    private LinearLayout page;
    private MaterialButton choose, connect, write, update;
    private TextView imageName, imageMeta, deviceName, deviceMeta, status, release;
    private LinearProgressIndicator progress;
    private String installed, newTag;
    private File tempDir;
    private Future<?> preparation;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (PERMISSION.equals(intent.getAction()) && pending != null) {
                UsbDevice requested = pending;
                pending = null;
                if (usb.hasPermission(requested)) inspect(requested);
                else { setBusy(false); status.setText("Android nie przyznał dostępu do nośnika."); }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (target != null && !connected(target)) {
                    target = null;
                    showTarget();
                    if (!busy) status.setText("Odłączono wybrany nośnik.");
                }
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usb = (UsbManager)getSystemService(USB_SERVICE);
        try { installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { throw new IllegalStateException(e); }
        tempDir = new File(getCacheDir(), "images-" + UUID.randomUUID());
        tempDir.mkdirs();
        File[] stale = getCacheDir().listFiles(f -> f.getName().startsWith("images-") && f.isDirectory()
                && System.currentTimeMillis() - f.lastModified() > 86400000L);
        if (stale != null) for (File dir : stale) clear(dir);
        buildScreen();
        IntentFilter f = new IntentFilter(PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        checkUpdates();
    }

    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(getColor(color)); d.setCornerRadius(dp(radius)); return d;
    }
    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }
    private void pad(View view, int x, int y) { view.setPadding(dp(x),dp(y),dp(x),dp(y)); }
    private TextView label(LinearLayout box, String value, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(getColor(bold ? R.color.ink : R.color.muted));
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(t);
        return t;
    }
    private MaterialButton button(LinearLayout box, String title, Runnable action, boolean primary) {
        MaterialButton b = new MaterialButton(this, null, primary ?
                com.google.android.material.R.attr.materialButtonStyle : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(title); b.setAllCaps(false); b.setMinHeight(dp(54)); b.setCornerRadius(dp(26));
        b.setOnClickListener(v -> action.run()); box.addView(b); return b;
    }
    private LinearLayout card(int color, int radius) {
        LinearLayout box = column();
        box.setBackground(shape(color, radius)); pad(box,20,16);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin = dp(8);
        page.addView(box,p); return box;
    }
    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(getColor(R.color.paper));
        ViewCompat.setOnApplyWindowInsetsListener(scroll,(v,insets)-> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        page = column(); pad(page,18,18); page.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(page); setContentView(scroll);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        boolean light = (getResources().getConfiguration().uiMode & 48) != 32;
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(),scroll);
        bars.setAppearanceLightStatusBars(light); bars.setAppearanceLightNavigationBars(light);
        LinearLayout hero = card(R.color.container,32);
        ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.drive);
        hero.addView(icon,dp(56),dp(56));
        icon.setRotation(-16); icon.animate().rotation(0).setDuration(600).start();
        TextView brand = label(hero,"RufusPortable",34,true);
        ViewCompat.setAccessibilityHeading(brand,true);
        label(hero,"Zapisz obraz dysku na pendrivie przez USB OTG.",16,false);
        LinearLayout source = card(R.color.panel,24);
        label(source,"Obraz źródłowy",15,true);
        imageName = label(source,"Wybierz obraz dysku",22,true);
        imageMeta = label(source,"IMG, RAW lub hybrydowy ISO",15,false);
        choose = button(source,"Wybierz plik",this::pickFile,false);
        LinearLayout destination = card(R.color.panel,24);
        label(destination,"Nośnik docelowy",15,true);
        deviceName = label(destination,"Podłącz pendrive",22,true);
        deviceMeta = label(destination,"Użyj przejściówki lub przewodu OTG.",15,false);
        connect = button(destination,"Wybierz urządzenie USB",this::pickDisk,false);
        LinearLayout job = card(R.color.blue,24);
        status = label(job,"Wybierz obraz i nośnik, aby rozpocząć.",16,true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress = new LinearProgressIndicator(this);
        progress.setMax(100); job.addView(progress,-1,dp(8)); progress.setVisibility(View.GONE);
        write = button(job,"Zapisz obraz",this::writeAction,true);
        LinearLayout footer = card(R.color.paper,20);
        release = label(footer,"RufusPortable " + installed,14,false);
        update = button(footer,"Sprawdź aktualizacje",this::updateAction,false);
        refresh();
        for (int i=0; i<page.getChildCount(); i++) {
            View v=page.getChildAt(i); v.setAlpha(0); v.setTranslationY(dp(14));
            v.animate().alpha(1).translationY(0).setDuration(320).setStartDelay(45L*i).start();
        }
    }
    private void refresh() {
        choose.setEnabled(!busy); connect.setEnabled(!busy);
        write.setEnabled(flashing ? !cancel : !busy && image != null && target != null);
        write.setText(flashing ? "Przerwij operację" : "Zapisz obraz");
    }
    private void setBusy(boolean value) {
        busy=value; refresh();
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void ui(Runnable r) { runOnUiThread(()->{ if (!isDestroyed() && !isFinishing()) r.run(); }); }
    private void error(Exception e) {
        ui(()->{
            flashing=false; setBusy(false); progress.setVisibility(View.GONE);
            String message=e.getLocalizedMessage();
            status.setText(message == null ? "Operacja nie powiodła się." : message);
        });
    }
    private String size(long bytes) { return Formatter.formatFileSize(this,bytes); }
    private void pickFile() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i,1);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request!=1 || result!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if (image != null) image.file.delete();
        image=null; imageName.setText("Przygotowywanie obrazu…"); imageMeta.setText("");
        status.setText("Kopiowanie pliku do pamięci tymczasowej telefonu…"); setBusy(true);
        preparation=diskWork.submit(()->{
            try {
                ImageFile selected=ImageFile.prepare(this,uri,tempDir);
                ui(()->{
                    image=selected; imageName.setText(image.name); imageMeta.setText(size(image.file.length())+" · "+image.partition);
                    status.setText(target==null ? "Teraz wybierz nośnik USB." : "Obraz jest gotowy do zapisu."); setBusy(false);
                });
            } catch (Exception e) { ui(()->imageName.setText("Wybierz obraz dysku")); error(e); }
        });
    }
    private boolean connected(UsbDevice device) {
        UsbDevice live=usb.getDeviceList().get(device.getDeviceName());
        return live!=null && live.getDeviceId()==device.getDeviceId();
    }
    private String name(UsbDevice d) {
        String product=d.getProductName(); return product==null ? "Pamięć USB" : product;
    }
    private void pickDisk() {
        ArrayList<UsbDevice> devices=new ArrayList<>();
        for (UsbDevice d:usb.getDeviceList().values()) if (UsbDisk.storage(d)!=null) devices.add(d);
        MaterialAlertDialogBuilder dialog=new MaterialAlertDialogBuilder(this).setTitle("Wybierz nośnik USB");
        if (devices.isEmpty()) dialog.setMessage("Podłącz pamięć masową przez OTG. Obsługiwane są nośniki USB w trybie Bulk-Only.").setPositiveButton("Rozumiem",null);
        else {
            String[] labels=new String[devices.size()];
            for (int i=0;i<labels.length;i++) labels[i]=name(devices.get(i))+" · USB "+devices.get(i).getDeviceId();
            dialog.setItems(labels,(d,index)->request(devices.get(index))).setNegativeButton("Anuluj",null);
        }
        dialog.show();
    }
    private void request(UsbDevice device) {
        target=null; showTarget(); setBusy(true);
        if (usb.hasPermission(device)) inspect(device);
        else {
            pending=device;
            PendingIntent result=PendingIntent.getBroadcast(this,device.getDeviceId(),
                    new Intent(PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE);
            usb.requestPermission(device,result);
        }
    }
    private void inspect(UsbDevice device) {
        status.setText("Odczytywanie pojemności nośnika…");
        diskWork.execute(()->{
            try (UsbDisk disk=new UsbDisk(usb,device)) {
                long bytes=disk.capacity;
                ui(()->{
                    target=connected(device)?device:null; capacity=bytes; showTarget(); setBusy(false);
                    status.setText(target==null ? "Odłączono nośnik." : image==null ? "Teraz wybierz obraz dysku." : "Możesz rozpocząć zapis.");
                });
            } catch (Exception e) { error(e); }
        });
    }
    private void showTarget() {
        deviceName.setText(target==null?"Podłącz pendrive":name(target));
        deviceMeta.setText(target==null?"Użyj przejściówki lub przewodu OTG.":size(capacity)+" · USB "+target.getDeviceId());
        refresh();
    }
    private void writeAction() {
        if (flashing) {
            new MaterialAlertDialogBuilder(this).setTitle("Przerwać operację?")
                    .setMessage("Na nośniku może pozostać niepełny obraz.").setNegativeButton("Kontynuuj",null)
                    .setPositiveButton("Przerwij",(d,w)->{cancel=true; refresh();}).show(); return;
        }
        if (image==null || target==null) return;
        if (image.file.length()>capacity) { status.setText("Obraz jest większy od nośnika. Wybierz większy pendrive."); return; }
        UsbDevice selected=target;
        new MaterialAlertDialogBuilder(this).setTitle("Usunąć dane i zapisać obraz?")
                .setMessage(image.name+" ("+size(image.file.length())+")\n↓\n"+name(selected)+" · USB "+selected.getDeviceId()+" ("+size(capacity)+")\n\nZapis zastąpi dane na tym nośniku. Pozostaw aplikację otwartą do zakończenia weryfikacji.")
                .setNegativeButton("Anuluj",null).setPositiveButton("Usuń dane i zapisz",(d,w)->startWrite(selected)).show();
    }
    private void startWrite(UsbDevice selected) {
        if (busy || target!=selected || !connected(selected)) {status.setText("Wybierz ponownie podłączony nośnik."); return;}
        flashing=true; cancel=false; setBusy(true); progress.setVisibility(View.VISIBLE); progress.setProgress(0);
        status.setText("Rozpoczynanie zapisu…");
        File input=image.file;
        long expectedCapacity=capacity;
        diskWork.execute(()->{
            try (UsbDisk disk=new UsbDisk(usb,selected)) {
                if (disk.capacity!=expectedCapacity) throw new IOException("Zmieniła się pojemność urządzenia. Wybierz nośnik ponownie.");
                final int[] last={-1};
                disk.flash(input,(verify,bytes)->{
                    if (cancel || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano operację. Zapisz obraz ponownie przed użyciem nośnika.");
                    int percent=(int)(100.0*bytes/input.length());
                    int step=(verify?100:0)+percent;
                    if (step!=last[0]) {
                        last[0]=step; ui(()->{
                            if (percent==0 || percent%10==0) status.setText((verify?"Weryfikacja":"Zapisywanie")+" · "+percent+"%");
                            progress.setProgressCompat(percent,percent>0);
                        });
                    }
                });
                ui(()->{
                    progress.setProgressCompat(100,true); flashing=false; setBusy(false);
                    status.setText("Gotowe. Zapis zweryfikowany przez SHA-256. Możesz odłączyć nośnik.");
                });
            } catch (Exception e) { error(e); }
        });
    }
    private void updateAction() {
        if (newTag!=null) startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(Updates.RELEASES+"/tag/"+newTag)));
        else checkUpdates();
    }
    private void checkUpdates() {
        if (checking) return;
        checking=true; update.setEnabled(false); release.setText("Sprawdzanie wydań na GitHubie…");
        network.execute(()->{
            try {
                String tag=Updates.latest();
                boolean newer=tag!=null && Updates.newer(tag,installed);
                ui(()->{
                    newTag=newer?tag:null;
                    release.setText(newer?"Dostępna wersja "+tag:tag==null?"Brak opublikowanych wydań.":"Masz aktualną wersję "+installed);
                    update.setText(newer?"Otwórz wydanie "+tag:"Sprawdź aktualizacje");
                });
            } catch(Exception e) { ui(()->release.setText("Nie udało się sprawdzić wydań. Sprawdź połączenie z GitHubem.")); }
            finally { ui(()->{checking=false; update.setEnabled(true);}); }
        });
    }
    @Override public void onBackPressed() {
        if (flashing) writeAction(); else super.onBackPressed();
    }
    private static void clear(File dir) {
        File[] files=dir.listFiles(); if (files!=null) for (File f:files) f.delete();
        dir.delete();
    }
    @Override protected void onDestroy() {
        cancel=true;
        if (preparation!=null) preparation.cancel(true);
        unregisterReceiver(receiver); network.shutdownNow();
        diskWork.execute(()->clear(tempDir)); diskWork.shutdown();
        super.onDestroy();
    }
}
