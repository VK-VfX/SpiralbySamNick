package app.skypix.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class Net {
    private Net() {}

    static final String UA = "Purrcast/1.0 (Android weather app; offline-first, no tracking)";

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 3 * 1024 * 1024) throw new IOException("response too large");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
