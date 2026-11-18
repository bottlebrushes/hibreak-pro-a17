package local.eink.shade;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** "Art cover" lock screen: public-domain paintings from the Art Institute of Chicago open-access API (CC0),
 *  cached on the phone in grayscale. One new painting each time the phone locks; double-tap for another. */
final class ArtCover {
    private ArtCover() {}
    private static final int KEEP = 12, AHEAD = 3;
    private static volatile boolean fetching;

    static File dir(Context c) { File d = new File(c.getFilesDir(), "art"); d.mkdirs(); return d; }

    /** Cached paintings, oldest first. */
    static List<File> images(Context c) {
        File[] f = dir(c).listFiles((d, n) -> n.endsWith(".jpg"));
        if (f == null) return new ArrayList<>();
        Arrays.sort(f, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        return new ArrayList<>(Arrays.asList(f));
    }

    static File current(Context c) {
        List<File> l = images(c);
        if (l.isEmpty()) return null;
        int i = Math.min(LockStyles.prefs(c).getInt("art_idx", 0), l.size() - 1);
        return l.get(Math.max(0, i));
    }

    /** title, artist, date */
    static String[] meta(File img) {
        String t = LockStyles.readFile(img.getAbsolutePath().replace(".jpg", ".txt"));
        String[] p = t.split("\n", -1);
        return new String[]{p.length > 0 ? p[0] : "", p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : ""};
    }

    /** Move to the next cached painting, dropping old ones. */
    static void next(Context c) {
        List<File> l = images(c);
        int i = LockStyles.prefs(c).getInt("art_idx", 0) + 1;
        if (i >= l.size()) i = Math.max(0, l.size() - 1);
        while (i > 0 && l.size() > KEEP) {
            File old = l.remove(0);
            old.delete(); new File(old.getAbsolutePath().replace(".jpg", ".txt")).delete();
            i--;
        }
        LockStyles.prefs(c).edit().putInt("art_idx", i).apply();
    }

    static int unseen(Context c) {
        return images(c).size() - 1 - LockStyles.prefs(c).getInt("art_idx", 0);
    }

    /** Download paintings in the background until a few unseen ones are ready. */
    static void ensure(Context c, Runnable onNew) {
        if (fetching) return;
        fetching = true;
        new Thread(() -> {
            try {
                for (int tries = 0; tries < 6 && unseen(c) < AHEAD; tries++) {
                    boolean first = images(c).isEmpty();
                    if (fetchOne(c) && first && onNew != null) onNew.run();
                }
            } finally { fetching = false; }
        }).start();
    }

    private static boolean fetchOne(Context c) {
        try {
            String q = "{\"query\":{\"bool\":{\"must\":[{\"term\":{\"is_public_domain\":true}},{\"term\":{\"artwork_type_id\":1}},{\"exists\":{\"field\":\"image_id\"}}]}}}";
            int page = 1 + new Random().nextInt(180);
            String url = "https://api.artic.edu/api/v1/artworks/search?params=" + URLEncoder.encode(q, "UTF-8")
                    + "&limit=10&page=" + page + "&fields=id,title,artist_title,date_display,image_id";
            JSONArray data = new JSONObject(Net.get(url)).getJSONArray("data");
            List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) items.add(data.getJSONObject(i));
            java.util.Collections.shuffle(items);
            for (JSONObject a : items) {
                String imageId = a.optString("image_id", "");
                if (imageId.isEmpty() || imageId.equals("null")) continue;
                File jpg = new File(dir(c), a.getInt("id") + ".jpg");
                if (jpg.exists()) continue;
                File raw = new File(c.getCacheDir(), "art_raw.jpg");
                raw.delete();
                if (!Net.download("https://www.artic.edu/iiif/2/" + imageId + "/full/843,/0/default.jpg", raw)) continue;
                Bitmap src = BitmapFactory.decodeFile(raw.getAbsolutePath());
                raw.delete();
                if (src == null) continue;
                Bitmap g = grayscale(src);
                try (FileOutputStream o = new FileOutputStream(jpg)) { g.compress(Bitmap.CompressFormat.JPEG, 92, o); }
                String artist = a.optString("artist_title", "");
                if (artist.equals("null")) artist = "";
                String meta = a.optString("title", "") + "\n" + artist + "\n" + a.optString("date_display", "");
                try (FileOutputStream o = new FileOutputStream(jpg.getAbsolutePath().replace(".jpg", ".txt"))) {
                    o.write(meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Grayscale with a little extra contrast, which reads better on e-ink. */
    static Bitmap grayscale(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0);
        float k = 1.15f, t = -0.075f * 255;
        ColorMatrix contrast = new ColorMatrix(new float[]{k, 0, 0, 0, t, 0, k, 0, 0, t, 0, 0, k, 0, t, 0, 0, 0, 1, 0});
        m.postConcat(contrast);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(m));
        new Canvas(out).drawBitmap(src, 0, 0, p);
        return out;
    }
}
