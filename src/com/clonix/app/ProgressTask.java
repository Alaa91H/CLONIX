package com.clonix.app;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/**
 * M3 long-task progress (replaces blocking ProgressDialog):
 * non-cancelable-by-touch dialog with indeterminate bar + live status,
 * plus "Run in background" — the work continues, Notify fires on finish,
 * user stays free to navigate (M3: wait >5s must not trap the user).
 */
public final class ProgressTask {
    private ProgressTask() {}

    public static final class Handle {
        androidx.appcompat.app.AlertDialog dialog;
        TextView status;
        volatile boolean backgrounded;

        public void setStatus(final String s) {
            try {
                final TextView st = status;
                if (st == null) return;
                android.os.Handler h =
                    new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> {
                    try { st.setText(s); } catch (Throwable ignore) { }
                });
            } catch (Throwable ignore) { }
        }

        public void dismiss() {
            try {
                final androidx.appcompat.app.AlertDialog d = dialog;
                if (d == null) return;
                android.os.Handler h =
                    new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> {
                    try { d.dismiss(); } catch (Throwable ignore) { }
                });
            } catch (Throwable ignore) { }
        }
    }

    /**
     * Show the dialog. onBackground runs on the UI thread when the user
     * taps "Run in background" (typically: just note it, keep working).
     */
    public static Handle show(final Activity a, final String title,
            final String initialStatus, final Runnable onBackground) {
        final Handle h = new Handle();
        try {
            LinearLayout root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
            root.setPadding(pad, pad / 2, pad, 0);
            LinearProgressIndicator bar = new LinearProgressIndicator(a);
            try { bar.setIndeterminate(true); } catch (Throwable ignore) { }
            root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView st = new TextView(a);
            try {
                st.setTextAppearance(
                    com.google.android.material.R.style
                        .TextAppearance_Material3_BodyMedium);
            } catch (Throwable ignore) { }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = pad / 2;
            root.addView(st, lp);
            try { st.setText(initialStatus); } catch (Throwable ignore) { }
            h.status = st;
            androidx.appcompat.app.AlertDialog d =
                new MaterialAlertDialogBuilder(a)
                    .setTitle(title)
                    .setView(root)
                    .setCancelable(false)
                    .setNeutralButton(R.string.task_background, (dd, ww) -> {
                        h.backgrounded = true;
                        if (onBackground != null) {
                            try { onBackground.run(); } catch (Throwable ignore) { }
                        }
                    })
                    .create();
            try { d.setCanceledOnTouchOutside(false); } catch (Throwable ignore) { }
            h.dialog = d;
            try { d.show(); } catch (Throwable ignore) { }
            // Live-region: TalkBack announces status changes.
            try {
                st.setAccessibilityLiveRegion(
                    View.ACCESSIBILITY_LIVE_REGION_POLITE);
            } catch (Throwable ignore) { }
        } catch (Throwable ignore) { }
        return h;
    }
}
