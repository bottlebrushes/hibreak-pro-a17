package local.eink.shade;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Platform/system APIs used by the privileged Android 17 build. */
final class PlatformControls {
    private static final int STATUS_BAR_DISABLE_EXPAND = 0x00010000;
    private static final int STATUS_BAR_DISABLE_NOTIFICATION_ALERTS = 0x00040000;

    private PlatformControls() {}

    static void setStatusBarDisabled(Context context, boolean disabled) {
        Object statusBar = context.getSystemService(Context.STATUS_BAR_SERVICE);
        if (statusBar == null) throw new SecurityException("status bar service unavailable");
        invoke(statusBar, "disable", new Class<?>[]{int.class},
                disabled ? STATUS_BAR_DISABLE_EXPAND | STATUS_BAR_DISABLE_NOTIFICATION_ALERTS : 0);
    }

    static void setBrightness(Context context, int brightness) {
        Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
        Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightness);
    }

    static boolean setWifiEnabled(Context context, boolean enabled) {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        return wifi != null && wifi.setWifiEnabled(enabled);
    }

    static void setMobileData(Context context, boolean enabled) {
        TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
        if (telephony == null) throw new SecurityException("telephony service unavailable");
        invoke(telephony, "setDataEnabledForReason", new Class<?>[]{int.class, boolean.class}, 0, enabled);
    }

    static void setRingerMode(AudioManager audio, int mode) {
        try {
            invoke(audio, "setRingerModeInternal", new Class<?>[]{int.class}, mode);
        } catch (SecurityException e) {
            // Android 15's public SDK has setRingerMode; the platform build uses the
            // internal variant so it does not route through an app-op.
            audio.setRingerMode(mode);
        }
    }

    static void setAirplaneMode(Context context, boolean enabled) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) throw new SecurityException("connectivity service unavailable");
        invoke(connectivity, "setAirplaneMode", new Class<?>[]{boolean.class}, enabled);
    }

    static void setBluetooth(Context context, boolean enabled) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) throw new SecurityException("bluetooth adapter unavailable");
        if (enabled) adapter.enable(); else adapter.disable();
    }

    static void setPowerSaveMode(Context context, boolean enabled) {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power == null) throw new SecurityException("power service unavailable");
        invoke(power, "setPowerSaveModeEnabled", new Class<?>[]{boolean.class}, enabled);
    }

    static void setLocation(Context context, boolean enabled) {
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE,
                enabled ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY : Settings.Secure.LOCATION_MODE_OFF);
    }

    static void setDnd(Context context, boolean enabled) {
        android.app.NotificationManager notifications = context.getSystemService(android.app.NotificationManager.class);
        if (notifications == null) throw new SecurityException("notification service unavailable");
        notifications.setInterruptionFilter(enabled
                ? android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
    }

    /** Give our NotifService notification access (MANAGE_NOTIFICATION_LISTENERS); Android 17 doesn't grant it by default. */
    static void ensureNotificationAccess(Context context) {
        android.app.NotificationManager nm = context.getSystemService(android.app.NotificationManager.class);
        android.content.ComponentName cn = new android.content.ComponentName(context, NotifService.class);
        if (nm == null || nm.isNotificationListenerAccessGranted(cn)) return;
        invoke(nm, "setNotificationListenerAccessGranted", new Class<?>[]{android.content.ComponentName.class, boolean.class}, cn, true);
    }

    static void setNfc(Context context, boolean enabled) {
        try {
            Object adapter = Class.forName("android.nfc.NfcAdapter")
                    .getMethod("getDefaultAdapter", Context.class).invoke(null, context);
            if (adapter == null) throw new SecurityException("nfc adapter unavailable");
            invoke(adapter, enabled ? "enable" : "disable", new Class<?>[0]);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new SecurityException("nfc unavailable", e);
        } catch (IllegalAccessException e) {
            throw new SecurityException("nfc API inaccessible", e);
        } catch (InvocationTargetException e) {
            throw security(e.getCause());
        }
    }
    /**
     * Check a PIN with LockSettings (LockPatternUtils.verifyCredential, needs ACCESS_KEYGUARD_SECURE_STORAGE).
     * Returns 0 when it matches, -1 when it is wrong, or the lockout in ms once LockSettings throttles attempts.
     */
    static long verifyPin(Context context, String pin) {
        try {
            Class<?> credentialClass = Class.forName("com.android.internal.widget.LockscreenCredential");
            Object credential = credentialClass.getMethod("createPin", CharSequence.class).invoke(null, pin);
            Class<?> utilsClass = Class.forName("com.android.internal.widget.LockPatternUtils");
            Constructor<?> constructor = utilsClass.getConstructor(Context.class);
            Object utils = constructor.newInstance(context);
            Object response = utilsClass.getMethod("verifyCredential", credentialClass, int.class, int.class)
                    .invoke(utils, credential, Context.class.getMethod("getUserId").invoke(context), 0);
            if ((Boolean) response.getClass().getMethod("isMatched").invoke(response)) return 0;
            Object t = response.getClass().getMethod("getTimeout").invoke(response); // int up to A16, Duration in A17
            long timeout = t instanceof java.time.Duration ? ((java.time.Duration) t).toMillis() : t instanceof Integer ? (Integer) t : 0;
            return timeout > 0 ? timeout : -1;
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                 | IllegalAccessException | InvocationTargetException e) {
            throw security(e instanceof InvocationTargetException ? e.getCause() : e);
        }
    }

    /** IWindowManager.dismissKeyguard (needs CONTROL_KEYGUARD). */
    static void dismissKeyguard() {
        try {
            Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
            Object wm = globalClass.getMethod("getWindowManagerService").invoke(null);
            invoke(wm, "dismissKeyguard", new Class<?>[]{Class.forName("com.android.internal.policy.IKeyguardDismissCallback"),
                    CharSequence.class}, null, null);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw security(e);
        }
    }

    /** Type keys into the focused window (the keyguard's bouncer under our overlay): digits, '\n' = Enter. Needs INJECT_EVENTS. */
    static void typeIntoFocusedWindow(String keys) {
        try {
            Object im;
            try { im = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null); }
            catch (ClassNotFoundException e) { im = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null); }
            Method inject = im.getClass().getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            for (int i = 0; i < keys.length(); i++) {
                char ch = keys.charAt(i);
                int code = ch == '\n' ? android.view.KeyEvent.KEYCODE_ENTER : android.view.KeyEvent.KEYCODE_0 + (ch - '0');
                long now = android.os.SystemClock.uptimeMillis();
                for (int action : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP}) {
                    android.view.KeyEvent ev = new android.view.KeyEvent(now, now, action, code, 0, 0,
                            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD);
                    inject.invoke(im, ev, 2 /* INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */);
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw security(e);
        } catch (InvocationTargetException e) {
            throw security(e.getCause());
        }
    }


    private static void invoke(Object receiver, String name, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = receiver.getClass().getMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(receiver, args);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new SecurityException(name + " unavailable", e);
        } catch (InvocationTargetException e) {
            throw security(e.getCause());
        }
    }

    private static SecurityException security(Throwable cause) {
        if (cause instanceof SecurityException) return (SecurityException) cause;
        return new SecurityException("platform API failed", cause);
    }
}
