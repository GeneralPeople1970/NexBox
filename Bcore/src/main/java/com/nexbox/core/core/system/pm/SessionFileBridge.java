package com.nexbox.core.core.system.pm;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Never exposes a writable descriptor for the APK used by a committed generation. */
final class SessionFileBridge {
    interface WriteQuota { void reserve(long extent) throws IOException; }
    private static final long MAX_FILE = 1024L * 1024 * 1024;
    private static final HandlerThread CALLBACK_THREAD = new HandlerThread("PackageSessionFiles");
    static { CALLBACK_THREAD.start(); }
    private final RandomAccessFile target;
    private final WriteQuota quota;
    private ParcelFileDescriptor server;
    private ParcelFileDescriptor proxyDescriptor;
    private IBinder owner;
    private final IBinder.DeathRecipient ownerDeath = this::cancel;
    volatile boolean closed;
    volatile boolean failed;

    SessionFileBridge(File file, long offset, long length, WriteQuota quota) throws IOException {
        if (offset < 0 || offset > MAX_FILE || length < -1 || length > MAX_FILE
                || (length >= 0 && offset > length)) throw new IllegalArgumentException("Invalid APK write range");
        this.quota = quota;
        quota.reserve(file.length());
        target = new RandomAccessFile(file, "rw");
        try { target.seek(offset); }
        catch (IOException failure) { target.close(); throw failure; }
    }

    void bindOwner(IBinder binder) throws android.os.RemoteException {
        owner = binder;
        binder.linkToDeath(ownerDeath, 0);
    }

    ParcelFileDescriptor open(Context context, long offset) throws Exception {
        boolean revocable = PackageInstaller.class.getField("ENABLE_REVOCABLE_FD").getBoolean(null);
        if (revocable) {
            StorageManager storage = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            ParcelFileDescriptor descriptor = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY,
                    new ProxyFileDescriptorCallback() {
                        @Override public long onGetSize() throws ErrnoException {
                            synchronized (SessionFileBridge.this) {
                                try { return target.length(); } catch (IOException e) { throw ioError(); }
                            }
                        }
                        @Override public int onWrite(long at, int size, byte[] data) throws ErrnoException {
                            synchronized (SessionFileBridge.this) {
                                try {
                                    if (closed || at < 0 || size < 0 || at > MAX_FILE - size) throw new IOException();
                                    quota.reserve(at + size);
                                    target.seek(at);
                                    target.write(data, 0, size);
                                    return size;
                                } catch (IOException e) { failed = true; throw ioError(); }
                            }
                        }
                        @Override public void onFsync() throws ErrnoException {
                            synchronized (SessionFileBridge.this) {
                                try { target.getFD().sync(); } catch (IOException e) { failed = true; throw ioError(); }
                            }
                        }
                        @Override public void onRelease() { finish(false); }
                    }, new Handler(CALLBACK_THREAD.getLooper()));
            proxyDescriptor = descriptor;
            Os.lseek(descriptor.getFileDescriptor(), offset, OsConstants.SEEK_SET);
            return descriptor;
        }
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        server = pair[0];
        Thread worker = new Thread(this::runBridge, "PackageSessionWrite");
        worker.start();
        return pair[1];
    }

    private static ErrnoException ioError() { return new ErrnoException("sessionWrite", OsConstants.EIO); }

    private void runBridge() {
        try (DataInputStream input = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(server));
             DataOutputStream output = new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(
                     ParcelFileDescriptor.dup(server.getFileDescriptor())))) {
            byte[] bytes = new byte[65536];
            while (!closed) {
                int command = input.readInt();
                int length = input.readInt();
                if (command == 1) {
                    if (length < 0) throw new IOException("Invalid file bridge command");
                    while (length > 0) {
                        int count = Math.min(length, bytes.length);
                        input.readFully(bytes, 0, count);
                        synchronized (this) {
                            if (closed || target.getFilePointer() > MAX_FILE - count) throw new IOException("APK too large");
                            quota.reserve(target.getFilePointer() + count);
                            target.write(bytes, 0, count);
                        }
                        length -= count;
                    }
                } else if (command == 2 || command == 3) {
                    synchronized (this) { target.getFD().sync(); }
                    if (command == 3) finish(false);
                    output.writeInt(command);
                    output.writeInt(length);
                    output.flush();
                    if (command == 3) return;
                } else throw new IOException("Unknown file bridge command");
            }
        } catch (IOException error) { finish(true); }
        finally { finish(!closed); }
    }

    synchronized void finish(boolean error) {
        if (!closed) {
            failed |= error;
            try { target.getFD().sync(); }
            catch (IOException failure) { failed = true; }
            try { target.close(); }
            catch (IOException failure) { failed = true; }
            closed = true;
            notifyAll();
            if (owner != null) {
                try { owner.unlinkToDeath(ownerDeath, 0); }
                catch (java.util.NoSuchElementException ignored) { }
                owner = null;
            }
        }
    }

    void cancel() {
        finish(true);
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        // Closing our handle and target revokes all future proxy callbacks. A remote
        // duplicate may live until its process releases it, but cannot access the APK.
        if (proxyDescriptor != null) try { proxyDescriptor.close(); } catch (IOException ignored) { }
    }

    synchronized boolean awaitClosed(long deadline) throws InterruptedException {
        while (!closed) {
            long remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0) return false;
            wait(remaining);
        }
        return true;
    }
}
