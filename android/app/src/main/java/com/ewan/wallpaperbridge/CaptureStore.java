package com.ewan.wallpaperbridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CaptureStore extends SQLiteOpenHelper {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'，。！？；）】]+", Pattern.CASE_INSENSITIVE);

    CaptureStore(Context context) { super(context, "captures.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE captures (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, source TEXT NOT NULL, shared_text TEXT NOT NULL, url TEXT NOT NULL, state TEXT NOT NULL)");
        db.execSQL("CREATE INDEX captures_state_time ON captures(state, created_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    String save(String source, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("没有可收藏的文字或链接");
        if (text.length() > 30000) text = text.substring(0, 30000);
        Matcher match = URL.matcher(text);
        String url = match.find() ? match.group() : "";
        ContentValues row = new ContentValues();
        String id = UUID.randomUUID().toString();
        row.put("id", id);
        row.put("created_at", System.currentTimeMillis());
        row.put("source", source == null ? "未知应用" : source);
        row.put("shared_text", text);
        row.put("url", url);
        row.put("state", "pending");
        getWritableDatabase().insertOrThrow("captures", null, row);
        return id;
    }

    JSONArray pending(int limit) throws Exception {
        JSONArray items = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id, created_at, source, shared_text, url FROM captures WHERE state='pending' ORDER BY created_at LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getString(0));
                item.put("created_at", cursor.getLong(1));
                item.put("source", cursor.getString(2));
                item.put("shared_text", cursor.getString(3));
                item.put("url", cursor.getString(4));
                items.put(item);
            }
        }
        return items;
    }

    void acknowledge(JSONArray ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.length(); i++) {
                ContentValues values = new ContentValues();
                values.put("state", "received_by_computer");
                db.update("captures", values, "id=? AND state='pending'", new String[]{ids.optString(i)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    int pendingCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT count(*) FROM captures WHERE state='pending'", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    int totalCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT count(*) FROM captures", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    JSONArray recent(int limit) throws Exception {
        JSONArray items = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT source, shared_text, state FROM captures ORDER BY created_at DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                items.put(new JSONObject().put("source", cursor.getString(0))
                        .put("text", cursor.getString(1))
                        .put("state", cursor.getString(2)));
            }
        }
        return items;
    }
}
