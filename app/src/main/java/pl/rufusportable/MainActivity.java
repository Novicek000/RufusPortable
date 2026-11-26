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
    private MaterialButton settings, hash, other;
    private boolean gpt, verifyWrite=true, useIso, testMedia, dos;
    private int ntfsClusterSize=4096;
    private boolean quickFormat=true;
    private String volumeLabel="RUFUS";
    private Uri pickedUri;
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
        File[] stale = getCacheDir().listFiles(f -> f.getName().startsWith("images-") && f.isDirectory()
                && System.currentTimeMillis() - f.lastModified() > 86400000L);
        if (stale != null) for (File dir : stale) clear(dir);
        buildScreen();
        IntentFilter f = new IntentFilter(PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        if (state!=null) {
            gpt=state.getBoolean("gpt"); verifyWrite=state.getBoolean("verify",true);
            ntfsClusterSize=state.getInt("ntfsCluster",4096); quickFormat=state.getBoolean("quick",true); volumeLabel=state.getString("label","RUFUS");
            String saved=state.getString("source");
            testMedia=state.getBoolean("testMedia");
            if (saved!=null) select(Uri.parse(saved),state.getBoolean("iso"));
            else if (state.getBoolean("builtin")) builtin(state.getBoolean("dos"));
        }
        checkUpdates();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (pickedUri!=null) state.putString("source",pickedUri.toString());
        state.putBoolean("builtin",image!=null && image.uri==null); state.putBoolean("dos",dos); state.putBoolean("testMedia",testMedia);
        state.putBoolean("iso",useIso); state.putBoolean("gpt",gpt); state.putBoolean("verify",verifyWrite);
        state.putInt("ntfsCluster",ntfsClusterSize); state.putBoolean("quick",quickFormat); state.putString("label",volumeLabel);
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
        imageMeta = label(source,"Windows / Linux ISO, IMG, RAW, BIN lub DD",15,false);
        choose = button(source,"Wybierz plik",this::pickFile,false);
        other=button(source,"Inne opcje rozruchu",()->new MaterialAlertDialogBuilder(this).setTitle("Opcje rozruchu")
                .setItems(new String[]{"FreeDOS 1.4 · BIOS","Formatuj jako FAT32","Pobierz Windows 11 od Microsoftu"},(d,i)->{
                    if (i<2) builtin(i==0);
                    else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.microsoft.com/pl-pl/software-download/windows11")));
                }).setNegativeButton("Anuluj",null).show(),false);
        LinearLayout destination = card(R.color.panel,24);
        label(destination,"Nośnik docelowy",15,true);
        deviceName = label(destination,"Podłącz pendrive",22,true);
        deviceMeta = label(destination,"Użyj przejściówki lub przewodu OTG.",15,false);
        connect = button(destination,"Wybierz urządzenie USB",this::pickDisk,false);
        settings = button(destination,"Opcje zapisu",this::options,false);
        hash = button(source,"Oblicz sumę SHA-256",this::hashImage,false);
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
        settings.setEnabled(!busy && image!=null);
        other.setEnabled(!busy);
        hash.setVisibility(image==null || image.uri==null?View.GONE:View.VISIBLE); hash.setEnabled(!busy);
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
        select(data.getData(),null);
    }
    private void select(Uri uri,Boolean restoreMode) {
        pickedUri=uri;
        image=null; imageName.setText(uri.getLastPathSegment()); imageMeta.setText("Odczytywanie nagłówka…");
        status.setText("Sprawdzanie obrazu…"); setBusy(true);
        preparation=diskWork.submit(()->{
            try {
                ImageFile selected=ImageFile.describe(this,uri);
                ui(()->imageName.setText(selected.name));
                selected.inspect(this);
                ui(()->{
                    image=selected;
                    useIso=selected.iso && (!selected.hybrid || Boolean.TRUE.equals(restoreMode));
                    showImage();
                    status.setText(target==null ? "Teraz wybierz nośnik USB." : "Obraz jest gotowy do zapisu."); setBusy(false);
                });
            } catch (Exception e) { ui(()->imageMeta.setText("Nie można przygotować tego obrazu.")); error(e); }
        });
    }
    private void builtin(boolean boot) {
        pickedUri=null; dos=boot; useIso=false; image=ImageFile.volume(boot);
        showImage(); status.setText(target==null?"Wybierz nośnik USB.":"Sprawdź opcje i rozpocznij zapis."); refresh();
    }
    private void showImage() {
        imageName.setText(image.name);
        if (image.uri==null) { imageMeta.setText("MBR · FAT32 · "+(dos?"BIOS":"formatowanie")); return; }
        imageMeta.setText((image.length<0?"Rozmiar nieznany":size(image.length))+" · "+(useIso?"ISO · tryb plikowy · "+(gpt?"GPT":"MBR")+" · NTFS":image.partition+" · DD"));
    }
    private Spinner choices(LinearLayout box,String title,String[] items,int selected) {
        TextView text=label(box,title,16,true);
        Spinner spinner=new Spinner(this); spinner.setId(View.generateViewId()); text.setLabelFor(spinner.getId());
        ArrayAdapter<String> values=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,items);
        values.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(values);
        spinner.setSelection(selected); spinner.setMinimumHeight(dp(48)); box.addView(spinner);
        return spinner;
    }
    private void options() {
        LinearLayout box=column(); pad(box,20,8);
        boolean switchable=image.iso && image.hybrid;
        Spinner mode=choices(box,"Metoda zapisu",switchable?new String[]{"Sektorowa (DD)","ISO · tryb plikowy"}:
                new String[]{image.uri==null?image.name:useIso?"ISO · tryb plikowy":"Sektorowa (DD)"},switchable && useIso?1:0);
        LinearLayout isoBox=column(); box.addView(isoBox);
        Spinner scheme=choices(isoBox,"Schemat partycji",new String[]{"MBR","GPT"},gpt?1:0);
        int[] clusterSizes={512,1024,2048,4096,8192,16384,32768,65536,131072,262144,524288,1048576,2097152};
        int clusterIndex=3;
        for(int i=0;i<clusterSizes.length;i++) if(clusterSizes[i]==ntfsClusterSize) clusterIndex=i;
        Spinner clusters=choices(isoBox,"Klaster NTFS",new String[]{"512 B","1 KiB","2 KiB","4 KiB","8 KiB","16 KiB","32 KiB","64 KiB","128 KiB","256 KiB","512 KiB","1 MiB","2 MiB"},clusterIndex);
        CheckBox quick=new CheckBox(this); quick.setText("Szybkie formatowanie"); quick.setChecked(quickFormat); isoBox.addView(quick);
        LinearLayout volumeBox=column(); box.addView(volumeBox);
        volumeBox.setVisibility(useIso || image.uri==null?View.VISIBLE:View.GONE);
        TextView caption=label(volumeBox,"Etykieta woluminu",16,true);
        EditText volume=new EditText(this); volume.setId(View.generateViewId()); caption.setLabelFor(volume.getId());
        volume.setSingleLine(); volume.setText(volumeLabel); volumeBox.addView(volume);
        isoBox.setVisibility(useIso?View.VISIBLE:View.GONE);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent,View v,int i,long id) {
                boolean active=switchable?i==1:useIso;
                isoBox.setVisibility(active?View.VISIBLE:View.GONE); volumeBox.setVisibility(active || image.uri==null?View.VISIBLE:View.GONE);
            }
        });
        CheckBox verify=new CheckBox(this); verify.setText("Sprawdź zapis przez SHA-256"); verify.setChecked(verifyWrite); box.addView(verify);
        CheckBox surface=new CheckBox(this); surface.setText("Test bloków przed zapisem (pełny zapis i odczyt nośnika)");
        surface.setChecked(testMedia); box.addView(surface);
        ScrollView scroll=new ScrollView(this); scroll.addView(box);
        var dialog=new MaterialAlertDialogBuilder(this).setTitle("Opcje zapisu").setView(scroll)
                .setNegativeButton("Anuluj",null).setPositiveButton("Zastosuj",null).show();
        dialog.getButton(-1).setOnClickListener(v->{
            boolean iso=switchable?mode.getSelectedItemPosition()==1:useIso;
            String label=volume.getText().toString().trim();
            if (iso && !WindowsInstaller.validLabel(label)) { volume.setError("Etykieta NTFS może mieć do 32 znaków."); return; }
            if (image.uri==null && !label.matches("[a-zA-Z0-9 _-]{1,11}")) {volume.setError("Wpisz do 11 liter ASCII, cyfr, spacji, - lub _."); return;}
            useIso=iso; if (iso || image.uri==null) volumeLabel=label; testMedia=surface.isChecked();
            gpt=scheme.getSelectedItemPosition()==1; ntfsClusterSize=clusterSizes[clusters.getSelectedItemPosition()]; quickFormat=quick.isChecked();
            verifyWrite=verify.isChecked(); showImage(); dialog.dismiss();
        });
    }
    private void hashImage() {
        ImageFile selected=image; setBusy(true); status.setText("Obliczanie SHA-256…");
        preparation=diskWork.submit(()->{
            try (InputStream in=selected.open(this)) {
                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                byte[] data=new byte[131072]; int n;
                while ((n=in.read(data))!=-1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    digest.update(data,0,n);
                }
                StringBuilder value=new StringBuilder();
                for (byte v:digest.digest()) value.append(String.format(Locale.ROOT,"%02x",v&255));
                ui(()->{setBusy(false); status.setText("SHA-256 · "+selected.name+"\n"+value);status.setTextIsSelectable(true);});
            } catch (Exception e) { error(e); }
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
        if (image.length<0) {
            ImageFile selected=image; setBusy(true);
            status.setText("Dostawca nie podał rozmiaru. Liczenie bajtów przed potwierdzeniem zapisu…");
            preparation=diskWork.submit(()->{
                try {
                    long size=selected.measure(this);
                    if (size<512) throw new IOException("Plik jest za mały na obraz dysku.");
                    ui(()->{selected.length=size; showImage(); setBusy(false); if (target!=null) writeAction();});
                } catch (Exception e) { error(e); }
            });
            return;
        }
        if (!useIso && image.length>capacity) { status.setText("Obraz jest większy od nośnika. Wybierz większy pendrive."); return; }
        UsbDevice selected=target;
        String method=image.uri==null?"MBR · FAT32 · "+volumeLabel:
                useIso?(gpt?"GPT":"MBR")+" · NTFS · "+volumeLabel:"Zapis sektorowy DD";
        String confirmation="\n\n"+method+(testMedia?"\nPełny test zapisu i odczytu całego nośnika.":"")
                +"\nZapis zastąpi dane na tym nośniku."
                +(image.uri!=null?" Plik źródłowy musi znajdować się poza wybranym pendrivem.":"")
                +" Pozostaw aplikację otwartą do zakończenia operacji.";
        new MaterialAlertDialogBuilder(this).setTitle("Usunąć dane i zapisać obraz?")
                .setMessage(image.name+(image.uri==null?"":" ("+size(image.length)+")")+"\n↓\n"+name(selected)+" · USB "+selected.getDeviceId()+" ("+size(capacity)+")"+confirmation)
                .setNegativeButton("Anuluj",null).setPositiveButton("Usuń dane i zapisz",(d,w)->startWrite(selected)).show();
    }
    private void startWrite(UsbDevice selected) {
        if (busy || target!=selected || !connected(selected)) {status.setText("Wybierz ponownie podłączony nośnik."); return;}
        flashing=true; cancel=false; setBusy(true); progress.setVisibility(View.VISIBLE); progress.setProgress(0);
        status.setText("Rozpoczynanie zapisu…");
        ImageFile input=image;
        long expectedCapacity=capacity;
        diskWork.execute(()->{
            try (InputStream accessible=input.uri==null?null:input.open(this);
                 UsbDisk disk=new UsbDisk(usb,selected)) {
                if (accessible!=null) {
                    if (accessible.read()==-1) throw new IOException("Źródło nie zawiera danych.");
                    ImageFile current=ImageFile.describe(this,input.uri);
                    if (current.length>=0 && current.length!=input.length)
                        throw new IOException("Zmieniono plik źródłowy. Wybierz go ponownie.");
                }
                if (disk.capacity!=expectedCapacity) throw new IOException("Zmieniła się pojemność urządzenia. Wybierz nośnik ponownie.");
                DiskLayout.Source source=()->input.open(this);
                DiskLayout layout;
                WindowsLayout windowsLayout=null;
                WindowsInstaller windowsInstaller=null;
                byte[] bootPrefix=null,bootCode=null;
                if (input.uri==null) layout=Fat32.create(n->getAssets().open("freedos/"+n),disk.capacity,disk.blockSize,dos,volumeLabel);
                else if (useIso) {
                    if (!WindowsInstaller.validLabel(volumeLabel)) throw new IOException("Nieprawidłowa etykieta NTFS.");
                    windowsLayout=WindowsLayout.create(disk.capacity,disk.blockSize,gpt,ntfsClusterSize,
                            bootResource("uefi-ntfs.img"),bootResource("mbr_win7.bin"));
                    windowsInstaller=new WindowsInstaller(input.contents(this),windowsLayout.mainLength,ntfsClusterSize);
                    bootPrefix=bootResource("br_ntfs_0x0.bin"); bootCode=bootResource("br_ntfs_0x54.bin");
                    layout=windowsLayout.metadata;
                }
                else {layout=new DiskLayout(); layout.add(0,input.length,source);}
                layout.validate(disk.capacity,disk.blockSize);
                if (testMedia) {
                    DiskLayout test=new DiskLayout();
                    test.add(0,disk.capacity,()->new ExFat.Padded(new byte[0],disk.capacity));
                    disk.flash(test,(reading,bytes)->{
                        if (cancel || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano test bloków.");
                        int percent=(int)(100.0*bytes/disk.capacity);
                        if (bytes%Math.max(131072,(disk.capacity/200/131072)*131072)==0)
                            ui(()->{status.setText((reading?"Odczyt testowy":"Zerowanie testowe")+" · "+percent+"%");progress.setProgressCompat(percent,false);});
                    },true);
                }
                final int[] last={-1};
                disk.flash(layout,(verify,bytes)->{
                    if (cancel || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano operację. Zapisz obraz ponownie przed użyciem nośnika.");
                    int percent=(int)(100.0*bytes/layout.bytes);
                    int step=(verify?100:0)+percent;
                    if (step!=last[0]) {
                        last[0]=step; ui(()->{
                            if (percent==0 || percent%10==0) status.setText((verify?"Weryfikacja":"Zapisywanie")+" · "+percent+"%");
                            progress.setProgressCompat(percent,percent>0);
                        });
                    }
                },verifyWrite);
                if (windowsInstaller!=null) {
                    BlockDevice raw=disk.partition(WindowsLayout.MAIN_START*512,windowsLayout.mainLength);
                    BlockDevice partition=new BlockDevice() {
                        public long size() {return raw.size();}
                        public int sectorSize() {return raw.sectorSize();}
                        private void check() throws IOException {
                            if (cancel || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano operację.");
                        }
                        public void read(long offset,byte[] data) throws IOException {check();raw.read(offset,data);}
                        public void write(long offset,byte[] data) throws IOException {check();raw.write(offset,data);}
                        public void sync() throws IOException {raw.sync();}
                    };
                    final int[] lastFilePercent={-1};
                    windowsInstaller.install(partition,entry->input.openEntry(this,entry),volumeLabel,ntfsClusterSize,
                            quickFormat,bootPrefix,bootCode,verifyWrite,(verify,path,done,total)->{
                                if (cancel || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano operację.");
                                int percent=total==0?0:(int)(100.0*done/total);
                                int step=(verify?100:0)+percent;
                                if (lastFilePercent[0]!=step || done==0) {
                                    lastFilePercent[0]=step;
                                    ui(()->{status.setText((verify?"Weryfikacja":"Zapisywanie")+" · "+percent+"%\n"+path);progress.setProgressCompat(percent,percent>0);});
                                }
                            });
                }
                ui(()->{
                    progress.setProgressCompat(100,true); flashing=false; setBusy(false);
                    status.setText((verifyWrite?"Zapis zweryfikowany przez SHA-256. ":"Zapis zakończony. ")+(useIso?"Uruchom komputer z przygotowanego USB.":"Możesz odłączyć nośnik."));
                });
            } catch (Exception e) { error(e); }
        });
    }
    private byte[] bootResource(String name) throws IOException {
        try (InputStream input=getAssets().open("rufus/"+name);
             ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[16384]; int count;
            while ((count=input.read(buffer))!=-1) bytes.write(buffer,0,count);
            return bytes.toByteArray();
        }
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
        diskWork.shutdown();
        super.onDestroy();
    }
}
