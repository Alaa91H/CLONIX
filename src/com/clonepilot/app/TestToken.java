package com.clonepilot.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;

/**
 * Headless-test token. Stored in app-private files/ (0700, unreadable by
 * other non-root apps). Shell reads it for test broadcasts:
 *   adb shell run-as com.clonepilot.app cat files/test_token
 * then passes it as --es token <value>.
 */
public final class TestToken {
    private static final String TAG = "ClonePilot";
    private static final String FILE = "test_token";

    private TestToken() {}

    /** Return existing token, generating + persisting one if absent. */
    public static synchronized String ensure(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] b = new byte[(int) f.length()];
                    int n = 0, r;
                    while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                    String t = new String(b, "UTF-8").trim();
                    if (t.length() >= 16) return t;
                } finally { try { in.close(); } catch (Throwable ignore) { } }
            }
            byte[] rnd = new byte[24];
            new SecureRandom().nextBytes(rnd);
            StringBuilder sb = new StringBuilder();
            for (byte x : rnd) sb.append("0123456789abcdef".charAt((x & 0xF0) >>> 4))
                                 .append("0123456789abcdef".charAt(x & 0x0F));
            String t = sb.toString();
            FileOutputStream out = c.openFileOutput(FILE, Context.MODE_PRIVATE);
            try { out.write(t.getBytes("UTF-8")); }
            finally { try { out.close(); } catch (Throwable ignore) { } }
            return t;
        } catch (Throwable t) {
            Log.e(TAG, "token failed", t);
            return "";
        }
    }

    public static boolean check(Context c, String got) {
        if (got == null || got.isEmpty()) return false;
        String want = ensure(c);
        if (want.isEmpty() || want.length() != got.length()) return false;
        int diff = 0;
        for (int i = 0; i < want.length(); i++) diff |= want.charAt(i) ^ got.charAt(i);
        return diff == 0;
    }
}
