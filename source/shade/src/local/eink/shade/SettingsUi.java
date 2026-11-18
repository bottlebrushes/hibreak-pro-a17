package local.eink.shade;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Settings pages in the style of Hisense's e-ink settings: a plain title bar, full-width list rows
 * (title + grey summary, control on the right, inset hairline dividers), short black section labels,
 * pill switches, and choices picked from a pop-up list. Black on white only.
 */
final class SettingsUi {
    private SettingsUi() {}
    static final int GREY = 0xFF6B6B6B, RULE = 0xFFBDBDBD;

    static int dp(Context c, float v) { return Ui.dp(c, v); }

    /**
     * Title bar + scrolling list; returns the list to add rows to. Pages re-render on every change, so the
     * previous list's scroll position is carried over. Focus highlights are off: after a hardware key the page
     * leaves touch mode and Android would grey the whole focused list (a dithered grey on e-ink).
     */
    static LinearLayout page(Activity a, String title, boolean back) {
        View old = a.findViewById(android.R.id.list);
        int keepY = old == null ? 0 : old.getScrollY();
        a.getWindow().setStatusBarColor(Ui.PAPER);
        a.getWindow().setNavigationBarColor(Ui.PAPER);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Ui.PAPER);
        outer.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, back ? 4 : 24), 0, dp(a, 16), 0);
        if (back) {
            TextView b = Ui.text(a, "‹", 34, Ui.INK);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> a.finish());
            bar.addView(b, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 56)));
        }
        TextView t = Ui.text(a, title, 21, Ui.INK);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(t);
        outer.addView(bar, new LinearLayout.LayoutParams(-1, dp(a, 56)));
        View rule = new View(a); rule.setBackgroundColor(Ui.INK);
        outer.addView(rule, new LinearLayout.LayoutParams(-1, dp(a, 1)));
        ScrollView sv = new ScrollView(a);
        sv.setId(android.R.id.list);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        sv.setDefaultFocusHighlightEnabled(false);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(a, 32));
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        a.setContentView(outer);
        if (keepY > 0) sv.post(() -> sv.scrollTo(0, keepY));
        return list;
    }

    static void category(LinearLayout list, String name) {
        Context c = list.getContext();
        TextView t = Ui.text(c, name, 14, Ui.INK);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.BOTTOM);
        t.setPadding(dp(c, 24), dp(c, 18), dp(c, 24), dp(c, 6));
        list.addView(t, new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(c); rule.setBackgroundColor(Ui.INK);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1.2f)));
        rl.leftMargin = dp(c, 24); rl.rightMargin = dp(c, 24);
        list.addView(t.getParent() == null ? rule : rule, rl);
    }

    /** One list row. widget may be null; onClick may be null (then the row is plain text). */
    static LinearLayout row(LinearLayout list, String title, String summary, View widget, View.OnClickListener onClick) {
        Context c = list.getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 64));
        r.setPadding(dp(c, 24), dp(c, 10), dp(c, 20), dp(c, 10));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, title, 16, Ui.INK);
        text.addView(t);
        if (summary != null && !summary.isEmpty()) {
            TextView s = Ui.text(c, summary, 13, GREY);
            s.setLineSpacing(0, 1.1f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = dp(c, 4);
            text.addView(s, sl);
        }
        r.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        if (widget != null) {
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2); wl.leftMargin = dp(c, 14);
            r.addView(widget, wl);
        }
        if (onClick != null) r.setOnClickListener(onClick);
        r.setDefaultFocusHighlightEnabled(false);
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
        View div = new View(c); div.setBackgroundColor(RULE);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 1);
        dl.leftMargin = dp(c, 24); dl.rightMargin = dp(c, 24);
        list.addView(div, dl);
        return r;
    }

    static View chevron(Context c, String value) {
        TextView t = Ui.text(c, (value == null || value.isEmpty() ? "" : value + "  ") + "›", 15, GREY);
        t.setMaxWidth(dp(c, 170));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** Pill switch: on = black track, white knob on the right; off = outlined track, black knob on the left. */
    static View pill(Context c, boolean on) {
        return new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onMeasure(int w, int h) { setMeasuredDimension(dp(c, 46), dp(c, 26)); }
            @Override protected void onDraw(Canvas cv) {
                float s = dp(c, 2), w = getWidth(), h = getHeight(), r = h / 2f;
                RectF track = new RectF(s / 2, s / 2, w - s / 2, h - s / 2);
                p.setStyle(Paint.Style.FILL); p.setColor(on ? Ui.INK : Ui.PAPER);
                cv.drawRoundRect(track, r, r, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s); p.setColor(Ui.INK);
                cv.drawRoundRect(track, r, r, p);
                p.setStyle(Paint.Style.FILL); p.setColor(on ? Ui.PAPER : Ui.INK);
                float knob = r - dp(c, 5);
                cv.drawCircle(on ? w - r : r, h / 2f, knob, p);
            }
        };
    }

    // ---------------------------------------------------------------- dialogs

    private static Dialog sheet(Context c, String title, LinearLayout body) {
        Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.box(c, 16, false));
        box.setPadding(dp(c, 4), dp(c, 18), dp(c, 4), dp(c, 8));
        TextView t = Ui.text(c, title, 18, Ui.INK);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(c, 20), 0, dp(c, 20), dp(c, 10));
        box.addView(t);
        box.addView(body);
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            d.getWindow().setDimAmount(0f);
            d.getWindow().setLayout(c.getResources().getDisplayMetrics().widthPixels - dp(c, 40), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    interface Pick { void pick(int index); }

    /** Single-choice pop-up list with round radio marks. */
    static void choose(Context c, String title, String[] names, int selected, Pick onPick) {
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        Dialog[] d = new Dialog[1];
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(dp(c, 56));
            r.setPadding(dp(c, 20), 0, dp(c, 20), 0);
            r.addView(Ui.text(c, names[i], 16, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(radio(c, i == selected));
            r.setOnClickListener(v -> { d[0].dismiss(); onPick.pick(k); });
            body.addView(r);
        }
        d[0] = sheet(c, title, body);
        d[0].show();
    }

    private static View radio(Context c, boolean on) {
        return new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onMeasure(int w, int h) { setMeasuredDimension(dp(c, 22), dp(c, 22)); }
            @Override protected void onDraw(Canvas cv) {
                float r = getWidth() / 2f;
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(c, 2)); p.setColor(Ui.INK);
                cv.drawCircle(r, r, r - dp(c, 1.5f), p);
                if (on) { p.setStyle(Paint.Style.FILL); cv.drawCircle(r, r, r - dp(c, 6), p); }
            }
        };
    }

    interface Text { void done(String s); }

    /** Text entry pop-up with Cancel / OK. */
    static void edit(Context c, String title, String hint, String value, boolean multiLine, Text onDone) {
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        EditText e = new EditText(c);
        e.setText(value); e.setHint(hint);
        e.setTextSize(17); e.setTextColor(Ui.INK); e.setHintTextColor(GREY);
        e.setBackground(Ui.box(c, 10, false));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        if (multiLine) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(5); e.setGravity(Gravity.TOP);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setSingleLine(true);
        }
        body.addView(e);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(c, 10), 0, 0);
        Dialog[] d = new Dialog[1];
        TextView cancel = Ui.text(c, "Cancel", 16, Ui.INK), ok = Ui.text(c, "OK", 16, Ui.INK);
        for (TextView b : new TextView[]{cancel, ok}) { b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12)); buttons.addView(b); }
        ok.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancel.setOnClickListener(v -> d[0].dismiss());
        ok.setOnClickListener(v -> { d[0].dismiss(); onDone.done(e.getText().toString()); });
        body.addView(buttons);
        d[0] = sheet(c, title, body);
        d[0].show();
    }

    /** Small outlined pill button (Hisense's Apply / Applied). */
    static TextView smallPill(Context c, String text, boolean filled) {
        TextView b = Ui.text(c, text, 14, filled ? Ui.PAPER : Ui.INK);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.box(c, 14, filled));
        b.setPadding(dp(c, 18), dp(c, 6), dp(c, 18), dp(c, 6));
        return b;
    }

    static FrameLayout.LayoutParams center() { return new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER); }
}
