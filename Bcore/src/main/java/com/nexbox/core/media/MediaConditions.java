package com.nexbox.core.media;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.util.UUID;
import com.nexbox.core.utils.MutationReceipt.Rejected;

/** Conditional provenance is separate from public query columns; existing media is never claimed. */
final class MediaConditions {
    private static final String TABLE = "media_conditions";
    private static final String EPOCH = "media1." + UUID.randomUUID() + ".";

    static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (media_id INTEGER PRIMARY KEY, version TEXT NOT NULL,"
                + " owner_token TEXT NOT NULL, content_sha256 TEXT NOT NULL, file_identity TEXT NOT NULL, writable_exposed INTEGER NOT NULL DEFAULT 0)");
        // Every ordinary row mutation invalidates the control version, including changing a value back.
        db.execSQL("CREATE TRIGGER IF NOT EXISTS media_condition_update AFTER UPDATE ON " + NexBoxMediaContract.TABLE_MEDIA
                + " BEGIN UPDATE " + TABLE + " SET version=lower(hex(randomblob(32))) WHERE media_id=OLD."
                + NexBoxMediaContract.COL_ID + "; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS media_condition_delete AFTER DELETE ON " + NexBoxMediaContract.TABLE_MEDIA
                + " BEGIN DELETE FROM " + TABLE + " WHERE media_id=OLD." + NexBoxMediaContract.COL_ID + "; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS media_condition_insert AFTER INSERT ON " + NexBoxMediaContract.TABLE_MEDIA
                + " BEGIN DELETE FROM " + TABLE + " WHERE media_id=NEW." + NexBoxMediaContract.COL_ID + "; END");
    }

    static String register(SQLiteDatabase db, NexBoxMediaEntry entry, String sha256, String ownerToken) {
        String version = EPOCH + UUID.randomUUID();
        ContentValues values = new ContentValues();
        values.put("media_id", entry.getId());
        values.put("version", version);
        values.put("owner_token", ownerToken);
        values.put("content_sha256", sha256);
        values.put("file_identity", identity(new File(entry.getFilePath())));
        db.insertOrThrow(TABLE, null, values);
        return version;
    }

    static void require(SQLiteDatabase db, NexBoxMediaEntry entry, String version, String owner, String sha256) {
        if (version == null || owner == null || sha256 == null || !version.startsWith(EPOCH)) throw new Rejected("resource_changed");
        try (Cursor row = db.query(TABLE, null, "media_id=?", new String[]{Long.toString(entry.getId())}, null, null, null)) {
            if (!row.moveToFirst()) throw new Rejected("resource_changed");
            if (row.getInt(row.getColumnIndexOrThrow("writable_exposed")) != 0) throw new Rejected("resource_busy");
            if (!version.equals(row.getString(row.getColumnIndexOrThrow("version")))
                    || !owner.equals(row.getString(row.getColumnIndexOrThrow("owner_token")))
                    || !sha256.equalsIgnoreCase(row.getString(row.getColumnIndexOrThrow("content_sha256")))
                    || !identity(new File(entry.getFilePath())).equals(row.getString(row.getColumnIndexOrThrow("file_identity")))) {
                throw new Rejected("resource_changed");
            }
        }
    }

    /** Issuing a writable descriptor revokes the capability permanently, even across dup/detach. */
    static void exposeWriter(SQLiteDatabase db, File file) {
        ContentValues values = new ContentValues();
        values.put("writable_exposed", 1);
        db.update(TABLE, values, "media_id IN (SELECT " + NexBoxMediaContract.COL_ID + " FROM "
                + NexBoxMediaContract.TABLE_MEDIA + " WHERE " + NexBoxMediaContract.COL_CANONICAL_PATH + "=?)",
                new String[]{SharedMediaPaths.canonicalPath(file)});
    }

    private static String identity(File file) {
        try {
            StructStat stat = Os.lstat(file.getPath());
            if (!android.system.OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1) throw new Rejected("atomicity_unavailable");
            return stat.st_dev + ":" + stat.st_ino + ":" + stat.st_size + ":" + stat.st_mtim.tv_sec + ":"
                    + stat.st_mtim.tv_nsec + ":" + stat.st_ctim.tv_sec + ":" + stat.st_ctim.tv_nsec;
        } catch (android.system.ErrnoException failure) { throw new Rejected("resource_changed"); }
    }
}
