package com.clonix.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Share archives / APKs through the system sharesheet (Quick Share /
 * Nearby Share appears there automatically). Root-only files are staged
 * into the app cache first; the cache is wiped afterwards best-effort.
 */
public final class ShareHelper {
    private ShareHelper() {}

    public static final String AUTHORITY = "com.clonix.app.share";

    /** Stage root-only paths into getCacheDir()/share, return staged Files. */
    public static List<File> stage(Activity a, List<String> paths) {
        List<File> out = new ArrayList<>();
        if (paths == null) return out;
        File dir = new File(a.getCacheDir(), "share");
        try { dir.mkdirs(); } catch (Throwable ignore) { }
        for (String p : paths) {
            try {
                if (p == null) continue;
                File src = new File(p);
                if (!src.getName().matches("[A-Za-z0-9_.\\-]+")) continue;
                File dst = new File(dir, src.getName());
                if (ShellEngine.isDirectPath(p)) {
                    if (src.canRead()) {
                        copyJava(src, dst);
                        out.add(dst);
                    }
                    continue;
                }
                // Root-only: cp via su -mm into our cache (app-writable).
                ShellEngine.ExecResult r = ShellEngine.execRootGlobal(
                    new String[]{"sh", "-c",
                        "cp '" + p + "' '" + dst.getAbsolutePath() + "'"
                        + " && chmod 644 '" + dst.getAbsolutePath() + "'"
                        + " && echo STAGE_OK"}, 300000);
                if (r.ok && r.out.contains("STAGE_OK") && dst.exists()) {
                    out.add(dst);
                }
            } catch (Throwable ignore) { }
        }
        return out;
    }

    private static void copyJava(File src, File dst) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(src);
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                try { fos.close(); } catch (Throwable ignore) { }
            }
        } finally {
            try { fis.close(); } catch (Throwable ignore) { }
        }
    }

    /** Share staged files (APKs: application/vnd.android.package-archive). */
    public static void shareFiles(Activity a, List<File> files, String mime,
            String title) {
        try {
            if (files == null || files.isEmpty()) {
                Toast.makeText(a, R.string.share_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) {
                try {
                    uris.add(androidx.core.content.FileProvider.getUriForFile(
                        a, AUTHORITY, f));
                } catch (Throwable ignore) { }
            }
            if (uris.isEmpty()) {
                Toast.makeText(a, R.string.share_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            i.setType(mime != null ? mime : "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                i.setClipData(android.content.ClipData.newRawUri("", uris.get(0)));
            } catch (Throwable ignore) { }
            a.startActivity(Intent.createChooser(i, title));
        } catch (Throwable t) {
            Toast.makeText(a, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    public static void wipeStage(Activity a) {
        try {
            File dir = new File(a.getCacheDir(), "share");
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) {
                try { f.delete(); } catch (Throwable ignore) { }
            }
        } catch (Throwable ignore) { }
    }
}
