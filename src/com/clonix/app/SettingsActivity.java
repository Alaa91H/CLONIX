package com.clonix.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

/** Sectioned settings: Storage/security, Notifications, Advanced, Help. */
public class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        ThemeHelper.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        findViewById(R.id.btn_backup_center).setOnClickListener(v ->
            startActivity(new Intent(this, StorageActivity.class)));
        findViewById(R.id.btn_backup_center2).setOnClickListener(v ->
            startActivity(new Intent(this, BackupCenterActivity.class)));

        View enc = findViewById(R.id.btn_retention);
        enc.setOnClickListener(v -> showPasswordDialog());

        findViewById(R.id.btn_scan_invalid).setOnClickListener(v ->
            Maintenance.scanInvalid(this));

        findViewById(R.id.btn_apply_retention).setOnClickListener(v ->
            Maintenance.applyRetentionNow(this));

        findViewById(R.id.btn_manage_space).setOnClickListener(v ->
            showRetentionDialog());

        findViewById(R.id.btn_channels).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(this, String.valueOf(t.getMessage()),
                    Toast.LENGTH_LONG).show();
            }
        });

        MaterialSwitch swSounds = findViewById(R.id.sw_sounds);
        swSounds.setChecked(Prefs.sounds(this));
        swSounds.setOnCheckedChangeListener((v, checked) ->
            Prefs.setSounds(this, checked));

        findViewById(R.id.btn_badge).setOnClickListener(v ->
            BadgeEditor.showGlobal(this));

        View theme = findViewById(R.id.btn_theme);
        theme.setOnClickListener(v -> showThemeDialog());

        View lang = findViewById(R.id.btn_language);
        lang.setOnClickListener(v -> openLocale());

        View nohib = findViewById(R.id.btn_nohibernate);
        nohib.setOnClickListener(v -> {
            try {
                startActivity(new Intent(
                    "android.intent.action.AUTO_REVOKE_PERMISSIONS")
                    .setData(Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                Toast.makeText(this, String.valueOf(t.getMessage()),
                    Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.btn_logger).setOnClickListener(v -> showLogger());

        findViewById(R.id.btn_restart).setOnClickListener(v -> restartApp());

        MaterialSwitch swPin = findViewById(R.id.sw_autopin);
        swPin.setChecked(Prefs.autoPin(this));
        swPin.setOnCheckedChangeListener((v, checked) ->
            Prefs.setAutoPin(this, checked));

        MaterialSwitch swFreeze = findViewById(R.id.sw_autofreeze);
        swFreeze.setChecked(Prefs.autoFreeze(this));
        swFreeze.setOnCheckedChangeListener((v, checked) -> {
            Prefs.setAutoFreeze(this, checked);
            AutoFreezeService.sync(this);
        });

        findViewById(R.id.btn_about).setOnClickListener(v -> showAbout());
        refreshSummaries();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshSummaries();
    }

    /** AOSP glance rule: every row shows its live value, no tap needed. */
    /** EngineLog viewer viewer: scrollable log + share/clear. */
    private void showLogger() {
        final String text = EngineLog.dump(this);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_logger)
            .setMessage(text.isEmpty()
                ? getString(R.string.log_empty) : text)
            .setPositiveButton(R.string.export_done_share, (d, w) -> {
                java.io.File f = EngineLog.stage(this);
                if (f != null) {
                    ShareHelper.shareFiles(this,
                        java.util.Collections.singletonList(f),
                        "text/plain", "clonix_log");
                }
            })
            .setNeutralButton(R.string.log_clear, (d, w) -> {
                EngineLog.clear(this);
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.got_it, null)
            .show();
    }

    private void refreshSummaries() {
        try {
            TextView st = findViewById(R.id.sum_theme);
            if (st != null) {
                String m = Prefs.themeMode(this);
                int id = "light".equals(m) ? R.string.theme_light
                    : "dark".equals(m) ? R.string.theme_dark
                    : "amoled".equals(m) ? R.string.theme_amoled
                    : R.string.theme_system;
                st.setText(id);
            }
        } catch (Throwable ignore) { }
        try {
            TextView sl = findViewById(R.id.sum_language);
            if (sl != null) {
                java.util.Locale l = java.util.Locale.getDefault();
                String name = "";
                try { name = l.getDisplayLanguage(l); } catch (Throwable ig) { }
                if (name == null || name.isEmpty()) name = l.toString();
                sl.setText(Bidi.isolate(name));
            }
        } catch (Throwable ignore) { }
        try {
            TextView se = findViewById(R.id.sum_enc);
            if (se != null) {
                boolean has = CryptoVault.hasSessionPassword();
                se.setText(has ? R.string.ov_granted : R.string.ov_not_granted);
            }
        } catch (Throwable ignore) { }
        try {
            TextView ss = findViewById(R.id.sum_space);
            if (ss != null) {
                int n = Prefs.maxPerApp(this);
                ss.setText(n <= 0 ? getString(R.string.retention_hint)
                    : getString(R.string.settings_retention) + ": " + n);
            }
            TextView sr = findViewById(R.id.sum_apply_retention);
            if (sr != null) {
                int n = Prefs.maxPerApp(this);
                sr.setText(n <= 0 ? getString(R.string.maint_retention_unlimited)
                    : getString(R.string.maint_apply_retention_sub, n));
            }
        } catch (Throwable ignore) { }
        try {
            TextView sa = findViewById(R.id.sum_about);
            if (sa != null) {
                String ver = "?";
                try {
                    ver = getPackageManager().getPackageInfo(
                        getPackageName(), 0).versionName;
                } catch (Throwable ignore) { }
                sa.setText(getString(R.string.settings_about, ver));
            }
        } catch (Throwable ignore) { }
    }

    private void openLocale() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                Intent i = new Intent(Settings.ACTION_APP_LOCALE_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                startActivity(new Intent(Settings.ACTION_LOCALE_SETTINGS));
            }
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void restartApp() {
        try {
            Intent i = getBaseContext().getPackageManager()
                .getLaunchIntentForPackage(getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            finish();
            startActivity(i);
            Runtime.getRuntime().gc();
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void showAbout() {
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignore) { }
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.set_about) + " • v" + ver)
            .setMessage(R.string.acc_about_text)
            .setPositiveButton(R.string.got_it, null)
            .show();
    }

    private void showPasswordDialog() {
        boolean has = CryptoVault.hasSessionPassword();
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pw_title)
            .setMessage(has ? R.string.pw_locked_sum : R.string.pw_unlocked_sum)
            .setPositiveButton(has ? R.string.pw_lock : R.string.pw_set,
                (d, w) -> {
                    if (has) {
                        CryptoVault.clearSessionPassword();
                        Toast.makeText(this, R.string.pw_locked,
                            Toast.LENGTH_SHORT).show();
                        refreshSummaries();
                    } else {
                        askPassword();
                    }
                })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void askPassword() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        TextInputEditText input = v.findViewById(R.id.rename);
        input.setHint(R.string.pw_hint);
        try {
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } catch (Throwable ignore) { }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pw_title)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                String s = input.getText() == null ? ""
                    : input.getText().toString();
                if (s.length() < CryptoVault.MIN_PW) {
                    Toast.makeText(this, R.string.pw_short,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                CryptoVault.setSessionPassword(s.toCharArray());
                try { input.setText(""); } catch (Throwable ignore) { }
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                refreshSummaries();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showRetentionDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        TextInputEditText input = v.findViewById(R.id.rename);
        input.setText(String.valueOf(Prefs.maxPerApp(this)));
        input.setHint(R.string.retention_hint);
        try {
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        } catch (Throwable ignore) { }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_retention)
            .setMessage(R.string.retention_sum)
            .setView(v)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                int n = 5;
                try {
                    n = Integer.parseInt(input.getText() == null ? "5"
                        : input.getText().toString().trim());
                } catch (Throwable ignore) { }
                Prefs.setMaxPerApp(this, n);
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                refreshSummaries();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showThemeDialog() {
        String cur = Prefs.themeMode(this);
        String[] names = {
            getString(R.string.theme_system), getString(R.string.theme_light),
            getString(R.string.theme_dark), getString(R.string.theme_amoled)};
        String[] vals = {"system", "light", "dark", "amoled"};
        int sel = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i].equals(cur)) sel = i;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(names, sel, null)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                androidx.appcompat.app.AlertDialog ad =
                    (androidx.appcompat.app.AlertDialog) d;
                int which = ad.getListView().getCheckedItemPosition();
                if (which >= 0 && which < vals.length) {
                    Prefs.setThemeMode(this, vals[which]);
                    recreate();
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }
}
