package com.nexbox.core.media;

import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.NativeCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.utils.MutationReceipt;
import com.nexbox.core.utils.MutationReceipt.Rejected;
import com.nexbox.core.utils.Slog;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Host-only core operations. Lifecycle excludes guest writers; store lock excludes host path/provider writers. */
public final class ConditionalMedia {
    private ConditionalMedia() { }

    public static Bundle importVerified(String contentBase64, long length, String sha256, String mime, String requestId) {
        com.nexbox.core.core.system.xposed.XposedCallerGuard.enforceHostManager();
        MutationReceipt receipt = new MutationReceipt(requestId);
        try {
            requireCoreProcess();
            VerifiedPng png = VerifiedPng.decode(contentBase64, length, sha256, mime);
            byte[] bytes = png.copyBytes();
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (decoded == null) throw new Rejected("source_mismatch");
            try {
                if (decoded.getWidth() != png.width || decoded.getHeight() != png.height) throw new Rejected("source_mismatch");
            } finally { decoded.recycle(); }
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                BProcessManagerService.get().requireMediaQuiescence();
                return SharedMediaDatabase.withStoreLock(() -> publish(png, bytes, receipt));
            }
        } catch (Rejected failure) {
            return receipt.failure(failure.code);
        } catch (Exception failure) {
            return receipt.failure("media_storage_unconfirmed");
        } finally { log(receipt); }
    }

    private static Bundle publish(VerifiedPng png, byte[] bytes, MutationReceipt receipt) throws IOException {
        SQLiteDatabase db = SharedMediaDatabase.getDatabaseLocked();
        SharedMediaPaths.ensureRoots(NexBoxMediaContract.SHARED_USER_ID);
        File directory = SharedMediaPaths.getImagesRoot(NexBoxMediaContract.SHARED_USER_ID);
        File target = SharedMediaPaths.requireSharedFile(new File(directory, "verified_" + UUID.randomUUID() + ".png"));
        String owner = "owner1." + UUID.randomUUID();
        receipt.data.putBoolean("created", false);
        receipt.data.putBoolean("published", false);
        receipt.data.putBoolean("rollbackAttempted", false);
        receipt.data.putBoolean("rollbackConfirmed", false);
        boolean published = false;
        NexBoxMediaEntry entry = null;
        try (SharedMediaImportStage staged = SharedMediaImportStage.create(SharedMediaPaths.getSharedMediaRoot(), directory)) {
            // The original bytes live only in this process. No guest exists and all other shared-path writers
            // take the store lock, including FileProvider and DocumentsProvider descriptor issuance.
            staged.copyFrom(new ByteArrayInputStream(bytes), null);
            SharedMediaScanner.MediaMetadata metadata = new SharedMediaScanner.MediaMetadata();
            metadata.mimeType = "image/png";
            metadata.width = png.width;
            metadata.height = png.height;
            try {
                receipt.begin("publish_media");
                staged.publish(target);
                published = true;
                receipt.data.putBoolean("published", true);
                receipt.confirmed("mediaFilePublished");
                receipt.begin("persist_media");
                String version;
                db.beginTransaction();
                try {
                    entry = SharedMediaIndex.upsertFileSnapshotLocked(db, NexBoxMediaContract.SHARED_USER_ID,
                            SharedMediaSnapshot.FileState.capture(target, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                            "image/png", NexBoxMediaOwner.host(NexBoxCore.getHostPkg()), null, metadata, 0);
                    receipt.data.putLong("mediaId", entry.getId());
                    version = MediaConditions.register(db, entry, png.sha256, owner);
                    SharedMediaIndex.refreshStoredState(NexBoxMediaContract.SHARED_USER_ID, entry.getMediaType());
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                putIdentity(receipt.data, entry, version, owner, png.sha256);
                receipt.data.putBoolean("created", true);
                receipt.data.putBoolean("saved", true);
                receipt.confirmed("mediaCreated", identity(entry.getId()));
                NexBoxMediaStore.notifyChanged();
                return receipt.success();
            } catch (Exception failure) {
                if (published) {
                    receipt.data.putBoolean("rollbackAttempted", true);
                    try {
                        staged.rollbackPublished(target);
                        if (entry != null) db.delete(NexBoxMediaContract.TABLE_MEDIA,
                                NexBoxMediaContract.COL_ID + "=?", new String[]{Long.toString(entry.getId())});
                        receipt.data.putBoolean("rollbackConfirmed", true);
                        receipt.confirmed("publicationRolledBack");
                    } catch (Exception unconfirmed) {
                        receipt.begin("rollback_media");
                    }
                }
                return receipt.failure("media_publication_unconfirmed");
            }
        }
    }

    public static Bundle deleteIfVersion(long mediaId, String version, String owner, String sha256, String requestId) {
        com.nexbox.core.core.system.xposed.XposedCallerGuard.enforceHostManager();
        MutationReceipt receipt = new MutationReceipt(requestId);
        receipt.data.putLong("mediaId", mediaId);
        try {
            requireCoreProcess();
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                BProcessManagerService.get().requireMediaQuiescence();
                return SharedMediaDatabase.withStoreLock(() -> {
                    SQLiteDatabase db = SharedMediaDatabase.getDatabaseLocked();
                    NexBoxMediaEntry entry = requireEntry(db, mediaId, version, owner, sha256);
                    try (LeasedSource source = new LeasedSource(entry)) {
                        MediaConditions.require(db, entry, version, owner, sha256);
                        source.readMatchedBytes(entry, sha256);
                        source.requireHeld();
                        receipt.begin("delete_media_file");
                        File file = SharedMediaPaths.requireSharedFile(new File(entry.getFilePath()));
                        if (!file.delete()) return receipt.failure("media_delete_unconfirmed");
                        receipt.confirmed("mediaFileDeleted", identity(mediaId));
                        receipt.begin("delete_media_index");
                        if (db.delete(NexBoxMediaContract.TABLE_MEDIA, NexBoxMediaContract.COL_ID + "=?",
                                new String[]{Long.toString(mediaId)}) != 1) return receipt.failure("media_delete_unconfirmed");
                        receipt.confirmed("mediaIndexDeleted", identity(mediaId));
                        receipt.data.putBoolean("deleted", true);
                        NexBoxMediaStore.notifyChanged();
                        return receipt.success();
                    }
                });
            }
        } catch (Rejected failure) {
            return receipt.failure(failure.code);
        } catch (Exception failure) {
            return receipt.failure("media_storage_unconfirmed");
        } finally { log(receipt); }
    }

    /** The returned byte array is private Binder data, removed by the host before any CLI response. */
    public static Bundle exportSnapshot(long mediaId, String version, String owner, String sha256, String requestId) {
        com.nexbox.core.core.system.xposed.XposedCallerGuard.enforceHostManager();
        MutationReceipt receipt = new MutationReceipt(requestId);
        receipt.data.putLong("mediaId", mediaId);
        try {
            requireCoreProcess();
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                BProcessManagerService.get().requireMediaQuiescence();
                return SharedMediaDatabase.withStoreLock(() -> {
                    SQLiteDatabase db = SharedMediaDatabase.getDatabaseLocked();
                    NexBoxMediaEntry entry = requireEntry(db, mediaId, version, owner, sha256);
                    byte[] bytes;
                    try (LeasedSource source = new LeasedSource(entry)) {
                        MediaConditions.require(db, entry, version, owner, sha256);
                        bytes = source.readMatchedBytes(entry, sha256);
                        source.requireHeld();
                    }
                    putIdentity(receipt.data, entry, version, owner, sha256);
                    receipt.data.putByteArray("snapshotBytes", bytes);
                    receipt.data.putString("displayName", entry.getDisplayName());
                    receipt.data.putBoolean("snapshotMatched", true);
                    receipt.success();
                    receipt.stage("snapshot_ready");
                    return receipt.data;
                });
            }
        } catch (Rejected failure) {
            return receipt.failure(failure.code);
        } catch (Exception failure) {
            return receipt.failure("media_snapshot_unavailable");
        } finally { log(receipt); }
    }

    private static NexBoxMediaEntry requireEntry(SQLiteDatabase db, long mediaId, String version, String owner, String sha256) throws IOException {
        if (mediaId <= 0) throw new Rejected("invalid_request");
        NexBoxMediaEntry entry = SharedMediaIndex.getIndexedEntryLocked(db, mediaId);
        if (entry == null) throw new Rejected("resource_not_found");
        if (entry.getIsPending() != 0) throw new Rejected("resource_busy");
        SharedMediaPaths.requireSharedFile(new File(entry.getFilePath()));
        MediaConditions.require(db, entry, version, owner, sha256);
        return entry;
    }

    private static final class LeasedSource implements AutoCloseable {
        private final ParcelFileDescriptor descriptor;

        LeasedSource(NexBoxMediaEntry entry) throws IOException {
            File file = SharedMediaPaths.requireSharedFile(new File(entry.getFilePath()));
            int fd;
            try { fd = NativeCore.acquireMediaFileLease(file.getPath()); }
            catch (UnsatisfiedLinkError unsupported) { throw new Rejected("atomicity_unavailable"); }
            if (fd < 0) {
                int error = -fd;
                throw new Rejected(error == android.system.OsConstants.EAGAIN ? "resource_busy"
                        : error == android.system.OsConstants.ENOENT ? "resource_changed" : "atomicity_unavailable");
            }
            descriptor = ParcelFileDescriptor.adoptFd(fd);
        }

        byte[] readMatchedBytes(NexBoxMediaEntry entry, String expected) throws IOException {
            if (entry.getSize() < 1 || entry.getSize() > VerifiedPng.MAX_BYTES) throw new Rejected("resource_changed");
            byte[] bytes = new byte[(int) entry.getSize()];
            try {
                int offset = 0;
                while (offset < bytes.length) {
                    int count = android.system.Os.read(descriptor.getFileDescriptor(), bytes, offset, bytes.length - offset);
                    if (count <= 0) throw new Rejected("resource_changed");
                    offset += count;
                }
                byte[] tail = new byte[1];
                if (android.system.Os.read(descriptor.getFileDescriptor(), tail, 0, 1) != 0
                        || !VerifiedPng.digest(bytes).equalsIgnoreCase(expected)) throw new Rejected("resource_changed");
            } catch (android.system.ErrnoException failure) { throw new IOException("Could not read leased media", failure); }
            return bytes;
        }

        void requireHeld() {
            if (!NativeCore.isMediaFileLeaseHeld(descriptor.getFd())) throw new Rejected("resource_busy");
        }

        @Override public void close() throws IOException { descriptor.close(); }
    }

    private static Bundle identity(long mediaId) {
        Bundle value = new Bundle();
        value.putLong("mediaId", mediaId);
        return value;
    }

    private static void requireCoreProcess() {
        if (!NexBoxCore.get().isServerProcess()) throw new Rejected("atomicity_unavailable");
    }

    private static void putIdentity(Bundle data, NexBoxMediaEntry entry, String version, String owner, String sha256) {
        data.putLong("mediaId", entry.getId());
        data.putString("version", version);
        data.putString("ownerToken", owner);
        data.putString("contentSha256", sha256);
        data.putLong("sizeBytes", entry.getSize());
        data.putString("mimeType", entry.getMimeType());
    }

    private static void log(MutationReceipt receipt) {
        Slog.i("ConditionalMedia", "requestId=" + receipt.data.getString("requestId") + " stage=" + receipt.data.getString("stage")
                + " mutationState=" + receipt.data.getString("mutationState") + " success=" + receipt.data.getBoolean("success")
                + " reason=" + receipt.data.getString("errorCode"));
    }
}
