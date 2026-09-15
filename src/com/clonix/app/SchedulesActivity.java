package com.clonix.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Schedules tab: backup schedule (on/off, interval, charging,
 * idle, run-now) and integrity checks (on/off, weekly, run-now) with
 * live "last run" values, backed by BackupScheduler / VerifyScheduler.
 */
public class SchedulesActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        ThemeHelper.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_schedules);
        NavHelper.setup(this, R.id.nav_schedules);
        findViewById(R.id.toolbar).setOnClickListener(v -> { });

        MaterialSwitch bOn = findViewById(R.id.sched_on);
        MaterialSwitch bChg = findViewById(R.id.sched_charging);
        MaterialSwitch bIdle = findViewById(R.id.sched_idle);
        bOn.setChecked(BackupScheduler.enabled(this));
        bChg.setChecked(BackupScheduler.requireCharging(this));
        bIdle.setChecked(BackupScheduler.requireIdle(this));
        bOn.setOnCheckedChangeListener((v, c) -> saveBackup());
        bChg.setOnCheckedChangeListener((v, c) -> saveBackup());
        bIdle.setOnCheckedChangeListener((v, c) -> saveBackup());
        findViewById(R.id.sched_interval).setOnClickListener(v ->
            showIntervalDialog());
        findViewById(R.id.sched_run).setOnClickListener(v -> runBackupNow());

        MaterialSwitch vOn = findViewById(R.id.verify_on);
        MaterialSwitch vChg = findViewById(R.id.verify_charging);
        vOn.setChecked(VerifyScheduler.enabled(this));
        vChg.setChecked(VerifyScheduler.requireCharging(this));
        vOn.setOnCheckedChangeListener((v, c) -> saveVerify());
        vChg.setOnCheckedChangeListener((v, c) -> saveVerify());
        findViewById(R.id.verify_interval).setOnClickListener(v ->
            showVerifyIntervalDialog());
        findViewById(R.id.verify_run).setOnClickListener(v -> runVerifyNow());
    }

    @Override protected void onResume() {
        super.onResume();
        try { NavHelper.refreshBadges(this); } catch (Throwable ignore) { }
        refresh();
    }

    private void refresh() {
        TextView iv = findViewById(R.id.sched_interval_val);
        iv.setText(intervalLabel(BackupScheduler.intervalHours(this),
            BackupScheduler.intervalUnit(this)));
        String last = BackupJob.lastSchedule(this);
        TextView lv = findViewById(R.id.sched_last);
        lv.setText(last == null || last.isEmpty()
            ? getString(R.string.sched_last_run, getString(R.string.never))
            : getString(R.string.sched_last_run, last));
        TextView vv = findViewById(R.id.verify_interval_val);
        vv.setText(intervalLabel(VerifyScheduler.intervalHours(this),
            VerifyScheduler.intervalUnit(this)));
        String vlast = VerifyScheduler.lastRun(this);
        TextView vlv = findViewById(R.id.verify_last);
        vlv.setText(vlast == null || vlast.isEmpty()
            ? getString(R.string.sched_last_run, getString(R.string.never))
            : getString(R.string.sched_last_run, vlast));
    }

    /** "Every 3 days" / "Every 12 h" in the user's language. */
    static String intervalLabel(int hours, String unit) {
        android.app.Application ctx = ClonixApp.context();
        return ("d".equals(unit) && hours >= 24)
            ? ctx.getString(R.string.sched_days_fmt, hours / 24)
            : ctx.getString(R.string.sched_hours_fmt, hours);
    }

    private void saveBackup() {
        MaterialSwitch on = findViewById(R.id.sched_on);
        MaterialSwitch chg = findViewById(R.id.sched_charging);
        MaterialSwitch idle = findViewById(R.id.sched_idle);
        BackupScheduler.save(this, on.isChecked(),
            BackupScheduler.intervalHours(this),
            chg.isChecked(), idle.isChecked());
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
    }

    private void saveVerify() {
        MaterialSwitch on = findViewById(R.id.verify_on);
        MaterialSwitch chg = findViewById(R.id.verify_charging);
        VerifyScheduler.save(this, on.isChecked(),
            VerifyScheduler.intervalHours(this), chg.isChecked());
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
    }

    private void showIntervalDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_interval, null);
        final TextInputEditText num = v.findViewById(R.id.interval_value);
        final TextView uh = v.findViewById(R.id.unit_h);
        final TextView ud = v.findViewById(R.id.unit_d);
        final String[] unit = {BackupScheduler.intervalUnit(this)};
        num.setText(String.valueOf(BackupScheduler.intervalValue(this)));
        num.setHint(R.string.schedule_hint_hours);
        Runnable restyle = () -> {
            boolean d = "d".equals(unit[0]);
            uh.setSelected(!d);
            ud.setSelected(d);
            uh.setBackgroundResource(d ? R.drawable.bg_chip_toggle
                : R.drawable.bg_chip_selected);
            ud.setBackgroundResource(d ? R.drawable.bg_chip_selected
                : R.drawable.bg_chip_toggle);
            try {
                int on = uh.getContext().getColor(R.color.onPrimary);
                int off = uh.getContext().getColor(R.color.onSurfaceVariant);
                uh.setTextColor(d ? off : on);
                ud.setTextColor(d ? on : off);
            } catch (Throwable ignore) { }
        };
        restyle.run();
        uh.setOnClickListener(x -> { unit[0] = "h"; restyle.run(); });
        ud.setOnClickListener(x -> { unit[0] = "d"; restyle.run(); });
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.schedule_title)
            .setMessage(R.string.schedule_sum)
            .setView(v)
            .setPositiveButton(R.string.save_default, (d, w) -> {
                int val = 24;
                try {
                    val = Integer.parseInt(num.getText() == null ? "24"
                        : num.getText().toString().trim());
                } catch (Throwable ignore) { }
                if (val < 1) val = 1;
                if ("d".equals(unit[0]) && val > 31) val = 31;
                if ("h".equals(unit[0]) && val > 720) val = 720;
                MaterialSwitch on = findViewById(R.id.sched_on);
                MaterialSwitch chg = findViewById(R.id.sched_charging);
                MaterialSwitch idle = findViewById(R.id.sched_idle);
                BackupScheduler.save(this, on.isChecked(), val, unit[0],
                    chg.isChecked(), idle.isChecked());
                refresh();
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void showVerifyIntervalDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_interval, null);
        final TextInputEditText num = v.findViewById(R.id.interval_value);
        final TextView uh = v.findViewById(R.id.unit_h);
        final TextView ud = v.findViewById(R.id.unit_d);
        final String[] unit = {VerifyScheduler.intervalUnit(this)};
        num.setText(String.valueOf(VerifyScheduler.intervalValue(this)));
        num.setHint(R.string.schedule_hint_hours);
        Runnable restyle = () -> {
            boolean d = "d".equals(unit[0]);
            uh.setSelected(!d);
            ud.setSelected(d);
            uh.setBackgroundResource(d ? R.drawable.bg_chip_toggle
                : R.drawable.bg_chip_selected);
            ud.setBackgroundResource(d ? R.drawable.bg_chip_selected
                : R.drawable.bg_chip_toggle);
            try {
                int on = uh.getContext().getColor(R.color.onPrimary);
                int off = uh.getContext().getColor(R.color.onSurfaceVariant);
                uh.setTextColor(d ? off : on);
                ud.setTextColor(d ? on : off);
            } catch (Throwable ignore) { }
        };
        restyle.run();
        uh.setOnClickListener(x -> { unit[0] = "h"; restyle.run(); });
        ud.setOnClickListener(x -> { unit[0] = "d"; restyle.run(); });
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.verify_title)
            .setView(v)
            .setPositiveButton(R.string.save_default, (d, w) -> {
                int val = 7;
                try {
                    val = Integer.parseInt(num.getText() == null ? "7"
                        : num.getText().toString().trim());
                } catch (Throwable ignore) { }
                if (val < 1) val = 1;
                if ("d".equals(unit[0]) && val > 31) val = 31;
                if ("h".equals(unit[0]) && val > 720) val = 720;
                MaterialSwitch on = findViewById(R.id.verify_on);
                MaterialSwitch chg = findViewById(R.id.verify_charging);
                VerifyScheduler.save(this, on.isChecked(), val, unit[0],
                    chg.isChecked());
                refresh();
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void runBackupNow() {
        Toast.makeText(this, R.string.backing_up, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String res = BackupScheduler.runOnce(this);
            runOnUiThread(() -> {
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.sched_run_now)
                    .setMessage(res)
                    .setPositiveButton(R.string.got_it, null)
                    .show();
                refresh();
            });
        }).start();
    }

    private void runVerifyNow() {
        Toast.makeText(this, R.string.verifying, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String res = VerifyScheduler.runOnce(this);
            runOnUiThread(() -> {
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.sched_run_now)
                    .setMessage(res)
                    .setPositiveButton(R.string.got_it, null)
                    .show();
                refresh();
            });
        }).start();
    }
}
