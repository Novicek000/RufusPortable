package pl.rufusportable;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.math.BigInteger;

final class Updates {
    static final String RELEASES = "https://github.com/Novicek000/RufusPortable/releases";
    static String latest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(
                "https://api.github.com/repos/Novicek000/RufusPortable/releases/latest").openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = connection.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("GitHub zwrócił kod " + code + ".");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) body.append(line);
                String version = new JSONObject(body.toString()).getString("tag_name");
                if (!version.matches("v?[0-9]+(\\.[0-9]+){2}")) throw new IOException("Nieznany format wersji na GitHubie.");
                return version;
            }
        } finally { connection.disconnect(); }
    }

    static boolean newer(String remote, String local) {
        String[] a = remote.replaceFirst("^v", "").split("\\.");
        String[] b = local.split("\\.");
        for (int i=0; i<3; i++) {
            int cmp = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
            if (cmp != 0) return cmp > 0;
        }
        return false;
    }
}
