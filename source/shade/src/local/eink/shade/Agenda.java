package local.eink.shade;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Read-only view of the phone's calendars (the ones shown in Google Calendar). Opening or adding uses that app. */
final class Agenda {
    private Agenda() {}

    static final class Event {
        long id, begin, end;
        boolean allDay;
        String title = "", location = "";
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** Event instances overlapping [from, to), earliest first; empty without permission. */
    static List<Event> between(Context c, long from, long to) {
        List<Event> out = new ArrayList<>();
        if (!allowed(c)) return out;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] cols = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION};
        try (Cursor cur = c.getContentResolver().query(b.build(), cols, CalendarContract.Instances.VISIBLE + "=1", null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                Event e = new Event();
                e.id = cur.getLong(0);
                e.allDay = cur.getInt(3) == 1;
                e.begin = cur.getLong(1);
                e.end = cur.getLong(2);
                if (e.allDay) { e.begin = utcDayToLocal(e.begin); e.end = utcDayToLocal(e.end); } // all-day times are UTC midnights
                e.title = cur.getString(4) == null || cur.getString(4).isEmpty() ? "(No title)" : cur.getString(4);
                e.location = cur.getString(5) == null ? "" : cur.getString(5);
                if (e.end > from && e.begin < to) out.add(e);
            }
        } catch (Exception ignored) {}
        // by day; all-day events first within a day; then by start time
        out.sort(java.util.Comparator.<Event>comparingLong(x -> startOfDay(x.begin))
                .thenComparing(x -> !x.allDay).thenComparingLong(x -> x.begin));
        return out;
    }

    private static long utcDayToLocal(long utcMidnight) {
        Calendar u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(utcMidnight);
        Calendar l = Calendar.getInstance();
        l.clear();
        l.set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH));
        return l.getTimeInMillis();
    }

    static long startOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long addDays(long t, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    static boolean sameDay(long a, long b) { return startOfDay(a) == startOfDay(b); }

    /** "9:00 – 10:30", "All day", or "Until 11:00" for something that started on an earlier day. */
    static String when(Context c, Event e, long day) {
        if (e.allDay) return "All day";
        java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(c);
        boolean startsToday = e.begin >= day, endsToday = e.end <= addDays(day, 1);
        if (!startsToday && !endsToday) return "All day";
        if (!startsToday) return "Until " + tf.format(new java.util.Date(e.end));
        if (!endsToday) return tf.format(new java.util.Date(e.begin)) + " \u2192";
        return tf.format(new java.util.Date(e.begin)) + " \u2013 " + tf.format(new java.util.Date(e.end));
    }

    /** Opens the event in the calendar app (Google Calendar). */
    static Intent open(Event e) {
        return new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** New event in the calendar app, starting at the next full hour of `day` (or now, for today). */
    static Intent create(long day) {
        Calendar c = Calendar.getInstance();
        if (!sameDay(day, System.currentTimeMillis())) { c.setTimeInMillis(day); c.set(Calendar.HOUR_OF_DAY, 9); }
        else c.add(Calendar.HOUR_OF_DAY, 1);
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.getTimeInMillis())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.getTimeInMillis() + 3600_000L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
