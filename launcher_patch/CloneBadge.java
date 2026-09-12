package com.evolution.launcherclone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Numbered bottom-corner badge for clone icons inside the launcher drawer.
 * Mirrors DualMessenger's BadgeSettings defaults (orange rings + number);
 * per-user customization of the badge lives in the DualMessenger app and
 * applies to pinned shortcuts. This renderer is the static ROM-side twin
 * for drawer icons (launchers cannot read another app's prefs cheaply).
 *
 * To change the ROM-side look, edit DEFAULT_COLOR / STYLE_RINGS_ONLY here.
 */
public final class CloneBadge {
    public static final int DEFAULT_COLOR = 0xFFFF8A00; // Samsung orange
    /** false = dual-rings + number (Samsung), true = plain circle + number. */
    public static final boolean STYLE_NUMBER_ONLY = false;

    private CloneBadge() {}

    public static Drawable apply(Context c, Drawable base, int slot) {
        return apply(c, base, slot, DEFAULT_COLOR);
    }

    public static Drawable apply(Context c, Drawable base, int slot, int color) {
        if (base == null || c == null) return base;
        int size = Math.max(base.getIntrinsicWidth(), base.getIntrinsicHeight());
        if (size <= 0) size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        base.setBounds(0, 0, size, size);
        base.draw(cv);

        float r = size * 0.22f;
        float cx = size - r - size * 0.04f; // bottom-right (Samsung)
        float cy = size - r - size * 0.04f;

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(color);
        bg.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx, cy, r, bg);

        if (!STYLE_NUMBER_ONLY) {
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setColor(0xFFFFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(r * 0.14f);
            cv.drawCircle(cx - r * 0.22f, cy - r * 0.12f, r * 0.34f, ring);
            cv.drawCircle(cx + r * 0.22f, cy - r * 0.12f, r * 0.34f, ring);
        }
        // Number always drawn: slot is the differentiator in the main drawer.
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xFFFFFFFF);
        txt.setTextSize(r * (STYLE_NUMBER_ONLY ? 0.95f : 0.62f));
        txt.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = txt.getFontMetrics();
        float y = (STYLE_NUMBER_ONLY ? cy : cy + r * 0.42f) - (fm.ascent + fm.descent) / 2f;
        cv.drawText(String.valueOf(Math.max(1, slot)), cx, y, txt);

        return new BitmapDrawable(c.getResources(), bmp);
    }
}
