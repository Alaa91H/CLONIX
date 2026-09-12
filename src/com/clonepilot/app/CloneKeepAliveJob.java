package com.clonepilot.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Keeps background clone users (slots 2+) started so their apps stay alive
 * and deliver notifications as close to original apps as the OS allows.
 * Slot 1 (clone profile) runs concurrently with the owner natively.
 * Lightweight: one `pm list users` scan every 30 min, starts only stopped slots.
 */
public class CloneKeepAliveJob extends JobService {
    private static final String TAG = "ClonePilot";
    private static final int JOB_ID = 4201;
    private static final long INTERVAL_MS = 30 * 60 * 1000L;

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) {
                if (j.getId() == JOB_ID) return; // already scheduled
            }
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(c, CloneKeepAliveJob.class))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build();
            int r = js.schedule(job);
            Log.i(TAG, "keepalive scheduled=" + r);
        } catch (Throwable t) {
            Log.w(TAG, "keepalive schedule failed", t);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { CloneManager.startAllClonesInBackground(getApplicationContext()); }
            catch (Throwable t) { Log.w(TAG, "keepalive run failed", t); }
            finally { try { jobFinished(params, false); } catch (Throwable ignore) { } }
        }).start();
        return true; // work continues on thread
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false; // do not reschedule immediately; periodic covers it
    }
}
