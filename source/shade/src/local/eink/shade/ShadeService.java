package local.eink.shade;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * E-ink notification shade: replaces the system pull-down with a flat, outlined, animation-free panel.
 * Swipe down on the status bar to open. Back, swipe up, or a tap on empty space closes it.
 */
public class ShadeService extends AccessibilityService implements NotifService.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View trigger, panel, banner;
    private LinearLayout grid, extraRow, cards;
    private SeekBar slider;
    private boolean expanded;
    private final Set<String> expandedCards = new HashSet<>();
    private final Set<String> collapsedCards = new HashSet<>();
    private TextView offBtn, autoBtn; private int lastBright = 40;
    private ImageView headerChev;
    private final List<Tile> tiles = new ArrayList<>();
    private boolean torchOn;
    private String torchId;
    private int statusBarH, navBarH, screenW, screenH;
    private LockScreen lock;
    private VolumePanel volume;

    // ---------------------------------------------------------------- lifecycle

    static ShadeService instance;

    /** For the settings page: a full-size picture of a lock-screen style (null if the service isn't running). */
    static android.graphics.Bitmap lockPreview(String style) {
        ShadeService s = instance;
        if (s == null || s.lock == null) return null;
        s.measureBars();
        return s.lock.renderPreview(style, s.screenW, s.screenH);
    }

    @Override protected void onServiceConnected() {
        instance = this;
        wm = getSystemService(WindowManager.class);
        measureBars();
        NotifService.listener = this;
        try { PlatformControls.ensureNotificationAccess(this); }
        catch (SecurityException e) { android.util.Log.w("EinkShade", "notification access", e); }
        PlatformControls.setStatusBarDisabled(this, true);
        setupTorch();
        addTrigger();
        lock = new LockScreen(this, wm, (sbn, open, grouped) -> card(sbn, open, grouped ? CARD_LOCK_GROUP : CARD_LOCK));
        volume = new VolumePanel(this, wm, () -> einkClean(80));
        // service (re)started while the phone is already locked: cover the stock keyguard right away
        main.postDelayed(() -> {
            android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
            if (lock != null && km != null && km.isKeyguardLocked()) lock.showIfLocked(screenW, screenH);
        }, 800);
        refreshWeather();
        scheduleLockPage(3000);
        // The keyguard reports "locked" as the phone starts going to sleep, ~0.5 s before ACTION_SCREEN_OFF. Showing our
        // page then gets it drawn before the panel freezes, so the stock keyguard/dream clock never shows first.
        try {
            getSystemService(android.app.KeyguardManager.class).addKeyguardLockedStateListener(getMainExecutor(), keyguardLocked);
        } catch (SecurityException e) { android.util.Log.w("EinkShade", "keyguard state listener", e); }
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_DREAMING_STARTED);
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS); // Home / Recents never reach the panel's key handler
        registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED);
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                measureBars();
                if (i.getBooleanExtra("hide", false)) { if (lock != null) lock.hide(); return; }
                closePanel(false);
                String st = i.getStringExtra("style");
                if (st != null) LockStyles.prefs(c).edit().putString("lock_style", st).apply();
                for (String k : new String[]{"lock_clock_face", "lock_notifs", "lock_unlock", "lock_signature", "lock_todo_items"}) {
                    String v = i.getStringExtra(k);
                    if (v != null) LockStyles.prefs(c).edit().putString(k, k.equals("lock_todo_items") ? v.replace(";", "\n") : v).apply();
                }
                if (lock != null) lock.preview(screenW, screenH);
            }
        }, new IntentFilter("local.eink.shade.PREVIEW_LOCK"), Context.RECEIVER_EXPORTED);
        // reading lock screen: the e-ink controller's "Next / Previous page" button actions (--ei d 1|-1)
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { if (lock != null) lock.turnPage(i.getIntExtra("d", 1)); }
        }, new IntentFilter("local.eink.shade.LOCK_PAGE"), Context.RECEIVER_EXPORTED);
    }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        PlatformControls.setStatusBarDisabled(this, false);
        removeAll();
        if (volume != null) volume.hide();
        NotifService.listener = null;
        main.removeCallbacks(renderLockPage);
        LockPageProvider.file(this).delete(); // no stale page for the dream while the shade isn't running
        try { getSystemService(android.app.KeyguardManager.class).removeKeyguardLockedStateListener(keyguardLocked); }
        catch (Exception ignored) {}
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        PlatformControls.setStatusBarDisabled(this, false);
        instance = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- lock page picture for the sleep dream

    private final Runnable renderLockPage = this::renderLockPage;
    private int tickCount;

    /** Draw the next lock page off-screen and save it for LockPageProvider (PNG written off the main thread). */
    private void renderLockPage() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (lock == null || lock.isShowing() || pm == null || !pm.isInteractive()) return;
        measureBars();
        final android.graphics.Bitmap b = lock.renderNextPage(screenW, screenH);
        if (b == null) return;
        final java.io.File out = LockPageProvider.file(this);
        new Thread(() -> {
            java.io.File tmp = new java.io.File(out.getPath() + ".tmp");
            try (java.io.FileOutputStream s = new java.io.FileOutputStream(tmp)) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, s);
            } catch (java.io.IOException e) { tmp.delete(); return; }
            if (!tmp.renameTo(out)) tmp.delete();
        }).start();
    }

    private void scheduleLockPage(long delay) {
        main.removeCallbacks(renderLockPage);
        main.postDelayed(renderLockPage, delay);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // "Current interface" style: keep a recent picture of the screen. It can't be taken when the screen turns
        // off: the sleep dream is already drawn by the time SCREEN_OFF arrives, so the picture would be the dream.
        // Taken shortly after each app change, scroll or content change instead (at most every few seconds while
        // content keeps changing).
        if (lock == null || lock.isShowing() || !"current".equals(LockStyles.style(this))) return;
        long sinceLast = android.os.SystemClock.uptimeMillis() - lastCapture;
        if (main.hasCallbacks(preCapture) && sinceLast > 6000) return; // keeps changing: let the pending one run
        main.removeCallbacks(preCapture);
        main.postDelayed(preCapture,
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ? 1500 : 2000);
    }

    private final Runnable preCapture = this::capture;
    private long lastCapture;

    private void capture() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (panel != null || (pm != null && !pm.isInteractive()) || (km != null && km.isKeyguardLocked())
                || (lock != null && lock.isShowing())
                || android.os.SystemClock.uptimeMillis() - lastCapture < 1100) return;
        lastCapture = android.os.SystemClock.uptimeMillis();
        saveScreenshot(LockStyles.currentFile(this), null);
    }

    /** Screenshot to a PNG, written next to the old one and swapped in, so a lock that starts meanwhile never reads half a file. */
    private void saveScreenshot(java.io.File out, Runnable onSaved) {
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                    r.getHardwareBuffer().close();
                    if (hw == null) return;
                    android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                    hw.recycle();
                    new Thread(() -> {
                        java.io.File tmp = new java.io.File(out.getPath() + ".tmp");
                        try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
                            sw.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
                        } catch (Exception e) { tmp.delete(); return; }
                        finally { sw.recycle(); }
                        if (tmp.renameTo(out) && onSaved != null) main.post(onSaved);
                    }).start();
                }
                @Override public void onFailure(int code) {}
            });
        } catch (Exception ignored) {}
    }

    /** Long-press on the Screenshot tile: pin what's on screen to the lock screen until it's unpinned in Lock screen settings. */
    private void pinScreen() {
        closePanel(false);
        main.postDelayed(() -> saveScreenshot(LockStyles.pinnedFile(this), () -> {
            LockStyles.prefs(this).edit().putBoolean("lock_pinned", true).apply();
            android.widget.Toast.makeText(this, "Pinned to the lock screen. Unpin in Lock screen settings.", android.widget.Toast.LENGTH_LONG).show();
        }), 700);
    }
    @Override public void onInterrupt() {}

    private final android.app.KeyguardManager.KeyguardLockedStateListener keyguardLocked = locked -> {
        if (!locked) return;
        android.util.Log.i("EinkLock", "keyguard locked signal");
        onLocking();
    };

    /** Keyguard locked (going to sleep) or screen off: close our panels and cover the keyguard with our lock page. */
    private void onLocking() {
        if (lock == null) return;
        main.removeCallbacks(preCapture);
        closePanel(false); hideBanner();
        if (volume != null) volume.hide();
        measureBars();
        lock.showIfLocked(screenW, screenH);
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a) || Intent.ACTION_DREAMING_STARTED.equals(a)) {
                // the sleep-picture dream starts as the phone goes to sleep, before SCREEN_OFF
                android.util.Log.i("EinkLock", a + " signal");
                onLocking();
            } else if (Intent.ACTION_TIME_TICK.equals(a)) {
                if (++tickCount % 5 == 0) scheduleLockPage(0); // keeps the dream's picture of the clock roughly current
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                lock.hide();
                scheduleLockPage(2000);
                StatusBarNotification open = lock.takePendingOpen();
                if (open != null && open.getNotification().contentIntent != null) {
                    send(open.getNotification().contentIntent);
                    if ((open.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) NotifService.dismiss(open);
                }
                einkClean(250);
                // home-screen widgets: today's agenda and the weather (fetched again when over half an hour old)
                CalendarWidget.updateAll(ShadeService.this);
                WeatherWidget.updateAll(ShadeService.this);
                refreshWeather();
            } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(a)) {
                String why = i.getStringExtra("reason");
                if ("homekey".equals(why) || "recentapps".equals(why)) closePanel(true);
            } else { measureBars(); }
        }
    };

    private void measureBars() {
        WindowMetrics m = wm.getMaximumWindowMetrics();
        Insets in = m.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
        screenW = m.getBounds().width();
        screenH = m.getBounds().height();
        statusBarH = Math.max(in.top, Ui.dp(this, 24));
        navBarH = in.bottom;
    }

    /** Volume keys (the service asks for key filtering): the e-ink volume panel takes them unless it's switched off. */
    @Override protected boolean onKeyEvent(KeyEvent e) {
        return volume != null && volume.onKeyEvent(e);
    }

    private void removeAll() {
        for (View v : new View[] {trigger, panel, banner}) {
            if (v != null) try { wm.removeView(v); } catch (Exception ignored) {}
        }
        trigger = panel = banner = null;
    }

    // ---------------------------------------------------------------- trigger strip over the status bar

    private void addTrigger() {
        View v = new View(this);
        v.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            boolean fired;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downY = e.getRawY(); fired = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!fired && e.getRawY() - downY > Ui.dp(ShadeService.this, 12)) { fired = true; openPanel(); }
                        return true;
                    default: return true;
                }
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, statusBarH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("EinkShadeTrigger");
        wm.addView(v, lp);
        trigger = v;
    }

    // ---------------------------------------------------------------- panel

    private void openPanel() {
        if (panel != null) return;
        hideBanner();
        measureBars();
        refreshWeather();
        FrameLayout root = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_HOME) {
                    if (e.getAction() == KeyEvent.ACTION_UP) closePanel(true);
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setBackgroundColor(Ui.PAPER);
        root.setFocusableInTouchMode(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        col.setPadding(pad, Ui.dp(this, 6), pad, 0);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        col.addView(statusLine());
        col.addView(header());
        tiles.clear();
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        buildTiles();
        col.addView(grid);
        col.addView(handle());
        if ("single".equals(prefs().getString("bright_mode", "dual"))) col.addView(brightnessRow());
        else col.addView(dualBrightness());
        if (prefs().getBoolean("light_presets", true)
                && ("single".equals(prefs().getString("bright_mode", "dual")) || lightExpanded())) col.addView(presetRow());

        // keep swiping up past the end of the list (or on a list too short to scroll) to close the shade
        ScrollView sv = new ScrollView(this) {
            float lastY, overscroll;
            @Override public boolean dispatchTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastY = e.getRawY(); overscroll = 0; break;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = lastY - e.getRawY();           // > 0 while the finger moves up
                        lastY = e.getRawY();
                        if (dy > 0 && !canScrollVertically(1)) overscroll += dy;
                        else if (dy < 0) overscroll = Math.max(0, overscroll + dy);
                        if (overscroll > Ui.dp(ShadeService.this, 90)) {
                            overscroll = 0;
                            closePanel(true);
                            return true;
                        }
                        break;
                    }
                }
                return super.dispatchTouchEvent(e);
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFillViewport(true);   // empty area below the cards still closes the shade, but no scrolling past them
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        cards.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 90));
        FrameLayout scrollBody = new FrameLayout(this);
        scrollBody.addView(cards, new FrameLayout.LayoutParams(-1, -2));

        scrollBody.setOnClickListener(v -> closePanel(true));
        sv.addView(scrollBody, new ViewGroup.LayoutParams(-1, -2));
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        fillCards();

        // swipe up anywhere above the list closes
        View.OnTouchListener swipeUp = new View.OnTouchListener() {
            float downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) downY = e.getRawY();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && downY - e.getRawY() > Ui.dp(ShadeService.this, 60)) {
                    closePanel(true);
                    return true;
                }
                return false;
            }
        };
        col.getChildAt(0).setOnTouchListener(swipeUp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, screenH - navBarH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP;
        lp.y = 0;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        lp.setTitle("EinkShadePanel");
        wm.addView(root, lp);
        panel = root;
        root.requestFocus();
        refreshTiles();
    }

    private void closePanel(boolean clean) {
        if (panel == null) return;
        try { wm.removeView(panel); } catch (Exception ignored) {}
        panel = null; grid = null; cards = null; extraRow = null; slider = null; coolBar = null; warmBar = null;
        expanded = false;
        if (clean) einkClean(200);
    }

    /**
     * One full clean (EINK_FORCE_CLEAR). Only when a surface closes over the app (shade, volume panel, peek banner,
     * lock page): opening, expanding / collapsing and light changes don't clean, the driver's auto clean in the fast
     * modes handles their small changes.
     */
    private void einkClean(long delay) {
        main.postDelayed(() -> sendBroadcast(new Intent("EINK_FORCE_CLEAR")), delay);
    }

    /** Our own status line (the system one can't be recolored over this window): carrier · Wi-Fi … battery. */
    private View statusLine() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        String carrier = "";
        try {
            android.telephony.TelephonyManager tm = getSystemService(android.telephony.TelephonyManager.class);
            carrier = tm.getNetworkOperatorName();
        } catch (Exception ignored) {}
        if (TextUtils.isEmpty(carrier)) carrier = "No service";
        TextView left = Ui.text(this, carrier, 13, Ui.INK);
        row.addView(left);
        WifiManager wifi = getSystemService(WifiManager.class);
        if (wifi != null && wifi.isWifiEnabled()) {
            ImageView w = Ui.icon(this, R.drawable.i_wifi, Ui.INK, 14);
            LinearLayout.LayoutParams wl = (LinearLayout.LayoutParams) w.getLayoutParams();
            wl.leftMargin = Ui.dp(this, 8);
            row.addView(w);
        }
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
        int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = bm.isCharging();
        row.addView(Ui.text(this, (charging ? "⚡" : "") + pct + "%", 13, Ui.INK));
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, statusBarH - Ui.dp(this, 6)));
        return row;
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 2), Ui.dp(this, 10));
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(40);
        time.setTextColor(Ui.INK);
        time.setIncludeFontPadding(false);
        row.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEE d MMM");
        date.setFormat24Hour("EEE d MMM");
        date.setTextSize(17);
        date.setTextColor(Ui.INK);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, -2, 1f);
        dlp.leftMargin = Ui.dp(this, 16);
        row.addView(date, dlp);
        // expand / collapse every expandable notification
        headerChev = Ui.icon(this, expandDefault() ? R.drawable.i_less : R.drawable.i_more, Ui.INK, 30);
        headerChev.setContentDescription("Expand or collapse all notifications");
        headerChev.setOnClickListener(v -> {
            setExpandDefault(!expandDefault());
            collapsedCards.clear(); expandedCards.clear();
            fillCards();
        });
        row.addView(headerChev);
        View gap2 = new View(this);
        row.addView(gap2, new LinearLayout.LayoutParams(Ui.dp(this, 24), 1));
        ImageView gear = Ui.icon(this, R.drawable.i_settings, Ui.INK, 30);
        gear.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        row.addView(gear);
        return row;
    }

    private View handle() {
        FrameLayout f = new FrameLayout(this);
        View bar = new View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Ui.INK);
        g.setCornerRadius(Ui.dp(this, 2));
        bar.setBackground(g);
        f.addView(bar, new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 4), Gravity.CENTER));
        f.setOnClickListener(v -> toggleExpanded());
        f.setOnTouchListener(new View.OnTouchListener() {
            float downY; boolean longFired;
            final Runnable longPress = () -> {};
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downY = e.getRawY(); longFired = false; v.postDelayed(longPress, 600); }
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(e.getRawY() - downY) > Ui.dp(ShadeService.this, 8)) v.removeCallbacks(longPress);
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) v.removeCallbacks(longPress);
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    v.removeCallbacks(longPress);
                    if (longFired) return true;
                    float dy = e.getRawY() - downY;
                    if (dy > Ui.dp(ShadeService.this, 20) && !expanded) toggleExpanded();
                    else if (dy < -Ui.dp(ShadeService.this, 20) && expanded) toggleExpanded();
                    else if (Math.abs(dy) < Ui.dp(ShadeService.this, 8)) toggleExpanded();
                    return true;
                }
                return true;
            }
        });
        f.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 28)));
        return f;
    }

    private void toggleExpanded() {
        expanded = !expanded;
        if (extraRow != null) extraRow.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- brightness / front light

    private View brightnessRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        TextView label = Ui.text(this, "Bright\nness", 12, Ui.INK);
        label.setGravity(Gravity.CENTER);
        row.addView(label, new LinearLayout.LayoutParams(Ui.dp(this, 46), -2));
        row.addView(stepButton("−", -6));
        slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgressDrawable(sliderTrack());
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(Ui.PAPER);
        thumb.setStroke(Ui.dp(this, 2), Ui.INK);
        thumb.setSize(Ui.dp(this, 16), Ui.dp(this, 16));
        slider.setThumb(thumb);
        slider.setSplitTrack(false);
        slider.setProgress(readBrightnessPercent());
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) writeBrightnessPercent(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f);
        row.addView(slider, slp);
        row.addView(stepButton("+", 6));
        lastBright = prefs().getInt("last_bright", 40);
        offBtn = Ui.text(this, readBrightnessPercent() > 0 ? "Off" : "On", 17, Ui.INK);
        TextView off = offBtn;
        off.setGravity(Gravity.CENTER);
        off.setBackground(Ui.box(this, 8, false));
        off.setOnClickListener(v -> {
            int cur = slider.getProgress();
            if (cur > 0) {
                lastBright = cur; prefs().edit().putInt("last_bright", cur).apply();
                slider.setProgress(0); writeBrightnessPercent(0); off.setText("On");
            } else {
                int r = lastBright > 0 ? lastBright : 40;
                slider.setProgress(r); writeBrightnessPercent(r); off.setText("Off");
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 42));
        olp.leftMargin = Ui.dp(this, 14);
        row.addView(off, olp);
        return row;
    }

    // ---------------------------------------------------------------- cool + warm front light (via the e-ink controller)

    private SeekBar coolBar, warmBar;

    private static int rawToPct(int raw) { return (int) Math.round(Math.sqrt(Math.max(0, raw) / 254.0) * 100); }
    private static int pctToRaw(int p) { return (int) Math.round(254 * (p / 100.0) * (p / 100.0)); }
    private int lightLevel(String ch) { return Settings.Global.getInt(getContentResolver(), "eink_light_" + ch, 0); }

    private void sendLight(Intent i) { sendBroadcast(i.setAction("EINK_SET_LIGHT")); }

    private boolean lightExpanded() { return prefs().getBoolean("light_expanded", false); }

    /** Collapsed: just the Cool row and a chevron. Expanded: Cool + On, Warm + Auto, then the presets. */
    private View dualBrightness() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        boolean open = lightExpanded();
        coolBar = lightSlider();
        warmBar = open ? lightSlider() : null;
        offBtn = null; autoBtn = null;
        ImageView chev = Ui.icon(this, open ? R.drawable.i_less : R.drawable.i_more, Ui.INK, 30);
        chev.setOnClickListener(v -> {
            prefs().edit().putBoolean("light_expanded", !lightExpanded()).apply();
            closePanel(false); openPanel();
        });
        if (open) {
            offBtn = pillButton("Off", v -> {
                boolean on = lightLevel("cold") + lightLevel("warm") > 0;
                setAutoLight(false);
                sendLight(new Intent().putExtra("preset", on ? "off" : "on"));
                main.postDelayed(this::syncLightBars, 350);
            });
            autoBtn = pillButton("Auto", v -> setAutoLight(!autoLight()));
            box.addView(lightRow("Cool", coolBar, "cold", offBtn, chev));
            box.addView(lightRow("Warm", warmBar, "warm", autoBtn, null));
        } else {
            box.addView(lightRow("Cool", coolBar, "cold", null, chev));
        }
        syncLightBars();
        return box;
    }

    private TextView pillButton(String label, View.OnClickListener l) {
        TextView b = Ui.text(this, label, 17, Ui.INK);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.box(this, 8, false));
        b.setOnClickListener(l);
        return b;
    }

    private SeekBar lightSlider() {
        SeekBar b = new SeekBar(this);
        b.setMax(100);
        b.setProgressDrawable(sliderTrack());
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(Ui.PAPER); thumb.setStroke(Ui.dp(this, 2), Ui.INK);
        thumb.setSize(Ui.dp(this, 16), Ui.dp(this, 16));
        b.setThumb(thumb); b.setSplitTrack(false);
        return b;
    }

    /** One light channel: label, − slider +, then an optional button (On / Auto) and an optional chevron slot. */
    private View lightRow(String name, SeekBar bar, String channel, View button, View chev) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 1), 0, Ui.dp(this, 3));
        TextView label = Ui.text(this, name, 12, Ui.INK);
        label.setGravity(Gravity.CENTER);
        row.addView(label, new LinearLayout.LayoutParams(Ui.dp(this, 46), -2));
        TextView minus = Ui.text(this, "−", 20, Ui.INK), plus = Ui.text(this, "+", 20, Ui.INK);
        for (TextView b : new TextView[]{minus, plus}) {
            b.setGravity(Gravity.CENTER); b.setBackground(Ui.box(this, 6, false));
        }
        minus.setOnClickListener(v -> { setAutoLight(false); bar.setProgress(Math.max(0, bar.getProgress() - 6)); sendLight(new Intent().putExtra(channel, pctToRaw(bar.getProgress()))); });
        plus.setOnClickListener(v -> { setAutoLight(false); bar.setProgress(Math.min(100, bar.getProgress() + 6)); sendLight(new Intent().putExtra(channel, pctToRaw(bar.getProgress()))); });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        blp.leftMargin = Ui.dp(this, 6); blp.rightMargin = Ui.dp(this, 6);
        row.addView(minus, blp);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) sendLight(new Intent().putExtra(channel, pctToRaw(p))); }
            @Override public void onStartTrackingTouch(SeekBar s) { setAutoLight(false); }
            @Override public void onStopTrackingTouch(SeekBar s) { main.postDelayed(ShadeService.this::syncOffButton, 300); }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        plp.leftMargin = Ui.dp(this, 6); plp.rightMargin = Ui.dp(this, 6);
        row.addView(plus, plp);
        if (button != null) {
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 38));
            olp.leftMargin = Ui.dp(this, 14);
            row.addView(button, olp);
        }
        // the chevron column keeps Warm's Auto lined up under Cool's On
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 38));
        clp.leftMargin = Ui.dp(this, 4);
        row.addView(chev != null ? chev : new View(this), clp);
        if (chev instanceof ImageView) ((ImageView) chev).setScaleType(ImageView.ScaleType.CENTER);
        return row;
    }

    private void syncLightBars() {
        if (coolBar != null) coolBar.setProgress(rawToPct(lightLevel("cold")));
        if (warmBar != null) warmBar.setProgress(rawToPct(lightLevel("warm")));
        syncOffButton();
    }

    private void syncOffButton() {
        if (offBtn != null && coolBar != null) offBtn.setText(lightLevel("cold") + lightLevel("warm") > 0 ? "Off" : "On");
        if (autoBtn != null) {
            boolean a = autoLight();
            autoBtn.setBackground(Ui.box(this, 8, a));
            autoBtn.setTextColor(a ? Ui.PAPER : Ui.INK);
        }
    }

    // ---------------------------------------------------------------- auto: the phone's own adaptive brightness

    private boolean autoLight() {
        return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    /** Auto switches Android's adaptive brightness; a manual light change switches it back off. */
    private void setAutoLight(boolean on) {
        if (on == autoLight()) return;
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        syncOffButton();
    }

    private View presetRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 10));
        String[][] ps = {{"day", "Day"}, {"reading", "Read"}, {"evening", "Evening"}, {"bedtime", "Bedtime"}};
        for (int i = 0; i < ps.length; i++) {
            final String id = ps[i][0];
            TextView b = Ui.text(this, ps[i][1], 14, Ui.INK);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.box(this, 14, false));
            b.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            b.setOnClickListener(v -> {
                setAutoLight(false);
                sendLight(new Intent().putExtra("preset", id));
                main.postDelayed(this::syncLightBars, 350);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(this, 8);
            row.addView(b, lp);
        }
        return row;
    }

    private View stepButton(String sign, int delta) {
        TextView b = Ui.text(this, sign, 20, Ui.INK);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.box(this, 6, false));
        b.setOnClickListener(v -> {
            int p = Math.max(0, Math.min(100, slider.getProgress() + delta));
            slider.setProgress(p);
            writeBrightnessPercent(p);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        lp.leftMargin = Ui.dp(this, 6);
        lp.rightMargin = Ui.dp(this, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private Drawable sliderTrack() {
        android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
        line.setColor(Ui.INK);
        line.setSize(1, Ui.dp(this, 2));
        android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(line, 0, Ui.dp(this, 19), 0, Ui.dp(this, 19));
        return inset;
    }

    private int readBrightnessPercent() {
        int b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 0);
        return (int) Math.round(Math.sqrt(b / 255.0) * 100);
    }

    private void writeBrightnessPercent(int p) {
        int b = (int) Math.round(255 * (p / 100.0) * (p / 100.0));
        PlatformControls.setBrightness(this, b);
    }

    private android.content.SharedPreferences prefs() { return getSharedPreferences("shade", MODE_PRIVATE); }
    private boolean expandDefault() { return prefs().getBoolean("expand_default", false); }
    private void setExpandDefault(boolean v) { prefs().edit().putBoolean("expand_default", v).apply(); }

    void refreshWeather() {
        if (!Weather.fresh(this)) Weather.refreshAsync(this, null);
    }

    boolean isExpandable(Notification n) {
        Bundle ex = n.extras;
        if (n.actions != null && n.actions.length > 0) return true;
        CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence txt = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (!TextUtils.isEmpty(big) && (TextUtils.isEmpty(txt) || big.length() > txt.length() + 2)) return true;
        CharSequence[] tl = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (tl != null && tl.length > 1) return true;
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 1) return true;
        if (ex.containsKey(Notification.EXTRA_PICTURE)) return true;
        return false;
    }

    // ---------------------------------------------------------------- tiles

    private final class Tile {
        final String id;
        final String baseLabel;
        int icon;
        boolean active;
        String label;
        final boolean detail;
        LinearLayout view;
        ImageView iconView;
        TextView labelView;
        Ui.CornerMark mark;

        Tile(String id, String label, int icon, boolean detail) {
            this.id = id; this.baseLabel = label; this.label = label; this.icon = icon; this.detail = detail;
        }

        View build() {
            LinearLayout v = new LinearLayout(ShadeService.this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setGravity(Gravity.CENTER_HORIZONTAL);
            v.setPadding(Ui.dp(ShadeService.this, 3), Ui.dp(ShadeService.this, 12), Ui.dp(ShadeService.this, 3), Ui.dp(ShadeService.this, 6));
            iconView = Ui.icon(ShadeService.this, icon, Ui.INK, 26);
            v.addView(iconView);
            labelView = Ui.text(ShadeService.this, label, 12, Ui.INK);
            labelView.setGravity(Gravity.CENTER);
            labelView.setMaxLines(2);
            labelView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 0, 1f);
            llp.topMargin = Ui.dp(ShadeService.this, 7);
            v.addView(labelView, llp);
            FrameLayout frame = new FrameLayout(ShadeService.this);
            frame.addView(v, new FrameLayout.LayoutParams(-1, -1));
            if (detail) {
                mark = new Ui.CornerMark(ShadeService.this);
                FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(Ui.dp(ShadeService.this, 7), Ui.dp(ShadeService.this, 7),
                        Gravity.BOTTOM | Gravity.END);
                mlp.rightMargin = Ui.dp(ShadeService.this, 6);
                mlp.bottomMargin = Ui.dp(ShadeService.this, 6);
                frame.addView(mark, mlp);
            }
            frame.setOnClickListener(x -> onTileClick(this));
            frame.setOnLongClickListener(x -> { onTileLongClick(this); return true; });
            view = v;
            frameOf.put(this, frame);
            apply();
            return frame;
        }

        void apply() {
            if (view == null) return;
            View frame = frameOf.get(this);
            frame.setBackground(Ui.box(ShadeService.this, 10, active));
            int fg = active ? Ui.PAPER : Ui.INK;
            Ui.tintIcon(iconView, icon, fg);
            labelView.setText(label);
            // one long word (a Wi-Fi name) would be broken mid-word over two lines; cut it with an ellipsis instead
            labelView.setMaxLines(label != null && !label.trim().matches("(?s).*\\s.*") ? 1 : 2);
            labelView.setTextColor(fg);
            if (mark != null) mark.setColor(fg);
        }
    }

    private final java.util.Map<Tile, View> frameOf = new java.util.HashMap<>();

    static final String[] DEFAULT_TILES = {"wifi", "data", "ringer", "rotate", "flashlight",
            "airplane", "hotspot", "bluetooth", "battery", "location",
            "screenshot", "refresh", "dnd", "nfc", "settings"};
    static final String[] TILE_NAMES = {"Wi-Fi", "Data connection", "Ringer", "Auto-rotate", "Flashlight",
            "Airplane mode", "Hotspot", "Bluetooth", "Power saving", "Location",
            "Screenshot", "Refresh screen", "Do not disturb", "NFC", "Settings"};

    /** Saved tile order (first 10 = the two rows always shown); falls back to the default for anything missing. */
    static String[] tileOrder(Context c) {
        String saved = c.getSharedPreferences("shade", MODE_PRIVATE).getString("tile_order", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.List<String> all = java.util.Arrays.asList(DEFAULT_TILES);
        for (String id : saved.split(",")) if (all.contains(id) && !out.contains(id)) out.add(id);
        for (String id : DEFAULT_TILES) if (!out.contains(id)) out.add(id);
        return out.toArray(new String[0]);
    }

    static String tileName(String id) {
        for (int i = 0; i < DEFAULT_TILES.length; i++) if (DEFAULT_TILES[i].equals(id)) return TILE_NAMES[i];
        return id;
    }

    private void buildTiles() {
        frameOf.clear();
        String[] order = tileOrder(this);
        String[][] rows = new String[3][5];
        for (int k = 0; k < 15; k++) rows[k / 5][k % 5] = order[k];
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 84));
            rlp.bottomMargin = Ui.dp(this, 10);
            for (int c = 0; c < rows[r].length; c++) {
                Tile t = make(rows[r][c]);
                tiles.add(t);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (c > 0) lp.leftMargin = Ui.dp(this, 9);
                row.addView(t.build(), lp);
            }
            grid.addView(row, rlp);
            if (r == 2) { extraRow = row; row.setVisibility(View.GONE); }
        }
    }

    private Tile make(String id) {
        switch (id) {
            case "wifi": return new Tile(id, "Wi-Fi", R.drawable.i_wifi, true);
            case "data": return new Tile(id, "Data\nConnection", R.drawable.i_data, true);
            case "ringer": return new Tile(id, "Ringer", R.drawable.i_ringer, false);
            case "rotate": return new Tile(id, "Auto-rotate", R.drawable.i_rotate, false);
            case "flashlight": return new Tile(id, "Flashlight", R.drawable.i_flashlight, false);
            case "airplane": return new Tile(id, "Airplane\nmode", R.drawable.i_airplane, false);
            case "hotspot": return new Tile(id, "Hotspot", R.drawable.i_hotspot, true);
            case "bluetooth": return new Tile(id, "Bluetooth", R.drawable.i_bluetooth, true);
            case "battery": return new Tile(id, "Power\nsaving", R.drawable.i_battery, false);
            case "location": return new Tile(id, "Location", R.drawable.i_location, false);
            case "screenshot": return new Tile(id, "Screenshot", R.drawable.i_screenshot, false);
            case "refresh": return new Tile(id, "Refresh\nscreen", R.drawable.i_refresh, false);
            case "dnd": return new Tile(id, "Do not\ndisturb", R.drawable.i_dnd, false);
            case "nfc": return new Tile(id, "NFC", R.drawable.i_nfc, false);
            default: return new Tile(id, "Settings", R.drawable.i_settings, false);
        }
    }

    /** Reads every tile's state off the main thread, then repaints. */
    private void refreshTiles() {
        new Thread(() -> {
            final java.util.Map<String, Object[]> st = new java.util.HashMap<>();
            WifiManager wifi = getSystemService(WifiManager.class);
            boolean wifiOn = wifi != null && wifi.isWifiEnabled();
            String ssid = null;
            try {
                android.net.wifi.WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
                if (info != null && info.getNetworkId() >= 0) ssid = info.getSSID();
            } catch (SecurityException ignored) {}
            st.put("wifi", new Object[] {wifiOn, ssid != null ? ssid : "Wi-Fi"});
            st.put("data", new Object[] {Settings.Global.getInt(getContentResolver(), "mobile_data", 0) == 1, null});
            AudioManager am = getSystemService(AudioManager.class);
            int mode = am.getRingerMode();
            st.put("ringer", new Object[] {mode != AudioManager.RINGER_MODE_SILENT,
                    mode == AudioManager.RINGER_MODE_NORMAL ? "Ringer" : mode == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : "Silent",
                    mode == AudioManager.RINGER_MODE_NORMAL ? R.drawable.i_ringer : mode == AudioManager.RINGER_MODE_VIBRATE ? R.drawable.i_vibrate : R.drawable.i_silent});
            st.put("rotate", new Object[] {Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1, null});
            st.put("flashlight", new Object[] {torchOn, null});
            st.put("airplane", new Object[] {Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1, null});
            boolean ap = false;
            try { ap = (Boolean) WifiManager.class.getMethod("isWifiApEnabled").invoke(wifi); } catch (Exception ignored) {}
            st.put("hotspot", new Object[] {ap, null});
            boolean bt = false;
            try { bt = getSystemService(BluetoothManager.class).getAdapter().isEnabled(); } catch (Exception ignored) {}
            st.put("bluetooth", new Object[] {bt, null});
            st.put("battery", new Object[] {getSystemService(PowerManager.class).isPowerSaveMode(), null});
            st.put("location", new Object[] {getSystemService(LocationManager.class).isLocationEnabled(), null});
            NotificationManager nm = getSystemService(NotificationManager.class);
            st.put("dnd", new Object[] {nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL, null});
            boolean nfc = false;
            try {
                Object a = Class.forName("android.nfc.NfcAdapter").getMethod("getDefaultAdapter", Context.class).invoke(null, this);
                nfc = a != null && (Boolean) a.getClass().getMethod("isEnabled").invoke(a);
            } catch (Exception ignored) {}
            st.put("nfc", new Object[] {nfc, null});
            main.post(() -> {
                for (Tile t : tiles) {
                    Object[] s = st.get(t.id);
                    if (s == null) continue;
                    t.active = (Boolean) s[0];
                    t.label = s[1] != null ? (String) s[1] : t.baseLabel;
                    if (s.length > 2) t.icon = (Integer) s[2];
                    t.apply();
                }
            });
        }).start();
    }

    private void onTileClick(Tile t) {
        try {
            onTileClickUnchecked(t);
        } catch (SecurityException e) {
            // A missing permission must never take the whole shade down: report it and leave the tile as is.
            android.util.Log.w("EinkShade", "tile " + t.id + " failed", e);
            android.widget.Toast.makeText(this, t.id + ": permission missing", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void onTileClickUnchecked(Tile t) {
        switch (t.id) {
            case "wifi": PlatformControls.setWifiEnabled(this, !t.active); break;
            case "data": PlatformControls.setMobileData(this, !t.active); break;
            case "ringer": {
                AudioManager am = getSystemService(AudioManager.class);
                int m = am.getRingerMode();
                int next = m == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                        : m == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                PlatformControls.setRingerMode(am, next);
                break;
            }
            case "rotate":
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, t.active ? 0 : 1);
                break;
            case "flashlight":
                try { getSystemService(CameraManager.class).setTorchMode(torchId, !torchOn); } catch (Exception ignored) {}
                break;
            case "airplane": PlatformControls.setAirplaneMode(this, !t.active); break;
            case "hotspot": onTileLongClick(t); return;
            case "bluetooth": PlatformControls.setBluetooth(this, !t.active); break;
            case "battery": PlatformControls.setPowerSaveMode(this, !t.active); break;
            case "location": PlatformControls.setLocation(this, !t.active); break;
            case "screenshot":
                closePanel(false);
                main.postDelayed(() -> saveScreenshot(LockStyles.currentFile(this), null), 700);
                return;
            case "refresh": closePanel(true); return;
            case "dnd": PlatformControls.setDnd(this, !t.active); break;
            case "nfc": PlatformControls.setNfc(this, !t.active); break;
            case "settings": launch(new Intent(Settings.ACTION_SETTINGS)); return;
        }
        if (!t.id.equals("ringer")) { t.active = !t.active; t.apply(); }
        main.postDelayed(this::refreshTiles, 900);
        main.postDelayed(this::refreshTiles, 2500);
    }

    /** Long-click opens the corresponding system settings page. */
    private void onTileLongClick(Tile t) {
        if (t.id.equals("screenshot")) { pinScreen(); return; }
        String action;
        switch (t.id) {
            case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
            case "data": action = Settings.ACTION_DATA_ROAMING_SETTINGS; break;
            case "ringer": action = Settings.ACTION_SOUND_SETTINGS; break;
            case "rotate": action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "airplane": action = Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
            case "hotspot": action = "android.settings.TETHER_SETTINGS"; break;
            case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "battery": action = Settings.ACTION_BATTERY_SAVER_SETTINGS; break;
            case "location": action = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
            case "dnd": action = Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS; break;
            case "nfc": action = Settings.ACTION_NFC_SETTINGS; break;
            default: return;
        }
        launch(new Intent(action));
    }

    private void setupTorch() {
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            for (String id : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) { torchId = id; break; }
            }
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean enabled) {
                    if (id.equals(torchId)) torchOn = enabled;
                }
            }, main);
        } catch (Exception ignored) {}
    }

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
        }
        closePanel(false);
    }

    // ---------------------------------------------------------------- notifications

    @Override public void onNotificationsChanged() {
        if (cards != null) fillCards();
        if (lock != null) lock.refreshNotifications();
        scheduleLockPage(3000);
    }

    @Override public void onNewNotification(StatusBarNotification sbn) {
        if (panel != null) return;
        if (lock != null && lock.isShowing()) { lock.refreshNotifications(); return; }
        showBanner(sbn);
    }

    private void fillCards() {
        if (cards == null) return;
        cards.removeAllViews();
        int clearable = 0, expandableCount = 0;
        for (StatusBarNotification sbn : NotifService.visible()) {
            if (sbn.isClearable()) clearable++;
            boolean exp = isExpandable(sbn.getNotification());
            if (exp) expandableCount++;
            boolean open = exp && (expandDefault() ? !collapsedCards.contains(sbn.getKey())
                                                   : expandedCards.contains(sbn.getKey()));
            View card = card(sbn, open, CARD_PANEL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 9);
            cards.addView(card, lp);
        }
        // clear-all: the list's last row, centred under the last card (it never covers a card)
        if (clearable > 0) {
            ImageView trash = Ui.icon(this, R.drawable.i_delete, Ui.INK, 30);
            int tp = Ui.dp(this, 11);
            trash.setPadding(tp, tp, tp, tp);
            trash.setBackground(Ui.circle(this));
            trash.setContentDescription("Clear all");
            trash.setOnClickListener(v -> {
                for (StatusBarNotification sbn : NotifService.visible()) NotifService.hide(sbn);
                fillCards(); main.postDelayed(this::fillCards, 200);
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56));
            tlp.gravity = Gravity.CENTER_HORIZONTAL;
            tlp.topMargin = Ui.dp(this, 3);  // + the last card's 9 dp = 12 dp
            tlp.bottomMargin = Ui.dp(this, 14);
            cards.addView(trash, tlp);
        }
        if (headerChev != null) {
            headerChev.setVisibility(expandableCount > 0 ? View.VISIBLE : View.INVISIBLE);
            headerChev.setImageDrawable(Ui.icon(this, expandDefault() ? R.drawable.i_less : R.drawable.i_more, Ui.INK, 30).getDrawable());
        }
    }

    /** Where a notification card is shown: the open shade, a peek banner, the lock screen's list, or inside a lock-screen
     *  app group whose header already names the app (CARD_LOCK_GROUP: the card shows just the time). */
    static final int CARD_PANEL = 0, CARD_BANNER = 1, CARD_LOCK = 2, CARD_LOCK_GROUP = 3;

    View card(StatusBarNotification sbn, boolean open, int where) {
        final boolean inPanel = where == CARD_PANEL, banner = where == CARD_BANNER;
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.box(this, 12, false));
        int p = Ui.dp(this, 12);
        c.setPadding(p, Ui.dp(this, 9), Ui.dp(this, 8), Ui.dp(this, 11));

        // header: small icon · app · subtext · time  ⌄
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final boolean grouped = where == CARD_LOCK_GROUP;
        if (!grouped) {
            ImageView ic = new ImageView(this);
            try {
                Icon small = n.getSmallIcon();
                Drawable d = small != null ? small.loadDrawable(createPackageContext(sbn.getPackageName(), 0)) : null;
                if (d != null) { d.mutate().setTint(Ui.INK); ic.setImageDrawable(d); }
            } catch (Exception ignored) {}
            head.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
        }
        StringBuilder h = new StringBuilder(grouped ? "" : appName(sbn.getPackageName()));
        long when = n.when != 0 ? n.when : sbn.getPostTime();
        if (grouped) h.append(ago(when));
        else {
            CharSequence sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (!TextUtils.isEmpty(sub)) h.append(" • ").append(sub);
            if (n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true) || n.when == 0) h.append(" • ").append(ago(when));
        }
        TextView ht = Ui.text(this, h, grouped ? 14 : 15, grouped ? Ui.MUTED : Ui.INK);
        ht.setSingleLine(true);
        ht.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1f);
        hlp.leftMargin = grouped ? 0 : Ui.dp(this, 10);
        head.addView(ht, hlp);
        boolean expandable = isExpandable(n);
        final ImageView chev = expandable ? Ui.icon(this, open ? R.drawable.i_less : R.drawable.i_more, Ui.INK, 26) : null;
        if (chev != null) head.addView(chev);
        c.addView(head);

        // body lines
        List<CharSequence> lines = bodyLines(n, open);
        for (CharSequence l : lines) {
            TextView t = Ui.text(this, l, 17, Ui.INK);
            t.setMaxLines(open ? 6 : 1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 8);
            lp.rightMargin = Ui.dp(this, 6);
            c.addView(t, lp);
        }

        // actions when expanded
        if (open && n.actions != null && n.actions.length > 0) {
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            for (Notification.Action a : n.actions) {
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue; // inline reply not supported
                TextView b = Ui.text(this, a.title, 15, Ui.INK);
                b.setGravity(Gravity.CENTER);
                b.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
                b.setBackground(Ui.box(this, 8, false));
                b.setOnClickListener(v -> { send(a.actionIntent); if (inPanel) closePanel(true); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = Ui.dp(this, 8);
                acts.addView(b, lp);
            }
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
            alp.topMargin = Ui.dp(this, 10);
            c.addView(acts, alp);
        }

        if (chev != null) chev.setOnClickListener(v -> {
            if (!inPanel) return;
            String key = sbn.getKey();
            if (open) { if (expandDefault()) collapsedCards.add(key); else expandedCards.remove(key); }
            else      { if (expandDefault()) collapsedCards.remove(key); else expandedCards.add(key); }
            fillCards();
        });
        c.setOnClickListener(v -> {
            if (n.contentIntent != null) send(n.contentIntent);
            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) NotifService.dismiss(sbn);
            if (inPanel) closePanel(true); else hideBanner();
        });
        // swipe sideways to dismiss (card follows the finger); long-press for this notification's settings
        c.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; boolean dragging, longFired;
            final int slop = Ui.dp(ShadeService.this, 10);
            // long-press: that app's notification settings (not on the lock screen, where Settings would open unseen behind it)
            final Runnable longPress = () -> { if (where != CARD_LOCK && where != CARD_LOCK_GROUP) { longFired = true; openNotifSettings(sbn); } };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); dragging = false; longFired = false;
                        v.postDelayed(longPress, 550);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) v.removeCallbacks(longPress);
                        if (!dragging && Math.abs(dx) > 2 * slop && Math.abs(dx) > 2 * Math.abs(dy)) {
                            dragging = true;
                            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (dragging) {
                            v.setTranslationX(dx);
                            v.setAlpha(1f - 0.6f * Math.min(1f, Math.abs(dx) / Math.max(1, v.getWidth())));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        v.removeCallbacks(longPress);
                        if (longFired) return true;
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (dragging) {
                            if (Math.abs(dx) > v.getWidth() * 0.3f) {
                                v.setVisibility(View.INVISIBLE);
                                if (banner) hideBanner(); else dismissCard(sbn, v);
                            } else { v.setTranslationX(0); v.setAlpha(1f); }
                            return true;
                        }
                        if (banner && dy < -Ui.dp(ShadeService.this, 30)) { hideBanner(); return true; }
                        if (Math.abs(dx) < slop && Math.abs(dy) < slop) v.performClick();
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(longPress); v.setTranslationX(0); v.setAlpha(1f);
                        return true;
                    default: return true;
                }
            }
        });
        return c;
    }

    /** Swiped away in the shade or on the lock screen: gone from both lists at once, and its space closes up. */
    private void dismissCard(StatusBarNotification sbn, View card) {
        NotifService.hide(sbn);
        if (card != null && card.getParent() instanceof ViewGroup) ((ViewGroup) card.getParent()).removeView(card);
        fillCards();
        if (lock != null) lock.refreshNotifications();
        main.postDelayed(() -> { fillCards(); if (lock != null) lock.refreshNotifications(); }, 400);
    }

    private void openNotifSettings(StatusBarNotification sbn) {
        String ch = sbn.getNotification().getChannelId();
        Intent i = ch != null
                ? new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, ch)
                : new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        i.putExtra(Settings.EXTRA_APP_PACKAGE, sbn.getPackageName());
        launch(i);
    }

    private List<CharSequence> bodyLines(Notification n, boolean open) {
        Bundle ex = n.extras;
        List<CharSequence> out = new ArrayList<>();
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0) {
            int from = Math.max(0, msgs.length - (open ? 8 : 2));
            for (int i = from; i < msgs.length; i++) {
                if (!(msgs[i] instanceof Bundle)) continue;
                Bundle b = (Bundle) msgs[i];
                CharSequence sender = b.getCharSequence("sender");
                if (sender == null) {
                    Object person = b.getParcelable("sender_person");
                    if (person instanceof android.app.Person) sender = ((android.app.Person) person).getName();
                }
                CharSequence text = b.getCharSequence("text");
                out.add(sender != null ? TextUtils.concat(bold(sender), " ", text) : text);
            }
            return out;
        }
        CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            for (int i = 0; i < Math.min(lines.length, open ? 8 : 2); i++) out.add(lines[i]);
            return out;
        }
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = open && ex.getCharSequence(Notification.EXTRA_BIG_TEXT) != null
                ? ex.getCharSequence(Notification.EXTRA_BIG_TEXT) : ex.getCharSequence(Notification.EXTRA_TEXT);
        if (!TextUtils.isEmpty(title)) out.add(title);
        if (!TextUtils.isEmpty(text)) out.add(text);
        return out;
    }

    private CharSequence bold(CharSequence s) {
        android.text.SpannableString sp = new android.text.SpannableString(s);
        sp.setSpan(new android.text.style.StyleSpan(Typeface.NORMAL), 0, sp.length(), 0);
        return sp;
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) { return pkg; }
    }

    private static String ago(long when) {
        long d = System.currentTimeMillis() - when;
        if (d < 60_000) return "now";
        if (d < 3_600_000) return (d / 60_000) + "m";
        if (d < 86_400_000) return (d / 3_600_000) + "h";
        return (d / 86_400_000) + "d";
    }

    private void send(PendingIntent pi) {
        if (pi == null) return;
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            pi.send(this, 0, null, null, null, null, o.toBundle());
        } catch (Exception e) {
            try { pi.send(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- heads-up banner

    private final Runnable hideBannerRunnable = this::hideBanner;

    private void showBanner(StatusBarNotification sbn) {
        hideBanner();
        FrameLayout f = new FrameLayout(this);
        f.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        f.addView(card(sbn, false, CARD_BANNER), new FrameLayout.LayoutParams(-1, -2));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = statusBarH;
        lp.windowAnimations = 0;
        lp.setTitle("EinkShadeBanner");
        wm.addView(f, lp);
        banner = f;
        main.postDelayed(hideBannerRunnable, 6000);
    }

    private void hideBanner() {
        main.removeCallbacks(hideBannerRunnable);
        if (banner == null) return;
        try { wm.removeView(banner); } catch (Exception ignored) {}
        banner = null;
        einkClean(80);
    }
}
