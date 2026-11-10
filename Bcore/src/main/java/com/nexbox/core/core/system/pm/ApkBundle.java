package com.nexbox.core.core.system.pm;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Shared document preflight/server importer. Entry paths never become extraction paths. */
public final class ApkBundle {
    public static final long MAX_DOCUMENT_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long MAX_APK_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_APKS = 1024;
    private static final int MAX_ENTRIES = 16384;

    private ApkBundle() { }

    public interface Progress {
        void step(String stage, String details);
        default void checkCancelled() throws IOException { }
    }

    public interface ManifestReader {
        Manifest read(File apk) throws IOException;
    }

    public static final class Manifest {
        public final String packageName;
        public final String splitName;
        public final long versionCode;
        public final boolean featureSplit;
        public final String configForSplit;
        public final String usesSplitName;
        public final boolean splitRequired;
        public final Set<String> nativeAbis;

        public Manifest(String packageName, String splitName, long versionCode, boolean featureSplit,
                        String configForSplit, String usesSplitName, boolean splitRequired,
                        Set<String> nativeAbis) {
            this.packageName = packageName;
            this.splitName = emptyToNull(splitName);
            this.versionCode = versionCode;
            this.featureSplit = featureSplit;
            this.configForSplit = emptyToNull(configForSplit);
            this.usesSplitName = emptyToNull(usesSplitName);
            this.splitRequired = splitRequired;
            this.nativeAbis = Collections.unmodifiableSet(new LinkedHashSet<>(nativeAbis));
        }
    }

    public static final class ImportException extends IOException {
        public final String stage;
        public final String reason;

        ImportException(String stage, String reason, String message) {
            super("[" + stage + "] " + message);
            this.stage = stage;
            this.reason = reason;
        }
    }

    /** Owns only its newly created cache directory, including unselected variants. */
    public static final class Prepared implements Closeable {
        private final File root;
        public final File packageDirectory;
        public final File baseApk;
        public final String packageName;
        public final int splitCount;
        public final int apkCount;
        public final boolean hasToc;

        private Prepared(File root, File directory, File base, String name, int splits,
                         int apkCount, boolean hasToc) {
            this.root = root;
            this.packageDirectory = directory;
            this.baseApk = base;
            this.packageName = name;
            this.splitCount = splits;
            this.apkCount = apkCount;
            this.hasToc = hasToc;
        }

        @Override public void close() throws IOException {
            if (!deleteOwnedDirectory(root)) throw new IOException("Could not release staged APK bundle");
        }
    }

    private static final class Part {
        final File file;
        final Manifest manifest;
        Part(File file, Manifest manifest) { this.file = file; this.manifest = manifest; }
    }

    public static Prepared prepare(File archive, File cacheDirectory, String[] supportedAbis,
                                   Progress progress) throws IOException {
        return prepare(archive, cacheDirectory, supportedAbis, ApkManifest::read, progress);
    }

    static Prepared prepare(File archive, File cacheDirectory, String[] supportedAbis,
                            ManifestReader reader, Progress progress) throws IOException {
        File root = new File(cacheDirectory, "apk-bundle-" + UUID.randomUUID());
        if (!root.mkdir()) throw failure("bundleStage", "io", "Could not create APK bundle staging directory.");
        boolean accepted = false;
        try (ZipFile zip = openArchive(archive)) {
            List<ZipEntry> apks = new ArrayList<>();
            Set<String> names = new HashSet<>();
            boolean hasToc = false;
            boolean hasSplitsDirectory = false;
            int entryCount = 0;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                if (++entryCount > MAX_ENTRIES) throw failure("bundleInspect", "limit", "The APK bundle has too many entries.");
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                String lower = path.toLowerCase(Locale.ROOT);
                if (!names.add(path)) throw failure("bundleInspect", "duplicate", "The APK bundle contains duplicate ZIP entries.");
                if (lower.equals("toc.pb") || lower.endsWith("/toc.pb")) hasToc = true;
                if (lower.endsWith(".obb") || hasDirectory(lower, "asset-slices")) {
                    throw failure("bundleInspect", "expansion", "OBB/Play Asset Delivery bundles are not supported. Import an APK-only export.");
                }
                if (!lower.endsWith(".apk")) continue;
                if (apks.size() >= MAX_APKS) throw failure("bundleInspect", "limit", "The APK bundle contains too many APK files.");
                apks.add(entry);
                hasSplitsDirectory |= hasDirectory(lower, "splits");
            }
            if (apks.isEmpty()) throw failure("bundleInspect", "empty", "No APK files were found. Encrypted APKM archives are not supported.");
            progress.step("bundleInspected", "apkCount=" + apks.size() + " toc=" + hasToc);

            List<Part> parts = new ArrayList<>();
            long extractedBytes = 0L;
            int skippedVariants = 0;
            for (int index = 0; index < apks.size(); index++) {
                ZipEntry entry = apks.get(index);
                String path = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                // Bundletool's legacy standalone/instant/system alternatives are not splits.
                // Only an unambiguous split set is supported; duplicate manifest identities
                // below reject multi-variant TOCs instead of guessing by APK size or filename.
                if (hasToc && hasSplitsDirectory && !hasDirectory(path, "splits")) {
                    skippedVariants++;
                    continue;
                }
                if (hasToc && (hasDirectory(path, "instant") || hasDirectory(path, "system"))) {
                    throw unsupportedVariants();
                }
                File apk = new File(root, "part-" + index + ".apk");
                String entryDetails = "entry=" + (index + 1) + " name=" + safeEntryName(entry.getName());
                progress.step("bundleEntryRead", entryDetails + " declaredBytes=" + entry.getSize());
                try {
                    extractedBytes += extract(zip, entry, apk, MAX_DOCUMENT_BYTES - extractedBytes, progress);
                } catch (IOException error) {
                    ImportException failure = failure("bundleEntryRead", "entry", "Could not read APK entry "
                            + (index + 1) + " (" + safeEntryName(entry.getName()) + "): corrupt, unsupported compression, or size limit exceeded.");
                    failure.initCause(error);
                    throw failure;
                }
                Manifest manifest;
                try {
                    manifest = reader.read(apk);
                } catch (IOException error) {
                    ImportException failure = failure("bundleEntryManifest", "manifest", "Cannot read the manifest of APK entry "
                            + (index + 1) + " (" + safeEntryName(entry.getName()) + "). The entry is not a valid APK.");
                    failure.initCause(error);
                    throw failure;
                }
                if (manifest == null || manifest.packageName == null || manifest.packageName.isEmpty()) {
                    throw failure("bundleEntryManifest", "manifest", "APK entry " + (index + 1) + " has no package name.");
                }
                parts.add(new Part(apk, manifest));
                progress.step("bundleEntryValidated", entryDetails + " kind="
                        + (manifest.splitName == null ? "base" : manifest.featureSplit ? "feature" : "config"));
            }
            List<Part> selected = select(parts, supportedAbis);
            File cluster = new File(root, "cluster");
            if (!cluster.mkdir()) throw failure("bundleStage", "io", "Could not stage the APK split set.");
            File base = new File(cluster, "base.apk");
            for (int index = 0; index < selected.size(); index++) {
                File target = index == 0 ? base : new File(cluster, "split-" + index + ".apk");
                if (!selected.get(index).file.renameTo(target)) {
                    throw failure("bundleStage", "io", "Could not stage the selected APK split set.");
                }
            }
            progress.step("bundleSelected", "splits=" + (selected.size() - 1) + " excludedAbi="
                    + (parts.size() - selected.size()) + " excludedVariants=" + skippedVariants
                    + " tocMode=" + (hasToc ? "singleSplitSet" : "manifest"));
            Prepared prepared = new Prepared(root, cluster, base, selected.get(0).manifest.packageName,
                    selected.size() - 1, apks.size(), hasToc);
            accepted = true;
            return prepared;
        } finally {
            if (!accepted && !deleteOwnedDirectory(root)) {
                progress.step("bundleCleanup", "success=false");
            }
        }
    }

    private static List<Part> select(List<Part> parts, String[] supportedAbis) throws IOException {
        Part base = null;
        String packageName = null;
        Long version = null;
        Map<String, Part> splits = new LinkedHashMap<>();
        Set<String> availableAbis = new LinkedHashSet<>();
        for (Part part : parts) {
            Manifest manifest = part.manifest;
            if (packageName == null) { packageName = manifest.packageName; version = manifest.versionCode; }
            if (!packageName.equals(manifest.packageName)) {
                throw failure("bundleSelect", "packages", "The bundle contains different applications; import one application at a time.");
            }
            if (version.longValue() != manifest.versionCode) {
                throw failure("bundleSelect", "versions", "The APK bundle mixes different application versions.");
            }
            if (manifest.splitName == null) {
                if (base != null) throw unsupportedVariants();
                base = part;
            } else if (splits.put(manifest.splitName, part) != null) {
                throw unsupportedVariants();
            }
            availableAbis.addAll(manifest.nativeAbis);
            String splitAbi = splitAbi(manifest);
            if (splitAbi != null) availableAbis.add(splitAbi);
        }
        if (base == null) throw failure("bundleSelect", "base", "The bundle has no base APK. Import the complete APK split set.");
        String selectedAbi = null;
        for (String abi : supportedAbis) {
            if (availableAbis.contains(abi) && (base.manifest.nativeAbis.isEmpty() || base.manifest.nativeAbis.contains(abi))) {
                selectedAbi = abi;
                break;
            }
        }
        if (!availableAbis.isEmpty() && selectedAbi == null) {
            throw failure("bundleSelect", "abi", "The bundle has no native APK for this container's CPU architecture.");
        }
        List<Part> selected = new ArrayList<>();
        selected.add(base);
        Set<String> selectedNames = new HashSet<>();
        for (Part part : splits.values()) {
            Manifest manifest = part.manifest;
            String abi = splitAbi(manifest);
            if (abi != null && !abi.equals(selectedAbi)) continue;
            if (!manifest.nativeAbis.isEmpty() && !manifest.nativeAbis.contains(selectedAbi)) {
                // A feature is not optional just because its native code is incompatible.
                if (manifest.featureSplit || abi == null) {
                    throw failure("bundleSelect", "abi", "A required split has no native code for this container's CPU architecture.");
                }
                continue;
            }
            selected.add(part);
            selectedNames.add(manifest.splitName);
        }
        if (base.manifest.splitRequired && selected.size() == 1) {
            throw failure("bundleSelect", "missing", "The base APK requires missing splits. Import the complete APK split set.");
        }
        for (Part part : selected) {
            String dependency = part.manifest.usesSplitName;
            String target = part.manifest.configForSplit;
            if ((dependency != null && !"base".equals(dependency) && !selectedNames.contains(dependency))
                    || (target != null && !"base".equals(target) && !selectedNames.contains(target))) {
                throw failure("bundleSelect", "missing", "The bundle is missing a required feature split. Import the complete APK split set.");
            }
        }
        // Keep every resource/language and feature split. Android chooses resources and parses
        // feature components from this cluster; selecting one split globally loses modules.
        selected.subList(1, selected.size()).sort(Comparator.comparing(part -> part.manifest.splitName));
        return selected;
    }

    private static String splitAbi(Manifest manifest) {
        if (manifest.featureSplit || manifest.splitName == null) return null;
        String name = manifest.splitName.toLowerCase(Locale.ROOT).replace('-', '_');
        for (String abi : Arrays.asList("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "mips64", "mips", "riscv64")) {
            if (name.endsWith("." + abi.replace('-', '_'))) return abi;
        }
        return null;
    }

    private static ZipFile openArchive(File source) throws IOException {
        try { return new ZipFile(source); }
        catch (IOException error) {
            ImportException failure = failure("bundleOpen", "archive", "The file is not a readable ZIP APK bundle. Corrupt or encrypted APKM archives are not supported.");
            failure.initCause(error);
            throw failure;
        }
    }

    private static long extract(ZipFile archive, ZipEntry entry, File target, long remaining,
                                Progress progress) throws IOException {
        long limit = Math.min(MAX_APK_BYTES, remaining);
        if (entry.getSize() > limit || limit <= 0L) throw new IOException("APK size limit exceeded");
        long count = 0L;
        CRC32 crc = new CRC32();
        try (InputStream input = archive.getInputStream(entry); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while (true) {
                progress.checkCancelled();
                read = input.read(buffer);
                if (read == -1) break;
                progress.checkCancelled();
                count += read;
                if (count > limit) throw new IOException("APK size limit exceeded");
                output.write(buffer, 0, read);
                crc.update(buffer, 0, read);
            }
        }
        if (count == 0 || (entry.getSize() >= 0 && count != entry.getSize())
                || (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc())) {
            throw new IOException("APK entry size or CRC mismatch");
        }
        return count;
    }

    private static boolean hasDirectory(String path, String directory) {
        return path.startsWith(directory + "/") || path.contains("/" + directory + "/");
    }

    private static String safeEntryName(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return name.substring(0, Math.min(name.length(), 96));
    }

    private static ImportException unsupportedVariants() {
        return failure("bundleSelect", "variants", "This multi-variant/system/instant APKS bundle is not supported. Export a device-specific APKS or a universal APK with bundletool.");
    }

    private static ImportException failure(String stage, String reason, String message) {
        return new ImportException(stage, reason, message);
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    private static boolean deleteOwnedDirectory(File file) {
        if (!file.exists()) return true;
        boolean deleted = true;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleted &= deleteOwnedDirectory(child);
        return file.delete() && deleted;
    }
}
