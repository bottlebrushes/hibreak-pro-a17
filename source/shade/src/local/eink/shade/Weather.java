package local.eink.shade;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Weather for the Weather app, its widget and the lock screen, from open-meteo (no account). Location follows the
 * phone (network location, coarse) when allowed, else /sdcard/lockscreen_weather.txt ("lat,lon"), else the IP address.
 * The last forecast is kept in files/weather.json so everything can draw at once, offline too.
 */
final class Weather {
    private Weather() {}

    static final String LOC_FILE = "/sdcard/lockscreen_weather.txt";
    static final long FRESH_MS = 30 * 60_000L;

    static final class Hour { long time; int temp, code, rain; boolean day; }
    static final class Day { long date; int hi, lo, code, rain; String sunrise = "", sunset = ""; }

    static final class Forecast {
        String place = "";
        long fetched;
        boolean celsius;
        int temp, feels, humidity, code;
        double wind;
        boolean day = true;
        final List<Hour> hours = new ArrayList<>();
        final List<Day> days = new ArrayList<>();
        String unit() { return celsius ? "\u00B0C" : "\u00B0F"; }
        String windUnit() { return celsius ? "km/h" : "mph"; }
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("shade", Context.MODE_PRIVATE); }
    static boolean celsius(Context c) { return "c".equals(prefs(c).getString("weather_unit", "f")); }
    private static File cacheFile(Context c) { return new File(c.getFilesDir(), "weather.json"); }

    /** The saved forecast (may be old), or null if there has never been one. */
    static Forecast cached(Context c) {
        try {
            JSONObject o = new JSONObject(LockStyles.readFile(cacheFile(c).getPath()));
            return parse(o.getJSONObject("data"), o.optString("place", ""), o.getLong("fetched"), o.optBoolean("celsius", false));
        } catch (Exception e) { return null; }
    }

    static boolean fresh(Context c) {
        Forecast f = cached(c);
        return f != null && f.celsius == celsius(c) && System.currentTimeMillis() - f.fetched < FRESH_MS;
    }

    interface Done { void done(Forecast f); }

    /** Fetch in the background (a few tries: Wi-Fi may still be waking up); `done` runs on the main thread, null on failure. */
    static void refreshAsync(Context c, Done done) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            Forecast f = null;
            for (int attempt = 0; attempt < 4 && f == null; attempt++) {
                if (attempt > 0) try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
                try { f = fetch(app); } catch (Exception ignored) {}
            }
            final Forecast r = f;
            if (r != null) WeatherWidget.updateAll(app);
            if (done != null) new Handler(Looper.getMainLooper()).post(() -> done.done(r));
        }).start();
    }

    /** Blocking fetch; saves the result and the lock screen's one-line summary. */
    static Forecast fetch(Context c) throws Exception {
        double[] ll = null;
        String place = "";
        Location loc = phoneLocation(c);
        if (loc != null) {
            ll = new double[]{loc.getLatitude(), loc.getLongitude()};
            place = placeName(c, loc.getLatitude(), loc.getLongitude());
        }
        if (ll == null) {
            String s = LockStyles.readFile(LOC_FILE);
            if (s.contains(",")) {
                String[] p = s.split(",");
                ll = new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())};
            }
        }
        if (ll == null) {
            JSONObject ip = new JSONObject(Net.get("https://get.geojs.io/v1/ip/geo.json"));
            ll = new double[]{ip.getDouble("latitude"), ip.getDouble("longitude")};
            place = ip.optString("city", "");
        } else if (place.isEmpty()) {
            place = placeName(c, ll[0], ll[1]);
        }
        boolean cel = celsius(c);
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day"
                + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                + "&timezone=auto&forecast_days=7&timeformat=unixtime%s", ll[0], ll[1],
                cel ? "" : "&temperature_unit=fahrenheit&wind_speed_unit=mph");
        JSONObject data = new JSONObject(Net.get(url));
        long now = System.currentTimeMillis();
        Forecast f = parse(data, place, now, cel);
        JSONObject save = new JSONObject().put("data", data).put("place", place).put("fetched", now).put("celsius", cel);
        File out = cacheFile(c), tmp = new File(out.getPath() + ".tmp");
        java.nio.file.Files.write(tmp.toPath(), save.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        tmp.renameTo(out);
        prefs(c).edit().putString("weather_cache", lockLine(f)).putLong("weather_ts", now).apply();
        return f;
    }

    private static Forecast parse(JSONObject d, String place, long fetched, boolean cel) throws Exception {
        Forecast f = new Forecast();
        f.place = place; f.fetched = fetched; f.celsius = cel;
        JSONObject cur = d.getJSONObject("current");
        f.temp = (int) Math.round(cur.getDouble("temperature_2m"));
        f.feels = (int) Math.round(cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")));
        f.humidity = cur.optInt("relative_humidity_2m", 0);
        f.code = cur.optInt("weather_code", 0);
        f.wind = cur.optDouble("wind_speed_10m", 0);
        f.day = cur.optInt("is_day", 1) == 1;
        JSONObject h = d.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time");
        long nowSec = System.currentTimeMillis() / 1000 - 3600;
        for (int i = 0; i < ht.length() && f.hours.size() < 24; i++) {
            if (ht.getLong(i) < nowSec) continue;
            Hour x = new Hour();
            x.time = ht.getLong(i) * 1000;
            x.temp = (int) Math.round(h.getJSONArray("temperature_2m").getDouble(i));
            x.code = h.getJSONArray("weather_code").getInt(i);
            x.rain = h.getJSONArray("precipitation_probability").optInt(i, 0);
            x.day = h.getJSONArray("is_day").optInt(i, 1) == 1;
            f.hours.add(x);
        }
        JSONObject dd = d.getJSONObject("daily");
        JSONArray dt = dd.getJSONArray("time");
        for (int i = 0; i < dt.length(); i++) {
            Day x = new Day();
            x.date = dt.getLong(i) * 1000;
            x.hi = (int) Math.round(dd.getJSONArray("temperature_2m_max").getDouble(i));
            x.lo = (int) Math.round(dd.getJSONArray("temperature_2m_min").getDouble(i));
            x.code = dd.getJSONArray("weather_code").getInt(i);
            x.rain = dd.getJSONArray("precipitation_probability_max").optInt(i, 0);
            x.sunrise = clock(dd.getJSONArray("sunrise").optLong(i, 0) * 1000);
            x.sunset = clock(dd.getJSONArray("sunset").optLong(i, 0) * 1000);
            f.days.add(x);
        }
        return f;
    }

    private static String clock(long ms) {
        return ms <= 0 ? "" : new java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(new java.util.Date(ms));
    }

    /** Last known coarse location from any provider, if the app may use location. */
    private static Location phoneLocation(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) return null;
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) {}
        }
        return best;
    }

    private static String placeName(Context c, double lat, double lon) {
        try {
            @SuppressWarnings("deprecation")
            List<android.location.Address> a = new android.location.Geocoder(c, Locale.getDefault()).getFromLocation(lat, lon, 1);
            if (a != null && !a.isEmpty()) {
                android.location.Address x = a.get(0);
                if (x.getLocality() != null) return x.getLocality();
                if (x.getSubAdminArea() != null) return x.getSubAdminArea();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** "55°F · Clear", used by the lock screen. */
    static String lockLine(Forecast f) { return f.temp + f.unit() + "  \u00B7  " + describe(f.code); }

    /** WMO weather code in words. */
    static String describe(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mainly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Overcast";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c == 85 || c == 86) return "Snow showers";
        if (c >= 95) return "Thunderstorm";
        return "";
    }
}
