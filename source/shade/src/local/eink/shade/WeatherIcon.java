package local.eink.shade;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Black-and-white weather symbols drawn with plain strokes (no greys, so they stay sharp on e-ink): sun or moon,
 * cloud, rain, snow, fog, thunder. Drawn into a square; the colour can be changed for inverted use.
 */
final class WeatherIcon extends Drawable {
    private final int code;
    private final boolean day;
    private int color = Ui.INK, paper = Ui.PAPER;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    WeatherIcon(int code, boolean day) {
        this.code = code; this.day = day;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    static Bitmap bitmap(int code, boolean day, int sizePx) {
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        WeatherIcon i = new WeatherIcon(code, day);
        i.setBounds(0, 0, sizePx, sizePx);
        i.draw(new Canvas(b));
        return b;
    }

    @Override public void setTint(int tint) { color = tint; invalidateSelf(); }
    void setPaper(int c) { paper = c; }

    @Override public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height());
        c.save();
        c.translate(getBounds().left, getBounds().top);
        c.scale(s / 48f, s / 48f);          // all shapes below are in a 48 x 48 box
        p.setStrokeWidth(3f);
        boolean clear = code == 0, partly = code == 1 || code == 2, fog = code == 45 || code == 48;
        boolean drizzle = code >= 51 && code <= 57, rain = (code >= 61 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86, storm = code >= 95;
        if (clear) {
            if (day) sun(c, 24, 24, 9, true); else moon(c, 24, 24, 13);
        } else if (partly) {
            if (day) sun(c, 18, 17, 7, true); else moon(c, 18, 17, 9);
            cloud(c, 9, 20, 30, 18);
        } else if (fog) {
            cloud(c, 8, 8, 32, 18);
            for (int k = 0; k < 3; k++) line(c, 9 + (k % 2) * 4, 32 + k * 5, 39 - (k % 2) * 4, 32 + k * 5);
        } else if (code == 3 || drizzle || rain || snow || storm) {
            boolean precip = drizzle || rain || snow || storm;
            cloud(c, 6, precip ? 6 : 12, 36, precip ? 20 : 24);
            if (drizzle) for (int k = 0; k < 3; k++) dot(c, 15 + k * 9, 36 + (k % 2) * 5);
            if (rain) for (int k = 0; k < 3; k++) line(c, 17 + k * 8, 31, 13 + k * 8, 41);
            if (snow) for (int k = 0; k < 3; k++) flake(c, 15 + k * 9, 36 + (k % 2) * 5);
            if (storm) bolt(c);
        } else {
            cloud(c, 6, 12, 36, 24);
        }
        c.restore();
    }

    private void stroke() { p.setStyle(Paint.Style.STROKE); p.setColor(color); }

    private void line(Canvas c, float x1, float y1, float x2, float y2) { stroke(); c.drawLine(x1, y1, x2, y2, p); }

    private void dot(Canvas c, float x, float y) { p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawCircle(x, y, 2.2f, p); }

    private void flake(Canvas c, float x, float y) {
        stroke(); p.setStrokeWidth(2.2f);
        for (int a = 0; a < 3; a++) {
            double r = Math.PI / 3 * a;
            float dx = (float) (Math.cos(r) * 3.6), dy = (float) (Math.sin(r) * 3.6);
            c.drawLine(x - dx, y - dy, x + dx, y + dy, p);
        }
        p.setStrokeWidth(3f);
    }

    private void sun(Canvas c, float cx, float cy, float r, boolean rays) {
        stroke();
        c.drawCircle(cx, cy, r, p);
        if (!rays) return;
        for (int k = 0; k < 8; k++) {
            double a = Math.PI / 4 * k;
            float x1 = cx + (float) Math.cos(a) * (r + 4), y1 = cy + (float) Math.sin(a) * (r + 4);
            float x2 = cx + (float) Math.cos(a) * (r + 8), y2 = cy + (float) Math.sin(a) * (r + 8);
            c.drawLine(x1, y1, x2, y2, p);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        Path a = new Path(), b = new Path();
        a.addCircle(cx, cy, r, Path.Direction.CW);
        b.addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW);
        a.op(b, Path.Op.DIFFERENCE);
        stroke();
        c.drawPath(a, p);
    }

    /** Cloud in the box (x, y, w, h): filled with paper so it covers a sun behind it, then outlined. */
    private void cloud(Canvas c, float x, float y, float w, float h) {
        Path path = new Path(), t = new Path();
        path.addRoundRect(new RectF(x, y + h * 0.45f, x + w, y + h), h * 0.28f, h * 0.28f, Path.Direction.CW);
        t.addCircle(x + w * 0.33f, y + h * 0.5f, h * 0.32f, Path.Direction.CW); path.op(t, Path.Op.UNION); t.reset();
        t.addCircle(x + w * 0.6f, y + h * 0.4f, h * 0.4f, Path.Direction.CW); path.op(t, Path.Op.UNION);
        p.setStyle(Paint.Style.FILL); p.setColor(paper);
        c.drawPath(path, p);
        stroke();
        c.drawPath(path, p);
    }

    private void bolt(Canvas c) {
        Path b = new Path();
        b.moveTo(26, 28); b.lineTo(19, 38); b.lineTo(25, 38); b.lineTo(21, 46); b.lineTo(31, 34); b.lineTo(25, 34); b.close();
        p.setStyle(Paint.Style.FILL); p.setColor(color);
        c.drawPath(b, p);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
