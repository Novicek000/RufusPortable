package pl.rufusportable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class WindowsTimeZones {
    private final Properties mapping=new Properties();
    final List<String> windows;
    WindowsTimeZones(InputStream input) throws IOException {
        try(Reader reader=new InputStreamReader(input,StandardCharsets.UTF_8)){mapping.load(reader);}
        TreeSet<String> names=new TreeSet<>(mapping.stringPropertyNames().stream()
                .map(mapping::getProperty).collect(java.util.stream.Collectors.toList()));
        names.add("UTC");windows=Collections.unmodifiableList(new ArrayList<>(names));
    }
    String windowsZone(String iana) {return iana==null?"UTC":mapping.getProperty(iana,"UTC");}
}
