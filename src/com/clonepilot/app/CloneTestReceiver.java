package com.clonepilot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.util.Log;

/**
 * Headless E2E test hook (NO UI taps needed).
 * Trigger: adb shell am broadcast -a com.clonepilot.app.TEST \
 *   -n com.clonepilot.app/.CloneTestReceiver \
 *   --es op clone --es pkg org.telegram.messenger --es token <token>
 * Token: adb shell run-as com.clonepilot.app cat files/test_token
 * Ops: clone | launch | delete | status (+ diag). Results go to logcat
 * and to the broadcast resultData.
 *
 * SECURITY: token from app-private file (shell via run-as) is decisive;
 * sender-uid attribution is a secondary check. Safe to ship.
 */
public class CloneTestReceiver extends BroadcastReceiver {
    private static final String TAG = "ClonePilot";
    public static final String ACTION = "com.clonepilot.app.TEST";

    /**
     * True sender uid. Binder.getCallingUid() inside onReceive returns
     * system_server, NOT the sender — must use getSendingUid() (API 34+)
     * via reflection to stay compatible with minSdk 29.
     */
    private int sendingUid() {
        try {
            java.lang.reflect.Method m = getClass().getMethod("getSendingUid");
            Object r = m.invoke(this);
            return (Integer) r;
        } catch (Throwable t) {
            return Binder.getCallingUid();
        }
    }

    @Override public void onReceive(Context c, Intent i) {
        int caller = sendingUid();
        int binder = Binder.getCallingUid();
        int self = -1;
        try {
            self = c.getPackageManager().getPackageUid(c.getPackageName(), 0);
        } catch (Throwable ignore) { }
        // Diag mode: expose attribution values in resultData (harmless).
        if (i != null && i.getBooleanExtra("diag", false)) {
            setResultData("binder=" + binder + ",sending=" + caller + ",self=" + self);
            return;
        }
        if (caller != 0 && caller != 2000 && caller != self) {
            Log.w(TAG, "TEST rejected for uid " + caller);
            setResultData("rejected:uid=" + caller);
            return;
        }
        // Decisive gate: token from app-private file (shell reads via run-as).
        if (i == null || !TestToken.check(c, i.getStringExtra("token"))) {
            Log.w(TAG, "TEST rejected (bad token), attrib uid=" + caller);
            setResultData("rejected:bad-token");
            return;
        }
        Log.i(TAG, "TEST authorized attrib=" + caller);
        if (i == null || !ACTION.equals(i.getAction())) return;
        String op = i.getStringExtra("op");
        String pkg = i.getStringExtra("pkg");
        int userId = i.getIntExtra("userId", -1);
        String res;
        try {
            CloneDatabase db = new CloneDatabase(c);
            if ("status".equals(op)) {
                res = "engine=" + ShellEngine.mode(c)
                    + " clones=" + db.listAll().size();
            } else if ("clone".equals(op) && pkg != null) {
                String nick = i.getStringExtra("nickname");
                boolean sep = i.getBooleanExtra("separate", true);
                int uid = CloneManager.cloneToNextSlot(c, db, pkg,
                    nick == null ? "" : nick, sep);
                res = uid >= 0 ? "cloned:" + pkg + ":u" + uid : "clone:FAILED";
            } else if ("launch".equals(op) && pkg != null && userId >= 0) {
                CloneManager.launchClone(c, pkg, userId);
                res = "launched:" + pkg + ":u" + userId;
            } else if ("delete".equals(op) && pkg != null && userId >= 0) {
                CloneManager.deleteClone(c, db, pkg, userId);
                res = "deleted:" + pkg + ":u" + userId;
            } else {
                res = "usage: op=clone|launch|delete|status pkg=... userId=..";
            }
        } catch (Throwable t) {
            res = "error:" + t;
            Log.e(TAG, "TEST failed", t);
        }
        Log.i(TAG, "TEST " + op + " -> " + res);
        setResultData(res);
        try { Thread.sleep(300); } catch (Throwable ignore) { }
    }
}
