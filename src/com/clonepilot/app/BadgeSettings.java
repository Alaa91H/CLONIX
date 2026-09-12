package com.clonepilot.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User-customizable clone badge.
 * Styles: rings (dual-rings + optional number), number (plain circle + number),
 * none (original icon untouched). Color + bottom-corner position replaceable.
 * Default: plain numbered badge in Material baseline primary.
 * All icon producers (shortcuts, lists) read this, so one change applies everywhere.
 */
public class BadgeSettings {
    public static final String STYLE_RINGS = "rings";
    public static final String STYLE_NUMBER = "number";
    public static final String STYLE_NONE = "none";

    public static final String POS_BR = "br"; // bottom-right (default)
    public static final String POS_BL = "bl"; // bottom-left

    public static final int[] COLORS = {
        0xFF6750A4, // Material baseline primary
        0xFF2196F3, // blue
        0xFF4CAF50, // green
        0xFFFF8A00, // orange
        0xFF9C27B0, // purple
        0xFFF44336, // red
        0xFF607D8B, // grey
    };

    private final SharedPreferences p;

    public BadgeSettings(Context c) {
        p = c.getSharedPreferences("badge", Context.MODE_PRIVATE);
    }

    public String style() { return p.getString("style", STYLE_NUMBER); }
    public void setStyle(String s) { p.edit().putString("style", s).apply(); }

    public boolean showNumber() { return p.getBoolean("show_number", true); }
    public void setShowNumber(boolean b) { p.edit().putBoolean("show_number", b).apply(); }

    public int color() { return p.getInt("color", COLORS[0]); }
    public void setColor(int c) { p.edit().putInt("color", c).apply(); }
    public int colorIndex() {
        int cur = color();
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == cur) return i;
        return 0;
    }

    public String position() { return p.getString("pos", POS_BR); }
    public void setPosition(String pos) { p.edit().putString("pos", pos).apply(); }

    public boolean enabled() { return !STYLE_NONE.equals(style()); }
}
