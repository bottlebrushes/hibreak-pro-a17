package local.eink.shade;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** Shared look: paper white, near-black ink, thick outlines, no shadows or gradients. */
final class Ui {
    static final int PAPER = Color.WHITE;
    static final int INK = 0xFF1E1E1E;
    static final int MUTED = 0xFF555555;
    static final float STROKE_DP = 2f;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable box(Context c, float radiusDp, boolean filled) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(filled ? INK : PAPER);
        g.setStroke(dp(c, STROKE_DP), INK);
        return g;
    }

    static GradientDrawable circle(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(PAPER);
        g.setStroke(dp(c, STROKE_DP), INK);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        v.setImageDrawable(d);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    static void tintIcon(ImageView v, int res, int color) {
        Drawable d = v.getContext().getDrawable(res).mutate();
        d.setTint(color);
        v.setImageDrawable(d);
    }

    /** Small filled corner triangle that marks tiles with a details page (long press). */
    static final class CornerMark extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        CornerMark(Context c) { super(c); }
        void setColor(int color) { p.setColor(color); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            path.reset();
            path.moveTo(w, 0); path.lineTo(w, h); path.lineTo(0, h); path.close();
            canvas.drawPath(path, p);
        }
    }

    static void center(TextView t) {
        t.setGravity(Gravity.CENTER);
    }
}
