package local.eink.shade;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Lock screen settings in the Hisense e-ink layout: swipeable style previews with Apply, then option rows. */
public class LockSettingsActivity extends Activity {
    private static final int PICK = 7, PICK_BOOK = 8;
    private LinearLayout list;
    private SharedPreferences p;
    private int shown = -1;                       // style page being previewed
    private final Map<String, Bitmap> previews = new HashMap<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = LockStyles.prefs(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        previews.clear(); // clock/picture may have changed
        if (list != null) render();
    }

    private int dp(float v) { return Ui.dp(this, v); }
    private int idx(String id) { for (int i = 0; i < LockStyles.STYLES.length; i++) if (LockStyles.STYLES[i].equals(id)) return i; return 0; }

    private void render() {
        String cur = LockStyles.style(this);
        if (shown < 0) shown = idx(cur);
        list = SettingsUi.page(this, "Lock screen", getIntent().getBooleanExtra("back", false));
        list.addView(stylePager(cur), new LinearLayout.LayoutParams(-1, -2));

        if (LockStyles.pinned(this)) {
            SettingsUi.category(list, "Pinned screenshot");
            SettingsUi.row(list, "Unpin screenshot", "The lock screen shows your pinned screenshot instead of the style above",
                    SettingsUi.chevron(this, ""), v -> { LockStyles.unpin(this); previews.clear(); render(); });
        }

        SettingsUi.category(list, "Lock screen content");
        if (!cur.equals("blank")) {
            if (!cur.equals("alarm")) toggle("lock_show_clock", true, "Clock", "Show the time and date");
            toggle("lock_show_weather", true, "Weather", "Current temperature and conditions");
            String sig = LockStyles.signature(this);
            SettingsUi.row(list, "Lock screen signature", sig.isEmpty() ? "Off" : sig, SettingsUi.chevron(this, ""),
                    v -> SettingsUi.edit(this, "Lock screen signature", "Shown at the bottom (empty = off)", sig, false,
                            s -> { p.edit().putString("lock_signature", s.trim()).apply(); render(); }));
        }
        toggle("lock_music_cover", true, "Music cover", "While music plays, show the album cover and controls");
        if (!LockStyles.pinned(this))
            SettingsUi.row(list, "Pin a screenshot", "Long-press Screenshot in the notification shade to keep what's on screen on the lock screen until you unpin it", null, null);

        if (cur.equals("alarm")) {
            SettingsUi.category(list, "Alarm clock");
            String[] ids = {"classic", "flip", "modern"}, names = {"Classic", "Flip", "Modern"};
            String f = LockStyles.clockFace(this);
            int sel = java.util.Arrays.asList(ids).indexOf(f);
            SettingsUi.row(list, "Clock style", "Alarm clock display style on the lock screen", SettingsUi.chevron(this, names[Math.max(0, sel)]),
                    v -> SettingsUi.choose(this, "Clock style", names, sel, k -> { p.edit().putString("lock_clock_face", ids[k]).apply(); previews.clear(); render(); }));
        }
        if (cur.equals("wallpaper") || cur.equals("window")) {
            SettingsUi.category(list, "Wallpaper");
            File own = new File(getFilesDir(), "lockscreen.png");
            SettingsUi.row(list, "Choose picture", own.exists() ? "Using your picture" : "Using the built-in drawing", SettingsUi.chevron(this, ""), v -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                startActivityForResult(i, PICK);
            });
            if (own.exists()) SettingsUi.row(list, "Use the built-in drawing", null, null, v -> { own.delete(); previews.clear(); render(); });
        }
        if (cur.equals("todo")) {
            SettingsUi.category(list, "To-do");
            StringBuilder sb = new StringBuilder();
            int open = 0;
            for (String[] it : LockStyles.todos(this)) { sb.append(it[1].equals("1") ? "x " : "").append(it[0]).append('\n'); if (it[1].equals("0")) open++; }
            final String text = sb.toString().trim();
            SettingsUi.row(list, "To-do content settings", open == 0 ? "No to-dos" : open + " open", SettingsUi.chevron(this, ""),
                    v -> SettingsUi.edit(this, "To-dos", "One per line; start a line with x for finished", text, true,
                            s -> { p.edit().putString("lock_todo_items", s).apply(); previews.clear(); render(); }));
            toggle("lock_todo_habits", true, "Today's habits from Loop", "Show habits you haven't done yet today");
        }
        if (cur.equals("words")) {
            SettingsUi.category(list, "Recite words");
            SettingsUi.row(list, "Word book", "Built-in list of 25 words. Your own: " + LockStyles.WORDS_FILE + "  (word | noun | meaning | example)", null, null);
        }
        if (cur.equals("poetry")) {
            SettingsUi.category(list, "Poetry");
            SettingsUi.row(list, "Poems", "Built-in public-domain poems. Your own: " + LockStyles.POEMS_FILE + "  (title, author, lines; --- between poems)", null, null);
        }
        if (cur.equals("book")) {
            SettingsUi.category(list, "Reading");
            String text = LockStyles.bookText(this);
            int pct = text.isEmpty() ? 0 : Math.round(100f * Math.min(p.getInt("lock_book_pos", 0), text.length()) / text.length());
            SettingsUi.row(list, "Book", LockStyles.bookTitle(this) + "  \u00B7  " + pct + "% read", SettingsUi.chevron(this, "Choose"), v -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
                startActivityForResult(i, PICK_BOOK);
            });
            File own = new File(getFilesDir(), "book.txt");
            if (own.exists()) SettingsUi.row(list, "Remove this book", "Back to " + LockStyles.BOOK_FILE + " or the built-in sample", null,
                    v -> { own.delete(); previews.clear(); render(); });
            SettingsUi.row(list, "Start from the beginning", null, null,
                    v -> { p.edit().putInt("lock_book_pos", 0).putString("lock_book_back", "").apply(); previews.clear(); render(); });
            SettingsUi.row(list, "Turning pages", "Tap the right side of the page for the next one, the left side to go back. "
                    + "For the e-ink buttons, set a button's screen-off press to Next page or Previous page in E-ink display.",
                    SettingsUi.chevron(this, ""), v -> {
                        try { startActivity(new Intent().setClassName("com.lmqr.ha9_comp_service", "com.lmqr.ha9_comp_service.SettingsActivity")); }
                        catch (Exception ignored) {}
                    });
        }
        if (cur.equals("art")) {
            SettingsUi.category(list, "Art cover");
            int n = ArtCover.images(this).size();
            SettingsUi.row(list, "Paintings", "Public-domain works from the Art Institute of Chicago. " + (n == 0 ? "Downloading…" : n + " saved on the phone."), null, null);
            if (n == 0) ArtCover.ensure(this, () -> runOnUiThread(() -> { previews.clear(); render(); }));
        }

        if (!cur.equals("blank")) {
            SettingsUi.category(list, "Notifications");
            boolean showN = !LockStyles.notifs(this).equals("hidden");
            SettingsUi.row(list, "Lock screen notifications", showN ? "Shown; tap to expand" : "Hidden", SettingsUi.pill(this, showN),
                    v -> { p.edit().putString("lock_notifs", showN ? "hidden" : "cards").apply(); previews.clear(); render(); });
        }

        SettingsUi.category(list, "Unlock");
        String[] uids = {"slide", "longpress", "both"}, unames = {"Slide up", "Press and hold", "Slide up or press and hold"};
        int us = java.util.Arrays.asList(uids).indexOf(LockStyles.unlock(this));
        SettingsUi.row(list, "Unlock gesture", "Fingerprint always works too", SettingsUi.chevron(this, unames[Math.max(0, us)]),
                v -> SettingsUi.choose(this, "Unlock gesture", unames, us, k -> { p.edit().putString("lock_unlock", uids[k]).apply(); previews.clear(); render(); }));
        SettingsUi.row(list, "Preview on the phone", "Show the lock screen now; slide up to close", SettingsUi.chevron(this, ""),
                v -> sendBroadcast(new Intent("local.eink.shade.PREVIEW_LOCK").setPackage(getPackageName())));
    }

    // ---------------------------------------------------------------- style pager (Hisense: preview, arrows, dots, Apply)

    private View stylePager(String cur) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(14), 0, dp(6));
        String id = LockStyles.STYLES[shown];
        TextView title = Ui.text(this, "E-ink lock screen style", 15, Ui.INK);
        title.setPadding(dp(24), 0, dp(24), 0);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout stage = new FrameLayout(this);
        int w = dp(170), h = dp(340);
        FrameLayout phone = new FrameLayout(this);
        phone.setBackground(Ui.box(this, 18, false));
        phone.setPadding(dp(6), dp(6), dp(6), dp(6));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        Bitmap bm = previews.get(id);
        if (bm == null) { bm = ShadeService.lockPreview(id); if (bm != null) previews.put(id, bm); }
        if (bm != null) img.setImageBitmap(bm);
        phone.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (bm == null) phone.addView(Ui.text(this, LockStyles.STYLE_NAMES[shown], 14, Ui.INK), SettingsUi.center());
        stage.addView(phone, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        TextView left = Ui.text(this, "‹", 44, Ui.INK);
        TextView right = Ui.text(this, "›", 44, Ui.INK);
        if (shown == 0) left.setVisibility(View.INVISIBLE);
        if (shown == LockStyles.STYLES.length - 1) right.setVisibility(View.INVISIBLE);
        left.setPadding(dp(24), dp(20), dp(24), dp(20)); right.setPadding(dp(24), dp(20), dp(24), dp(20));
        left.setOnClickListener(v -> page(-1)); right.setOnClickListener(v -> page(1));
        stage.addView(left, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        stage.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) < Math.abs(vy)) return false;
                page(vx < 0 ? 1 : -1); return true;
            }
        });
        stage.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, h + dp(8)); sl.topMargin = dp(12);
        box.addView(stage, sl);

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < LockStyles.STYLES.length; i++) {
            View d = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(i == shown ? Ui.INK : Ui.PAPER); g.setStroke(dp(1.2f), Ui.INK);
            d.setBackground(g);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(7), dp(7)); dl.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(d, dl);
        }
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2); dlp.topMargin = dp(10);
        box.addView(dots, dlp);

        TextView name = Ui.text(this, LockStyles.STYLE_NAMES[shown], 17, Ui.INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2); nl.topMargin = dp(12);
        box.addView(name, nl);
        TextView desc = Ui.text(this, LockStyles.STYLE_DESC[shown], 13, SettingsUi.GREY);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(dp(32), dp(4), dp(32), 0);
        box.addView(desc);
        boolean applied = id.equals(cur);
        TextView apply = SettingsUi.smallPill(this, applied ? "Applied" : "Apply", applied);
        apply.setOnClickListener(v -> { if (!applied) { p.edit().putString("lock_style", id).apply(); render(); } });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2); al.topMargin = dp(12);
        box.addView(apply, al);
        return box;
    }

    private void page(int d) {
        int n = shown + d;
        if (n < 0 || n >= LockStyles.STYLES.length) return;
        shown = n;
        render();
    }

    private void toggle(String key, boolean def, String title, String summary) {
        boolean on = p.getBoolean(key, def);
        SettingsUi.row(list, title, summary, SettingsUi.pill(this, on), v -> { p.edit().putBoolean(key, !on).apply(); previews.clear(); render(); });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == PICK_BOOK) { saveBook(data.getData()); return; }
        if (req != PICK) return;
        Uri u = data.getData();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(u);
                 FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "lockscreen.png"))) {
                Bitmap bm = android.graphics.BitmapFactory.decodeStream(in);
                int max = 1800;
                if (bm.getWidth() > max || bm.getHeight() > max) {
                    float k = (float) max / Math.max(bm.getWidth(), bm.getHeight());
                    bm = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * k), Math.round(bm.getHeight() * k), true);
                }
                bm.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {}
            runOnUiThread(() -> { previews.clear(); render(); });
        }).start();
    }

    /** Copy the chosen .txt into the app (the picker's permission doesn't outlive the activity) and remember its name. */
    private void saveBook(Uri u) {
        String name = "Your book";
        try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0).replaceFirst("(?i)\\.txt$", "");
        } catch (Exception ignored) {}
        final String title = name;
        new Thread(() -> {
            File out = new File(getFilesDir(), "book.txt"), tmp = new File(getFilesDir(), "book.txt.tmp");
            try (InputStream in = getContentResolver().openInputStream(u); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > 32L << 20) throw new java.io.IOException("too big"); // 32 MB of text is far more than a book
                    o.write(buf, 0, n);
                }
            } catch (Exception e) { tmp.delete(); runOnUiThread(this::render); return; }
            tmp.renameTo(out);
            p.edit().putString("lock_book_title", title).apply();
            runOnUiThread(() -> { previews.clear(); render(); });
        }).start();
    }
}
