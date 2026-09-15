package com.clonix.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;

/**
 *  Account tab: device card (model, Android version, privilege
 * mode chip) + settings list (Settings / Archives / Language / Help /
 * Contact / Rate / Share / About) exactly like the Clonix ACCOUNT tab.
 */
public class AccountActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        ThemeHelper.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_account);
        NavHelper.setup(this, R.id.nav_account);

        findViewById(R.id.row_settings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.row_archives).setOnClickListener(v ->
            startActivity(new Intent(this, ArchivesActivity.class)));
        findViewById(R.id.row_language).setOnClickListener(v -> openLocale());
        findViewById(R.id.row_help).setOnClickListener(v -> showHelp());
        findViewById(R.id.row_contact).setOnClickListener(v -> contact());
        findViewById(R.id.row_rate).setOnClickListener(v -> rate());
        findViewById(R.id.row_share).setOnClickListener(v -> shareApp());
        findViewById(R.id.row_about).setOnClickListener(v -> showAbout());
    }

    @Override protected void onResume() {
        super.onResume();
        try { NavHelper.refreshBadges(this); } catch (Throwable ignore) { }
        new Thread(() -> {
            ShellEngine.Mode m;
            try { m = ShellEngine.mode(this); }
            catch (Throwable t) { m = ShellEngine.Mode.NONE; }
            final ShellEngine.Mode mode = m;
            runOnUiThread(() -> render(mode));
        }).start();
    }

    private void render(ShellEngine.Mode mode) {
        TextView dev = findViewById(R.id.acc_device);
        String model = android.os.Build.MODEL == null ? "?"
            : android.os.Build.MODEL;
        String ver = "?";
        try { ver = android.os.Build.VERSION.RELEASE; } catch (Throwable ignore) { }
        dev.setText(getString(R.string.acc_device_fmt, model, ver));
        TextView chip = findViewById(R.id.acc_mode_chip);
        if (mode == ShellEngine.Mode.ROOT) {
            chip.setText(R.string.acc_mode_root);
        } else if (mode == ShellEngine.Mode.DIRECT) {
            chip.setText(R.string.acc_mode_system);
        } else {
            chip.setText(R.string.acc_mode_none);
        }
        TextView counts = findViewById(R.id.acc_counts);
        try {
            counts.setText(getString(R.string.apps_count_fmt, CountCache.apps(this))
                + " • " + getString(R.string.archives_count_short,
                    BackupJob.lastArchives(this)));
        } catch (Throwable ignore) { }
    }

    private void openLocale() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                Intent i = new Intent(
                    android.provider.Settings.ACTION_APP_LOCALE_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                startActivity(new Intent(
                    android.provider.Settings.ACTION_LOCALE_SETTINGS));
            }
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void showHelp() {
        try {
            startActivity(new Intent(this, BackupCenterActivity.class));
        } catch (Throwable ignore) { }
    }

    private void contact() {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO,
                Uri.parse("mailto:clonix@example.com"));
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()),
                Toast.LENGTH_LONG).show();
        }
    }

    private void rate() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                "market://details?id=" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://play.google.com/store/apps/details?id="
                        + getPackageName())));
            } catch (Throwable ignore) { }
        }
    }

    private void shareApp() {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT,
                "CLONIX — https://github.com/clonix");
            startActivity(Intent.createChooser(i,
                getString(R.string.share_via)));
        } catch (Throwable ignore) { }
    }

    private void showAbout() {
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignore) { }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.acc_about) + " • v" + ver)
            .setMessage(R.string.acc_about_text)
            .setPositiveButton(R.string.got_it, null)
            .show();
    }
}
