package com.nexbox.core.media;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;

import com.nexbox.core.core.env.BEnvironment;

/** Records source consumption in the same transaction as retaining/moving its media ID. */
public final class SharedMediaMigrationJournal {
    private static final String TABLE = "shared_media_migrations";
    private SharedMediaMigrationJournal() { }

    public interface RowUpdate { void apply() throws IOException; }

    public static void createSchema(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE
                + " (legacy_path TEXT PRIMARY KEY, shared_path TEXT NOT NULL)");
    }

    public static boolean contains(SQLiteDatabase database, String sourcePath) {
        try (Cursor cursor = database.query(TABLE, new String[]{"shared_path"},
                "legacy_path=?", new String[]{sourcePath}, null, null, null)) {
            return cursor.moveToFirst();
        }
    }

    public static void relocateStoragePaths(SQLiteDatabase database) {
        try (Cursor cursor = database.query(TABLE, new String[]{"legacy_path", "shared_path"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                String oldSource = cursor.getString(0);
                String oldTarget = cursor.getString(1);
                String source = BEnvironment.relocateLegacyPath(oldSource);
                String target = BEnvironment.relocateLegacyPath(oldTarget);
                if (oldSource.equals(source) && oldTarget.equals(target)) continue;
                ContentValues values = new ContentValues();
                values.put("legacy_path", source);
                values.put("shared_path", target);
                database.update(TABLE, values, "legacy_path=?", new String[]{oldSource});
            }
        }
    }

    public static void commit(SQLiteDatabase database, String sourcePath, String targetPath, RowUpdate update) throws IOException {
        database.beginTransaction();
        try {
            if (!contains(database, sourcePath)) {
                update.apply();
                ContentValues migration = new ContentValues();
                migration.put("legacy_path", sourcePath);
                migration.put("shared_path", targetPath);
                database.insertOrThrow(TABLE, null, migration);
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }
}
