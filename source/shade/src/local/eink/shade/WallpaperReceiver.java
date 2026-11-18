package local.eink.shade;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;

/** adb shell am broadcast -n local.eink.shade/.WallpaperReceiver [--es color "#FFFFFF"] : solid e-ink wallpaper. */
public class WallpaperReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int color = Color.WHITE;
        String s = i.getStringExtra("color");
        if (s != null) try { color = Color.parseColor(s); } catch (Exception ignored) {}
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        b.eraseColor(color);
        try {
            WallpaperManager.getInstance(c).setBitmap(b, null, true,
                    WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            setResultData("ok");
        } catch (Exception e) {
            setResultData("error: " + e);
        }
    }
}
