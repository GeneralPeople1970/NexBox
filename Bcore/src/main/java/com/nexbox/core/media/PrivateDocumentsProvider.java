package com.nexbox.core.media;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import org.json.JSONObject;

public final class PrivateDocumentsProvider extends DocumentsProvider {
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.startsWith("picker.")) return PrivateDocuments.picker(method.substring(7), arg, extras);
        return super.call(method, arg, extras);
    }
    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{"root_id", "document_id", "title", "flags"} : projection);
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        synchronized (PrivateDocuments.lock()) { try {
            File file = PrivateDocuments.authorized(id, 1);
            if (!file.exists()) throw new FileNotFoundException();
            MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection); row(cursor, id, file); return cursor;
        } catch (Exception failure) { throw unavailable(failure); } }
    }
    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        synchronized (PrivateDocuments.lock()) { try {
            File parent = PrivateDocuments.authorized(parentId, 1); File[] children = parent.listFiles();
            if (children == null) throw new FileNotFoundException();
            MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
            for (File child : children) {
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue;
                JSONObject p = PrivateDocuments.decode(parentId); String path = p.getString("path");
                p.put("path", path.isEmpty() ? child.getName() : path + "/" + child.getName());
                String id = PrivateDocuments.encode(p); PrivateDocuments.authorized(id, 1); row(cursor, id, child);
            }
            return cursor;
        } catch (Exception failure) { throw unavailable(failure); } }
    }
    private static void row(MatrixCursor cursor, String id, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: value = id; break;
                case Document.COLUMN_DISPLAY_NAME: value = file.getName(); break;
                case Document.COLUMN_MIME_TYPE: value = PrivateDocuments.mime(file); break;
                case Document.COLUMN_SIZE: value = file.length(); break;
                case Document.COLUMN_LAST_MODIFIED: value = file.lastModified(); break;
                case Document.COLUMN_FLAGS: value = file.canWrite() ? (file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE) : 0; break;
            }
            row.add(value);
        }
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        synchronized (PrivateDocuments.lock()) { try {
            if (signal != null) signal.throwIfCanceled();
            int flags = mode.contains("w") || mode.contains("+") ? mode.contains("r") ? 3 : 2 : 1;
            File file = PrivateDocuments.authorized(id, flags);
            if (!file.isFile()) throw new FileNotFoundException();
            if (NexBoxMediaStore.isSharedPath(file)) {
                return NexBoxMediaStore.openSharedPath(file, ParcelFileDescriptor.parseMode(mode));
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        } catch (Exception failure) { throw unavailable(failure); } }
    }
    @Override public String createDocument(String parentId, String mime, String name) throws FileNotFoundException {
        synchronized (PrivateDocuments.lock()) { try {
            File parent = PrivateDocuments.authorized(parentId, 2); PrivateDocuments.validateName(name);
            if (!parent.isDirectory()) throw new FileNotFoundException();
            File child = new File(parent, name);
            if (!(Document.MIME_TYPE_DIR.equals(mime) ? child.mkdir() : child.createNewFile())) throw new FileNotFoundException("Name already exists");
            JSONObject p = PrivateDocuments.decode(parentId); String path = p.getString("path");
            p.put("path", path.isEmpty() ? name : path + "/" + name); return PrivateDocuments.encode(p);
        } catch (Exception failure) { throw unavailable(failure); } }
    }
    @Override public boolean isChildDocument(String parent, String child) {
        synchronized (PrivateDocuments.lock()) { try {
            File a = PrivateDocuments.authorized(parent, 1); File b = PrivateDocuments.authorized(child, 1);
            return b.getCanonicalPath().startsWith(a.getCanonicalPath() + File.separator);
        } catch (Exception failure) { return false; } }
    }
    private static FileNotFoundException unavailable(Exception cause) {
        if (cause instanceof SecurityException) throw (SecurityException) cause;
        FileNotFoundException failure = new FileNotFoundException("Private document unavailable"); failure.initCause(cause); return failure;
    }
}
