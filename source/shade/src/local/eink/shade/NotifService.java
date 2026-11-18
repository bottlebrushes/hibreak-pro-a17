package local.eink.shade;

import android.app.Notification;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Feeds the shade with the current notifications and tells it about new ones. */
public class NotifService extends NotificationListenerService {
    interface Listener {
        void onNotificationsChanged();
        void onNewNotification(StatusBarNotification sbn);
    }

    static volatile NotifService instance;
    static volatile Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();

    @Override public void onListenerConnected() {
        instance = this;
        StatusBarNotification[] all = safeActive();
        for (StatusBarNotification s : all) seen.add(s.getKey());
        notifyChanged();
    }

    @Override public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        boolean isNew = seen.add(sbn.getKey());
        Notification n = sbn.getNotification();
        boolean alertOnce = (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
        boolean peek = isNew || !alertOnce;
        if (peek && shouldPeek(sbn, rankingMap)) {
            main.post(() -> { Listener l = listener; if (l != null) l.onNewNotification(sbn); });
        }
        notifyChanged();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        seen.remove(sbn.getKey());
        notifyChanged();
    }

    private boolean shouldPeek(StatusBarNotification sbn, RankingMap map) {
        Notification n = sbn.getNotification();
        if (sbn.isOngoing()) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if (sbn.getPackageName().equals(getPackageName())) return false;
        Ranking r = new Ranking();
        if (map != null && map.getRanking(sbn.getKey(), r)) {
            if (r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return false;
            if ((r.getSuppressedVisualEffects() & NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK) != 0) return false;
            if (r.matchesInterruptionFilter() == false) return false;
        }
        return true;
    }

    private void notifyChanged() {
        main.post(() -> { Listener l = listener; if (l != null) l.onNotificationsChanged(); });
    }

    StatusBarNotification[] safeActive() {
        try {
            StatusBarNotification[] a = getActiveNotifications();
            return a == null ? new StatusBarNotification[0] : a;
        } catch (Exception e) {
            return new StatusBarNotification[0];
        }
    }

    /**
     * Swiped-away notifications, key -> post time. Android removes a dismissed notification asynchronously and never
     * removes ongoing ones (USB, charging), so they stay out of every list (shade and lock screen) until they change.
     */
    private static final java.util.Map<String, Long> hidden = new java.util.concurrent.ConcurrentHashMap<>();

    /** Hide it everywhere now and cancel it if Android allows that. */
    static void hide(StatusBarNotification sbn) {
        hidden.put(sbn.getKey(), sbn.getPostTime());
        dismiss(sbn);
    }

    /** Notifications to show, newest first; group summaries hidden when their children are present. */
    static List<StatusBarNotification> visible() {
        NotifService s = instance;
        if (s == null) return Collections.emptyList();
        List<StatusBarNotification> all = new ArrayList<>(Arrays.asList(s.safeActive()));
        Set<String> groupsWithChildren = new HashSet<>();
        for (StatusBarNotification n : all) {
            if ((n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0 && n.getGroupKey() != null)
                groupsWithChildren.add(n.getGroupKey());
        }
        List<StatusBarNotification> out = new ArrayList<>();
        for (StatusBarNotification n : all) {
            boolean summary = (n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
            if (summary && groupsWithChildren.contains(n.getGroupKey())) continue;
            Long hid = hidden.get(n.getKey());
            if (hid != null && hid == n.getPostTime()) continue;
            out.add(n);
        }
        out.sort((a, b) -> Long.compare(b.getNotification().when == 0 ? b.getPostTime() : b.getNotification().when,
                a.getNotification().when == 0 ? a.getPostTime() : a.getNotification().when));
        return out;
    }

    static void dismiss(StatusBarNotification sbn) {
        NotifService s = instance;
        if (s != null && sbn.isClearable()) s.cancelNotification(sbn.getKey());
    }

    static void dismissAll() {
        NotifService s = instance;
        if (s != null) s.cancelAllNotifications();
    }
}
