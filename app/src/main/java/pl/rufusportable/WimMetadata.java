package pl.rufusportable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class WimMetadata {
    static {
        System.loadLibrary("rufus_wim");
    }

    private static final String[] PROPERTIES={"NAME","DESCRIPTION","WINDOWS/ARCH","WINDOWS/EDITIONID",
            "WINDOWS/VERSION/BUILD","WINDOWS/LANGUAGES/LANGUAGE","DISPLAYNAME",
            "WINDOWS/VERSION/MAJOR","WINDOWS/VERSION/MINOR","WINDOWS/PRODUCTTYPE"};

    static final class Image {
        final int index;
        final String name, description, architecture, edition, build, language;

        private final Map<String,String> properties;
        Image(int index, Map<String,String> properties) {
            this.index=index;
            this.properties=Collections.unmodifiableMap(new HashMap<>(properties));
            name=property("NAME");description=property("DESCRIPTION");architecture=property("WINDOWS/ARCH");
            edition=property("WINDOWS/EDITIONID");build=property("WINDOWS/VERSION/BUILD");
            language=property("WINDOWS/LANGUAGES/LANGUAGE");
        }
        String property(String key) {return properties.getOrDefault(key,"");}
        String editionName() {
            String display=property("DISPLAYNAME");
            return !display.isEmpty()?display:!description.isEmpty()?description:"Nieznana wersja Windows";
        }

        String displayName() {
            return name.isEmpty() ? "Obraz " + index : name;
        }

        String platform() {
            switch (architecture) {
                case "0": return "x86";
                case "9": return "x64";
                case "12": return "ARM64";
                case "5": return "ARM";
                default: return architecture;
            }
        }
    }

    static List<Image> read(String path) throws IOException {
        byte[][] fields = inspect(path,PROPERTIES);
        if (fields.length % PROPERTIES.length != 0) {
            throw new IOException("Niepełne informacje WIM.");
        }
        List<Image> images = new ArrayList<>();
        for (int i = 0; i < fields.length; i += PROPERTIES.length) {
            Map<String,String> properties=new HashMap<>();
            for(int j=0;j<PROPERTIES.length;j++)
                properties.put(PROPERTIES[j],new String(fields[i+j],StandardCharsets.UTF_8));
            images.add(new Image(i / PROPERTIES.length + 1,properties));
        }
        return Collections.unmodifiableList(images);
    }

    static long addFile(String wimPath,int image,String source,String target) throws IOException {
        if(image<1 || target.isEmpty() || target.startsWith("/") || target.contains("\\") ||
                Arrays.asList(target.split("/",-1)).stream().anyMatch(p->p.isEmpty() || p.equals(".") || p.equals("..")))
            throw new IOException("Nieprawidłowa ścieżka lub indeks obrazu WIM.");
        return update(wimPath,image,source.getBytes(StandardCharsets.UTF_8),target.getBytes(StandardCharsets.UTF_8));
    }
    static void extractFile(String path,int image,String target,String directory) throws IOException {
        if(image<1 || target.indexOf(0)>=0 || directory.indexOf(0)>=0)
            throw new IOException("Nieprawidłowe parametry wypakowania WIM.");
        extract(path,image,target.getBytes(StandardCharsets.UTF_8),directory.getBytes(StandardCharsets.UTF_8));
    }
    static native void requireEditable(String path) throws IOException;
    static void verify(String path) throws IOException {verifyWim(path);}
    private static native void verifyWim(String path) throws IOException;
    private static native void extract(String path,int image,byte[] target,byte[] directory) throws IOException;
    private static native long update(String path,int image,byte[] source,byte[] target) throws IOException;

    private static native byte[][] inspect(String path,String[] properties) throws IOException;
}
