package com.clonix.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * EngineLog viewer: rolling in-app log of engine operations (backup / restore /
 * verify / clone / errors) shown in Settings. Powers bug reports without
 * needing logcat access. Capped at 500 lines, persisted to a private file.
 */
public final class EngineLog {
    private EngineLog() {}

    private static final int MAX_LINES = 500;
    private static final Deque<String> MEM = new ArrayDeque<>(MAX_LINES);

    private static File file(Context c) {
        return new File(c.getFilesDir(), "engine.log");
    }

    /** Thread-safe append; keeps memory ring + file in sync. */
    public static void i(Context c, String tag, String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            .format(new Date()) + " [" + tag + "] " + msg;
        synchronized (MEM) {
            MEM.addLast(line);
            while (MEM.size() > MAX_LINES) MEM.removeFirst();
        }
        try {
            // Cheap persistence: rewrite ring (500 lines max, ~40KB worst case).
            StringBuilder sb = new StringBuilder();
            synchronized (MEM) {
                for (String l : MEM) sb.append(l).append('\n');
            }
            try (PrintWriter w = new PrintWriter(new FileWriter(file(c)))) {
                w.write(sb.toString());
            }
        } catch (Throwable ignore) { }
    }

    /** Whole log, memory first then whatever is on disk. */
    public static String dump(Context c) {
        StringBuilder sb = new StringBuilder();
        synchronized (MEM) {
            for (String l : MEM) sb.append(l).append('\n');
        }
        if (MEM.isEmpty()) {
            try (BufferedReader r = new BufferedReader(
                    new FileReader(file(c)))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (Throwable ignore) { }
        }
        return sb.toString();
    }

    public static void clear(Context c) {
        synchronized (MEM) { MEM.clear(); }
        try { //noinspection ResultOfMethodCallIgnored
            file(c).delete();
        } catch (Throwable ignore) { }
    }

    /** Stage the log file for sharing (activity starts the sheet). */
    public static File stage(Context c) {
        try {
            File out = new File(c.getCacheDir(), "clonix_log.txt");
            try (PrintWriter w = new PrintWriter(new FileWriter(out))) {
                w.write(dump(c));
            }
            return out;
        } catch (Throwable t) { return null; }
    }
}
