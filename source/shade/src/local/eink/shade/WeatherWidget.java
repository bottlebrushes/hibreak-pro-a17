package local.eink.shade;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen weather: now and the next four days, from the Weather app's saved forecast. Tap opens the app. */
public class WeatherWidget extends AppWidgetProvider {
    private static final int[][] DAY_IDS = {
            {R.id.w_d0_name, R.id.w_d0_icon, R.id.w_d0_temp}, {R.id.w_d1_name, R.id.w_d1_icon, R.id.w_d1_temp},
            {R.id.w_d2_name, R.id.w_d2_icon, R.id.w_d2_temp}, {R.id.w_d3_name, R.id.w_d3_icon, R.id.w_d3_temp}};

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        updateAll(c);
        if (!Weather.fresh(c)) Weather.refreshAsync(c, null); // redraws the widgets when it lands
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, WeatherWidget.class));
        if (ids.length == 0) return;
        RemoteViews v = views(c, Weather.cached(c));
        m.updateAppWidget(ids, v);
    }

    private static RemoteViews views(Context c, Weather.Forecast f) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.w_weather);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 0,
                new Intent(c, WeatherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE));
        int icon = Ui.dp(c, 52), small = Ui.dp(c, 28);
        if (f == null) {
            v.setTextViewText(R.id.w_temp, "--");
            v.setTextViewText(R.id.w_cond, "Tap to load the weather");
            return v;
        }
        v.setImageViewBitmap(R.id.w_icon, WeatherIcon.bitmap(f.code, f.day, icon));
        v.setTextViewText(R.id.w_temp, f.temp + "\u00B0");
        v.setTextViewText(R.id.w_cond, Weather.describe(f.code));
        if (!f.days.isEmpty()) v.setTextViewText(R.id.w_hilo, "H " + f.days.get(0).hi + "\u00B0  L " + f.days.get(0).lo + "\u00B0");
        v.setTextViewText(R.id.w_place, f.place);
        SimpleDateFormat df = new SimpleDateFormat("EEE", Locale.getDefault());
        for (int i = 0; i < DAY_IDS.length; i++) {
            if (i + 1 >= f.days.size()) break;
            Weather.Day d = f.days.get(i + 1);              // tomorrow onwards; today is the top line
            v.setTextViewText(DAY_IDS[i][0], df.format(new Date(d.date)));
            v.setImageViewBitmap(DAY_IDS[i][1], WeatherIcon.bitmap(d.code, true, small));
            v.setTextViewText(DAY_IDS[i][2], d.lo + "\u00B0/" + d.hi + "\u00B0");
        }
        return v;
    }
}
