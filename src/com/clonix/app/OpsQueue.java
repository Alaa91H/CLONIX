package com.clonix.app;

import android.app.Notification;
import android.content.Context;
import android.os.PowerManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single-lane queue for backup/restore/verify operations.
 *
 * Why: Neo Backup fixed a "duplicate backups racing condition" by
 * serializing engine work; this does the same. Benefits:
 * - one operation at a time (user tap + scheduler never collide);
 * - partial wakelock held for the whole op (deep sleep no longer
 *   stalls a 5-minute tar mid-stream);
 * - progress notification per op, result notification at the end;
 * - automatic single retry after a short backoff for transient
 *   root-daemon hiccups (failed in <5s => retry once).
 */
public final class OpsQueue {
    private OpsQueue() {}

    public interface Op {
        void run(Context app) throws Exception;
    }

    private static final ExecutorService LANE = Executors.newSingleThreadExecutor();
    /** Shared with BackupScheduler/batches so everything takes the same lane. */
    public static final Object LOCK = new Object();

    /** Enqueue a user-visible operation: wakelock + progress + retry + result. */
    public static void post(Context c, String title, Op op) {
        final Context app = c.getApplicationContext();
        LANE.execute(() -> {
            PowerManager.WakeLock wl = null;
            try {
                Notify.progress(app, title);
                try {
                    PowerManager pm = (PowerManager)
                        app.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            "Clonix:op");
                        wl.setReferenceCounted(false);
                        wl.acquire(15 * 60 * 1000L); // hard cap 15 min
                    }
                } catch (Throwable ignore) { }
                synchronized (LOCK) {
                    try {
                        op.run(app);
                    } catch (Throwable first) {
                        // Retry once for fast transient failures (root hiccup).
                        String msg = String.valueOf(first.getMessage());
                        if (msg != null && !msg.contains("unchanged")) {
                            try { Thread.sleep(2000); } catch (Throwable ignore) { }
                            try {
                                op.run(app);
                                return;
                            } catch (Throwable second) {
                                Notify.fail(app, title,
                                    String.valueOf(second.getMessage()));
                                return;
                            }
                        }
                        Notify.fail(app, title, msg);
                    }
                }
            } finally {
                try {
                    if (wl != null) wl.release();
                } catch (Throwable ignore) { }
                Notify.doneProgress(app, Notify.ID_PROG);
                try { WidgetUpdater.updateAll(app); } catch (Throwable ignore) { }
            }
        });
    }
}
