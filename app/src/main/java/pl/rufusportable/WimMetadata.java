package pl.rufusportable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class WimMetadata {
    static {
        System.loadLibrary("rufus_wim");
    }

    static final class Image {
        final int index;
        final String name, description, architecture, edition, build, language;

        Image(int index, byte[][] fields, int start) {
            this.index = index;
            name = text(fields[start]);
            description = text(fields[start + 1]);
            architecture = text(fields[start + 2]);
            edition = text(fields[start + 3]);
            build = text(fields[start + 4]);
            language = text(fields[start + 5]);
        }

        private static String text(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
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
        byte[][] fields = inspect(path);
        if (fields.length % 6 != 0) {
            throw new IOException("Niepełne informacje WIM.");
        }
        List<Image> images = new ArrayList<>();
        for (int i = 0; i < fields.length; i += 6) {
            images.add(new Image(i / 6 + 1, fields, i));
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

    private static native byte[][] inspect(String path) throws IOException;
}
