package local.eink.shade;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** HTTP helpers using the app's network permission; no shell fallback is required on Android 17. */
final class Net {
    private Net() {}

    static String get(String url) throws Exception {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "einkshade/1.1");
            try (InputStream in = c.getInputStream()) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } finally { c.disconnect(); }
        } catch (Exception e) {
            if (android.os.Build.VERSION.SDK_INT < 35) {
                String out = Root.exec("curl -sL --max-time 20 -A einkshade/1.1 '" + url + "'");
                if (!out.trim().isEmpty()) return out;
            }
            throw e;
        }
    }

    static boolean download(String url, File dest) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "einkshade/1.1");
            try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(dest)) {
                in.transferTo(o);
            } finally { c.disconnect(); }
            return dest.length() > 0;
        } catch (Exception e) {
            if (android.os.Build.VERSION.SDK_INT < 35) {
                String p = dest.getAbsolutePath();
                Root.exec("curl -sL --max-time 40 -A einkshade/1.1 -o '" + p + "' '" + url + "' && chmod 666 '" + p + "'");
            }
            return dest.canRead() && dest.length() > 0;
        }
    }
}
