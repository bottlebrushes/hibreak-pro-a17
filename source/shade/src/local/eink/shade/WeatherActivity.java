package local.eink.shade;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** E-ink weather in the Hisense layout: now, the next hours, seven days, then details and options. */
public class WeatherActivity extends Activity {
    private static final int ASK_LOCATION = 3;
    private LinearLayout list;
    private SharedPreferences p;
    private boolean loading;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Weather.prefs(this);
        render(Weather.cached(this));
    }

    @Override protected void onResume() {
        super.onResume();
        if (!Weather.fresh(this)) refresh();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void refresh() {
        if (loading) return;
        loading = true;
        render(Weather.cached(this));
        Weather.refreshAsync(this, f -> {
            loading = false;
            if (isFinishing() || isDestroyed()) return;
            Weather.Forecast show = f != null ? f : Weather.cached(this);
            render(show);
            if (f == null) SettingsUi.row(list, "Couldn't update", "No connection. Showing the last forecast.", null, v -> refresh());
            sendBroadcast(new android.content.Intent("EINK_FORCE_CLEAR"));
        });
    }

    private void render(Weather.Forecast f) {
        list = SettingsUi.page(this, "Weather", false);
        if (f == null) {
            SettingsUi.row(list, loading ? "Loading the forecast\u2026" : "No forecast yet", loading ? null : "Tap to try again", null,
                    loading ? null : v -> refresh());
            options(null);
            return;
        }
        now(f);
        hours(f);
        days(f);
        options(f);
    }

    /** Place, a large temperature with its symbol, the condition, and the day's high and low. */
    private void now(Weather.Forecast f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(18), dp(24), dp(10));
        if (!f.place.isEmpty()) box.addView(Ui.text(this, f.place, 16, Ui.INK));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new WeatherIcon(f.code, f.day));
        row.addView(ic, new LinearLayout.LayoutParams(dp(88), dp(88)));
        TextView t = Ui.text(this, f.temp + "\u00B0", 72, Ui.INK);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.leftMargin = dp(16);
        row.addView(t, tl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(8);
        box.addView(row, rl);
        TextView cond = Ui.text(this, Weather.describe(f.code), 22, Ui.INK);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.topMargin = dp(10);
        box.addView(cond, cl);
        StringBuilder sb = new StringBuilder("Feels like " + f.feels + "\u00B0  \u00B7  Humidity " + f.humidity + "%  \u00B7  Wind "
                + Math.round(f.wind) + " " + f.windUnit());
        TextView more = Ui.text(this, sb, 15, SettingsUi.GREY);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2); ml.topMargin = dp(8);
        box.addView(more, ml);
        if (!f.days.isEmpty()) {
            Weather.Day d = f.days.get(0);
            TextView hl = Ui.text(this, "High " + d.hi + "\u00B0  \u00B7  Low " + d.lo + "\u00B0" + (d.rain >= 10 ? "  \u00B7  Rain " + d.rain + "%" : ""), 15, SettingsUi.GREY);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2); hp.topMargin = dp(4);
            box.addView(hl, hp);
        }
        list.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Twelve hours side by side: time, symbol, temperature, and the chance of rain when it matters. */
    private void hours(Weather.Forecast f) {
        if (f.hours.isEmpty()) return;
        SettingsUi.category(list, "Next hours");
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(16), dp(12), dp(16), dp(8));
        SimpleDateFormat hf = new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h a", Locale.getDefault());
        for (int i = 0; i < Math.min(12, f.hours.size()); i++) {
            Weather.Hour h = f.hours.get(i);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(Ui.text(this, i == 0 ? "Now" : hf.format(new Date(h.time)), 14, Ui.INK));
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(new WeatherIcon(h.code, h.day));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(36), dp(36)); il.topMargin = dp(6);
            col.addView(ic, il);
            TextView t = Ui.text(this, h.temp + "\u00B0", 17, Ui.INK);
            LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(-2, -2); ttl.topMargin = dp(6);
            col.addView(t, ttl);
            TextView r = Ui.text(this, h.rain >= 20 ? h.rain + "%" : " ", 13, SettingsUi.GREY);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2); rp.topMargin = dp(2);
            col.addView(r, rp);
            row.addView(col, new LinearLayout.LayoutParams(dp(62), -2));
        }
        hs.addView(row);
        list.addView(hs, new LinearLayout.LayoutParams(-1, -2));
    }

    /** One row per day: name, symbol, condition, low / high. */
    private void days(Weather.Forecast f) {
        if (f.days.isEmpty()) return;
        SettingsUi.category(list, "7 days");
        SimpleDateFormat df = new SimpleDateFormat("EEEE", Locale.getDefault());
        Calendar today = Calendar.getInstance();
        for (int i = 0; i < f.days.size(); i++) {
            Weather.Day d = f.days.get(i);
            Calendar c = Calendar.getInstance(); c.setTimeInMillis(d.date);
            String name = c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ? "Today" : df.format(new Date(d.date));
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(dp(58));
            r.setPadding(dp(24), dp(6), dp(24), dp(6));
            TextView n = Ui.text(this, name, 16, Ui.INK);
            r.addView(n, new LinearLayout.LayoutParams(dp(104), -2));
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(new WeatherIcon(d.code, true));
            r.addView(ic, new LinearLayout.LayoutParams(dp(32), dp(32)));
            String desc = Weather.describe(d.code) + (d.rain >= 20 ? "  " + d.rain + "%" : "");
            TextView cond = Ui.text(this, desc, 14, SettingsUi.GREY);
            cond.setSingleLine(true);
            cond.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f); cl.leftMargin = dp(12);
            r.addView(cond, cl);
            TextView hl = Ui.text(this, d.lo + "\u00B0 / " + d.hi + "\u00B0", 16, Ui.INK);
            r.addView(hl);
            list.addView(r, new LinearLayout.LayoutParams(-1, -2));
            View div = new View(this); div.setBackgroundColor(SettingsUi.RULE);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 1); dl.leftMargin = dp(24); dl.rightMargin = dp(24);
            list.addView(div, dl);
        }
    }

    private void options(Weather.Forecast f) {
        SettingsUi.category(list, "Details");
        if (f != null && !f.days.isEmpty() && !f.days.get(0).sunrise.isEmpty())
            SettingsUi.row(list, "Sunrise " + f.days.get(0).sunrise + "  \u00B7  Sunset " + f.days.get(0).sunset, null, null, null);
        if (f != null) {
            String when = android.text.format.DateFormat.getTimeFormat(this).format(new Date(f.fetched));
            SettingsUi.row(list, loading ? "Updating\u2026" : "Updated " + when, "Tap to update now  \u00B7  open-meteo.com", null, v -> refresh());
        }
        boolean cel = Weather.celsius(this);
        String[] units = {"Fahrenheit (\u00B0F)", "Celsius (\u00B0C)"};
        SettingsUi.row(list, "Units", null, SettingsUi.chevron(this, cel ? "\u00B0C" : "\u00B0F"),
                v -> SettingsUi.choose(this, "Units", units, cel ? 1 : 0, k -> {
                    p.edit().putString("weather_unit", k == 1 ? "c" : "f").apply();
                    refresh();
                }));
        boolean located = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        SettingsUi.row(list, "Location", located ? "Follows the phone" : "From the internet connection. Tap to use the phone's location",
                SettingsUi.chevron(this, ""), located ? null
                        : v -> requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_LOCATION));
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == ASK_LOCATION) refresh();
    }
}
