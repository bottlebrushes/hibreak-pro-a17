package local.eink.shade;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Hisense-style e-ink volume panel, replacing the stock pop-up: the volume keys are taken by the shade's accessibility
 * service and the level is changed here without the system UI. A narrow column on the right edge, next to the keys,
 * shows the stream being changed as a tall stepped bar; the chevron adds every stream and the ring / vibrate / silent switch. No animation; it
 * closes a few seconds after the last change, with one clean flash.
 * Off (setting) = the stock pop-up. Calls, the screen being off and other key paths are left to the system.
 */
final class VolumePanel {
    static final String PREF_ON = "volume_panel", PREF_KEYS = "volume_keys"; // keys: "media" (default) | "ring"
    private static final int[] STREAMS = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM};
    private static final String[] NAMES = {"Media", "Ring", "Alarm"};  // ring also sets notifications
    private static final int[] ICONS = {R.drawable.i_music, R.drawable.i_ringer, R.drawable.i_alarm};

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final AudioManager am;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hide;
    private final Runnable clean;
    private FrameLayout root;
    private boolean more;
    private int active = AudioManager.STREAM_MUSIC;

    VolumePanel(AccessibilityService svc, WindowManager wm, Runnable clean) {
        this.svc = svc; this.wm = wm; this.clean = clean;
        am = svc.getSystemService(AudioManager.class);
        prefs = svc.getSharedPreferences("shade", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return c.getSharedPreferences("shade", Context.MODE_PRIVATE).getBoolean(PREF_ON, true); }

    /** From the accessibility key filter. True = handled here (the system then shows nothing). */
    boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (!prefs.getBoolean(PREF_ON, true)) return false;
        PowerManager pm = svc.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) return false;        // screen off: system default (e.g. music volume)
        int mode = am.getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE)
            return false;                                           // calls and ringing keep the system behaviour
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (root == null) active = keyStream();
            adjust(active, code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER);
            show();
        }
        return true;
    }

    /** Media while something plays or when chosen; otherwise the ringer. */
    private int keyStream() {
        if (am.isMusicActive()) return AudioManager.STREAM_MUSIC;
        return "ring".equals(prefs.getString(PREF_KEYS, "media")) ? AudioManager.STREAM_RING : AudioManager.STREAM_MUSIC;
    }

    private void adjust(int stream, int direction) {
        int flags = stream == AudioManager.STREAM_RING ? AudioManager.FLAG_ALLOW_RINGER_MODES : 0;
        try { am.adjustStreamVolume(stream, direction, flags); }
        catch (SecurityException ex) { am.adjustStreamVolume(stream, direction, 0); } // ringer mode change needs DND access
    }

    private void set(int stream, int index) {
        try { am.setStreamVolume(stream, index, 0); } catch (SecurityException ignored) {}
    }

    private void setRinger(int ringerMode) {
        AudioManager am = svc.getSystemService(AudioManager.class);
        PlatformControls.setRingerMode(am, ringerMode);
        main.postDelayed(this::refresh, 300);
    }

    // ---------------------------------------------------------------- window

    private final BroadcastReceiver volumeChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    private void show() {
        if (root == null) {
            root = new FrameLayout(svc) {
                @Override public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hide(); return true; }
                    restartTimer();
                    return super.dispatchTouchEvent(ev);
                }
            };
            // a narrow column on the right edge, next to the volume keys
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            lp.x = dp(8);
            lp.windowAnimations = 0;
            lp.setTitle("EinkVolumePanel");
            wm.addView(root, lp);
            IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            svc.registerReceiver(volumeChanged, f, Context.RECEIVER_EXPORTED);
        }
        refresh();
        restartTimer();
    }

    private void restartTimer() {
        main.removeCallbacks(hide);
        main.postDelayed(hide, more ? 6000 : 3000);
    }

    void hide() {
        main.removeCallbacks(hide);
        if (root == null) return;
        try { svc.unregisterReceiver(volumeChanged); } catch (Exception ignored) {}
        try { wm.removeView(root); } catch (Exception ignored) {}
        root = null;
        more = false;
        clean.run();
    }

    /**
     * Same footprint as the stock pop-up (about 355 x 60 dp): ringer button, the tall bar with the stream's icon at its
     * foot, the level, and a chevron. The chevron puts Media, Ring and Alarm side by side with a ring / vibrate / silent row.
     */
    private void refresh() {
        if (root == null) return;
        root.removeAllViews();
        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.box(svc, 30, false));
        card.setPadding(dp(8), dp(10), dp(8), dp(4));
        LinearLayout cols = new LinearLayout(svc);
        cols.setGravity(Gravity.TOP);
        if (more) {
            card.addView(ringerSwitch(), new LinearLayout.LayoutParams(-2, -2));
            for (int i = 0; i < STREAMS.length; i++) {
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) cl.leftMargin = dp(12);
                cols.addView(streamColumn(i, STREAMS[i] == active, true), cl);
            }
        } else {
            card.addView(ringerButton(am.getRingerMode(), true, cycleNext(am.getRingerMode())), new LinearLayout.LayoutParams(dp(44), dp(44)));
            int i = 0;
            for (int k = 0; k < STREAMS.length; k++) if (STREAMS[k] == active) i = k;
            cols.addView(streamColumn(i, true, false));
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = dp(10);
        card.addView(cols, sl);
        android.widget.ImageView toggle = Ui.icon(svc, more ? R.drawable.i_less : R.drawable.i_more, Ui.INK, 26);
        toggle.setPadding(dp(8), dp(4), dp(8), dp(4));
        toggle.setOnClickListener(v -> { more = !more; refresh(); restartTimer(); });
        card.addView(toggle, new LinearLayout.LayoutParams(dp(44), dp(34)));
        root.addView(card, new FrameLayout.LayoutParams(-2, -2));
    }

    /** The tall bar (tap or drag to set; the keys step it) with the stream icon at its foot, then the level. */
    private View streamColumn(int i, boolean isActive, boolean named) {
        int stream = STREAMS[i];
        int max = am.getStreamMaxVolume(stream), min = am.getStreamMinVolume(stream), cur = am.getStreamVolume(stream);
        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int icon = stream == AudioManager.STREAM_RING ? ringerIcon(am.getRingerMode()) : ICONS[i];
        StepBar bar = new StepBar(svc, min, max, cur, svc.getDrawable(icon).mutate(),
                idx -> { active = stream; set(stream, idx); refresh(); });
        col.addView(bar, new LinearLayout.LayoutParams(dp(44), dp(220)));
        int pct = max == 0 ? 0 : Math.round(100f * cur / max);
        TextView t = Ui.text(svc, pct + "%", 14, Ui.INK);
        if (isActive) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2); pl.topMargin = dp(6);
        col.addView(t, pl);
        if (named) {
            TextView n = Ui.text(svc, NAMES[i], 12, Ui.INK);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2); nl.topMargin = dp(2);
            col.addView(n, nl);
        }
        return col;
    }

    private static int ringerIcon(int mode) {
        return mode == AudioManager.RINGER_MODE_VIBRATE ? R.drawable.i_vibrate
                : mode == AudioManager.RINGER_MODE_SILENT ? R.drawable.i_silent : R.drawable.i_ringer;
    }

    private static int cycleNext(int mode) {
        return mode == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                : mode == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
    }

    /** Round ringer button showing `mode`; filled when it is the current mode. A tap switches to `target`. */
    private View ringerButton(int mode, boolean filled, int target) {
        android.widget.ImageView b = Ui.icon(svc, ringerIcon(mode), filled ? Ui.PAPER : Ui.INK, 24);
        android.graphics.drawable.GradientDrawable g = Ui.circle(svc);
        if (filled) g.setColor(Ui.INK);
        b.setBackground(g);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setOnClickListener(v -> { setRinger(target); restartTimer(); });
        return b;
    }

    /** Expanded: Ring / Vibrate / Silent above the three bars; the current one is filled. */
    private View ringerSwitch() {
        LinearLayout r = new LinearLayout(svc);
        int cur = am.getRingerMode();
        int[] modes = {AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT};
        for (int k = 0; k < 3; k++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            if (k > 0) lp.leftMargin = dp(12);
            r.addView(ringerButton(modes[k], cur == modes[k], modes[k]), lp);
        }
        return r;
    }

    private int dp(float v) { return Ui.dp(svc, v); }

    interface Pick { void pick(int index); }

    /** Tall outlined bar filled black from the bottom up to the level; tick marks between steps when there are few; tap or drag sets it. */
    static final class StepBar extends View {
        private final int min, max;
        private int cur;
        private final Pick onPick;
        private final android.graphics.drawable.Drawable icon;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        StepBar(Context c, int min, int max, int cur, android.graphics.drawable.Drawable icon, Pick onPick) {
            super(c);
            this.min = min; this.max = Math.max(max, min + 1); this.cur = cur; this.icon = icon; this.onPick = onPick;
        }

        @Override protected void onDraw(Canvas cv) {
            float s = Ui.dp(getContext(), 2), w = getWidth(), h = getHeight(), r = w / 2f;
            RectF box = new RectF(s / 2, s / 2, w - s / 2, h - s / 2);
            float frac = (float) (cur - min) / (max - min);
            p.setStyle(Paint.Style.FILL); p.setColor(Ui.INK);
            if (frac > 0) {                  // filled from the bottom up
                cv.save();
                cv.clipRect(0, h - s / 2 - (h - s) * frac, w, h);
                cv.drawRoundRect(box, r, r, p);
                cv.restore();
            }
            int steps = max - min;
            if (steps <= 16) {               // separators so the steps can be counted
                p.setStrokeWidth(Ui.dp(getContext(), 1.5f));
                float iconTop = icon == null ? h : h - w * 0.28f - w * 0.5f - Ui.dp(getContext(), 6);
                for (int k = 1; k < steps; k++) {
                    float y = h - s / 2 - (h - s) * k / steps;
                    if (y > iconTop) continue;   // keep the stream icon clear
                    p.setColor(k <= cur - min ? Ui.PAPER : Ui.INK);
                    cv.drawLine(w * 0.3f, y, w * 0.7f, y, p);
                }
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s); p.setColor(Ui.INK);
            cv.drawRoundRect(box, r, r, p);
            if (icon != null) {              // stream icon at the foot, white where the fill already covers it
                int sz = Math.round(w * 0.5f), left = Math.round((w - sz) / 2f), bottom = Math.round(h - w * 0.28f);
                icon.setBounds(left, bottom - sz, left + sz, bottom);
                icon.setTint(h - s / 2 - (h - s) * frac <= bottom - sz / 2f ? Ui.PAPER : Ui.INK);
                icon.draw(cv);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
                getParent().requestDisallowInterceptTouchEvent(true);
                float frac = Math.max(0f, Math.min(1f, 1f - e.getY() / Math.max(1, getHeight())));
                int idx = min + Math.round(frac * (max - min));
                if (idx != cur) { cur = idx; invalidate(); }
                if (a == MotionEvent.ACTION_UP) onPick.pick(cur);
                return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
