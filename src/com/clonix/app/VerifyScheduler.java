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
 * Scheduled integrity verification (SureBackup-style, local edition):
 * periodically re-verifies every archive (SHA-256 + tar listing),
 * marks healthy ones .verified, and reports with distinct tones.
 * Password-locked archives verify only while the session password is set
 * (headless runs report them as unchecked, never as failed).
 */
public final class VerifyScheduler {
    private VerifyScheduler() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("verify_sched", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        try { return p(c).getBoolean("on", false); }
        catch (Throwable t) { return false; }
    }

    /** Hours between runs (min 1, default weekly). */
    public static int intervalHours(Context c) {
        try { return Math.max(1, p(c).getInt("hours", 168)); }
        catch (Throwable t) { return 168; }
    }

    /** Unit the user picked in UI: "h" or "d" (days). Storage stays in hours. */
    public static String intervalUnit(Context c) {
        try { return "d".equals(p(c).getString("unit", "d")) ? "d" : "h"; }
        catch (Throwable t) { return "d"; }
    }

    /** Value in the user's unit (e.g. 7 when unit=days and hours=168). */
    public static int intervalValue(Context c) {
        int h = intervalHours(c);
        return "d".equals(intervalUnit(c)) ? Math.max(1, h / 24) : h;
    }

    public static boolean requireCharging(Context c) {
        try { return p(c).getBoolean("charging", true); }
        catch (Throwable t) { return true; }
    }

    public static String lastRun(Context c) {
        try {
            String s = p(c).getString("last", null);
            return s != null ? s : "";
        } catch (Throwable t) { return ""; }
    }

    private static void setLastRun(Context c, String s) {
        try { p(c).edit().putString("last", s).apply(); }
        catch (Throwable ignore) { }
    }

    public static void save(Context c, boolean on, int hours, boolean charging) {
        save(c, on, hours, intervalUnit(c), charging);
    }

    /** Save with explicit unit: days are converted to hours (x24). */
    public static void save(Context c, boolean on, int value, String unit,
            boolean charging) {
        boolean days = "d".equals(unit);
        int hours = Math.max(1, days ? value * 24 : value);
        try {
            p(c).edit().putBoolean("on", on).putInt("hours", hours)
                .putString("unit", days ? "d" : "h")
                .putBoolean("charging", charging).apply();
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
            if (interval < 3600000L) interval = 3600000L; // platform minimum
            JobInfo job = new JobInfo.Builder(SchedJob.JOB_ID,
                    new ComponentName(c, SchedJob.class))
                .setPeriodic(interval)
                .setPersisted(true)
                .setRequiresCharging(requireCharging(c))
                .setRequiresDeviceIdle(false)
                .build();
            int r = js.schedule(job);
            Log.i("Clonix", "verify schedule every=" + intervalHours(c)
                + "h result=" + r);
        } catch (Throwable t) {
            Log.w("Clonix", "verify sync failed", t);
        }
    }

    /** One verification sweep (also used by "Verify all" + TEST). */
    public static String runOnce(Context c) {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 9L * 60L * 1000L;
        char[] pw = CryptoVault.hasSessionPassword()
            ? CryptoVault.sessionPassword() : null;
        int ok = 0, bad = 0, locked = 0;
        List<String> badNames = new ArrayList<>();
        try {
            List<ShellEngine.Backup> all =
                CloneEngine.listFullBackups(c, null);
            for (ShellEngine.Backup bk : all) {
                if (System.currentTimeMillis() > deadline) break;
                if (bk.enc && pw == null) { locked++; continue; }
                ShellEngine.VerifyResult vr =
                    ShellEngine.verifyArchive(bk.path, bk.enc ? pw : null);
                if (vr.ok) {
                    ok++;
                    markVerified(bk.path);
                } else {
                    bad++;
                    if (badNames.size() < 3) {
                        String n = bk.path.substring(bk.path.lastIndexOf('/') + 1);
                        badNames.add(n);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("Clonix", "verify sweep failed", t);
        }
        sb.append("verified ").append(ok).append(" ok");
        if (bad > 0) sb.append(", ").append(bad).append(" FAILED");
        if (locked > 0) sb.append(", ").append(locked).append(" locked");
        if (!badNames.isEmpty()) {
            sb.append(": ");
            for (int i = 0; i < badNames.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(badNames.get(i));
            }
        }
        Log.i("Clonix", sb.toString());
        try { EngineLog.i(c, "verify", sb.toString()); } catch (Throwable ignore) { }
        try { BackupJob.touchLastBackup(c); } catch (Throwable ignore) { }
        setLastRun(c, sb.toString());
        try {
            Context app = c.getApplicationContext();
            if (bad == 0) Notify.ok(app, "Clonix", sb.toString());
            else Notify.fail(app, "Clonix", sb.toString());
        } catch (Throwable ignore) { }
        return sb.toString();
    }

    static void markVerified(String archive) {
        try {
            String v = ShellEngine.metaPathFor(archive).replace(".meta", ".verified")
                .replace(".verified.enc", ".verified");
            if (ShellEngine.isDirectPath(archive)) {
                try {
                    new java.io.FileOutputStream(v).close();
                } catch (Throwable ignore) { }
            } else {
                ShellEngine.execRootGlobal(new String[]{"sh", "-c",
                    "echo ok > '" + v + "'"}, 30000);
            }
        } catch (Throwable ignore) { }
    }

    /** Periodic worker. */
    public static class SchedJob extends JobService {
        static final int JOB_ID = 4203;

        @Override public boolean onStartJob(JobParameters params) {
            // Same engine lane as backups: verify never races a backup.
            new Thread(() -> {
                try {
                    synchronized (OpsQueue.LOCK) {
                        runOnce(getApplicationContext());
                    }
                } catch (Throwable t) {
                    Log.w("Clonix", "verify run failed", t);
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
