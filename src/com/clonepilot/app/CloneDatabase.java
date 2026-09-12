package com.clonepilot.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Minimal mapping: pkg -> N clones (userId + nickname). No Room to keep builds light. */
public class CloneDatabase extends SQLiteOpenHelper {
    private static final String DB = "clones.db";
    private static final int VER = 1;
    public static class Clone {
        public String pkg;
        public int userId;
        public int slotIndex; // 1..N, 1 = clone-profile slot
        public String nickname;
        public boolean separateContacts;
        public long createdAt;
    }

    public CloneDatabase(Context c) { super(c, DB, null, VER); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE clones (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "pkg TEXT NOT NULL, userId INTEGER NOT NULL, slotIndex INTEGER NOT NULL,"
            + "nickname TEXT, separateContacts INTEGER DEFAULT 1, createdAt INTEGER)");
        db.execSQL("CREATE UNIQUE INDEX idx_pkg_user ON clones(pkg, userId)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {}

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
        Cursor c = getReadableDatabase().rawQuery("SELECT pkg,userId,slotIndex,nickname,separateContacts,createdAt FROM clones ORDER BY createdAt", null);
        while (c.moveToNext()) {
            Clone cl = new Clone();
            cl.pkg = c.getString(0);
            cl.userId = c.getInt(1);
            cl.slotIndex = c.getInt(2);
            cl.nickname = c.getString(3);
            cl.separateContacts = c.getInt(4) == 1;
            cl.createdAt = c.getLong(5);
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
