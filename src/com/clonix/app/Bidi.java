package com.clonix.app;

/**
 * Bidirectional-text safety: isolate LTR tokens (file names, sizes,
 * dates, package names) inside RTL layouts so Arabic/Hebrew/Urdu UIs
 * never reorder or mirror them. Wraps with FSI...PDI isolates.
 */
public final class Bidi {
    private Bidi() {}

    // FSI (first strong isolate) and PDI (pop directional isolate).
    private static final String FSI = "\u2066";
    private static final String PDI = "\u2069";

    public static String isolate(String s) {
        if (s == null) return "";
        return FSI + s + PDI;
    }
}
