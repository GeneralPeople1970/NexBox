package com.nexbox.core.core.system;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.utils.Slog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepares the single bundled, class-free DEX used by native cookie probing. */
public final class JarManager {
    private static final String TAG = "JarManager";
    private static final JarManager INSTANCE = new JarManager();
    private final AtomicBoolean warming = new AtomicBoolean();
    private volatile File emptyJar;
    private int expectedSize;

    private JarManager() { }

    public static JarManager getInstance() {
        return INSTANCE;
    }

    public void initializeAsync() {
        if (emptyJar != null || !warming.compareAndSet(false, true)) return;
        try {
            new Thread(() -> {
                try {
                    getEmptyJar();
                } catch (RuntimeException failure) {
                    Slog.e(TAG, "action=prepareEmptyDex stage=failed", failure);
                } finally {
                    warming.set(false);
                }
            }, "JarManager-Init").start();
        } catch (RuntimeException | Error failure) {
            warming.set(false);
            throw failure;
        }
    }

    public synchronized File getEmptyJar() {
        if (isUsable(emptyJar, expectedSize)) return emptyJar;
        emptyJar = null;
        File target = BEnvironment.EMPTY_JAR;
        try {
            byte[] expected;
            try (InputStream input = NexBoxCore.getContext().getAssets().open("empty.jar");
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                expected = bytes.toByteArray();
            }
            if (expected.length < 100) throw new IOException("The bundled empty DEX is incomplete");
            boolean reused = matches(target, expected);
            if (!reused) publish(target, expected);
            if (!isUsable(target, expected.length)) throw new IOException("The empty DEX is unavailable or writable");
            expectedSize = expected.length;
            emptyJar = target;
            Slog.iLazy(TAG, () -> "action=prepareEmptyDex stage=ready reused=" + reused);
            return target;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not prepare the empty DEX", failure);
        }
    }

    private static boolean isUsable(File file, int size) {
        return file != null && file.isFile() && file.length() == size && file.canRead() && !file.canWrite();
    }

    private static boolean matches(File file, byte[] expected) {
        if (!isUsable(file, expected.length)) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] actual = new byte[expected.length];
            int offset = 0;
            while (offset < actual.length) {
                int count = input.read(actual, offset, actual.length - offset);
                if (count < 0) return false;
                offset += count;
            }
            return input.read() == -1 && Arrays.equals(actual, expected);
        } catch (IOException failure) {
            return false;
        }
    }

    private static void publish(File target, byte[] expected) throws IOException {
        File directory = target.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create the empty DEX cache directory");
        }
        File staged = File.createTempFile("empty-dex-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) {
                // Android requires read-only code paths. The already-open FD can finish writing.
                if (!staged.setReadOnly()) throw new IOException("Could not make the empty DEX read-only");
                output.write(expected);
            }
            // Concurrent processes publish identical bytes; readers never see a partial file.
            if (!staged.renameTo(target)) throw new IOException("Could not publish the empty DEX");
        } finally {
            if (staged.exists() && !staged.delete()) Slog.w(TAG, "action=prepareEmptyDex stage=cleanupFailed");
        }
    }
}
