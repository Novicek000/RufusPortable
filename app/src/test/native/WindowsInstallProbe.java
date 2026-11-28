package pl.rufusportable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** CI-only first-logon probe; the application generator remains byte-identical to Rufus. */
final class WindowsInstallProbe implements AutoCloseable {
    final Path boot;
    final Path script;
    final WindowsCustomization customization;
    private static final String SCRIPT_MEDIA="sources/$OEM$/$$/Setup/Scripts/rufus-ci-probe.ps1";
    private WindowsInstallProbe(Path boot,Path script,WindowsCustomization customization) {this.boot=boot;this.script=script;this.customization=customization;}
    static String script() {
        return "$ErrorActionPreference='Stop'\r\n"+
                "Start-Transcript -Path C:\\rufus-port-probe.log -Force\r\n"+
                "try {\r\nStart-Sleep -Seconds 60\r\n$os=Get-CimInstance Win32_OperatingSystem\r\n"+
                "@{User=$env:USERNAME;Caption=$os.Caption;Build=$os.BuildNumber;Version=$os.Version;"+
                "Culture=(Get-Culture).Name;Timezone=(Get-TimeZone).Id;"+
                "Portable=(Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control' -ErrorAction SilentlyContinue).PortableOperatingSystem;"+
                "ImageState=(Get-ItemProperty 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Setup\\State').ImageState}"+
                "|ConvertTo-Json|Set-Content -LiteralPath C:\\rufus-port-test.json -Encoding UTF8\r\n"+
                "} catch { $_ | Out-String | Set-Content C:\\rufus-port-error.txt -Encoding UTF8 }\r\n"+
                "Stop-Transcript\r\nshutdown.exe /s /t 0\r\n";
    }
    static String firstLogonProbe(String xml) {
        String command="powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\\Windows\\Setup\\Scripts\\rufus-ci-probe.ps1";
        if(!xml.contains("</FirstLogonCommands>"))throw new AssertionError("Missing first-logon command section");
        int order=0;var orders=java.util.regex.Pattern.compile("<Order>([0-9]+)</Order>").matcher(xml.substring(xml.indexOf("<FirstLogonCommands>"),xml.indexOf("</FirstLogonCommands>")));
        while(orders.find())order=Math.max(order,Integer.parseInt(orders.group(1)));order++;
        xml=xml.replace("</FirstLogonCommands>","<SynchronousCommand wcm:action=\"add\"><Order>"+order+"</Order><Description>CI installation probe</Description><CommandLine>"+
                command+"</CommandLine></SynchronousCommand></FirstLogonCommands>");
        return xml;
    }
    static WindowsInstallProbe prepare(List<IsoTree.Entry> entries,IsoTree.Reader source,Path assets) throws Exception {
        Path directory=Files.createTempDirectory("rufus-install-test-");
        Path boot=directory.resolve("boot.wim");
        try {
            IsoTree.Entry entry=entries.stream().filter(e->e.path.equalsIgnoreCase("sources/boot.wim")).findFirst().orElseThrow();
            try(InputStream input=new ExtentInputStream(source,entry)) {Files.copy(input,boot);}
            List<WimMetadata.Image> images=WimMetadata.read(boot.toString());
            int index=images.size()>=2?2:1;var setup=images.get(index-1);
            if(!setup.architecture.equals("9"))throw new IOException("The installation VM test requires an x64 ISO");
            WindowsUnattend.Options options=new WindowsUnattend.Options();
            options.architecture=2;options.username="RufusTest";
            options.flags=WindowsUnattend.BYPASS_REQUIREMENTS|WindowsUnattend.SILENT_INSTALL|WindowsUnattend.SET_USER|
                    WindowsUnattend.NO_DATA_COLLECTION|WindowsUnattend.NO_ONLINE_ACCOUNT|WindowsUnattend.DUPLICATE_LOCALE|
                    WindowsUnattend.DISABLE_BITLOCKER|WindowsUnattend.QOL_ENHANCEMENTS;
            options.setupLanguage=setup.language;options.fallbackLanguage=setup.language;
            String xml=firstLogonProbe(new String(WindowsUnattend.create(options),StandardCharsets.UTF_8));
            Path answer=directory.resolve("answer.xml");Files.writeString(answer,xml,StandardCharsets.UTF_8);
            WimMetadata.requireEditable(boot.toString());
            long length=WimMetadata.addFile(boot.toString(),index,answer.toString(),"Autounattend.xml");
            if(length!=Files.size(boot))throw new AssertionError("Updated boot.wim length");
            WimMetadata.verify(boot.toString());
            List<IsoTree.Entry> modified=new ArrayList<>(entries);
            modified.set(modified.indexOf(entry),new IsoTree.Entry(entry.path,false,length,Collections.emptyList()));
            Path script=directory.resolve("rufus-ci-probe.ps1");Files.writeString(script,script(),StandardCharsets.UTF_8);
            String parent="";String[] parts=SCRIPT_MEDIA.split("/");
            for(int i=0;i<parts.length-1;i++) {
                parent=parent.isEmpty()?parts[i]:parent+"/"+parts[i];String path=parent;
                if(modified.stream().noneMatch(e->e.path.equalsIgnoreCase(path)))modified.add(new IsoTree.Entry(path,true,0,0));
            }
            modified.add(new IsoTree.Entry(SCRIPT_MEDIA,false,Files.size(script),Collections.emptyList()));
            WindowsCustomization plan=new WindowsCustomization(modified,options.flags,Integer.parseInt(setup.build),
                    xml.getBytes(StandardCharsets.UTF_8),e->new ExtentInputStream(source,e),name->Files.readAllBytes(assets.resolve(name)));
            System.out.println("Prepared silent Windows install probe, boot.wim index="+index+" build="+setup.build);
            return new WindowsInstallProbe(boot,script,plan);
        } catch(Exception error) {remove(directory);throw error;}
    }
    InputStream open(IsoTree.Reader source,IsoTree.Entry entry) throws IOException {
        return customization.open(e->e.path.equalsIgnoreCase("sources/boot.wim")?Files.newInputStream(boot):
                e.path.equalsIgnoreCase(SCRIPT_MEDIA)?Files.newInputStream(script):new ExtentInputStream(source,e),entry);
    }
    private static void remove(Path directory) throws IOException {
        try(var paths=Files.walk(directory)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}
    }
    public void close() throws IOException {remove(boot.getParent());}
}
