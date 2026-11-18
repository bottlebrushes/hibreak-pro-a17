package local.eink.shade;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Notification shade settings in the Hisense e-ink layout. */
public class ShadeSettingsActivity extends Activity {
    private LinearLayout list;
    private SharedPreferences p;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("shade", MODE_PRIVATE);
        render();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void render() {
        list = SettingsUi.page(this, "Notification shade", false);

        SettingsUi.category(list, "Front light");
        boolean dual = !"single".equals(p.getString("bright_mode", "dual"));
        String[] names = {"Cool + warm sliders", "One slider"};
        SettingsUi.row(list, "Brightness sliders", "Front-light controls in the shade", SettingsUi.chevron(this, names[dual ? 0 : 1]),
                v -> SettingsUi.choose(this, "Brightness sliders", names, dual ? 0 : 1,
                        k -> { p.edit().putString("bright_mode", k == 0 ? "dual" : "single").apply(); render(); }));
        toggle("light_presets", true, "Light presets", "Day, Read, Evening and Bedtime buttons under the sliders");

        SettingsUi.category(list, "Notifications");
        toggle("expand_default", false, "Expand notifications", "Open long notifications fully by default");

        SettingsUi.category(list, "Volume");
        toggle(VolumePanel.PREF_ON, true, "E-ink volume panel", "Large black-and-white volume bars instead of the stock pop-up. Off = stock");
        if (p.getBoolean(VolumePanel.PREF_ON, true)) {
            String[] kn = {"Media", "Ringer"}, kv = {"media", "ring"};
            int ks = "ring".equals(p.getString(VolumePanel.PREF_KEYS, "media")) ? 1 : 0;
            SettingsUi.row(list, "Volume keys change", "While music plays the keys always change media", SettingsUi.chevron(this, kn[ks]),
                    v -> SettingsUi.choose(this, "Volume keys change", kn, ks, k -> { p.edit().putString(VolumePanel.PREF_KEYS, kv[k]).apply(); render(); }));
        }

        SettingsUi.category(list, "Quick settings order");
        List<String> order = new ArrayList<>(Arrays.asList(ShadeService.tileOrder(this)));
        for (int i = 0; i < order.size(); i++) {
            final int k = i;
            LinearLayout arrows = new LinearLayout(this);
            arrows.addView(arrow("↑", i > 0, v -> swap(order, k, k - 1)));
            arrows.addView(arrow("↓", i < order.size() - 1, v -> swap(order, k, k + 1)));
            String where = i < 5 ? "Row 1" : i < 10 ? "Row 2" : "Row 3 (pull further down)";
            SettingsUi.row(list, ShadeService.tileName(order.get(i)), i % 5 == 0 ? where : null, arrows, null);
        }
        SettingsUi.row(list, "Reset order", "Back to the original layout", null, v -> { p.edit().remove("tile_order").apply(); render(); });

        SettingsUi.category(list, "Lock screen");
        SettingsUi.row(list, "Lock screen style", LockStyles.STYLE_NAMES[Math.max(0, Arrays.asList(LockStyles.STYLES).indexOf(LockStyles.style(this)))],
                SettingsUi.chevron(this, ""), v -> startActivity(new Intent(this, LockSettingsActivity.class).putExtra("back", true)));
    }

    @Override protected void onResume() { super.onResume(); if (list != null) render(); }

    private void swap(List<String> order, int a, int b) {
        String t = order.get(a); order.set(a, order.get(b)); order.set(b, t);
        p.edit().putString("tile_order", String.join(",", order)).apply();
        render();
    }

    /** Move arrow; at either end of the list it stays in place but invisible (light grey text gets outlined by high-contrast text). */
    private TextView arrow(String s, boolean enabled, View.OnClickListener l) {
        TextView b = Ui.text(this, s, 20, Ui.INK);
        b.setGravity(Gravity.CENTER);
        if (enabled) b.setOnClickListener(l); else b.setVisibility(View.INVISIBLE);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(40)));
        return b;
    }

    private void toggle(String key, boolean def, String title, String summary) {
        boolean on = p.getBoolean(key, def);
        SettingsUi.row(list, title, summary, SettingsUi.pill(this, on), v -> { p.edit().putBoolean(key, !on).apply(); render(); });
    }
}
