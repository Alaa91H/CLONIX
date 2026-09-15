package com.clonix.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.radiobutton.MaterialRadioButton;

/**
 * Badge editor with live preview (style/color/number/position).
 * Supports GLOBAL defaults (Settings) and per-clone overrides.
 * Extracted from the retired legacy list so every screen can use it.
 */
public final class BadgeEditor {
    private BadgeEditor() {}

    /** Global badge settings editor; refreshes pinned shortcuts on save. */
    public static void showGlobal(Activity a) {
        show(a, null, null);
    }

    /**
     * @param baseIcon preview base icon (null = app's own icon, global mode)
     * @param cl       target clone for per-clone override, or null for global
     */
    public static void show(Activity a, Drawable baseIcon,
                            CloneStore.Clone cl) {
        boolean perClone = cl != null;
        BadgeSettings s = new BadgeSettings(a);
        if (baseIcon == null) {
            try { baseIcon = a.getPackageManager()
                .getApplicationIcon(a.getPackageName()); }
            catch (Throwable ignore) { }
        }
        final Drawable base = baseIcon;

        String initStyle = !perClone ? s.style()
            : (cl.badgeStyle != null ? cl.badgeStyle : s.style());
        int initColor = !perClone ? s.color()
            : (cl.badgeColor != -1 ? cl.badgeColor : s.color());
        boolean initShow = !perClone ? s.showNumber()
            : (cl.badgeShowNum != -1 ? cl.badgeShowNum == 1 : s.showNumber());
        String initPos = !perClone ? s.position()
            : (cl.badgePos != null ? cl.badgePos : s.position());
        final int initSlot = perClone ? cl.slotIndex : 1;

        View v = LayoutInflater.from(a).inflate(R.layout.dialog_badge, null);
        ImageView preview = v.findViewById(R.id.preview);
        android.widget.RadioGroup rgStyle = v.findViewById(R.id.rg_style);
        MaterialRadioButton rbNumber = v.findViewById(R.id.rb_number);
        MaterialRadioButton rbNone = v.findViewById(R.id.rb_none);
        MaterialCheckBox cbNumber = v.findViewById(R.id.cb_number);
        android.widget.RadioGroup rgColor = v.findViewById(R.id.rg_color);
        android.widget.RadioGroup rgPos = v.findViewById(R.id.rg_pos);

        if (BadgeSettings.STYLE_NUMBER.equals(initStyle)) rbNumber.setChecked(true);
        else if (BadgeSettings.STYLE_NONE.equals(initStyle)) rbNone.setChecked(true);
        cbNumber.setChecked(initShow);

        int[] colorBtns = {R.id.rc0, R.id.rc1, R.id.rc2, R.id.rc3,
            R.id.rc4, R.id.rc5, R.id.rc6};
        int ci = 0;
        for (int i = 0; i < BadgeSettings.COLORS.length && i < colorBtns.length; i++) {
            if (BadgeSettings.COLORS[i] == initColor) { ci = i; break; }
        }
        rgColor.check(colorBtns[ci]);
        rgPos.check(BadgeSettings.POS_BL.equals(initPos) ? R.id.rp_bl : R.id.rp_br);

        Runnable refresh = () -> {
            int cs = rgStyle.getCheckedRadioButtonId();
            String st = cs == R.id.rb_number ? BadgeSettings.STYLE_NUMBER
                : cs == R.id.rb_none ? BadgeSettings.STYLE_NONE
                : BadgeSettings.STYLE_RINGS;
            int cc = rgColor.getCheckedRadioButtonId();
            int co = BadgeSettings.COLORS[0];
            for (int i = 0; i < colorBtns.length; i++) {
                if (colorBtns[i] == cc) { co = BadgeSettings.COLORS[i]; break; }
            }
            String po = rgPos.getCheckedRadioButtonId() == R.id.rp_bl
                ? BadgeSettings.POS_BL : BadgeSettings.POS_BR;
            preview.setImageDrawable(BadgeRenderer.preview(
                a, base, initSlot, st, co, cbNumber.isChecked(), po));
        };
        android.widget.RadioGroup.OnCheckedChangeListener rl =
            (g, id) -> refresh.run();
        rgStyle.setOnCheckedChangeListener(rl);
        rgColor.setOnCheckedChangeListener(rl);
        rgPos.setOnCheckedChangeListener(rl);
        cbNumber.setOnCheckedChangeListener((b, checked) -> refresh.run());
        refresh.run();

        MaterialAlertDialogBuilder bld = new MaterialAlertDialogBuilder(a)
            .setTitle(perClone ? a.getString(R.string.badge_per_clone_title,
                    cl.nickname != null && !cl.nickname.isEmpty()
                        ? cl.nickname : a.getString(R.string.clone_n,
                        cl.slotIndex))
                : a.getString(R.string.badge_settings))
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                int checkedStyle = rgStyle.getCheckedRadioButtonId();
                final String st =
                    checkedStyle == R.id.rb_number ? BadgeSettings.STYLE_NUMBER
                    : checkedStyle == R.id.rb_none ? BadgeSettings.STYLE_NONE
                    : BadgeSettings.STYLE_RINGS;
                final boolean sh = cbNumber.isChecked();
                final int co = colorFor(rgColor.getCheckedRadioButtonId(),
                    colorBtns);
                final String po = rgPos.getCheckedRadioButtonId() == R.id.rp_bl
                    ? BadgeSettings.POS_BL : BadgeSettings.POS_BR;
                if (!perClone) {
                    s.setStyle(st);
                    s.setShowNumber(sh);
                    s.setColor(co);
                    s.setPosition(po);
                    refreshAllPinned(a);
                } else {
                    CloneStore db = new CloneStore(a);
                    db.updateBadge(cl.pkg, cl.userId, st, co, sh ? 1 : 0, po);
                    try { db.close(); } catch (Throwable ignore) { }
                    refreshPinnedForPkg(a, cl.pkg);
                }
                android.widget.Toast.makeText(a, R.string.badge_saved,
                    android.widget.Toast.LENGTH_LONG).show();
            })
            .setNegativeButton(R.string.cancel, null);
        if (perClone) {
            bld.setNeutralButton(R.string.badge_use_global, (d, w) -> {
                CloneStore db = new CloneStore(a);
                db.clearBadge(cl.pkg, cl.userId);
                try { db.close(); } catch (Throwable ignore) { }
                refreshPinnedForPkg(a, cl.pkg);
            });
        }
        bld.show();
    }

    private static int colorFor(int checkedId, int[] btns) {
        for (int i = 0; i < btns.length && i < BadgeSettings.COLORS.length; i++) {
            if (btns[i] == checkedId) return BadgeSettings.COLORS[i];
        }
        return BadgeSettings.COLORS[0];
    }

    /** Re-render every pinned shortcut after a global badge change. */
    private static void refreshAllPinned(Activity a) {
        new Thread(() -> {
            CloneStore db = new CloneStore(a);
            java.util.Set<String> pkgs = new java.util.HashSet<>();
            try { for (CloneStore.Clone cl : db.listAll()) pkgs.add(cl.pkg); }
            catch (Throwable ignore) { }
            try { db.close(); } catch (Throwable ignore) { }
            for (String p : pkgs) refreshPinnedForPkg(a, p);
        }).start();
    }

    private static void refreshPinnedForPkg(Context a, String pkg) {
        new Thread(() -> {
            try {
                Drawable base = a.getPackageManager().getApplicationIcon(pkg);
                String label;
                try {
                    label = String.valueOf(a.getPackageManager()
                        .getApplicationLabel(a.getPackageManager()
                            .getApplicationInfo(pkg, 0)));
                } catch (Throwable t) { label = pkg; }
                CloneStore db = new CloneStore(a);
                java.util.List<CloneStore.Clone> cls = db.listForPkg(pkg);
                try { db.close(); } catch (Throwable ignore) { }
                for (CloneStore.Clone cl : cls) {
                    String title = (cl.nickname != null && !cl.nickname.isEmpty())
                        ? cl.nickname : (label + " " + cl.slotIndex);
                    ShortcutHelper.refresh(a, title, base, cl);
                }
            } catch (Throwable ignore) { }
        }).start();
    }
}
