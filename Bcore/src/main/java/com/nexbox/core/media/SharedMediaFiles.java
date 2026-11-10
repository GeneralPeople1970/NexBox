package com.nexbox.core.media;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/** Filesystem primitives kept Android-independent so lossless import failure paths can be tested. */
public final class SharedMediaFiles {
    private SharedMediaFiles() { }

    @FunctionalInterface
    public interface AtomicPublisher {
        /** Move within the same directory atomically, failing if the destination exists. */
        void publish(File temporary, File target) throws IOException;
    }

    public static File requireInside(File root, File file) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!canonical.getPath().startsWith(root.getCanonicalPath() + File.separator)) {
            throw new IOException("Media path leaves the shared gallery");
        }
        return canonical;
    }

    public static void copyAtomically(InputStream input, File root, File target,
                                      AtomicPublisher publisher) throws IOException {
        if (input == null || publisher == null) throw new IOException("Missing media import source or publisher");
        target = requireInside(root, target);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create a media directory");
        }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to overwrite an existing media file");
        }
        File temporary = File.createTempFile(".nexbox-import-", ".pending", parent);
        Throwable failure = null;
        try {
            requireInside(root, temporary);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Media import interrupted");
                    output.write(buffer, 0, count);
                }
                if (Thread.currentThread().isInterrupted()) throw new IOException("Media import interrupted");
                output.getFD().sync();
            }
            if (!requireInside(root, temporary).equals(temporary)
                    || !requireInside(root, target).equals(target)) {
                throw new IOException("Media directory changed during import");
            }
            // Android's publisher uses renameat2(RENAME_NOREPLACE). A Java ATOMIC_MOVE
            // may replace an existing file on Unix, and hard links are blocked by SELinux.
            // The complete, synced byte copy is the only object published to the gallery.
            publisher.publish(temporary, target);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            // Never remove the destination on failure: it may belong to a concurrent writer.
            // Report cleanup failures instead of relying on deleteOnExit in an Android process.
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException cleanupError) {
                if (failure != null) failure.addSuppressed(cleanupError);
                else throw cleanupError;
            }
        }
    }

    public static boolean sameContent(File first, File second) throws IOException {
        if (!second.isFile() || first.length() != second.length()) return false;
        try (InputStream left = new FileInputStream(first); InputStream right = new FileInputStream(second)) {
            byte[] leftBuffer = new byte[32 * 1024];
            byte[] rightBuffer = new byte[leftBuffer.length];
            int count;
            while ((count = left.read(leftBuffer)) != -1) {
                int offset = 0;
                while (offset < count) {
                    int read = right.read(rightBuffer, offset, count - offset);
                    if (read < 0) return false;
                    offset += read;
                }
                for (int i = 0; i < count; i++) if (leftBuffer[i] != rightBuffer[i]) return false;
            }
            return right.read() == -1;
        }
    }
}
