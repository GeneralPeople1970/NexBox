package com.nexbox.core.core.env;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Same-parent, journaled storage-root migration. Never merges or deletes data trees. */
public final class VirtualStorageLayout {
    private static final String ROOT_NAME = "nexbox";
    private static final String JOURNAL_NAME = ".nexbox-storage-layout";
    private final File internal;
    private final File external;
    private final Map<String, String> historicalRoots;

    private VirtualStorageLayout(File internal, File external, Map<String, String> historicalRoots) {
        this.internal = internal;
        this.external = external;
        this.historicalRoots = Collections.unmodifiableMap(new LinkedHashMap<>(historicalRoots));
    }

    public static VirtualStorageLayout open(File appData, File externalFiles) throws IOException {
        File parent = requireParent(appData);
        File journal = new File(parent, JOURNAL_NAME);
        File lockPath = new File(parent, JOURNAL_NAME + ".lock");
        rejectLink(lockPath);
        try (RandomAccessFile lockFile = new RandomAccessFile(lockPath, "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            Properties state = readJournal(journal);
            Map<String, String> aliases = new LinkedHashMap<>();
            File internal = resolveRoot(parent, false, "internal", journal, state, aliases);
            File external = externalFiles == null ? null
                    : resolveRoot(requireParent(externalFiles), true, "external", journal, state, aliases);
            addInputAlias(appData, state.getProperty("internal.source"), internal, aliases);
            if (external != null) addInputAlias(externalFiles, state.getProperty("external.source"), external, aliases);
            return new VirtualStorageLayout(internal, external, aliases);
        }
    }

    public File internalRoot() { return internal; }
    public File externalRoot() { return external; }
    public Map<String, String> historicalRoots() { return historicalRoots; }

    public String relocate(String path) {
        if (path == null) return null;
        for (Map.Entry<String, String> entry : historicalRoots.entrySet()) {
            String oldRoot = entry.getKey();
            if (path.equals(oldRoot) || path.startsWith(oldRoot + File.separator)) {
                return entry.getValue() + path.substring(oldRoot.length());
            }
        }
        return path;
    }

    private static void addInputAlias(File originalParent, String sourceName, File target,
                                      Map<String, String> aliases) {
        if (sourceName != null && !sourceName.isEmpty()) {
            // Android can expose the same app-private parent through a canonical path
            // and a Context path. Previously stored metadata may contain either form.
            aliases.put(new File(originalParent, sourceName).getAbsolutePath(), target.getAbsolutePath());
        }
    }

    private static File requireParent(File directory) throws IOException {
        if (directory == null || !directory.isDirectory()) throw new IOException("Container storage is unavailable");
        return directory.getCanonicalFile();
    }

    private static void rejectLink(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Container layout contains a symbolic link");
    }

    private static File child(File parent, String name) throws IOException {
        return child(parent, name, null);
    }

    private static File child(File parent, String name, File permittedAliasTarget) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            throw new IOException("Invalid container root record");
        }
        File candidate = new File(parent, name);
        if (Files.isSymbolicLink(candidate.toPath())) {
            if (permittedAliasTarget == null || !isExactAlias(candidate, permittedAliasTarget)) {
                throw new IOException("Container root contains an unexpected symbolic link");
            }
        }
        if (!parent.equals(candidate.getCanonicalFile().getParentFile())) {
            throw new IOException("Container root is outside its storage parent");
        }
        return candidate;
    }

    private static boolean isExactAlias(File alias, File target) throws IOException {
        if (!Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        Path resolved = alias.getParentFile().toPath().resolve(Files.readSymbolicLink(alias.toPath())).normalize();
        return resolved.equals(target.toPath());
    }

    private static File resolveRoot(File parent, boolean external, String key, File journal,
                                    Properties state, Map<String, String> aliases) throws IOException {
        File target = child(parent, ROOT_NAME);
        String recorded = state.getProperty(key + ".source");
        File source = recorded == null || recorded.isEmpty() ? null
                : child(parent, recorded, external ? null : target);
        if (source != null && source.equals(target)) throw new IOException("Invalid root migration source");

        List<File> discovered = discover(parent, target, external);
        if (source == null && !discovered.isEmpty()) {
            if (discovered.size() != 1 || Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Multiple container data roots exist; automatic merging is disabled");
            }
            source = discovered.get(0);
            state.setProperty(key + ".source", source.getName());
            // Record the old name before moving anything. A process/power failure can
            // then be recovered without guessing which tree is authoritative.
            writeJournal(journal, state);
        }
        for (File candidate : discovered) {
            if (!candidate.equals(source)) {
                throw new IOException("Another container data root requires manual recovery");
            }
        }

        if (source != null) {
            boolean sourceExists = Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(source.toPath());
            boolean targetExists = Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS);
            if (sourceExists && targetExists) {
                throw new IOException("Both migration source and destination exist; neither was changed");
            }
            if (!sourceExists && !targetExists) throw new IOException("Recorded container data root is missing");
            if (sourceExists) {
                if (!looksLikeRoot(source, external)) throw new IOException("Recorded source is not a container data tree");
                // Both are canonical direct siblings, and startup processes share the
                // layout lock. No copy/delete fallback can leave a partial user tree.
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            if (!Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Migrated container root is not a directory");
            }
            if (!external && !Files.isSymbolicLink(source.toPath())) {
                // Kernel resolution of a guest's absolute symlink bypasses userspace
                // IO hooks. Keep this exact internal-only alias without changing any
                // guest file. The historical basename comes solely from the journal.
                Files.createSymbolicLink(source.toPath(), target.toPath().getFileName());
            }
            syncDirectory(parent);
            aliases.put(source.getAbsolutePath(), target.getAbsolutePath());
        } else {
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Container root is not a directory");
            }
            Files.createDirectories(target.toPath());
        }
        return target;
    }

    private static List<File> discover(File parent, File target, boolean external) throws IOException {
        File[] children = parent.listFiles();
        if (children == null) throw new IOException("Could not inspect container storage");
        List<File> found = new ArrayList<>();
        for (File entry : children) {
            if (entry.equals(target) || !Files.isDirectory(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            File candidate = child(parent, entry.getName());
            if (looksLikeRoot(candidate, external)) found.add(candidate);
        }
        return found;
    }

    private static boolean directory(File root, String relative) throws IOException {
        File path = new File(root, relative);
        if (!Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        return path.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)
                && !containsLinkedParent(root, path);
    }

    private static boolean containsLinkedParent(File root, File path) {
        for (File cursor = path; cursor != null && !cursor.equals(root); cursor = cursor.getParentFile()) {
            if (Files.isSymbolicLink(cursor.toPath())) return true;
        }
        return false;
    }

    private static boolean metadata(File root, String name) throws IOException {
        File file = new File(root, "system/" + name);
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                && file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)
                && !containsLinkedParent(root, file);
    }

    private static boolean looksLikeRoot(File root, boolean external) throws IOException {
        if (external) return directory(root, "storage/emulated");
        if (!directory(root, "system")) return false;
        boolean registry = false;
        for (String name : new String[]{"user.conf", "user.conf.bak", "uid.conf", "uid.conf.bak",
                "shared-user.conf", "accounts.conf", "fake-location.conf", "xposed-state.bin", "media-index.db"}) {
            if (metadata(root, name)) { registry = true; break; }
        }
        return registry && (directory(root, "data/app") || directory(root, "data/user")
                || directory(root, "cache") || directory(root, "shared-media"));
    }

    private static Properties readJournal(File journal) throws IOException {
        rejectLink(journal);
        Properties state = new Properties();
        if (!Files.exists(journal.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            state.setProperty("version", "1");
            return state;
        }
        try (FileInputStream input = new FileInputStream(journal)) { state.load(input); }
        if (!"1".equals(state.getProperty("version"))) throw new IOException("Unsupported container root journal");
        return state;
    }

    private static void writeJournal(File journal, Properties state) throws IOException {
        File pending = File.createTempFile(".layout-", ".pending", journal.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) {
                state.store(output, "Container storage migration");
                output.getFD().sync();
            }
            rejectLink(journal);
            Files.move(pending.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            // Persist the journal directory entry BEFORE moving a data root. Syncing
            // just the temporary file would not make its renamed name crash-durable.
            syncDirectory(journal.getParentFile());
        } finally {
            Files.deleteIfExists(pending.toPath());
        }
    }

    static void syncDirectory(File directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
