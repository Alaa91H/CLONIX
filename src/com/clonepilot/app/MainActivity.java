package com.clonepilot.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clone console: one row per app with its clones.
 * Tap a row to manage (open / home shortcut / rename / delete),
 * [+] button (or long-press) to add another copy.
 */
public class MainActivity extends Activity {
    private CloneDatabase db;
    private PackageManager pm;
    private RecyclerView list;
    private AppAdapter adapter;

    static class Row {
        ApplicationInfo ai;
        String label;
        Drawable icon;
        List<CloneDatabase.Clone> clones = new ArrayList<>();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        db = new CloneDatabase(this);
        pm = getPackageManager();
        MaterialToolbar bar = findViewById(R.id.toolbar);
        bar.setTitle(R.string.app_name);
        bar.setSubtitle(R.string.app_subtitle);
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter(this);
        list.setAdapter(adapter);
        TextInputEditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { adapter.filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        findViewById(R.id.btn_storage).setOnClickListener(v ->
            startActivity(new Intent(this, StorageActivity.class)));
        findViewById(R.id.btn_badge).setOnClickListener(v -> showBadgeDialog());
        checkEngine();
        reload();
    }

    @Override protected void onResume() { super.onResume(); reload(); }

    private void reload() {
        new Thread(() -> {
            List<Row> rows = loadRows();
            runOnUiThread(() -> {
                adapter.setRows(rows);
                findViewById(R.id.empty).setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    /** Show one-time engine status; guide to grant root when nothing works. */
    private void checkEngine() {
        new Thread(() -> {
            final ShellEngine.Mode m;
            try { m = ShellEngine.mode(this); }
            catch (Throwable t) { return; }
            runOnUiThread(() -> {
                if (m == ShellEngine.Mode.NONE) {
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.engine_title)
                        .setMessage(R.string.engine_none)
                        .setPositiveButton(R.string.got_it, (d, w) -> ShellEngine.invalidate())
                        .setCancelable(false)
                        .show();
                } else {
                    Toast.makeText(this,
                        m == ShellEngine.Mode.ROOT ? R.string.engine_root : R.string.engine_direct,
                        Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private List<Row> loadRows() {
        Map<String, Row> map = new HashMap<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : ris) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(ri.activityInfo.packageName, 0);
                if (!CloneManager.isCloneable(this, ai)) continue;
                if (map.containsKey(ai.packageName)) continue;
                Row r = new Row();
                r.ai = ai;
                r.label = String.valueOf(pm.getApplicationLabel(ai));
                r.icon = pm.getApplicationIcon(ai);
                map.put(ai.packageName, r);
            } catch (Throwable ignore) {}
        }
        for (CloneDatabase.Clone cl : db.listAll()) {
            Row r = map.get(cl.pkg);
            if (r == null) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(cl.pkg, 0);
                    r = new Row();
                    r.ai = ai;
                    r.label = String.valueOf(pm.getApplicationLabel(ai));
                    r.icon = pm.getApplicationIcon(ai);
                    map.put(cl.pkg, r);
                } catch (Throwable t) { continue; }
            }
            r.clones.add(cl);
        }
        List<Row> rows = new ArrayList<>(map.values());
        Collections.sort(rows, (a, c) -> {
            if (!a.clones.isEmpty() && c.clones.isEmpty()) return -1;
            if (a.clones.isEmpty() && !c.clones.isEmpty()) return 1;
            return a.label.compareToIgnoreCase(c.label);
        });
        for (Row r : rows) Collections.sort(r.clones, Comparator.comparingInt(x -> x.slotIndex));
        return rows;
    }

    private void showBadgeDialog() {
        BadgeSettings s = new BadgeSettings(this);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_badge, null);
        android.widget.RadioGroup rgStyle = v.findViewById(R.id.rg_style);
        MaterialRadioButton rbRings = v.findViewById(R.id.rb_rings);
        MaterialRadioButton rbNumber = v.findViewById(R.id.rb_number);
        MaterialRadioButton rbNone = v.findViewById(R.id.rb_none);
        MaterialCheckBox cbNumber = v.findViewById(R.id.cb_number);
        android.widget.RadioGroup rgColor = v.findViewById(R.id.rg_color);
        android.widget.RadioGroup rgPos = v.findViewById(R.id.rg_pos);

        String style = s.style();
        if (BadgeSettings.STYLE_NUMBER.equals(style)) rbNumber.setChecked(true);
        else if (BadgeSettings.STYLE_NONE.equals(style)) rbNone.setChecked(true);
        else rbRings.setChecked(true);
        cbNumber.setChecked(s.showNumber());

        int[] colorBtns = {R.id.rc0, R.id.rc1, R.id.rc2, R.id.rc3, R.id.rc4, R.id.rc5, R.id.rc6};
        int ci = s.colorIndex();
        if (ci >= 0 && ci < colorBtns.length) rgColor.check(colorBtns[ci]);
        rgPos.check(BadgeSettings.POS_BL.equals(s.position()) ? R.id.rp_bl : R.id.rp_br);

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.badge_settings)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                int checkedStyle = rgStyle.getCheckedRadioButtonId();
                if (checkedStyle == R.id.rb_number) s.setStyle(BadgeSettings.STYLE_NUMBER);
                else if (checkedStyle == R.id.rb_none) s.setStyle(BadgeSettings.STYLE_NONE);
                else s.setStyle(BadgeSettings.STYLE_RINGS);
                s.setShowNumber(cbNumber.isChecked());
                int checkedColor = rgColor.getCheckedRadioButtonId();
                for (int i = 0; i < colorBtns.length; i++) {
                    if (colorBtns[i] == checkedColor) { s.setColor(BadgeSettings.COLORS[i]); break; }
                }
                int checkedPos = rgPos.getCheckedRadioButtonId();
                s.setPosition(checkedPos == R.id.rp_bl ? BadgeSettings.POS_BL : BadgeSettings.POS_BR);
                Toast.makeText(this, R.string.badge_saved, Toast.LENGTH_LONG).show();
                reload();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showCloneDialog(Row row) {
        if (row.clones.size() >= CloneManager.MAX_CLONES_PER_APP) {
            Toast.makeText(this, getString(R.string.max_reached, CloneManager.MAX_CLONES_PER_APP), Toast.LENGTH_LONG).show();
            return;
        }
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_clone, null);
        TextInputEditText nick = v.findViewById(R.id.nickname);
        MaterialCheckBox sep = v.findViewById(R.id.separate_contacts);
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.clone_n, row.clones.size() + 1) + " • " + row.label)
            .setMessage(R.string.disclaimer)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> doClone(row,
                nick.getText() == null ? "" : nick.getText().toString().trim(), sep.isChecked()))
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void doClone(Row row, String nickname, boolean separate) {
        Toast.makeText(this, "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int userId = CloneManager.cloneToNextSlot(this, db, row.ai.packageName, nickname, separate);
            runOnUiThread(() -> {
                if (userId >= 0) {
                    Toast.makeText(this, getString(R.string.clone_created, db.listForPkg(row.ai.packageName).size(), row.label), Toast.LENGTH_LONG).show();
                    reload();
                    CloneManager.launchClone(this, row.ai.packageName, userId);
                } else {
                    Toast.makeText(this, R.string.not_cloneable, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private String cloneTitle(Row row, CloneDatabase.Clone cl) {
        return (cl.nickname != null && !cl.nickname.isEmpty())
            ? cl.nickname : (row.label + " " + cl.slotIndex);
    }

    private void showManageDialog(Row row) {
        if (row.clones.isEmpty()) { showCloneDialog(row); return; }
        List<String> items = new ArrayList<>();
        for (CloneDatabase.Clone cl : row.clones) {
            String title = cloneTitle(row, cl);
            items.add(getString(R.string.manage_open, title));
            items.add(getString(R.string.manage_shortcut, title));
            items.add(getString(R.string.manage_rename, title));
            items.add(getString(R.string.manage_delete, title));
        }
        items.add(getString(R.string.clone_n, row.clones.size() + 1));
        new MaterialAlertDialogBuilder(this)
            .setTitle(row.label + " • " + getString(R.string.clones_count, row.clones.size()))
            .setItems(items.toArray(new String[0]), (d, which) -> {
                if (which == items.size() - 1) { showCloneDialog(row); return; }
                CloneDatabase.Clone cl = row.clones.get(which / 4);
                switch (which % 4) {
                    case 0: CloneManager.launchClone(this, cl.pkg, cl.userId); break;
                    case 1: createShortcut(row, cl); break;
                    case 2: showRenameDialog(row, cl); break;
                    default:
                        new MaterialAlertDialogBuilder(this)
                            .setTitle(cloneTitle(row, cl))
                            .setMessage(R.string.delete)
                            .setPositiveButton(R.string.delete, (dd, ww) -> new Thread(() -> {
                                CloneManager.deleteClone(this, db, cl.pkg, cl.userId);
                                runOnUiThread(() -> {
                                    Toast.makeText(this, R.string.clone_deleted, Toast.LENGTH_SHORT).show();
                                    reload();
                                });
                            }).start())
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                        break;
                }
            }).show();
    }

    private void showRenameDialog(Row row, CloneDatabase.Clone cl) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        TextInputEditText name = v.findViewById(R.id.rename);
        name.setText(cl.nickname == null ? "" : cl.nickname);
        name.setHint(cloneTitle(row, cl));
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> new Thread(() -> {
                String nn = name.getText() == null ? "" : name.getText().toString().trim();
                db.updateNickname(cl.pkg, cl.userId, nn);
                runOnUiThread(this::reload);
            }).start())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void createShortcut(Row row, CloneDatabase.Clone cl) {
        try {
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm != null && !sm.isRequestPinShortcutSupported()) {
                Toast.makeText(this, R.string.shortcut_unsupported, Toast.LENGTH_SHORT).show();
                return;
            }
            String title = cloneTitle(row, cl);
            Drawable badged = DualBadgeUtil.badge(this, row.icon, cl.slotIndex);
            android.graphics.Bitmap bmp = drawableToBitmap(badged);
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 192, 192, true);
            android.content.pm.ShortcutInfo si = new android.content.pm.ShortcutInfo.Builder(this, "clone_" + cl.pkg + "_" + cl.userId)
                .setShortLabel(title)
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(scaled))
                .setIntent(CloneLauncherTrampoline.shortcutIntent(cl.pkg, cl.userId))
                .build();
            sm.requestPinShortcut(si, null);
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private static android.graphics.Bitmap drawableToBitmap(Drawable d) {
        if (d instanceof android.graphics.drawable.BitmapDrawable)
            return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        int s = 192;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        d.setBounds(0, 0, s, s);
        d.draw(c);
        return b;
    }

    class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {
        private final LayoutInflater inf;
        private List<Row> rows = new ArrayList<>();
        private List<Row> base = new ArrayList<>();
        AppAdapter(Context c) { inf = LayoutInflater.from(c); }
        void setRows(List<Row> r) {
            base = r; rows = new ArrayList<>(r);
            notifyDataSetChanged();
        }
        void filter(String q) {
            if (q == null || q.trim().isEmpty()) rows = new ArrayList<>(base);
            else {
                rows = new ArrayList<>();
                for (Row r : base) if (r.label.contains(q) || r.ai.packageName.contains(q)) rows.add(r);
            }
            notifyDataSetChanged();
        }
        @Override public int getItemCount() { return rows.size(); }
        @Override public Holder onCreateViewHolder(ViewGroup p, int t) {
            return new Holder(inf.inflate(R.layout.item_app, p, false));
        }
        @Override public void onBindViewHolder(Holder h, int pos) {
            Row r = rows.get(pos);
            if (!r.clones.isEmpty())
                h.icon.setImageDrawable(DualBadgeUtil.badge(MainActivity.this, r.icon, r.clones.get(0).slotIndex));
            else h.icon.setImageDrawable(r.icon);
            h.name.setText(r.label);
            h.sub.setText(r.ai.packageName + " • " + getString(R.string.clones_count, r.clones.size()));
            h.clone.setText(r.clones.isEmpty() ? getString(R.string.clone)
                : getString(R.string.clone_n, r.clones.size() + 1));
            h.clone.setOnClickListener(v -> showCloneDialog(r));
            if (!r.clones.isEmpty()) {
                h.open.setVisibility(View.VISIBLE);
                h.open.setOnClickListener(v -> showManageDialog(r));
            } else h.open.setVisibility(View.GONE);
            h.itemView.setOnClickListener(v -> showManageDialog(r));
        }
        class Holder extends RecyclerView.ViewHolder {
            final ImageView icon; final TextView name, sub;
            final MaterialButton clone, open;
            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                sub = v.findViewById(R.id.sub);
                clone = v.findViewById(R.id.btn_clone);
                open = v.findViewById(R.id.btn_open);
            }
        }
    }
}
