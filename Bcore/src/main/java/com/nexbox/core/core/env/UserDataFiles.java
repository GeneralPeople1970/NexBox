package com.nexbox.core.core.env;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Checked lifecycle I/O: missing is harmless; inaccessible or partially removed is failure. */
public final class UserDataFiles {
    private UserDataFiles() { }

    public static void deleteTree(File directory) throws IOException {
        Path path = directory.toPath().toAbsolutePath().normalize();
        if (path.getParent() == null) throw new IOException("Refusing to remove a filesystem root");
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return;
        }
        // No FOLLOW_LINKS: remove links themselves, including dangling links, and never
        // recurse into a shared library directory or another user's linked data.
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void createDirectory(File directory) throws IOException {
        Path path = directory.toPath();
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Could not create user directory: " + directory);
        }
    }
}
