package com.clonix.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Settings export/import: a single JSON file covering all preference
 * stores (settings, backup_opts incl. blacklist, sched, badge) so a new
 * device or fresh install restores the whole configuration in one tap.
 * Secrets (session passwords) and database content are NOT included.
 */
public final class SettingsBackup {
    private SettingsBackup() {}

    private static final String[] STORES =
        {"settings", "backup_opts", "backup_sched", "badge"};

    public static File export(Context c) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "clonix");
        root.put("version", 1);
        for (String store : STORES) {
            SharedPreferences p =
                c.getSharedPreferences(store, Context.MODE_PRIVATE);
            JSONObject o = new JSONObject();
            for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof Set) {
                    o.put(e.getKey(), new org.json.JSONArray((Set<?>) v));
                } else if (v != null) {
                    o.put(e.getKey(), v);
                }
            }
            root.put(store, o);
        }
        File f = new File(c.getCacheDir(), "clonix_settings.json");
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        fo.close();
        return f;
    }

    public static int importInto(Context c, File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int read = fi.read(b);
        fi.close();
        if (read <= 0) throw new Exception("empty file");
        JSONObject root = new JSONObject(new String(b, StandardCharsets.UTF_8));
        if (!"clonix".equals(root.optString("app"))) {
            throw new Exception("not a clonix settings file");
        }
        int applied = 0;
        for (String store : STORES) {
            if (!root.has(store)) continue;
            JSONObject o = root.getJSONObject(store);
            SharedPreferences.Editor ed =
                c.getSharedPreferences(store, Context.MODE_PRIVATE).edit();
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = o.get(k);
                if (v instanceof org.json.JSONArray) {
                    Set<String> s = new HashSet<>();
                    org.json.JSONArray arr = (org.json.JSONArray) v;
                    for (int j = 0; j < arr.length(); j++) {
                        s.add(String.valueOf(arr.get(j)));
                    }
                    ed.putStringSet(k, s);
                } else if (v instanceof Boolean) {
                    ed.putBoolean(k, (Boolean) v);
                } else if (v instanceof Integer) {
                    ed.putInt(k, (Integer) v);
                } else if (v instanceof Long) {
                    ed.putLong(k, (Long) v);
                } else if (v instanceof Double) {
                    ed.putFloat(k, (float) (double) (Double) v);
                } else {
                    ed.putString(k, String.valueOf(v));
                }
                applied++;
            }
            ed.apply();
        }
        return applied;
    }

    /** Hand the exported file to the sharesheet. */
    public static void share(Activity a) {
        try {
            File f = export(a);
            ShareHelper.shareFiles(a, java.util.Collections.singletonList(f),
                "application/json", "clonix_settings");
        } catch (Throwable t) {
            android.widget.Toast.makeText(a, String.valueOf(t.getMessage()),
                android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
