package com.clonepilot.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Clone badge drawn OVER the original app icon (icon itself never replaced):
 * rings = dual-rings (+number), number = plain circle + number, none = base icon.
 * Badge sits at the bottom corner so clones are distinguishable in one drawer.
 */
public class DualBadgeUtil {

    /** Default entry point: honors stored BadgeSettings. */
    public static Drawable badge(Context c, Drawable base, int slotIndex) {
        return badge(c, base, slotIndex, new BadgeSettings(c));
    }

    /** Per-clone badge: global settings + this clone's overrides (null/-1 = global). */
    public static Drawable badgeForClone(Context c, Drawable base, CloneDatabase.Clone cl) {
        BadgeSettings s = new BadgeSettings(c);
        String style = cl.badgeStyle != null ? cl.badgeStyle : s.style();
        int color = cl.badgeColor != -1 ? cl.badgeColor : s.color();
        boolean showNum = cl.badgeShowNum != -1 ? cl.badgeShowNum == 1 : s.showNumber();
        String pos = cl.badgePos != null ? cl.badgePos : s.position();
        if (BadgeSettings.STYLE_NONE.equals(style)) return base;
        return draw(c, base, cl.slotIndex, style, color, showNum, pos);
    }

    /** Live preview renderer with explicit options (used by the badge dialog). */
    public static Drawable preview(Context c, Drawable base, int slotIndex,
                                   String style, int color, boolean showNum, String pos) {
        if (BadgeSettings.STYLE_NONE.equals(style)) return base;
        return draw(c, base, slotIndex, style, color, showNum, pos);
    }

    public static Drawable badge(Context c, Drawable base, int slotIndex, BadgeSettings s) {
        if (s == null || !s.enabled()) return base;
        return draw(c, base, slotIndex, s.style(), s.color(), s.showNumber(), s.position());
    }

    private static Drawable draw(Context c, Drawable base, int slotIndex,
                                 String style, int color, boolean showNum, String pos) {
        if (base == null) return null;

        int size = Math.max(base.getIntrinsicWidth(), base.getIntrinsicHeight());
        if (size <= 0) size = 192;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        base.setBounds(0, 0, size, size);
        base.draw(cv);

        float r = size * 0.22f;
        float margin = size * 0.04f;
        boolean left = BadgeSettings.POS_BL.equals(pos);
        float cx = left ? (r + margin) : (size - r - margin);
        float cy = size - r - margin;

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(color);
        bg.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx, cy, r, bg);

        if (BadgeSettings.STYLE_NUMBER.equals(style)) {
            drawNumber(cv, cx, cy, r, slotIndex);
        } else { // rings (+ number per user toggle)
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setColor(0xFFFFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(r * 0.14f);
            cv.drawCircle(cx - r * 0.22f, cy - r * 0.12f, r * 0.34f, ring);
            cv.drawCircle(cx + r * 0.22f, cy - r * 0.12f, r * 0.34f, ring);
            if (showNum && slotIndex > 1) {
                drawNumber(cv, cx, cy + r * 0.28f, r * 0.62f, slotIndex);
            }
        }
        return new BitmapDrawable(c.getResources(), bmp);
    }

    private static void drawNumber(Canvas cv, float cx, float cy, float r, int n) {
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xFFFFFFFF);
        txt.setTextSize(r * 0.95f);
        txt.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = txt.getFontMetrics();
        float y = cy - (fm.ascent + fm.descent) / 2f;
        cv.drawText(String.valueOf(n), cx, y, txt);
    }
}
