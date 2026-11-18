package local.eink.shade;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Settings keys, built-in content and clock faces for the Hisense-style lock screen styles. */
final class LockStyles {
    private LockStyles() {}

    static final String PREFS = "shade";
    // style ids, in the order the settings page lists them
    static final String[] STYLES = {"wallpaper", "window", "art", "words", "poetry", "book", "alarm", "todo", "current", "blank"};
    static final String[] STYLE_NAMES = {"Wallpaper lock screen", "Window wallpaper lock screen", "Art cover lock screen", "Recite words lock screen",
            "Poetry lock screen", "Reading lock screen", "Alarm clock lock screen", "To-do lock screen", "Current interface lock screen", "Blank page"};
    static final String[] STYLE_DESC = {
            "Your picture fills the screen, with the clock on top.",
            "Your picture in a frame, with clock, date, weather and notifications.",
            "A public-domain painting from the Art Institute of Chicago, new each time you lock. Double-tap for another.",
            "A word and its meaning. Double-tap for the next word.",
            "A short poem. Double-tap for another.",
            "A page of your book. Tap the right or left side, or press an e-ink button, to turn the page.",
            "A large clock with your next alarm. Double-tap to turn it sideways.",
            "Your to-dos and today's unfinished habits from Loop.",
            "Keeps whatever was on screen when you locked the phone.",
            "A plain white page."};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String style(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("lock_style", null);
        if (s == null) { // migrate the old /sdcard/lockscreen_mode.txt switch
            String m = readFile(LockScreen.MODE_FILE).toLowerCase();
            SharedPreferences.Editor e = p.edit();
            if (m.contains("blank")) s = "blank";
            else if (m.startsWith("image") || m.equals("picture")) { s = "wallpaper"; e.putBoolean("lock_show_clock", false); }
            else if (m.contains("clock")) { s = "window"; e.putString("lock_notifs", "hidden"); }
            else s = "window";
            e.putString("lock_style", s).apply();
        }
        return s;
    }

    static String clockFace(Context c) { return prefs(c).getString("lock_clock_face", "classic"); }
    static boolean showClock(Context c) { return prefs(c).getBoolean("lock_show_clock", true); }
    static boolean showWeather(Context c) { return prefs(c).getBoolean("lock_show_weather", true); }
    static String signature(Context c) { return prefs(c).getString("lock_signature", ""); }
    static String notifs(Context c) { return prefs(c).getString("lock_notifs", "cards"); }   // cards | icons | hidden
    static String unlock(Context c) { return prefs(c).getString("lock_unlock", "both"); }    // slide | longpress | both
    static boolean musicCover(Context c) { return prefs(c).getBoolean("lock_music_cover", true); }
    static boolean todoHabits(Context c) { return prefs(c).getBoolean("lock_todo_habits", true); }

    static File pictureFile(Context c) {
        File own = new File(c.getFilesDir(), "lockscreen.png");
        return own.canRead() ? own : new File(LockScreen.USER_PICTURE);
    }
    static File currentFile(Context c) { return new File(c.getFilesDir(), "current.png"); }

    // ---------------------------------------------------------------- pinned screenshot (overrides the style until unpinned)

    static File pinnedFile(Context c) { return new File(c.getFilesDir(), "pinned.png"); }
    static boolean pinned(Context c) { return prefs(c).getBoolean("lock_pinned", false) && pinnedFile(c).canRead(); }
    static void unpin(Context c) { prefs(c).edit().putBoolean("lock_pinned", false).apply(); pinnedFile(c).delete(); }

    // ---------------------------------------------------------------- reading (a plain-text book; position kept across locks)

    static final String BOOK_FILE = "/sdcard/lockscreen_book.txt";
    static final String BUILT_IN_BOOK_TITLE = "A Tale of Two Cities (opening)";
    private static final String BUILT_IN_BOOK =
            "A Tale of Two Cities\nCharles Dickens\n\nBook the First: Recalled to Life\n\nI. The Period\n\n"
            + "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness, "
            + "it was the epoch of belief, it was the epoch of incredulity, it was the season of Light, it was the season of "
            + "Darkness, it was the spring of hope, it was the winter of despair, we had everything before us, we had nothing "
            + "before us, we were all going direct to Heaven, we were all going direct the other way\u2014in short, the period "
            + "was so far like the present period, that some of its noisiest authorities insisted on its being received, for "
            + "good or for evil, in the superlative degree of comparison only.\n\n"
            + "There were a king with a large jaw and a queen with a plain face, on the throne of England; there were a king "
            + "with a large jaw and a queen with a fair face, on the throne of France. In both countries it was clearer than "
            + "crystal to the lords of the State preserves of loaves and fishes, that things in general were settled for ever.\n\n"
            + "\u2014\n\nThis is a short built-in sample. Choose your own book (a .txt file) in Lock screen settings, "
            + "or copy one to " + BOOK_FILE + ".";

    /** The chosen book (settings copy), else the user's file on the sdcard, else null for the built-in sample. */
    static File bookFile(Context c) {
        File own = new File(c.getFilesDir(), "book.txt");
        if (own.canRead()) return own;
        File sd = new File(BOOK_FILE);
        return sd.canRead() ? sd : null;
    }

    static String bookTitle(Context c) {
        File f = bookFile(c);
        if (f == null) return BUILT_IN_BOOK_TITLE;
        String t = prefs(c).getString("lock_book_title", "");
        return f.getName().equals("book.txt") && !t.isEmpty() ? t : f.getName().replaceFirst("(?i)\\.txt$", "");
    }

    private static String bookCacheKey;
    private static String bookCache;

    /** Whole book text (cached until the file changes); a new book starts again at page one. */
    static String bookText(Context c) {
        File f = bookFile(c);
        String key = f == null ? "builtin" : f.getPath() + ":" + f.length() + ":" + f.lastModified();
        if (!key.equals(bookCacheKey)) {
            // plain-text books (Project Gutenberg etc.) are hard-wrapped at ~70 columns: join those lines back into
            // paragraphs, keeping blank-line breaks and indented lines (contents, verse) as they are
            bookCache = f == null ? BUILT_IN_BOOK
                    : readFile(f.getPath()).replace("\r\n", "\n").replaceAll("(?<=[^\\n])\\n(?=[^\\s])", " ");
            if (bookCache.isEmpty()) bookCache = BUILT_IN_BOOK;
            bookCacheKey = key;
        }
        SharedPreferences p = prefs(c);
        if (!key.equals(p.getString("lock_book_key", ""))) {
            p.edit().putString("lock_book_key", key).putInt("lock_book_pos", 0).putString("lock_book_back", "").apply();
        }
        return bookCache;
    }

    static String readFile(String path) {
        try { return new String(java.nio.file.Files.readAllBytes(new File(path).toPath()), java.nio.charset.StandardCharsets.UTF_8).trim(); }
        catch (Exception e) { return ""; }
    }

    // ---------------------------------------------------------------- to-dos (one per line, "x " prefix = finished)

    static List<String[]> todos(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String line : prefs(c).getString("lock_todo_items", "").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            boolean done = t.startsWith("x ") || t.startsWith("[x]");
            String text = done ? t.replaceFirst("^(x |\\[x\\])\\s*", "") : t.replaceFirst("^\\[ \\]\\s*", "");
            out.add(new String[]{text, done ? "1" : "0"});
        }
        return out;
    }

    static void saveTodos(Context c, List<String[]> items) {
        StringBuilder sb = new StringBuilder();
        for (String[] it : items) sb.append(it[1].equals("1") ? "x " : "").append(it[0]).append('\n');
        prefs(c).edit().putString("lock_todo_items", sb.toString().trim()).apply();
    }

    /** Read today's open Loop habits through the read-only provider shipped by loop-bw. */
    static List<String> openHabits(Context c) {
        List<String> out = new ArrayList<>();
        try (Cursor rows = c.getContentResolver().query(
                Uri.parse("content://org.isoron.uhabits.eink/habits"), new String[]{"name"},
                null, null, "position")) {
            if (rows != null) while (rows.moveToNext()) out.add(rows.getString(0));
        } catch (Exception ignored) {
            // Older sideloaded Loop builds do not expose the provider.
            if (Root.legacyAllowed(c)) {
                String res = Root.execGlobal("/system/bin/sqlite3 /data/data/org.isoron.uhabits/databases/uhabits.db "
                        + "\"select name from Habits where archived=0 order by position;\" 2>&1");
                for (String line : res.split("\n")) {
                    String value = line.trim();
                    if (!value.isEmpty() && !value.startsWith("Error")) out.add(value);
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- recite words (user file overrides)

    static final String WORDS_FILE = "/sdcard/lockscreen_words.txt"; // "word | part of speech | meaning | example" per line
    private static final String[][] WORDS = {
            {"ephemeral", "adjective", "Lasting for a very short time.", "The morning fog was ephemeral, gone by eight."},
            {"laconic", "adjective", "Using very few words.", "His laconic reply was a single nod."},
            {"sanguine", "adjective", "Cheerfully optimistic, especially in a hard situation.", "She stayed sanguine about the deadline."},
            {"perfunctory", "adjective", "Done quickly, with little care or interest.", "He gave the report a perfunctory glance."},
            {"equanimity", "noun", "Calmness and composure, especially under stress.", "She took the bad news with equanimity."},
            {"obfuscate", "verb", "To make something unclear on purpose.", "The memo seemed written to obfuscate the cost."},
            {"ubiquitous", "adjective", "Found everywhere.", "Phones have become ubiquitous at dinner tables."},
            {"tenacious", "adjective", "Holding firmly to something; persistent.", "A tenacious habit is hard to break."},
            {"candor", "noun", "Openness and honesty in what one says.", "I appreciated the candor of her feedback."},
            {"pragmatic", "adjective", "Dealing with things in a practical way.", "We chose the pragmatic option over the perfect one."},
            {"meticulous", "adjective", "Showing great attention to detail.", "He keeps meticulous notes in his calendar."},
            {"resilient", "adjective", "Able to recover quickly from setbacks.", "Habits make a routine more resilient."},
            {"serendipity", "noun", "Finding something good by chance.", "Meeting her at the bookshop was pure serendipity."},
            {"quixotic", "adjective", "Idealistic in a way that is not practical.", "His quixotic plan was to read every book in the library."},
            {"lucid", "adjective", "Clear and easy to understand.", "She gave a lucid account of what happened."},
            {"diligent", "adjective", "Careful and hard-working.", "A diligent student reviews every day."},
            {"austere", "adjective", "Plain and without decoration.", "The room was austere: a desk, a lamp, a chair."},
            {"gregarious", "adjective", "Fond of company; sociable.", "Her gregarious brother knows everyone in town."},
            {"wistful", "adjective", "Full of a gentle, sad longing.", "He gave a wistful look at the old photos."},
            {"alacrity", "noun", "Cheerful readiness or eagerness.", "She accepted the invitation with alacrity."},
            {"cogent", "adjective", "Clear, logical and convincing.", "He made a cogent case for leaving early."},
            {"prudent", "adjective", "Acting with care and thought for the future.", "It is prudent to review your budget monthly."},
            {"innocuous", "adjective", "Harmless; not likely to offend.", "It was an innocuous question, but she blushed."},
            {"verbose", "adjective", "Using more words than needed.", "The verbose manual could be half as long."},
            {"zeal", "noun", "Great energy for a cause or goal.", "He started the gym plan with real zeal."},
    };

    static String[][] words() {
        String f = readFile(WORDS_FILE);
        if (f.isEmpty()) return WORDS;
        List<String[]> out = new ArrayList<>();
        for (String l : f.split("\n")) {
            if (l.trim().isEmpty()) continue;
            String[] p = l.split("\\|");
            String[] w = new String[4];
            for (int i = 0; i < 4; i++) w[i] = i < p.length ? p[i].trim() : "";
            out.add(w);
        }
        return out.isEmpty() ? WORDS : out.toArray(new String[0][]);
    }

    // ---------------------------------------------------------------- poetry (public-domain; user file overrides)

    static final String POEMS_FILE = "/sdcard/lockscreen_poems.txt"; // title / author / lines..., poems separated by a line "---"
    private static final String[][] POEMS = {
            {"\u201CHope\u201D is the thing with feathers", "Emily Dickinson",
                    "\u201CHope\u201D is the thing with feathers \u2013\nThat perches in the soul \u2013\nAnd sings the tune without the words \u2013\nAnd never stops \u2013 at all \u2013"},
            {"Who Has Seen the Wind?", "Christina Rossetti",
                    "Who has seen the wind?\nNeither I nor you:\nBut when the leaves hang trembling,\nThe wind is passing through.\n\nWho has seen the wind?\nNeither you nor I:\nBut when the trees bow down their heads,\nThe wind is passing by."},
            {"Rain", "Robert Louis Stevenson",
                    "The rain is raining all around,\nIt falls on field and tree,\nIt rains on the umbrellas here,\nAnd on the ships at sea."},
            {"Auguries of Innocence", "William Blake",
                    "To see a World in a Grain of Sand\nAnd a Heaven in a Wild Flower,\nHold Infinity in the palm of your hand\nAnd Eternity in an hour."},
    };

    static String[][] poems() {
        String f = readFile(POEMS_FILE);
        if (f.isEmpty()) return POEMS;
        List<String[]> out = new ArrayList<>();
        for (String block : f.split("\n---+\n")) {
            String[] l = block.trim().split("\n", 3);
            if (l.length == 3) out.add(new String[]{l[0].trim(), l[1].trim(), l[2].trim()});
            else if (l.length > 0 && !l[0].isEmpty()) out.add(new String[]{"", "", block.trim()});
        }
        return out.isEmpty() ? POEMS : out.toArray(new String[0][]);
    }

    // ---------------------------------------------------------------- clock faces

    static View clockFace(Context c, String face, float scale) {
        if (face.equals("flip")) return flipClock(c, scale);
        if (face.equals("modern")) return new Analog(c);
        TextClock t = new TextClock(c);
        t.setFormat12Hour("h:mm"); t.setFormat24Hour("HH:mm");
        t.setTextSize(92 * scale);
        t.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        t.setTextColor(Ui.INK); t.setIncludeFontPadding(false); t.setGravity(Gravity.CENTER);
        return t;
    }

    private static View flipClock(Context c, float scale) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        String[][] parts = {{"hh", "HH"}, {"mm", "mm"}};
        for (int i = 0; i < 2; i++) {
            FrameLayout card = new FrameLayout(c);
            card.setBackground(Ui.box(c, 14, true));
            TextClock t = new TextClock(c);
            t.setFormat12Hour(parts[i][0]); t.setFormat24Hour(parts[i][1]);
            t.setTextSize(78 * scale); t.setTextColor(Ui.PAPER); t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setIncludeFontPadding(false);
            card.addView(t, new FrameLayout.LayoutParams(-1, -1));
            View split = new View(c); split.setBackgroundColor(Ui.PAPER);
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, Ui.dp(c, 2.5f), Gravity.CENTER_VERTICAL);
            card.addView(split, sl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(c, 132 * scale), Ui.dp(c, 150 * scale));
            cl.setMargins(Ui.dp(c, 7), 0, Ui.dp(c, 7), 0);
            row.addView(card, cl);
        }
        return row;
    }

    /** Minimal analog clock: ring, 12 ticks, two hands. Redraws once a minute. */
    static final class Analog extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable tick = new Runnable() { @Override public void run() { invalidate(); schedule(); } };
        Analog(Context c) { super(c); p.setColor(Ui.INK); p.setStrokeCap(Paint.Cap.ROUND); }
        private void schedule() { removeCallbacks(tick); postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000 + 200); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); schedule(); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
        @Override protected void onMeasure(int w, int h) {
            int s = Math.min(MeasureSpec.getSize(w), Ui.dp(getContext(), 300));
            setMeasuredDimension(s, s);
        }
        @Override protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f, r = s / 2f - Ui.dp(getContext(), 4);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Ui.dp(getContext(), 3));
            cv.drawCircle(cx, cy, r, p);
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2 * i / 12;
                float in = i % 3 == 0 ? r * 0.80f : r * 0.88f;
                p.setStrokeWidth(Ui.dp(getContext(), i % 3 == 0 ? 4 : 2));
                cv.drawLine(cx + (float) Math.sin(a) * in, cy - (float) Math.cos(a) * in,
                        cx + (float) Math.sin(a) * r * 0.94f, cy - (float) Math.cos(a) * r * 0.94f, p);
            }
            java.util.Calendar t = java.util.Calendar.getInstance();
            double m = t.get(java.util.Calendar.MINUTE), h = t.get(java.util.Calendar.HOUR) + m / 60.0;
            hand(cv, cx, cy, h / 12 * Math.PI * 2, r * 0.50f, 7);
            hand(cv, cx, cy, m / 60 * Math.PI * 2, r * 0.74f, 4);
            p.setStyle(Paint.Style.FILL); cv.drawCircle(cx, cy, Ui.dp(getContext(), 6), p);
        }
        private void hand(Canvas cv, float cx, float cy, double a, float len, float wdp) {
            p.setStrokeWidth(Ui.dp(getContext(), wdp));
            cv.drawLine(cx, cy, cx + (float) Math.sin(a) * len, cy - (float) Math.cos(a) * len, p);
        }
    }
}
