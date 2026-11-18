package local.eink.shade;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.app.Notification;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Hisense-style e-ink lock screen drawn over the system keyguard:
 * framed picture, large clock + date, notifications as a collapsed accordion, "Slide up to unlock".
 * The real keyguard stays underneath, so fingerprint unlock and the PIN pad keep working.
 */
final class LockScreen {
    interface CardFactory { View card(StatusBarNotification sbn, boolean open, boolean grouped); }

    static final String USER_PICTURE = "/sdcard/Pictures/lockscreen.png";
    static final String MODE_FILE = "/sdcard/lockscreen_mode.txt";          // legacy; migrated into LockStyles prefs

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final CardFactory cards;
    private FrameLayout root;
    private LinearLayout accordion;
    private boolean expanded;
    private int width, height;
    private TextView weatherView;

    LockScreen(AccessibilityService svc, WindowManager wm, CardFactory cards) {
        this.svc = svc; this.wm = wm; this.cards = cards;
    }

    boolean isShowing() { return root != null; }

    /** Show only when the device is actually locked with a secure lock (PIN/fingerprint). */
    void showIfLocked(int screenW, int screenH) {
        KeyguardManager km = svc.getSystemService(KeyguardManager.class);
        if (km == null || !km.isDeviceSecure()) return;
        if (root != null) {
            // already up (going to sleep again): close an open app group / list / PIN pad, so the page sleeps
            // and wakes in the quality mode instead of staying in Extreme
            if (unlocking || (artOpen == null && !expanded && pinPad == null)) return;
            artOpen = null; expanded = false;
            if (pinPad != null) { root.removeView(pinPad); pinPad = null; pin.setLength(0); pending = null; }
            populate();
            updateEinkMode();
            return;
        }
        width = screenW; height = screenH;
        expanded = false; rotated = false;
        build();
        android.util.Log.i("EinkLock", "lock page shown");
    }

    /** The page the next lock will show (same style choice as build()), drawn off-screen; null while the lock is up. */
    android.graphics.Bitmap renderNextPage(int w, int h) {
        String st = LockStyles.style(svc);
        if (LockStyles.pinned(svc)) st = "pinned";
        return renderPreview(st, w, h);
    }

    /** Show the page now even though the phone is unlocked (settings "Preview"); unlocking just closes it. */
    void preview(int screenW, int screenH) {
        hide();
        width = screenW; height = screenH;
        expanded = false; rotated = false;
        build();
        clean();
    }

    /** Draw a style off-screen at full size (for the settings page). Returns null while the lock screen is up. */
    android.graphics.Bitmap renderPreview(String st, int w, int h) {
        if (root != null) return null;
        android.media.session.MediaController m = music;
        music = null;
        style = st; width = w; height = h; expanded = false; rotated = false;
        root = new FrameLayout(svc);
        root.setBackgroundColor(Ui.PAPER);
        try {
            if (style.equals("current") && !LockStyles.currentFile(svc).canRead()) style = "window";
            populate();
            fillClocks(root);
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, w, h);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(b));
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            root = null; accordion = null; scroll = null; todoBox = null; weatherView = null; music = m;
            artStage = null; artRow = null; artOverlay = null; artOverlayScroll = null; artCaption = null; artOpen = null;
        }
    }

    /** TextClocks only fill themselves in once attached to a window; set their text by hand off-screen. */
    private void fillClocks(View v) {
        if (v instanceof TextClock) {
            TextClock t = (TextClock) v;
            CharSequence f = android.text.format.DateFormat.is24HourFormat(svc) ? t.getFormat24Hour() : t.getFormat12Hour();
            if (f != null) t.setText(android.text.format.DateFormat.format(f, System.currentTimeMillis()));
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fillClocks(g.getChildAt(i));
        }
    }

    void hide() {
        pinPad = null; pin.setLength(0); unlocking = false;
        if (root == null) return;
        try { wm.removeView(root); } catch (Exception ignored) {}
        root = null; rootLp = null; accordion = null; todoBox = null;
        artStage = null; artRow = null; artOverlay = null; artOverlayScroll = null; artCaption = null; artOpen = null;
        unwatchMusic();
        einkMode(-1);
        // move on to the next word / poem / painting now (not at lock time), so the picture the sleep dream draws at the
        // next lock is that page; the painting is decoded off the main thread meanwhile
        advanceOnLock();
        if ("art".equals(LockStyles.style(svc))) {
            final File next = ArtCover.current(svc);
            if (next != null) new Thread(() -> artBitmap(next)).start();
        }
    }

    private static File cachedArt;
    private static long cachedArtTime;
    private static android.graphics.Bitmap cachedArtBitmap;

    /** The painting's bitmap, decoded once per file (decoding took ~0.15 s, which the lock page can't spare). */
    private static synchronized android.graphics.Bitmap artBitmap(File f) {
        if (f.equals(cachedArt) && f.lastModified() == cachedArtTime && cachedArtBitmap != null) return cachedArtBitmap;
        cachedArtBitmap = BitmapFactory.decodeFile(f.getAbsolutePath());
        cachedArt = f; cachedArtTime = f.lastModified();
        return cachedArtBitmap;
    }

    void refreshNotifications() {
        if (artRow != null) fillArtNotifs();
        else if (accordion != null) fillAccordion();
    }

    private int dp(float v) { return Ui.dp(svc, v); }

    private String style;
    private WindowManager.LayoutParams rootLp;
    private boolean rotated;
    private int contentIdx;

    /** Counts lock pages, so a delayed fallback from one unlock attempt never acts on a later lock page. */
    private int pageNo;

    private void build() {
        pageNo++;
        style = LockStyles.style(svc);
        if (style.equals("current") && !LockStyles.currentFile(svc).canRead()) style = "window";
        if (LockStyles.pinned(svc)) style = "pinned";
        if (style.equals("art")) ArtCover.ensure(svc, () -> root_post(() -> { if (root != null && "art".equals(style)) { populate(); clean(); } }, 0));
        watchMusic();
        final String unlockMode = LockStyles.unlock(svc);
        final boolean slideOk = !unlockMode.equals("longpress");
        final boolean longOk = !unlockMode.equals("slide");
        final android.view.GestureDetector gd = new android.view.GestureDetector(svc, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { onDoubleTapContent(); return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                // art: a tap on the painting closes an open app's notifications
                if (artOpen != null && pinPad == null) { artOpen = null; fillArtNotifs(); return true; }
                if ("book".equals(style) && music == null && pinPad == null) turnPage(e.getRawX() < width / 3f ? -1 : 1);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) { if (longOk && pinPad == null) revealKeyguard(); }
        });
        root = new FrameLayout(svc) {
            float downY;
            boolean fromBottom;
            @Override public boolean onInterceptTouchEvent(MotionEvent e) {
                if (pinPad != null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        fromBottom = downY > height * 0.82f;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        // collapsed: swipe up anywhere; a list open: swipe up from the bottom strip (the list scrolls)
                        if (slideOk && downY - e.getRawY() > dp(70) && ((!expanded && artOpen == null) || fromBottom)) {
                            revealKeyguard();
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        };
        root.setBackgroundColor(Ui.PAPER);
        populate();

        root.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (pinPad != null) return true;
                gd.onTouchEvent(e);
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) downY = e.getRawY();
                if (slideOk && e.getActionMasked() == MotionEvent.ACTION_UP && downY - e.getRawY() > dp(80)) revealKeyguard();
                return true;
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP;
        lp.windowAnimations = 0;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("EinkLockScreen");
        wm.addView(root, lp);
        rootLp = lp;
        lastEinkMode = -2;
        updateEinkMode();
    }

    /** (Re)draw the page for the current style into root. */
    private void populate() {
        root.removeAllViews();
        accordion = null; scroll = null; weatherView = null;
        artStage = null; artRow = null; artOverlay = null; artOverlayScroll = null; artCaption = null;
        String chosen = style;
        if (music != null) style = "music";
        try { populateStyle(); } finally { style = chosen; }
    }

    private void populateStyle() {
        if (style.equals("blank")) return;
        boolean clock = LockStyles.showClock(svc);
        String notifMode = LockStyles.notifs(svc);

        if (style.equals("wallpaper") || style.equals("current") || style.equals("pinned")) {
            ImageView bg = new ImageView(svc);
            bg.setScaleType(style.equals("wallpaper") ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_XY);
            File f = style.equals("current") ? LockStyles.currentFile(svc) : style.equals("pinned") ? LockStyles.pinnedFile(svc) : LockStyles.pictureFile(svc);
            if (f.canRead()) bg.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
            else { bg.setImageResource(R.drawable.art_branch); bg.setScaleType(ImageView.ScaleType.FIT_CENTER); bg.setPadding(dp(40), dp(40), dp(40), dp(40)); }
            root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(38), dp(22), dp(14));

        if (style.equals("alarm") && rotated) {
            // turned sideways: lay the page out in a height x width box, rotated 90 degrees
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(height, width, Gravity.CENTER);
            col.setRotation(90);
            root.addView(col, rl);
        } else {
            root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }

        switch (style) {
            case "wallpaper": {
                if (clock) col.addView(onPaper(clockBlock(false)), fill(0));
                col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                break;
            }
            case "current":
            case "pinned": {
                col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                if (clock) col.addView(onPaper(clockRow()), fill(0));
                break;
            }
            case "book": {
                if (clock) col.addView(clockRow(), fill(0));
                String text = LockStyles.bookText(svc);
                int pos = Math.max(0, Math.min(prefs().getInt("lock_book_pos", 0), text.length()));
                BookPage page = new BookPage(text, pos);
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1f); bl.topMargin = dp(18);
                col.addView(page, bl);
                int pct = text.isEmpty() ? 100 : Math.round(100f * pos / text.length());
                col.addView(footnote(LockStyles.bookTitle(svc) + "  \u00B7  " + pct + "%  \u00B7  tap right: next, left: back"), fill(dp(8)));
                break;
            }
            case "window": {
                FrameLayout frame = new FrameLayout(svc);
                android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
                border.setColor(Ui.PAPER); border.setCornerRadius(dp(8)); border.setStroke(dp(1.5f), Ui.INK);
                frame.setBackground(border);
                frame.setPadding(dp(6), dp(6), dp(6), dp(6));
                ImageView pic = new ImageView(svc);
                File user = LockStyles.pictureFile(svc);
                if (user.canRead()) {
                    pic.setImageBitmap(BitmapFactory.decodeFile(user.getAbsolutePath()));
                    pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                } else {
                    pic.setImageResource(R.drawable.art_branch);
                    pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    pic.setPadding(dp(18), dp(18), dp(18), dp(18));
                }
                frame.addView(pic, new FrameLayout.LayoutParams(-1, -1));
                col.addView(frame, new LinearLayout.LayoutParams(-1, (int) (height * (clock ? 0.36f : 0.60f))));
                if (clock) col.addView(clockBlock(false), fill(dp(26)));
                col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                break;
            }
            case "words": {
                if (clock) col.addView(clockRow(), fill(0));
                String[][] w = LockStyles.words();
                int i = Math.floorMod(prefs().getInt("lock_word_idx", 0), w.length);
                col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                TextView word = Ui.text(svc, w[i][0], 40, Ui.INK);
                word.setTypeface(Typeface.create("serif", Typeface.BOLD));
                col.addView(word, wrap(0));
                if (!w[i][1].isEmpty()) {
                    TextView pos = Ui.text(svc, w[i][1], 16, Ui.MUTED);
                    pos.setTypeface(Typeface.create("serif", Typeface.ITALIC));
                    col.addView(pos, wrap(dp(8)));
                }
                View rule = new View(svc); rule.setBackgroundColor(Ui.INK);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(48), dp(2)); rl.topMargin = dp(18);
                col.addView(rule, rl);
                TextView def = Ui.text(svc, w[i][2], 21, Ui.INK);
                def.setLineSpacing(0, 1.2f);
                col.addView(def, wrap(dp(18)));
                if (!w[i][3].isEmpty()) {
                    TextView ex = Ui.text(svc, "“" + w[i][3] + "”", 17, Ui.MUTED);
                    ex.setTypeface(Typeface.create("serif", Typeface.ITALIC)); ex.setLineSpacing(0, 1.2f);
                    col.addView(ex, wrap(dp(14)));
                }
                col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                col.addView(footnote("Double-tap for the next word  ·  " + (i + 1) + "/" + w.length), wrap(0));
                break;
            }
            case "poetry": {
                if (clock) col.addView(clockRow(), fill(0));
                String[][] p = LockStyles.poems();
                int i = Math.floorMod(prefs().getInt("lock_poem_idx", 0), p.length);
                col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                if (!p[i][0].isEmpty()) {
                    TextView title = Ui.text(svc, p[i][0], 22, Ui.INK);
                    title.setTypeface(Typeface.create("serif", Typeface.BOLD)); title.setGravity(Gravity.CENTER);
                    col.addView(title, fill(0));
                }
                if (!p[i][1].isEmpty()) {
                    TextView au = Ui.text(svc, p[i][1], 15, Ui.MUTED); au.setGravity(Gravity.CENTER);
                    col.addView(au, fill(dp(8)));
                }
                TextView body = Ui.text(svc, p[i][2], 19, Ui.INK);
                body.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                body.setGravity(Gravity.CENTER); body.setLineSpacing(0, 1.35f);
                col.addView(body, fill(dp(22)));
                col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                col.addView(footnote("Double-tap for another poem"), wrap(0));
                break;
            }
            case "alarm": {
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                col.addView(LockStyles.clockFace(svc, LockStyles.clockFace(svc), rotated ? 1.15f : 1f), fill(0));
                TextClock date = new TextClock(svc);
                date.setFormat12Hour("EEEE, MMMM d"); date.setFormat24Hour("EEEE, MMMM d");
                date.setTextSize(19); date.setTextColor(Ui.INK); date.setGravity(Gravity.CENTER);
                col.addView(date, fill(dp(22)));
                col.addView(centered(nextAlarm(), 16, Ui.MUTED), fill(dp(10)));
                String bat = battery();
                if (!bat.isEmpty()) col.addView(centered(bat, 16, Ui.MUTED), fill(dp(4)));
                if (LockStyles.showWeather(svc)) {
                    weatherView = centered(prefs().getString("weather_cache", ""), 16, Ui.MUTED);
                    col.addView(weatherView, fill(dp(4)));
                    fetchWeatherAsync();
                }
                col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                col.addView(footnote(rotated ? "Double-tap to turn upright" : "Double-tap to turn sideways"), fill(0));
                break;
            }
            case "art": {
                if (clock) col.addView(artClockRow(), fill(0));
                File art = ArtCover.current(svc);
                if (art == null) {
                    col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                    col.addView(centered("Fetching paintings\u2026\nThey appear once the phone has been online.", 16, Ui.MUTED), fill(0));
                    col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
                    break;
                }
                // painting + caption in one stage; an app's notifications are laid over its lower part, bottom-anchored
                // just above the squircle row (the painting never changes size; the caption is hidden meanwhile)
                artStage = new FrameLayout(svc);
                LinearLayout artCol = new LinearLayout(svc);
                artCol.setOrientation(LinearLayout.VERTICAL);
                ImageView pic = new ImageView(svc);
                pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
                pic.setImageBitmap(artBitmap(art));
                artCol.addView(pic, new LinearLayout.LayoutParams(-1, 0, 1f));
                artCaption = new LinearLayout(svc);
                artCaption.setOrientation(LinearLayout.VERTICAL);
                String[] m = ArtCover.meta(art);
                TextView t = Ui.text(svc, m[0], 18, Ui.INK);
                t.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC)); t.setGravity(Gravity.CENTER);
                t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                artCaption.addView(t, fill(dp(14)));
                String by = m[1].isEmpty() ? m[2] : (m[2].isEmpty() ? m[1] : m[1] + ", " + m[2]);
                TextView a = centered(by, 14, Ui.MUTED); a.setMaxLines(2);
                artCaption.addView(a, fill(dp(4)));
                artCaption.addView(footnote("Art Institute of Chicago \u00B7 public domain \u00B7 double-tap for another"), fill(dp(6)));
                artCol.addView(artCaption, fill(0));
                artStage.addView(artCol, new FrameLayout.LayoutParams(-1, -1));
                ScrollView os = new ScrollView(svc);
                os.setVerticalScrollBarEnabled(false);
                os.setOverScrollMode(View.OVER_SCROLL_NEVER);
                artOverlay = new LinearLayout(svc);
                artOverlay.setOrientation(LinearLayout.VERTICAL);
                os.addView(artOverlay, new ViewGroup.LayoutParams(-1, -2));
                os.setVisibility(View.GONE);
                artOverlayScroll = os;
                artStage.addView(os, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, 0, 1f); pl.topMargin = dp(18);
                col.addView(artStage, pl);
                break;
            }
            case "music": {
                if (clock) col.addView(clockRow(), fill(0));
                col.addView(musicPanel(), new LinearLayout.LayoutParams(-1, 0, 1f));
                break;
            }
            case "todo": {
                if (clock) col.addView(clockRow(), fill(0));
                col.addView(todoList(), new LinearLayout.LayoutParams(-1, 0, 1f));
                break;
            }
        }

        // notifications (cards accordion / icon strip / hidden); the art style has its own row of app squircles
        if (!notifMode.equals("hidden") && artStage != null) {
            artRow = new LinearLayout(svc);
            artRow.setOrientation(LinearLayout.HORIZONTAL);
            artRow.setGravity(Gravity.CENTER);
            col.addView(artRow, fill(dp(12)));
            fillArtNotifs();
        } else if (!notifMode.equals("hidden")) {
            ScrollView sv = new ScrollView(svc);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            accordion = new LinearLayout(svc);
            accordion.setOrientation(LinearLayout.VERTICAL);
            sv.addView(accordion, new ViewGroup.LayoutParams(-1, -2));
            scroll = sv;
            iconsOnly = false; // every style can expand its notifications
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(10);
            col.addView(sv, sl);
            fillAccordion();
        }

        String sig = LockStyles.signature(svc);
        if (!sig.isEmpty() && !style.equals("current") && !style.equals("pinned")) {
            TextView s = centered(sig, 15, Ui.INK);
            s.setTypeface(Typeface.create("serif", Typeface.ITALIC));
            col.addView(onPaperIf(s), fill(dp(12)));
        }

        String hintText = LockStyles.unlock(svc).equals("slide") ? "Slide up to unlock"
                : LockStyles.unlock(svc).equals("longpress") ? "Press and hold to unlock" : "Slide up or press and hold to unlock";
        TextView hint = Ui.text(svc, hintText, 16, Ui.INK);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, dp(44));
        hl.topMargin = dp(10);
        col.addView(onPaperIf(hint), hl);
    }

    private boolean iconsOnly;

    private void onDoubleTapContent() {
        if (root == null || pinPad != null) return;
        switch (style) {
            case "words": prefs().edit().putInt("lock_word_idx", prefs().getInt("lock_word_idx", 0) + 1).apply(); break;
            case "poetry": prefs().edit().putInt("lock_poem_idx", prefs().getInt("lock_poem_idx", 0) + 1).apply(); break;
            case "alarm": rotated = !rotated; break;
            case "art": ArtCover.next(svc); ArtCover.ensure(svc, null); break;
            default: return;
        }
        // new content wants the full page in the quality mode: close an open notification list (it puts the panel in
        // the fast, dithered mode and shrinks the picture), switch back first, then redraw once the mode has landed
        expanded = false;
        updateEinkMode();
        root_post(() -> { if (root != null) { populate(); clean(); } }, 150);
    }

    // ---------------------------------------------------------------- reading style

    /** Where the page on screen ends (set when it is laid out); the next page starts there. */
    private int bookPageEnd = -1;

    /** Turn the book page: +1 next, -1 back. Taps, and the e-ink buttons via the controller's LOCK_PAGE broadcast. */
    void turnPage(int d) {
        if (root == null || pinPad != null || music != null || !"book".equals(style)) return;
        android.content.SharedPreferences p = prefs();
        int pos = p.getInt("lock_book_pos", 0);
        java.util.ArrayList<String> back = new java.util.ArrayList<>();
        for (String s : p.getString("lock_book_back", "").split(",")) if (!s.isEmpty()) back.add(s);
        if (d > 0) {
            if (bookPageEnd <= pos || bookPageEnd >= LockStyles.bookText(svc).length() + 1) return;
            back.add(String.valueOf(pos));
            while (back.size() > 300) back.remove(0);
            pos = bookPageEnd;
        } else {
            if (back.isEmpty()) return;
            try { pos = Integer.parseInt(back.remove(back.size() - 1)); } catch (NumberFormatException e) { pos = 0; }
        }
        p.edit().putInt("lock_book_pos", pos).putString("lock_book_back", String.join(",", back)).apply();
        populate();
    }

    /** One page of text: lays out from `start` and draws only the lines that fit, noting where it stopped. */
    private final class BookPage extends View {
        private final String text;
        private final int start;
        private final android.text.TextPaint paint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private android.text.StaticLayout layout;
        private int shownBottom;

        BookPage(String text, int start) {
            super(svc);
            this.text = text; this.start = start;
            paint.setColor(Ui.INK);
            paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            paint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 19, svc.getResources().getDisplayMetrics()));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            int w = r - l, h = b - t;
            if (w <= 0 || h <= 0) return;
            if (start >= text.length()) { layout = null; bookPageEnd = text.length() + 1; return; }
            // a page never holds more than a few thousand characters, so lay out only that slice
            String slice = text.substring(start, Math.min(text.length(), start + 6000));
            layout = android.text.StaticLayout.Builder.obtain(slice, 0, slice.length(), paint, w)
                    .setLineSpacing(0, 1.3f).setIncludePad(false)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NORMAL).build();
            int last = -1;
            for (int i = 0; i < layout.getLineCount() && layout.getLineBottom(i) <= h; i++) last = i;
            if (last < 0) last = 0;
            shownBottom = layout.getLineBottom(last);
            bookPageEnd = start + layout.getLineEnd(last);
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            if (layout == null) {
                android.text.TextPaint p = new android.text.TextPaint(paint);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                c.drawText("The end", getWidth() / 2f, getHeight() / 2f, p);
                return;
            }
            c.save();
            c.clipRect(0, 0, getWidth(), shownBottom);
            layout.draw(c);
            c.restore();
        }
    }

    /** Words, poems and paintings move on once per lock (done at unlock, see hide()). */
    private void advanceOnLock() {
        String s = LockStyles.style(svc);
        if (s.equals("words")) prefs().edit().putInt("lock_word_idx", prefs().getInt("lock_word_idx", 0) + 1).apply();
        if (s.equals("poetry")) prefs().edit().putInt("lock_poem_idx", prefs().getInt("lock_poem_idx", 0) + 1).apply();
        if (s.equals("art") && ArtCover.unseen(svc) > 0) ArtCover.next(svc);
    }

    // ---------------------------------------------------------------- building blocks

    private LinearLayout.LayoutParams wrap(int top) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.topMargin = top; return l; }
    private LinearLayout.LayoutParams fill(int top) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = top; return l; }

    private TextView centered(String s, float sp, int color) { TextView t = Ui.text(svc, s, sp, color); t.setGravity(Gravity.CENTER); return t; }

    private TextView footnote(String s) { TextView t = centered(s, 13, Ui.MUTED); return t; }

    /** On a picture background, put text on a white rounded card so it stays readable. */
    private View onPaper(View v) {
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.box(svc, 14, false));
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.addView(v);
        return box;
    }
    private View onPaperIf(View v) {
        if (!style.equals("wallpaper") && !style.equals("current") && !style.equals("pinned")) return v;
        View b = onPaper(v);
        return b;
    }

    /** Big clock + date + weather, left-aligned (window / wallpaper styles). */
    private View clockBlock(boolean small) {
        LinearLayout c = new LinearLayout(svc);
        c.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(svc);
        time.setFormat12Hour("h:mm"); time.setFormat24Hour("HH:mm");
        time.setTextSize(small ? 44 : 66);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setTextColor(Ui.INK); time.setIncludeFontPadding(false);
        c.addView(time, new LinearLayout.LayoutParams(-1, -2));
        TextClock date = new TextClock(svc);
        date.setFormat12Hour("EEE, MMM d"); date.setFormat24Hour("EEE, MMM d");
        date.setTextSize(15); date.setTextColor(Ui.MUTED);
        c.addView(date, wrap(dp(6)));
        if (LockStyles.showWeather(svc)) {
            weatherView = Ui.text(svc, prefs().getString("weather_cache", ""), 15, Ui.MUTED);
            c.addView(weatherView, wrap(dp(4)));
            fetchWeatherAsync();
        }
        return c;
    }

    /** Compact one-line clock header for the words / poetry / to-do / current styles. */
    private View clockRow() {
        LinearLayout r = new LinearLayout(svc);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextClock time = new TextClock(svc);
        time.setFormat12Hour("h:mm"); time.setFormat24Hour("HH:mm");
        time.setTextSize(40); time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setTextColor(Ui.INK); time.setIncludeFontPadding(false);
        time.setSingleLine(true);
        r.addView(time, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout side = new LinearLayout(svc);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(14), 0, 0, 0);
        TextClock date = new TextClock(svc);
        date.setFormat12Hour("EEE, MMM d"); date.setFormat24Hour("EEE, MMM d");
        date.setTextSize(15); date.setTextColor(Ui.INK);
        side.addView(date);
        if (LockStyles.showWeather(svc)) {
            weatherView = Ui.text(svc, prefs().getString("weather_cache", ""), 14, Ui.MUTED);
            side.addView(weatherView, wrap(dp(3)));
            fetchWeatherAsync();
        }
        r.addView(side, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    /** Art style header: a large clock spanning three lines on its right (date, weather, next calendar event). */
    private View artClockRow() {
        LinearLayout r = new LinearLayout(svc);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextClock time = new TextClock(svc);
        time.setFormat12Hour("h:mm"); time.setFormat24Hour("HH:mm");
        time.setTextSize(60); time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setTextColor(Ui.INK); time.setIncludeFontPadding(false);
        time.setSingleLine(true);
        r.addView(time, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout side = new LinearLayout(svc);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(16), 0, 0, 0);
        TextClock date = new TextClock(svc);
        date.setFormat12Hour("EEE, MMM d"); date.setFormat24Hour("EEE, MMM d");
        date.setTextSize(16); date.setTextColor(Ui.INK);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        side.addView(date);
        if (LockStyles.showWeather(svc)) {
            weatherView = Ui.text(svc, prefs().getString("weather_cache", ""), 15, Ui.MUTED);
            weatherView.setSingleLine(true); weatherView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            side.addView(weatherView, wrap(dp(5)));
            fetchWeatherAsync();
        }
        String ev = nextEvent();
        if (ev != null) {
            TextView e = Ui.text(svc, ev, 15, Ui.MUTED);
            e.setSingleLine(true); e.setEllipsize(android.text.TextUtils.TruncateAt.END);
            side.addView(e, wrap(dp(5)));
        }
        r.addView(side, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    /** "<title> at <time>" for the next timed calendar event in the coming 24 hours, or null. */
    private String nextEvent() {
        long now = System.currentTimeMillis();
        for (Agenda.Event e : Agenda.between(svc, now, now + 86_400_000L)) {
            if (e.allDay || e.begin < now) continue;
            String title = e.title.isEmpty() ? "(No title)" : e.title;
            boolean h24 = android.text.format.DateFormat.is24HourFormat(svc);
            return title + " at " + android.text.format.DateFormat.format(h24 ? "HH:mm" : "h:mm a", e.begin);
        }
        return null;
    }

    // ---------------------------------------------------------------- art style: app squircles + notification overlay

    private FrameLayout artStage;
    private LinearLayout artRow, artOverlay, artCaption;
    private ScrollView artOverlayScroll;
    private String artOpen;   // package whose notifications are laid over the painting, or null

    private void fillArtNotifs() {
        if (artRow == null) return;
        java.util.LinkedHashMap<String, List<StatusBarNotification>> groups = new java.util.LinkedHashMap<>();
        for (StatusBarNotification s : NotifService.visible())
            groups.computeIfAbsent(s.getPackageName(), k -> new java.util.ArrayList<>()).add(s);
        if (artOpen != null && !groups.containsKey(artOpen)) artOpen = null; // its last notification went

        artRow.removeAllViews();
        int shown = 0;
        for (java.util.Map.Entry<String, List<StatusBarNotification>> g : groups.entrySet()) {
            if (shown == 4) break;
            final String pkg = g.getKey();
            boolean on = pkg.equals(artOpen);
            FrameLayout sq = new FrameLayout(svc);
            sq.setBackground(Ui.box(svc, 56 * 0.3f, on));
            ImageView ic = new ImageView(svc);
            Drawable d = smallIcon(g.getValue().get(0));
            if (d != null) { d.mutate().setTint(on ? Ui.PAPER : Ui.INK); ic.setImageDrawable(d); }
            sq.addView(ic, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
            sq.setContentDescription(appName(pkg));
            sq.setOnClickListener(v -> { artOpen = pkg.equals(artOpen) ? null : pkg; fillArtNotifs(); });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(56), dp(56));
            l.setMargins(dp(6), 0, dp(6), 0);
            artRow.addView(sq, l);
            shown++;
        }
        int more = groups.size() - shown;
        if (more > 0) {
            TextView m = Ui.text(svc, "+" + more, 13, Ui.INK);
            m.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            m.setGravity(Gravity.CENTER);
            m.setBackground(Ui.box(svc, 30 * 0.3f, false));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(30), dp(30));
            ml.setMargins(dp(4), 0, dp(6), 0);
            artRow.addView(m, ml);
        }

        artOverlay.removeAllViews();
        boolean open = artOpen != null;
        artOverlayScroll.setVisibility(open ? View.VISIBLE : View.GONE);
        artCaption.setVisibility(open ? View.INVISIBLE : View.VISIBLE);
        if (open) {
            final List<StatusBarNotification> g = groups.get(artOpen);
            if (g.size() == 1) {
                final StatusBarNotification s = g.get(0);
                View card = cards.card(s, false, false);
                card.setOnClickListener(v -> openAfterUnlock(s));
                artOverlay.addView(card, new LinearLayout.LayoutParams(-1, -2));
            } else {
                LinearLayout box = new LinearLayout(svc);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setBackground(Ui.box(svc, 14, false));
                box.setPadding(dp(12), dp(10), dp(12), dp(12));
                LinearLayout head = new LinearLayout(svc);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                ImageView ic = new ImageView(svc);
                Drawable d = smallIcon(g.get(0));
                if (d != null) { d.mutate().setTint(Ui.INK); ic.setImageDrawable(d); }
                head.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
                TextView name = Ui.text(svc, appName(artOpen) + " \u00B7 " + g.size(), 16, Ui.INK);
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f); nl.leftMargin = dp(10);
                head.addView(name, nl);
                TextView all = Ui.text(svc, "\u2715  Dismiss all", 15, Ui.INK);
                all.setPadding(dp(10), dp(8), dp(4), dp(8));
                all.setOnClickListener(v -> {
                    for (StatusBarNotification s : g) NotifService.hide(s);
                    artOpen = null;
                    fillArtNotifs();
                    root_post(() -> { if (artRow != null) fillArtNotifs(); }, 400);
                });
                head.addView(all, new LinearLayout.LayoutParams(-2, -2));
                box.addView(head, new LinearLayout.LayoutParams(-1, -2));
                int n = 0;
                for (final StatusBarNotification s : g) {
                    if (n++ == 8) break;
                    View card = cards.card(s, false, true);
                    card.setOnClickListener(v -> openAfterUnlock(s));
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(8);
                    box.addView(card, cl);
                }
                artOverlay.addView(box, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        if (root != null && root.getWindowToken() != null) updateEinkMode();
    }

    private Drawable smallIcon(StatusBarNotification s) {
        try {
            Icon small = s.getNotification().getSmallIcon();
            return small != null ? small.loadDrawable(svc.createPackageContext(s.getPackageName(), 0)) : null;
        } catch (Exception e) { return null; }
    }

    private String appName(String pkg) {
        try {
            android.content.pm.PackageManager pm = svc.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    private String nextAlarm() {
        android.app.AlarmManager am = svc.getSystemService(android.app.AlarmManager.class);
        android.app.AlarmManager.AlarmClockInfo a = am == null ? null : am.getNextAlarmClock();
        if (a == null) return "No alarm set";
        java.util.Calendar t = java.util.Calendar.getInstance();
        t.setTimeInMillis(a.getTriggerTime());
        boolean h24 = android.text.format.DateFormat.is24HourFormat(svc);
        long hours = (a.getTriggerTime() - System.currentTimeMillis()) / 3_600_000L;
        String when = android.text.format.DateFormat.format(hours < 20 ? (h24 ? "HH:mm" : "h:mm a") : (h24 ? "EEE HH:mm" : "EEE h:mm a"), t).toString();
        return "Alarm  " + when;
    }

    private String battery() {
        android.content.Intent b = svc.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return "";
        int lvl = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        int st = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0);
        int pct = lvl < 0 ? -1 : lvl * 100 / sc;
        if (st == android.os.BatteryManager.BATTERY_STATUS_CHARGING) return "Charging  " + pct + "%";
        if (st == android.os.BatteryManager.BATTERY_STATUS_FULL) return "Charged";
        return pct >= 0 ? "Battery  " + pct + "%" : "";
    }

    // ---------------------------------------------------------------- music cover

    private android.media.session.MediaController music;
    private android.media.session.MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private final android.media.session.MediaController.Callback musicCb = new android.media.session.MediaController.Callback() {
        @Override public void onMetadataChanged(android.media.MediaMetadata m) { musicChanged(); }
        @Override public void onPlaybackStateChanged(android.media.session.PlaybackState st) { musicChanged(); }
        @Override public void onSessionDestroyed() { setMusic(null); }
    };
    private String lastTrack = "";

    private android.media.session.MediaSessionManager msm() { return svc.getSystemService(android.media.session.MediaSessionManager.class); }
    private android.content.ComponentName listenerName() { return new android.content.ComponentName(svc, NotifService.class); }

    private static boolean playing(android.media.session.MediaController c) {
        android.media.session.PlaybackState st = c.getPlaybackState();
        return st != null && (st.getState() == android.media.session.PlaybackState.STATE_PLAYING
                || st.getState() == android.media.session.PlaybackState.STATE_BUFFERING);
    }

    /** Pick the session that is playing right now (null if nothing plays or the option is off). */
    private android.media.session.MediaController findPlaying() {
        if (!LockStyles.musicCover(svc)) return null;
        try {
            for (android.media.session.MediaController c : msm().getActiveSessions(listenerName()))
                if (playing(c)) return c;
        } catch (Exception ignored) {}
        return null;
    }

    private void watchMusic() {
        setMusicQuiet(findPlaying());
        if (sessionsListener != null || !LockStyles.musicCover(svc)) return;
        sessionsListener = list -> root_post(() -> {
            if (root == null) return;
            android.media.session.MediaController p = findPlaying();
            if (p != null && (music == null || !p.getSessionToken().equals(music.getSessionToken()))) setMusic(p);
        }, 0);
        try { msm().addOnActiveSessionsChangedListener(sessionsListener, listenerName()); } catch (Exception e) { sessionsListener = null; }
    }

    private void unwatchMusic() {
        if (music != null) { try { music.unregisterCallback(musicCb); } catch (Exception ignored) {} music = null; }
        if (sessionsListener != null) { try { msm().removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) {} sessionsListener = null; }
    }

    private void setMusicQuiet(android.media.session.MediaController c) {
        if (music != null) { try { music.unregisterCallback(musicCb); } catch (Exception ignored) {} }
        music = c;
        lastTrack = trackKey();
        if (c != null) c.registerCallback(musicCb, new android.os.Handler(android.os.Looper.getMainLooper()));
    }

    private void setMusic(android.media.session.MediaController c) {
        setMusicQuiet(c);
        if (root != null && pinPad == null) { populate(); clean(); }
    }

    private String trackKey() {
        if (music == null || music.getMetadata() == null) return "";
        android.media.MediaMetadata m = music.getMetadata();
        return m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) + "|" + playing(music);
    }

    /** Redraw only when the track or play/pause state actually changes (each redraw flashes the e-ink panel). */
    private void musicChanged() {
        String k = trackKey();
        if (k.equals(lastTrack)) return;
        lastTrack = k;
        if (root != null && pinPad == null) { populate(); clean(); }
    }

    private View musicPanel() {
        LinearLayout p = new LinearLayout(svc);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        android.media.MediaMetadata m = music.getMetadata();
        android.graphics.Bitmap art = null;
        String title = "", artist = "", album = "";
        if (m != null) {
            for (String key : new String[]{android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, android.media.MediaMetadata.METADATA_KEY_ART,
                    android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                if (art == null) art = m.getBitmap(key);
            }
            title = nz(m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE));
            artist = nz(m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST));
            if (artist.isEmpty()) artist = nz(m.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
            album = nz(m.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM));
        }
        p.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
        FrameLayout frame = new FrameLayout(svc);
        frame.setBackground(Ui.box(svc, 12, false));
        frame.setPadding(dp(6), dp(6), dp(6), dp(6));
        ImageView cover = new ImageView(svc);
        if (art != null) {
            cover.setImageBitmap(ArtCover.grayscale(art));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            cover.setImageDrawable(svc.getDrawable(R.drawable.i_music));
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setPadding(dp(60), dp(60), dp(60), dp(60));
        }
        frame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        int side = Math.min(width - dp(44), (int) (height * 0.40f));
        p.addView(frame, new LinearLayout.LayoutParams(side, side));
        TextView t = centered(title.isEmpty() ? "Playing" : title, 21, Ui.INK);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.addView(t, fill(dp(20)));
        String sub = artist.isEmpty() ? album : (album.isEmpty() ? artist : artist + " \u00B7 " + album);
        if (!sub.isEmpty()) { TextView a = centered(sub, 15, Ui.MUTED); a.setMaxLines(1); a.setEllipsize(android.text.TextUtils.TruncateAt.END); p.addView(a, fill(dp(6))); }
        String app = "";
        try { app = svc.getPackageManager().getApplicationLabel(svc.getPackageManager().getApplicationInfo(music.getPackageName(), 0)).toString(); } catch (Exception ignored) {}
        if (!app.isEmpty()) p.addView(centered(app, 13, Ui.MUTED), fill(dp(4)));

        LinearLayout ctl = new LinearLayout(svc);
        ctl.setGravity(Gravity.CENTER);
        boolean isPlaying = playing(music);
        android.media.session.MediaController.TransportControls tc = music.getTransportControls();
        ctl.addView(mediaButton(R.drawable.i_prev, false, v -> tc.skipToPrevious()));
        ctl.addView(mediaButton(isPlaying ? R.drawable.i_pause : R.drawable.i_play, true, v -> { if (playing(music)) tc.pause(); else tc.play(); }));
        ctl.addView(mediaButton(R.drawable.i_next, false, v -> tc.skipToNext()));
        p.addView(ctl, fill(dp(24)));
        p.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
        return p;
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private View mediaButton(int icon, boolean primary, View.OnClickListener l) {
        FrameLayout b = new FrameLayout(svc);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(primary ? Ui.INK : Ui.PAPER); g.setStroke(dp(2), Ui.INK);
        b.setBackground(g);
        ImageView i = Ui.icon(svc, icon, primary ? Ui.PAPER : Ui.INK, primary ? 34 : 28);
        b.addView(i, new FrameLayout.LayoutParams(dp(primary ? 34 : 28), dp(primary ? 34 : 28), Gravity.CENTER));
        b.setOnClickListener(l);
        int sz = dp(primary ? 76 : 60);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.setMargins(dp(14), 0, dp(14), 0);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------------------------------------------------------- to-do list

    private LinearLayout todoBox;

    private View todoList() {
        ScrollView sv = new ScrollView(svc);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        todoBox = new LinearLayout(svc);
        todoBox.setOrientation(LinearLayout.VERTICAL);
        todoBox.setPadding(0, dp(18), 0, dp(6));
        sv.addView(todoBox, new ViewGroup.LayoutParams(-1, -2));
        fillTodos(java.util.Collections.emptyList(), false);
        if (LockStyles.todoHabits(svc)) {
            new Thread(() -> {
                List<String> h = LockStyles.openHabits(svc);
                root_post(() -> { if (todoBox != null && root != null) { fillTodos(h, true); clean(); } }, 0);
            }).start();
        }
        return sv;
    }

    private List<String> lastHabits = java.util.Collections.emptyList();

    private void fillTodos(List<String> habits, boolean habitsLoaded) {
        if (habitsLoaded) lastHabits = habits;
        todoBox.removeAllViews();
        List<String[]> items = LockStyles.todos(svc);
        int open = 0;
        for (String[] it : items) if (it[1].equals("0")) open++;
        TextView head = Ui.text(svc, "To-dos", 24, Ui.INK);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        todoBox.addView(head, wrap(0));
        if (items.isEmpty() && lastHabits.isEmpty())
            todoBox.addView(Ui.text(svc, "No to-dos. Add some in the Lock screen app.", 16, Ui.MUTED), wrap(dp(14)));
        for (int k = 0; k < items.size(); k++) if (items.get(k)[1].equals("0")) todoBox.addView(todoRow(items, k), fill(dp(12)));
        if (!lastHabits.isEmpty()) {
            TextView hh = Ui.text(svc, "Habits today", 15, Ui.MUTED);
            todoBox.addView(hh, wrap(dp(24)));
            for (String h : lastHabits) todoBox.addView(checkRow(h, false, null), fill(dp(10)));
        }
        if (open < items.size()) {
            TextView fh = Ui.text(svc, "Finished", 15, Ui.MUTED);
            todoBox.addView(fh, wrap(dp(24)));
            for (int k = 0; k < items.size(); k++) if (items.get(k)[1].equals("1")) todoBox.addView(todoRow(items, k), fill(dp(10)));
        }
    }

    private View todoRow(List<String[]> items, int k) {
        String[] it = items.get(k);
        return checkRow(it[0], it[1].equals("1"), v -> {
            it[1] = it[1].equals("1") ? "0" : "1";
            LockStyles.saveTodos(svc, items);
            fillTodos(lastHabits, false);
            clean();
        });
    }

    private View checkRow(String text, boolean done, View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(svc);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        View box = new View(svc);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(5)); g.setStroke(dp(2), Ui.INK); g.setColor(done ? Ui.INK : Ui.PAPER);
        box.setBackground(g);
        r.addView(box, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = Ui.text(svc, text, 18, done ? Ui.MUTED : Ui.INK);
        if (done) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f); tl.leftMargin = dp(14);
        r.addView(t, tl);
        r.setPadding(0, dp(4), 0, dp(4));
        if (tap != null) r.setOnClickListener(tap);
        return r;
    }

    private ScrollView scroll;

    private void fillAccordion() {
        accordion.removeAllViews();
        if (scroll != null) {
            ViewGroup.LayoutParams p = scroll.getLayoutParams();
            p.height = expanded ? (int) (height * 0.40f) : ViewGroup.LayoutParams.WRAP_CONTENT;
            scroll.setLayoutParams(p);
            scroll.scrollTo(0, 0);
        }
        List<StatusBarNotification> list = NotifService.visible();
        if (list.isEmpty()) {
            // last one swiped away: close the list, back to the quality refresh mode
            if (expanded) {
                expanded = false;
                if (scroll != null) { scroll.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT; scroll.requestLayout(); }
                updateEinkMode();
            }
            return;
        }
        if (!expanded || iconsOnly) {
            LinearLayout c = new LinearLayout(svc);
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setBackground(Ui.box(svc, 12, false));
            c.setPadding(dp(14), dp(12), dp(10), dp(12));
            int shown = 0;
            for (StatusBarNotification s : list) {
                if (shown == 4) break;
                ImageView ic = new ImageView(svc);
                try {
                    Icon small = s.getNotification().getSmallIcon();
                    Drawable d = small != null ? small.loadDrawable(svc.createPackageContext(s.getPackageName(), 0)) : null;
                    if (d != null) { d.mutate().setTint(Ui.INK); ic.setImageDrawable(d); }
                } catch (Exception ignored) {}
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(18), dp(18));
                il.rightMargin = dp(8);
                c.addView(ic, il);
                shown++;
            }
            TextView t = Ui.text(svc, list.size() + (list.size() == 1 ? " notification" : " notifications"), 16, Ui.INK);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = dp(4);
            c.addView(t, tl);
            if (!iconsOnly) {
                c.addView(Ui.icon(svc, R.drawable.i_more, Ui.INK, 26));
                c.setOnClickListener(v -> { expanded = true; fillAccordion(); updateEinkMode(); });
            }
            accordion.addView(c, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        // expanded: header row to collapse, then one card per notification (capped so the clock stays visible)
        TextView collapse = Ui.text(svc, "Hide notifications", 14, Ui.MUTED);
        collapse.setGravity(Gravity.END);
        collapse.setPadding(0, 0, dp(6), dp(8));
        collapse.setOnClickListener(v -> { expanded = false; fillAccordion(); updateEinkMode(); });
        accordion.addView(collapse, new LinearLayout.LayoutParams(-1, -2));
        int n = 0;
        for (StatusBarNotification s : list) {
            if (n++ == 6) break;
            View card = cards.card(s, false, false);
            card.setOnClickListener(v -> openAfterUnlock(s));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.bottomMargin = dp(8);
            accordion.addView(card, cl);
        }
    }

    private PendingOpen pending;
    private static final class PendingOpen { final StatusBarNotification sbn; PendingOpen(StatusBarNotification s) { sbn = s; } }

    private void openAfterUnlock(StatusBarNotification sbn) {
        pending = new PendingOpen(sbn);
        revealKeyguard();
    }

    /** Called on ACTION_USER_PRESENT: the real keyguard is gone. */
    StatusBarNotification takePendingOpen() {
        PendingOpen p = pending; pending = null;
        return p == null ? null : p.sbn;
    }

    // ---------------------------------------------------------------- e-ink PIN pad (replaces the stock bouncer)

    private FrameLayout pinPad;
    private final StringBuilder pin = new StringBuilder();
    private LinearLayout dots;
    private TextView pinMsg;
    private boolean unlocking;
    private long padShownAt;

    /**
     * Slide-up / long-press / notification tap. A secure keyguard gets our own PIN pad (our layer stays up, so the stock
     * lock screen never shows); an insecure one is dismissed underneath us and our layer goes on ACTION_USER_PRESENT.
     */
    private void revealKeyguard() {
        if (root == null) { revealStockKeyguard(); return; }
        KeyguardManager kmp = svc.getSystemService(KeyguardManager.class);
        if (kmp != null && !kmp.isKeyguardLocked()) { hide(); clean(); return; } // preview while unlocked
        if (pinPad != null) return;
        if (kmp == null || kmp.isKeyguardSecure()) { showPinPad(); return; }
        try {
            PlatformControls.dismissKeyguard();
            final int page = pageNo;
            root_post(() -> { if (root != null && pageNo == page && kmp.isKeyguardLocked()) revealStockKeyguard(); }, 3000);
        } catch (SecurityException e) {
            revealStockKeyguard();
        }
    }

    private void showPinPad() {
        pin.setLength(0); unlocking = false;
        padShownAt = android.os.SystemClock.uptimeMillis();
        pinPad = new FrameLayout(svc);
        pinPad.setBackgroundColor(Ui.PAPER);
        pinPad.setClickable(true);
        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(28), dp(70), dp(28), dp(24));
        pinPad.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextClock t = new TextClock(svc);
        t.setFormat12Hour("h:mm"); t.setFormat24Hour("HH:mm");
        t.setTextSize(44); t.setTextColor(Ui.INK);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));

        TextView title = Ui.text(svc, "Enter PIN", 20, Ui.INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.topMargin = dp(22);
        col.addView(title, tl);

        dots = new LinearLayout(svc);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(30)); dl.topMargin = dp(16);
        col.addView(dots, dl);

        pinMsg = Ui.text(svc, "or use your fingerprint", 14, Ui.MUTED);
        pinMsg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = dp(8);
        col.addView(pinMsg, ml);

        col.addView(new View(svc), new LinearLayout.LayoutParams(-1, 0, 1f));
        String[][] keys = {{"1","2","3"},{"4","5","6"},{"7","8","9"},{"\u232B","0","OK"}};
        for (String[] row : keys) {
            LinearLayout r = new LinearLayout(svc);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER);
            for (String k : row) {
                TextView b = Ui.text(svc, k, k.length() == 1 && Character.isDigit(k.charAt(0)) ? 30 : 20, Ui.INK);
                b.setGravity(Gravity.CENTER);
                if (k.equals("OK")) {
                    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    b.setTextColor(Ui.PAPER);
                    b.setBackground(Ui.box(svc, 18, true));
                } else {
                    b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    b.setBackground(Ui.box(svc, 18, false));
                }
                b.setOnClickListener(v -> onKey(k));
                LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(dp(92), dp(70));
                kl.setMargins(dp(9), dp(7), dp(9), dp(7));
                r.addView(b, kl);
            }
            col.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView cancel = Ui.text(svc, "Cancel", 16, Ui.INK);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(20), dp(14), dp(20), dp(6));
        cancel.setOnClickListener(v -> closePinPad());
        col.addView(cancel, new LinearLayout.LayoutParams(-2, -2));

        root.addView(pinPad, new FrameLayout.LayoutParams(-1, -1));
        updateEinkMode();
        renderDots();
    }

    private void closePinPad() {
        if (root != null && pinPad != null) root.removeView(pinPad);
        pinPad = null; pin.setLength(0); unlocking = false; pending = null;
        updateEinkMode(); // back to quality: einkMode cleans once
    }

    private void renderDots() {
        if (dots == null) return;
        dots.removeAllViews();
        int n = Math.max(4, pin.length());
        for (int i = 0; i < n; i++) {
            View d = new View(svc);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setStroke(dp(1.5f), Ui.INK);
            g.setColor(i < pin.length() ? Ui.INK : Ui.PAPER);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(14));
            lp.setMargins(dp(7), 0, dp(7), 0);
            dots.addView(d, lp);
        }
    }

    private void onKey(String k) {
        if (unlocking) return;
        // ignore the tail of the swipe that opened the pad
        if (android.os.SystemClock.uptimeMillis() - padShownAt < 600) return;
        if (k.equals("OK")) { submitPin(); return; }
        if (k.equals("\u232B")) { if (pin.length() > 0) pin.setLength(pin.length() - 1); }
        else if (pin.length() < 16) pin.append(k);
        if (pinMsg != null) { pinMsg.setText("or use your fingerprint"); pinMsg.setTextColor(Ui.MUTED); }
        renderDots();
    }

    private void submitPin() {
        if (pin.length() < 4) { showPinError("PIN is at least 4 digits"); return; }
        unlocking = true;
        final String entered = pin.toString();
        pinMsg.setText("Unlocking\u2026"); pinMsg.setTextColor(Ui.INK);
        final int page = pageNo;
        new Thread(() -> {
            final long result;
            try {
                result = PlatformControls.verifyPin(svc, entered);
            } catch (SecurityException e) {
                android.util.Log.w("EinkLock", "PIN verify unavailable, using the stock keyguard", e);
                if (Root.legacyAllowed(svc)) {
                    boolean legacy = legacyVerifyAndDismiss(entered);
                    root_post(() -> { if (legacy) hide(); else showPinError("Wrong PIN"); }, 0);
                } else {
                    root_post(this::revealStockKeyguard, 0);
                }
                return;
            }
            if (result != 0) {
                root_post(() -> showPinError(result > 0
                        ? "Too many attempts. Try again in " + ((result + 999) / 1000) + " s" : "Wrong PIN"), 0);
                return;
            }
            // Verified. Dismiss the keyguard under our layer; if it still wants its bouncer, type the same PIN into it.
            // Our layer stays until ACTION_USER_PRESENT (ShadeService hides it), so no stock frame is ever visible.
            KeyguardManager km = svc.getSystemService(KeyguardManager.class);
            try {
                PlatformControls.dismissKeyguard();
                Thread.sleep(600);
                boolean stillLocked = km != null && km.isKeyguardLocked();
                android.util.Log.i("EinkLock", "PIN verified; keyguard " + (stillLocked ? "still locked, typing into bouncer" : "dismissed"));
                if (stillLocked) {
                    PlatformControls.typeIntoFocusedWindow(entered);
                    Thread.sleep(500);
                    // the bouncer usually confirms a correct PIN by itself; Enter only if it is still waiting
                    if (km.isKeyguardLocked()) PlatformControls.typeIntoFocusedWindow("\n");
                }
            } catch (SecurityException | InterruptedException e) {
                android.util.Log.w("EinkLock", "keyguard dismiss failed", e);
            }
            root_post(() -> {
                if (root == null || pageNo != page) return;
                boolean locked = km != null && km.isKeyguardLocked();
                if (locked) android.util.Log.w("EinkLock", "still locked 5 s after a verified PIN: stock keyguard fallback");
                if (locked) revealStockKeyguard(); else hide();
            }, 5000);
        }).start();
    }
    private boolean legacyVerifyAndDismiss(String entered) {
        String out = Root.exec("cmd lock_settings verify --old " + entered);
        if (!out.contains("verified successfully")) return false;
        Root.exec("wm dismiss-keyguard");
        try { Thread.sleep(450); } catch (InterruptedException ignored) {}
        Root.exec("input text " + entered);
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        KeyguardManager km = svc.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) Root.exec("input keyevent 66");
        return true;
    }
    private void showPinError(String msg) {
        unlocking = false; pin.setLength(0); renderDots();
        if (pinMsg != null) { pinMsg.setText(msg); pinMsg.setTextColor(Ui.INK); }
    }

    /**
     * Last resort (PIN verification unavailable): swipe the system keyguard up so the stock PIN pad appears. Our layer
     * stops taking touches first so the gesture reaches the keyguard, and goes away only once the bouncer is up.
     */
    private void revealStockKeyguard() {
        if (root != null && rootLp != null) {
            rootLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(root, rootLp); } catch (Exception ignored) {}
        }
        Path path = new Path();
        path.moveTo(width / 2f, height * 0.85f);
        path.lineTo(width / 2f, height * 0.25f);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 120, 220)).build();
        svc.dispatchGesture(g, null, null);
        root_post(() -> { hide(); clean(); }, 700);
    }

    /** Ask the e-ink controller for a temporary refresh mode (0 Regal .. 4 Hyper, -1 = back to normal). */
    private void einkMode(int mode) {
        if (mode == lastEinkMode) return;
        // leaving Extreme for the quality mode: one full clean wipes the fast mode's ghosting
        if (lastEinkMode == MODE_FAST && mode == MODE_QUALITY) clean();
        lastEinkMode = mode;
        android.util.Log.i("EinkLock", "EINK_OVERRIDE_MODE " + mode);
        svc.sendBroadcast(new android.content.Intent("EINK_OVERRIDE_MODE")
                .putExtra("mode", mode).putExtra("neutral", mode >= 0));
    }
    private int lastEinkMode = -2;
    static final int MODE_QUALITY = 0, MODE_FAST = 3;

    /** Best greys for pictures; fast while a list or an app's notifications are open, or the PIN pad is up. */
    private void updateEinkMode() {
        if (root == null) { einkMode(-1); return; }
        einkMode(pinPad != null || (expanded && !iconsOnly) || artOpen != null ? MODE_FAST : MODE_QUALITY);
    }

    private void clean() {
        root_post(() -> svc.sendBroadcast(new android.content.Intent("EINK_FORCE_CLEAR")), 200);
    }

    private void root_post(Runnable r, long delay) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, delay);
    }

    private android.content.SharedPreferences prefs() {
        return svc.getSharedPreferences("shade", android.content.Context.MODE_PRIVATE);
    }

    /** The weather line comes from the Weather app's forecast; refresh it when it is over half an hour old. */
    private void fetchWeatherAsync() {
        if (Weather.fresh(svc)) return;
        Weather.refreshAsync(svc, f -> {
            TextView wv = weatherView;
            if (f != null && wv != null) wv.setText(Weather.lockLine(f));
        });
    }
}
