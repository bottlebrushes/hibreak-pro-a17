package local.eink.shade;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Home-screen calendar: today's date and the next events of the coming week (as many as fit). Tap opens the Calendar app. */
public class CalendarWidget extends AppWidgetProvider {
    private static final int[][] ROWS = {
            {R.id.c_e0, R.id.c_e0_time, R.id.c_e0_title}, {R.id.c_e1, R.id.c_e1_time, R.id.c_e1_title},
            {R.id.c_e2, R.id.c_e2_time, R.id.c_e2_title}, {R.id.c_e3, R.id.c_e3_time, R.id.c_e3_title},
            {R.id.c_e4, R.id.c_e4_time, R.id.c_e4_title}};

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { updateAll(c); }

    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, android.os.Bundle o) { updateAll(c); }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, CalendarWidget.class))) {
            // as many event rows as the widget's height holds (about 24 dp each under a 40 dp date line)
            int h = m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150);
            m.updateAppWidget(id, views(c, Math.max(1, Math.min(ROWS.length, (h - 44) / 25))));
        }
    }

    private static RemoteViews views(Context c, int rows) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.w_calendar);
        v.setOnClickPendingIntent(R.id.c_root, PendingIntent.getActivity(c, 1,
                new Intent(c, CalendarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE));
        long now = System.currentTimeMillis(), today = Agenda.startOfDay(now);
        v.setTextViewText(R.id.c_date, new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date(now)));
        if (!Agenda.allowed(c)) {
            v.setViewVisibility(R.id.c_empty, View.VISIBLE);
            v.setTextViewText(R.id.c_empty, "Open Calendar to allow access");
            return v;
        }
        List<Agenda.Event> events = Agenda.between(c, now, Agenda.addDays(today, 8));
        v.setViewVisibility(R.id.c_empty, events.isEmpty() ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.c_empty, "No events this week");
        java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(c);
        SimpleDateFormat wd = new SimpleDateFormat("EEE", Locale.getDefault());
        for (int i = 0; i < ROWS.length; i++) {
            if (i >= events.size() || i >= rows) { v.setViewVisibility(ROWS[i][0], View.GONE); continue; }
            Agenda.Event e = events.get(i);
            long day = Agenda.startOfDay(Math.max(e.begin, now));
            String when = (day == today ? "" : wd.format(new Date(day)) + " ")
                    + (e.allDay ? (day == today ? "All day" : "") : (e.begin < now ? "Now" : tf.format(new Date(e.begin))));
            v.setViewVisibility(ROWS[i][0], View.VISIBLE);
            v.setTextViewText(ROWS[i][1], when.trim());
            v.setTextViewText(ROWS[i][2], e.title);
        }
        return v;
    }
}
