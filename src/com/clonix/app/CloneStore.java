package com.clonix.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Minimal mapping: pkg -> N clones (userId + nickname). No Room to keep builds light. */
public class CloneStore extends SQLiteOpenHelper {
    private static final String DB = "clones.db";
    private static final int VER = 2;
    /** Badge override sentinels: null/-1 = follow global BadgeSettings. */
    public static class Clone {
        public String pkg;
        public int userId;
        public int slotIndex; // 1..N, 1 = clone-profile slot
        public String nickname;
        public boolean separateContacts;
        public long createdAt;
        public String badgeStyle;   // null = global
        public int badgeColor = -1; // -1 = global
        public int badgeShowNum = -1; // -1 = global, 0 = hide, 1 = show
        public String badgePos;     // null = global
    }

    public CloneStore(Context c) { super(c, DB, null, VER); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE clones (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "pkg TEXT NOT NULL, userId INTEGER NOT NULL, slotIndex INTEGER NOT NULL,"
            + "nickname TEXT, separateContacts INTEGER DEFAULT 1, createdAt INTEGER,"
            + "badgeStyle TEXT, badgeColor INTEGER DEFAULT -1,"
            + "badgeShowNum INTEGER DEFAULT -1, badgePos TEXT)");
        db.execSQL("CREATE UNIQUE INDEX idx_pkg_user ON clones(pkg, userId)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) {
            db.execSQL("ALTER TABLE clones ADD COLUMN badgeStyle TEXT");
            db.execSQL("ALTER TABLE clones ADD COLUMN badgeColor INTEGER DEFAULT -1");
            db.execSQL("ALTER TABLE clones ADD COLUMN badgeShowNum INTEGER DEFAULT -1");
            db.execSQL("ALTER TABLE clones ADD COLUMN badgePos TEXT");
        }
    }

    public synchronized void add(Clone cl) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("pkg", cl.pkg);
        v.put("userId", cl.userId);
        v.put("slotIndex", cl.slotIndex);
        v.put("nickname", cl.nickname);
        v.put("separateContacts", cl.separateContacts ? 1 : 0);
        v.put("createdAt", System.currentTimeMillis());
        db.insertWithOnConflict("clones", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void updateNickname(String pkg, int userId, String nickname) {
        ContentValues v = new ContentValues();
        v.put("nickname", nickname);
        getWritableDatabase().update("clones", v, "pkg=? AND userId=?",
            new String[]{pkg, String.valueOf(userId)});
    }

    /** Per-clone badge overrides; null/-1 = follow global settings. */
    public synchronized void updateBadge(String pkg, int userId,
                                         String style, int color, int showNum, String pos) {
        ContentValues v = new ContentValues();
        v.put("badgeStyle", style);
        v.put("badgeColor", color);
        v.put("badgeShowNum", showNum);
        v.put("badgePos", pos);
        getWritableDatabase().update("clones", v, "pkg=? AND userId=?",
            new String[]{pkg, String.valueOf(userId)});
    }

    public synchronized void clearBadge(String pkg, int userId) {
        updateBadge(pkg, userId, null, -1, -1, null);
    }

    public synchronized void remove(String pkg, int userId) {
        getWritableDatabase().delete("clones", "pkg=? AND userId=?",
            new String[]{pkg, String.valueOf(userId)});
    }

    public synchronized void removeAllForPkg(String pkg) {
        getWritableDatabase().delete("clones", "pkg=?", new String[]{pkg});
    }

    public synchronized void removeAllForUser(int userId) {
        getWritableDatabase().delete("clones", "userId=?", new String[]{String.valueOf(userId)});
    }

    public synchronized void clearAll() {
        getWritableDatabase().delete("clones", null, null);
    }

    public synchronized List<Clone> listAll() {
        List<Clone> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT pkg,userId,slotIndex,nickname,separateContacts,createdAt,"
            + "badgeStyle,badgeColor,badgeShowNum,badgePos FROM clones ORDER BY createdAt", null);
        int iStyle = c.getColumnIndex("badgeStyle");
        int iColor = c.getColumnIndex("badgeColor");
        int iShow = c.getColumnIndex("badgeShowNum");
        int iPos = c.getColumnIndex("badgePos");
        while (c.moveToNext()) {
            Clone cl = new Clone();
            cl.pkg = c.getString(0);
            cl.userId = c.getInt(1);
            cl.slotIndex = c.getInt(2);
            cl.nickname = c.getString(3);
            cl.separateContacts = c.getInt(4) == 1;
            cl.createdAt = c.getLong(5);
            cl.badgeStyle = iStyle < 0 ? null : c.getString(iStyle);
            cl.badgeColor = iColor < 0 ? -1 : c.getInt(iColor);
            cl.badgeShowNum = iShow < 0 ? -1 : c.getInt(iShow);
            cl.badgePos = iPos < 0 ? null : c.getString(iPos);
            out.add(cl);
        }
        c.close();
        return out;
    }

    public synchronized List<Clone> listForPkg(String pkg) {
        List<Clone> out = new ArrayList<>();
        for (Clone cl : listAll()) if (cl.pkg.equals(pkg)) out.add(cl);
        return out;
    }

    public synchronized int nextSlotIndex(String pkg) {
        int max = 0;
        for (Clone cl : listForPkg(pkg)) max = Math.max(max, cl.slotIndex);
        return max + 1;
    }
}
