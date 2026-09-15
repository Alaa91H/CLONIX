package com.clonix.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Archives at a tap: bottom sheet with summary + newest archives.
 * Tap an archive = unified restore wizard; Open all = full manager.
 */
public final class ArchivesSheet {
    private ArchivesSheet() {}

    public static void show(final Activity a) {
        final BottomSheetDialog sheet = new BottomSheetDialog(a);
        View v = LayoutInflater.from(a).inflate(R.layout.sheet_archives, null);
        sheet.setContentView(v);
        TextView summary = v.findViewById(R.id.sheet_summary);
        RecyclerView list = v.findViewById(R.id.sheet_list);
        list.setLayoutManager(new LinearLayoutManager(a));
        final SheetAdapter adapter = new SheetAdapter(a, sheet);
        list.setAdapter(adapter);
        summary.setText(a.getString(R.string.loading));
        v.findViewById(R.id.sheet_open_all).setOnClickListener(x -> {
            try { sheet.dismiss(); } catch (Throwable ignore) { }
            try {
                a.startActivity(new Intent(a, ArchivesActivity.class));
            } catch (Throwable ignore) { }
        });
        v.findViewById(R.id.sheet_verify).setOnClickListener(x -> {
            Toast.makeText(a, R.string.verifying, Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                final String res = VerifyScheduler.runOnce(a);
                a.runOnUiThread(() -> {
                    Toast.makeText(a, res, Toast.LENGTH_LONG).show();
                    try { sheet.dismiss(); } catch (Throwable ignore) { }
                });
            }).start();
        });
        sheet.show();
        try {
            sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            sheet.getBehavior().setSkipCollapsed(true);
        } catch (Throwable ignore) { }
        new Thread(() -> {
            List<ShellEngine.Backup> all = CloneEngine.listFullBackups(a, null);
            try {
                for (ShellEngine.Backup b : ShellEngine.listBackups("__none__")) {
                    boolean dup = false;
                    for (ShellEngine.Backup x : all) {
                        if (x.path.equals(b.path)) { dup = true; break; }
                    }
                    if (!dup) all.add(b);
                }
            } catch (Throwable ignore) { }
            List<ShellEngine.Backup> top = all.subList(0, Math.min(8, all.size()));
            long total = 0;
            for (ShellEngine.Backup b : all) if (b.size > 0) total += b.size;
            final List<ShellEngine.Backup> topF = new ArrayList<>(top);
            final int n = all.size();
            final long totalF = total;
            a.runOnUiThread(() -> {
                try {
                    summary.setText(a.getString(R.string.archives_summary, n,
                        CloneUsage.formatSize(a, totalF)));
                    adapter.setRows(topF);
                } catch (Throwable ignore) { }
            });
        }).start();
    }

    static class SheetAdapter extends RecyclerView.Adapter<SheetAdapter.Holder> {
        private final Activity a;
        private final BottomSheetDialog sheet;
        private List<ShellEngine.Backup> rows = new ArrayList<>();
        private final LayoutInflater inf;
        SheetAdapter(Activity a, BottomSheetDialog s) {
            this.a = a;
            sheet = s;
            inf = LayoutInflater.from(a);
        }
        void setRows(List<ShellEngine.Backup> r) {
            rows = r;
            notifyDataSetChanged();
        }
        @Override public int getItemCount() { return rows.size(); }
        @Override public Holder onCreateViewHolder(ViewGroup p, int t) {
            return new Holder(inf.inflate(R.layout.item_archive_mini, p, false));
        }
        @Override public void onBindViewHolder(Holder h, int pos) {
            final ShellEngine.Backup bk = rows.get(pos);
            String n = bk.name;
            h.name.setText(Bidi.isolate(n));
            String size = bk.size >= 0
                ? CloneUsage.formatSize(a, bk.size) : "?";
            String when = ShellEngine.prettyBackupDate(a, n);
            h.detail.setText((bk.enc ? "🔒 " : "") + Bidi.isolate(size)
                + (when.equals(n) ? "" : " • " + Bidi.isolate(when)));
            h.itemView.setOnClickListener(x -> {
                try { sheet.dismiss(); } catch (Throwable ignore) { }
                restoreDirect(a, bk);
            });
        }
        class Holder extends RecyclerView.ViewHolder {
            final TextView name, detail;
            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                detail = v.findViewById(R.id.detail);
            }
        }
    }

    /** Direct restore from the sheet (wizard, no manager needed). */
    static void restoreDirect(Activity a, ShellEngine.Backup bk) {
        String n = bk.name;
        if (n.startsWith("special_")) {
            String kind = "";
            for (String k : ShellEngine.SPECIAL_KINDS) {
                if (n.startsWith("special_" + k + "_")) kind = k;
            }
            if (!kind.isEmpty()) {
                RestoreWizard.startSpecial(a, kind, n);
                return;
            }
        }
        int u = n.indexOf("_u");
        if (u <= 0) {
            Toast.makeText(a, R.string.restore_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String pkg = n.substring(0, u);
        int userId = -1;
        try {
            String rest = n.substring(u + 2);
            int us = rest.indexOf('_');
            userId = Integer.parseInt(us > 0 ? rest.substring(0, us) : rest);
        } catch (Throwable ignore) { }
        if (userId < 0) {
            Toast.makeText(a, R.string.restore_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        CloneStore db = new CloneStore(a);
        RestoreWizard.startWithArchive(a, db, pkg, userId, n, bk.path, null);
    }
}
