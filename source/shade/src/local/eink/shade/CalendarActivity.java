package local.eink.shade;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * E-ink calendar in the Hisense layout, reading the phone's calendars: an agenda of the next weeks, or a month grid
 * with the chosen day's events under it. Events open in Google Calendar; "New event" adds one there.
 */
public class CalendarActivity extends Activity {
    private static final int ASK = 4;
    private LinearLayout list;
    private SharedPreferences p;
    private long month;          // first day of the month shown in the grid
    private long selected;       // day picked in the grid

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("shade", MODE_PRIVATE);
        selected = Agenda.startOfDay(System.currentTimeMillis());
        month = firstOfMonth(selected);
        if (!Agenda.allowed(this)) requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, ASK);
        render();
    }

    @Override protected void onResume() { super.onResume(); if (list != null) render(); }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) { render(); }

    private int dp(float v) { return Ui.dp(this, v); }
    private boolean monthView() { return "month".equals(p.getString("cal_view", "agenda")); }

    private void render() {
        list = SettingsUi.page(this, "Calendar", false);
        list.addView(switcher(), new LinearLayout.LayoutParams(-1, -2));
        if (!Agenda.allowed(this)) {
            SettingsUi.row(list, "Allow calendar access", "To show your events here", SettingsUi.chevron(this, ""),
                    v -> requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, ASK));
            return;
        }
        if (monthView()) month(); else agenda();
    }

    /** Agenda / Month as two joined buttons, and "+ New event". */
    private View switcher() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(24), dp(14), dp(24), dp(6));
        String[] names = {"Agenda", "Month"};
        for (int k = 0; k < 2; k++) {
            boolean on = (k == 1) == monthView();
            TextView b = SettingsUi.smallPill(this, names[k], on);
            final int kk = k;
            b.setOnClickListener(v -> { p.edit().putString("cal_view", kk == 1 ? "month" : "agenda").apply(); render(); clean(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (k > 0) lp.leftMargin = dp(8);
            r.addView(b, lp);
        }
        r.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView add = SettingsUi.smallPill(this, "+ New event", false);
        add.setOnClickListener(v -> startActivity(Agenda.create(monthView() ? selected : System.currentTimeMillis())));
        r.addView(add);
        return r;
    }

    // ---------------------------------------------------------------- agenda

    private void agenda() {
        long today = Agenda.startOfDay(System.currentTimeMillis());
        List<Agenda.Event> events = Agenda.between(this, today, Agenda.addDays(today, 30));
        if (events.isEmpty()) {
            SettingsUi.row(list, "Nothing in the next 30 days", null, null, null);
            return;
        }
        long day = -1;
        for (Agenda.Event e : events) {
            long d = Math.max(Agenda.startOfDay(e.begin), today);
            if (d != day) { day = d; SettingsUi.category(list, dayName(d)); }
            eventRow(e, d);
        }
    }

    private String dayName(long day) {
        long today = Agenda.startOfDay(System.currentTimeMillis());
        String date = new SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(new Date(day));
        if (day == today) return "Today  \u00B7  " + date;
        if (day == Agenda.addDays(today, 1)) return "Tomorrow  \u00B7  " + date;
        return new SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(new Date(day));
    }

    private void eventRow(Agenda.Event e, long day) {
        String sub = Agenda.when(this, e, day) + (e.location.isEmpty() ? "" : "  \u00B7  " + e.location);
        SettingsUi.row(list, e.title, sub, null, v -> {
            try { startActivity(Agenda.open(e)); } catch (Exception ignored) {}
        });
    }

    // ---------------------------------------------------------------- month

    private static long firstOfMonth(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(Agenda.startOfDay(t));
        c.set(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private void month() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(month);
        // header: ‹ November 2026 ›
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(8), dp(12), dp(4));
        TextView prev = Ui.text(this, "\u2039", 34, Ui.INK), next = Ui.text(this, "\u203A", 34, Ui.INK);
        prev.setPadding(dp(14), 0, dp(14), dp(4)); next.setPadding(dp(14), 0, dp(14), dp(4));
        prev.setOnClickListener(v -> { month = shiftMonth(-1); render(); clean(); });
        next.setOnClickListener(v -> { month = shiftMonth(1); render(); clean(); });
        TextView title = Ui.text(this, new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new Date(month)), 19, Ui.INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        head.addView(prev);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(next);
        list.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // events of the visible weeks, counted per day
        int firstDow = Calendar.getInstance().getFirstDayOfWeek();
        int offset = (c.get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7;
        long gridStart = Agenda.addDays(month, -offset);
        long gridEnd = Agenda.addDays(gridStart, 42);
        List<Agenda.Event> events = Agenda.between(this, gridStart, gridEnd);
        int[] counts = new int[42];
        for (Agenda.Event e : events) {
            for (int i = 0; i < 42; i++) {
                long d = Agenda.addDays(gridStart, i), dn = Agenda.addDays(d, 1);
                if (e.begin < dn && e.end > d) counts[i]++;
            }
        }
        MonthGrid grid = new MonthGrid(gridStart, c.get(Calendar.MONTH), counts, firstDow);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(330));
        gl.leftMargin = dp(16); gl.rightMargin = dp(16);
        list.addView(grid, gl);

        SettingsUi.category(list, dayName(selected));
        List<Agenda.Event> dayEvents = new ArrayList<>();
        for (Agenda.Event e : events) if (e.begin < Agenda.addDays(selected, 1) && e.end > selected) dayEvents.add(e);
        if (dayEvents.isEmpty()) SettingsUi.row(list, "No events", null, null, null);
        for (Agenda.Event e : dayEvents) eventRow(e, selected);
    }

    private long shiftMonth(int d) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(month);
        c.add(Calendar.MONTH, d);
        return c.getTimeInMillis();
    }

    private void clean() { sendBroadcast(new android.content.Intent("EINK_FORCE_CLEAR")); }

    /** 6 x 7 day grid: weekday letters on top; today outlined, the picked day filled; a dot per event (up to three). */
    private final class MonthGrid extends View {
        private final long start;
        private final int monthIndex, firstDow;
        private final int[] counts;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        MonthGrid(long start, int monthIndex, int[] counts, int firstDow) {
            super(CalendarActivity.this);
            this.start = start; this.monthIndex = monthIndex; this.counts = counts; this.firstDow = firstDow;
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), head = dp(28), cw = w / 7f, ch = (getHeight() - head) / 6f;
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Ui.INK);
            p.setTextSize(dp(13));
            SimpleDateFormat wd = new SimpleDateFormat("EEEEE", Locale.getDefault());
            Calendar c = Calendar.getInstance();
            for (int k = 0; k < 7; k++) {
                c.set(Calendar.DAY_OF_WEEK, (firstDow - 1 + k) % 7 + 1);
                cv.drawText(wd.format(c.getTime()), cw * k + cw / 2, dp(18), p);
            }
            long today = Agenda.startOfDay(System.currentTimeMillis());
            for (int i = 0; i < 42; i++) {
                long d = Agenda.addDays(start, i);
                c.setTimeInMillis(d);
                float x = cw * (i % 7), y = head + ch * (i / 7);
                float cx = x + cw / 2, cy = y + ch * 0.42f, r = Math.min(cw, ch) * 0.36f;
                boolean sel = d == selected, isToday = d == today, inMonth = c.get(Calendar.MONTH) == monthIndex;
                if (sel) { p.setStyle(Paint.Style.FILL); p.setColor(Ui.INK); cv.drawCircle(cx, cy, r, p); }
                else if (isToday) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(Ui.INK); cv.drawCircle(cx, cy, r, p); }
                p.setStyle(Paint.Style.FILL);
                // other months' days: smaller, never grey (grey text gets outlined by high-contrast text on this phone)
                p.setColor(sel ? Ui.PAPER : Ui.INK);
                p.setTextSize(dp(inMonth || sel ? 16 : 12));
                p.setTypeface(isToday || sel ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
                cv.drawText(String.valueOf(c.get(Calendar.DAY_OF_MONTH)), cx, cy + dp(6), p);
                int n = Math.min(3, counts[i]);
                p.setColor(Ui.INK);
                float dot = dp(2), gap = dp(4), total = n * 2 * dot + (n - 1) * gap, bx = cx - total / 2 + dot, by = y + ch * 0.86f;
                for (int k = 0; k < n; k++) cv.drawCircle(bx + k * (2 * dot + gap), by, dot, p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return super.onTouchEvent(e);
            float head = dp(28), cw = getWidth() / 7f, ch = (getHeight() - head) / 6f;
            if (e.getY() < head) return true;
            int col = (int) (e.getX() / cw), row = (int) ((e.getY() - head) / ch);
            int i = Math.max(0, Math.min(41, row * 7 + col));
            selected = Agenda.addDays(start, i);
            if (firstOfMonth(selected) != month) month = firstOfMonth(selected);
            render();
            return true;
        }
    }
}
