package com.clonix.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.List;

/**
 * Auto-backup of freshly installed/updated apps (opt-in).
 *
 * Android 14+ forbids heavy work directly from PACKAGE_ADDED/REPLACED
 * receivers (no foreground service from broadcast), so the receiver only
 * schedules this expedited one-shot job; the engine lane runs the backup
 * exactly like a scheduled run: force-stop -> backupAuto -> report.
 */
public final class AutoBackupJob extends JobService {
    private static final int JOB_ID = 4206;
    private static final long DEADLINE_MS = 10 * 60 * 1000L;

    /** Called from the package receiver; coalesces duplicates. */
    public static void scheduleFor(Context c, String pkg) {
        try {
            JobScheduler js = (JobScheduler)
                c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(c, AutoBackupJob.class))
                .setExpedited(true)
                .setOverrideDeadline(DEADLINE_MS)
                .setMinimumLatency(15_000L) // let the installer settle
                .build();
            js.schedule(job);
            Log.i("Clonix", "auto-backup queued for " + pkg);
        } catch (Throwable t) {
            Log.w("Clonix", "auto-backup schedule failed", t);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            String summary;
            try {
                synchronized (OpsQueue.LOCK) {
                    summary = runForPending(app);
                }
            } catch (Throwable t) {
                Log.w("Clonix", "auto-backup run failed", t);
                summary = "auto-backup failed: " + t.getMessage();
            } finally {
                try { jobFinished(params, false); } catch (Throwable ignore) { }
            }
            try { EngineLog.i(app, "auto", summary); } catch (Throwable ignore) { }
            try { WidgetUpdater.updateAll(app); } catch (Throwable ignore) { }
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }

    /** Back up all tracked clones of the newly installed package. */
    private static String runForPending(Context c) {
        CloneStore db = new CloneStore(c);
        StringBuilder sb = new StringBuilder("auto-backup:");
        int ok = 0, skip = 0;
        try {
            // The package being installed/updated may be an existing clone,
            // a tracked owner app, or unrelated. Only tracked ones matter.
            List<CloneStore.Clone> targets;
            String pending = Prefs.pendingAutoPkg(c);
            targets = db.listAll();
            for (CloneStore.Clone cl : targets) {
                if (pending == null || !pending.equals(cl.pkg)) continue;
                if (BackupJob.isBlacklisted(c, cl.pkg)) { skip++; continue; }
                try {
                    try { ShellEngine.forceStop(cl.pkg, cl.userId); }
                    catch (Throwable ignore) { }
                    ShellEngine.FullResult r = CloneEngine.backupAuto(
                        c, cl.pkg, cl.userId, BackupJob.defaults(c).clone());
                    if (r != null && r.level == -2) continue; // unchanged
                    ok++;
                    sb.append(" ").append(cl.pkg).append("(u").append(cl.userId).append(")");
                } catch (Throwable t) {
                    sb.append(" FAIL ").append(cl.pkg).append(": ")
                      .append(t.getMessage());
                }
            }
            if (ok == 0 && skip == 0) sb.append(" nothing tracked");
        } finally {
            try { db.close(); } catch (Throwable ignore) { }
        }
        Prefs.clearPendingAutoPkg(c);
        if (ok > 0) {
            Notify.ok(c, c.getString(R.string.set_autobackup), sb.toString());
        }
        return sb.toString();
    }
}
