package com.clonix.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled batch backups (Neo-style): periodic JobScheduler job that backs
 * up every tracked, non-blacklisted clone with the saved part defaults.
 *
 * Honest constraints (platform-enforced, documented in UI):
 * - minimum period 60 min; persisted across reboots;
 * - ~10 min execution cap per run: items are processed sequentially and the
 *   run stops gracefully at 9 min, reporting what finished;
 * - scheduled runs are always UNENCRYPTED (no password available headless).
 */
public final class BackupScheduler {
    private BackupScheduler() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("backup_sched", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        try { return p(c).getBoolean("on", false); }
        catch (Throwable t) { return false; }
    }

    /** Hours between runs (min 1). */
    public static int intervalHours(Context c) {
        try { return Math.max(1, p(c).getInt("hours", 24)); }
        catch (Throwable t) { return 24; }
    }

    /** Unit the user picked in UI: "h" or "d" (days). Storage stays in hours. */
    public static String intervalUnit(Context c) {
        try { return "d".equals(p(c).getString("unit", "h")) ? "d" : "h"; }
        catch (Throwable t) { return "h"; }
    }

    /** Value in the user's unit (e.g. 3 when unit=days and hours=72). */
    public static int intervalValue(Context c) {
        int h = intervalHours(c);
        return "d".equals(intervalUnit(c)) ? Math.max(1, h / 24) : h;
    }

    public static boolean requireCharging(Context c) {
        try { return p(c).getBoolean("charging", true); }
        catch (Throwable t) { return true; }
    }

    public static boolean requireIdle(Context c) {
        try { return p(c).getBoolean("idle", false); }
        catch (Throwable t) { return false; }
    }

    /** Convenience for old callers: hours + current unit. */
    public static void save(Context c, boolean on, int hours,
            boolean charging, boolean idle) {
        save(c, on, hours, intervalUnit(c), charging, idle);
    }

    /** Save with explicit unit: days are converted to hours (x24). */
    public static void save(Context c, boolean on, int value, String unit,
            boolean charging, boolean idle) {
        boolean days = "d".equals(unit);
        int hours = Math.max(1, days ? value * 24 : value);
        try {
            p(c).edit().putBoolean("on", on).putInt("hours", hours)
                .putString("unit", days ? "d" : "h")
                .putBoolean("charging", charging).putBoolean("idle", idle).apply();
        } catch (Throwable ignore) { }
        sync(c);
    }

    public static void sync(Context c) {
        try {
            JobScheduler js =
                (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.cancel(SchedJob.JOB_ID);
            if (!enabled(c)) return;
            long interval = (long) intervalHours(c) * 60L * 60L * 1000L;
            // JobInfo.MIN_PERIOD_MILLIS is hidden from the public SDK (= 1h).
            if (interval < 3600000L) interval = 3600000L;
            JobInfo job = new JobInfo.Builder(SchedJob.JOB_ID,
                    new ComponentName(c, SchedJob.class))
                .setPeriodic(interval)
                .setPersisted(true)
                .setRequiresCharging(requireCharging(c))
                .setRequiresDeviceIdle(requireIdle(c))
                .build();
            int r = js.schedule(job);
            Log.i("Clonix", "schedule backup every=" + intervalHours(c)
                + "h result=" + r);
        } catch (Throwable t) {
            Log.w("Clonix", "schedule sync failed", t);
        }
    }

    /** One scheduled run (also used by "Run now" + TEST). Returns summary. */
    public static String runOnce(Context c) {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 9L * 60L * 1000L;
        BackupJob opts = BackupJob.defaults(c);
        CloneStore db = new CloneStore(c);
        int ok = 0, skip = 0;
        List<String> fails = new ArrayList<>();
        try {
            for (CloneStore.Clone cl : db.listAll()) {
                if (System.currentTimeMillis() > deadline) {
                    fails.add("…time cap (rest skipped)");
                    break;
                }
                if (BackupJob.isBlacklisted(c, cl.pkg)) { skip++; continue; }
                try {
                    // Unattended run: freeze the tree first (no live-write
                    // races), then back up. Clones restart on next open.
                    try { ShellEngine.forceStop(cl.pkg, cl.userId); }
                    catch (Throwable ignore) { }
                    ShellEngine.FullResult r = CloneEngine.backupAuto(
                        c, cl.pkg, cl.userId, opts.clone());
                    if (r != null && r.level == -2) skip++;
                    else ok++;
                } catch (Throwable t) {
                    fails.add(cl.pkg + ": " + t.getMessage());
                }
            }
        } finally {
            try { db.close(); } catch (Throwable ignore) { }
        }
        sb.append("scheduled: ").append(ok).append(" ok");
        if (skip > 0) sb.append(", ").append(skip).append(" blacklisted");
        if (!fails.isEmpty()) {
            sb.append(", ").append(fails.size()).append(" failed: ");
            for (int i = 0; i < Math.min(3, fails.size()); i++) {
                if (i > 0) sb.append("; ");
                sb.append(fails.get(i));
            }
        }
        Log.i("Clonix", sb.toString());
        try { EngineLog.i(c, "sched", sb.toString()); } catch (Throwable ignore) { }
        try { BackupJob.touchLastBackup(c); } catch (Throwable ignore) { }
        try { BackupJob.setLastSchedule(c, sb.toString()); }
        catch (Throwable ignore) { }
        // Headless run: the notification IS the report (distinct tones).
        try {
            Context app = c.getApplicationContext();
            if (fails.isEmpty()) Notify.ok(app, "Clonix", sb.toString());
            else Notify.fail(app, "Clonix", sb.toString());
        } catch (Throwable ignore) { }
        return sb.toString();
    }

    /** Periodic worker. */
    public static class SchedJob extends JobService {
        static final int JOB_ID = 4202;

        @Override public boolean onStartJob(JobParameters params) {
            // Take the shared engine lane: never races user-initiated backups.
            new Thread(() -> {
                try {
                    synchronized (OpsQueue.LOCK) {
                        runOnce(getApplicationContext());
                    }
                } catch (Throwable t) {
                    Log.w("Clonix", "scheduled run failed", t);
                } finally {
                    try { jobFinished(params, false); } catch (Throwable ignore) { }
                }
            }).start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) {
            return false;
        }
    }
}
