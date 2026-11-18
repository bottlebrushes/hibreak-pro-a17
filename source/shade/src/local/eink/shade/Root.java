package local.eink.shade;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android 15 sideload fallback only; Android 17 uses PlatformControls/provider APIs. */
final class Root {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private Root() {}
    static boolean legacyAllowed(android.content.Context context) {
        int flags = context.getApplicationInfo().flags;
        // The A17 image installs Shade as a (privileged) system app; only a sideloaded A15 build may use su.
        return (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
    }

    static void run(String cmd) { POOL.execute(() -> exec(cmd)); }
    static String exec(String cmd) { return exec(cmd, false); }
    static String execGlobal(String cmd) { return exec(cmd, true); }

    private static String exec(String cmd, boolean global) {
        try {
            Process p = Runtime.getRuntime().exec(global
                    ? new String[]{"su", "-mm", "-c", cmd}
                    : new String[]{"su", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
