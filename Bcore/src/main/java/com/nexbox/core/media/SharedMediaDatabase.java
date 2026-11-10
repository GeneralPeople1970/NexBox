package com.nexbox.core.media;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.HashSet;
import java.util.Set;
import com.nexbox.core.core.env.BEnvironment;

/** Owns the database and the reentrant process/file lock used by every media mutation. */
final class SharedMediaDatabase {
    private static final String DATABASE_NAME = "media-index.db";
    private static final int DATABASE_VERSION = 4;
    static final Object DB_LOCK = new Object();
    private static SQLiteDatabase sDatabase;
    private static int sLockDepth;

    @FunctionalInterface
    interface StoreWork<T> { T run() throws IOException; }

    private SharedMediaDatabase() {
    }

    /** Caller holds DB_LOCK; opening the index does not recurse into media preparation. */
    static SQLiteDatabase getDatabaseLocked() {
        if (sDatabase != null && sDatabase.isOpen()) {
            return sDatabase;
        }
        BEnvironment.load();
        File databaseFile = new File(BEnvironment.getSystemDir(), DATABASE_NAME);
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        try {
            ensureSchemaLocked(database);
            SharedMediaLegacyMigration.relocateStoragePathsLocked(database);
            sDatabase = database;
            return database;
        } catch (RuntimeException failure) {
            database.close();
            throw failure;
        }
    }

    private static void ensureSchemaLocked(SQLiteDatabase database) {
        int version = database.getVersion();
        if (version == DATABASE_VERSION) return;
        if (version > DATABASE_VERSION) throw new IllegalStateException("Media index is from a newer version");
        database.beginTransaction();
        try {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS " + NexBoxMediaContract.TABLE_MEDIA + " ("
                            + NexBoxMediaContract.COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + NexBoxMediaContract.COL_USER_ID + " INTEGER NOT NULL, "
                            + NexBoxMediaContract.COL_MEDIA_TYPE + " INTEGER NOT NULL, "
                            + NexBoxMediaContract.COL_DISPLAY_NAME + " TEXT NOT NULL, "
                            + NexBoxMediaContract.COL_TITLE + " TEXT, "
                            + NexBoxMediaContract.COL_MIME_TYPE + " TEXT, "
                            + NexBoxMediaContract.COL_SIZE + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.COL_DATE_ADDED + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.COL_DATE_MODIFIED + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.COL_DATE_TAKEN + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.COL_RELATIVE_PATH + " TEXT NOT NULL, "
                            + NexBoxMediaContract.COL_BUCKET_DISPLAY_NAME + " TEXT, "
                            + NexBoxMediaContract.COL_BUCKET_ID + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.COL_WIDTH + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.COL_HEIGHT + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.COL_DURATION + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.COL_IS_PENDING + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.COL_DATA + " TEXT NOT NULL, "
                            + NexBoxMediaContract.COL_CANONICAL_PATH + " TEXT NOT NULL, "
                            + NexBoxMediaContract.COL_ROOT_PATH + " TEXT NOT NULL"
                            + ")"
            );
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_media_user_path ON "
                            + NexBoxMediaContract.TABLE_MEDIA + "("
                            + NexBoxMediaContract.COL_USER_ID + ", "
                            + NexBoxMediaContract.COL_CANONICAL_PATH + ")"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_media_user_type ON "
                            + NexBoxMediaContract.TABLE_MEDIA + "("
                            + NexBoxMediaContract.COL_USER_ID + ", "
                            + NexBoxMediaContract.COL_MEDIA_TYPE + ")"
            );
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS " + NexBoxMediaContract.TABLE_SCAN_STATE + " ("
                            + NexBoxMediaContract.STATE_COL_USER_ID + " INTEGER NOT NULL, "
                            + NexBoxMediaContract.STATE_COL_ROOT_PATH + " TEXT NOT NULL, "
                            + NexBoxMediaContract.STATE_COL_ROOT_LAST_MODIFIED + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.STATE_COL_FILE_COUNT + " INTEGER NOT NULL DEFAULT 0, "
                            + NexBoxMediaContract.STATE_COL_MAX_MODIFIED + " INTEGER NOT NULL DEFAULT 0, "
                            + "PRIMARY KEY (" + NexBoxMediaContract.STATE_COL_USER_ID + ", " + NexBoxMediaContract.STATE_COL_ROOT_PATH + ")"
                            + ")"
            );
            SharedMediaMigrationJournal.createSchema(database);
            ensureOwnerColumns(database);
            MediaConditions.createSchema(database);
            database.setVersion(DATABASE_VERSION);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /** Additive migration only: existing files, row IDs, shared namespace and unknown owners survive. */
    private static void ensureOwnerColumns(SQLiteDatabase database) {
        Set<String> columns = new HashSet<>();
        try (Cursor cursor = database.rawQuery("PRAGMA table_info(" + NexBoxMediaContract.TABLE_MEDIA + ")", null)) {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex));
        }
        for (String column : new String[]{NexBoxMediaContract.COL_OWNER_PACKAGE_NAME,
                NexBoxMediaContract.COL_OWNER_USER_ID, NexBoxMediaContract.COL_OWNER_USER_GENERATION}) {
            if (!columns.contains(column)) {
                String type = NexBoxMediaContract.COL_OWNER_PACKAGE_NAME.equals(column) ? " TEXT" : " INTEGER";
                database.execSQL("ALTER TABLE " + NexBoxMediaContract.TABLE_MEDIA + " ADD COLUMN " + column + type);
            }
        }
    }

    static <T> T withStoreLock(StoreWork<T> work) throws IOException {
        synchronized (DB_LOCK) {
            if (sLockDepth > 0) return work.run();
            BEnvironment.load();
            // SQLite serializes rows, not filesystem copies/renames. All host/provider/user
            // cleanup processes must share this lock as well as the in-process monitor.
            try (RandomAccessFile lockFile = new RandomAccessFile(new File(BEnvironment.getSystemDir(), "shared-media.lock"), "rw");
                 FileLock ignored = lockFile.getChannel().lock()) {
                sLockDepth++;
                try { return work.run(); }
                finally { sLockDepth--; }
            }
        }
    }

    static <T> T withStoreLockUnchecked(StoreWork<T> work) {
        try { return withStoreLock(work); }
        catch (IOException failure) { throw new IllegalStateException("Shared gallery storage is unavailable", failure); }
    }
}
