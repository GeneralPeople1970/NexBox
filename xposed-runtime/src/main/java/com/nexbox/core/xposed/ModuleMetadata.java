package com.nexbox.core.xposed;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Shared, bounded APK protocol parser used by discovery, preflight and the guest loader. */
public final class ModuleMetadata {
    public static final int LEGACY_API = 93;
    public static final int MODERN_API = 102;
    public static final String MODERN_ENTRY = "META-INF/xposed/java_init.list";
    private static final Pattern VERSION = Pattern.compile("[0-9]+");
    private static final Pattern LINES = Pattern.compile("\\r?\\n");
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    public final boolean modern;
    public final int minApi, targetApi;
    public final boolean staticScope;
    public final boolean protective;
    public final List<String> entries, scope;

    private ModuleMetadata(boolean modern, int minApi, int targetApi, boolean staticScope,
                           boolean protective, List<String> entries, List<String> scope) {
        this.modern = modern;
        this.minApi = minApi;
        this.targetApi = targetApi;
        this.staticScope = staticScope;
        this.protective = protective;
        this.entries = Collections.unmodifiableList(entries);
        this.scope = Collections.unmodifiableList(scope);
    }

    public static boolean hasModernEntry(String apk) {
        if (apk == null) return false;
        try (ZipFile zip = new ZipFile(apk)) { return zip.getEntry(MODERN_ENTRY) != null; }
        catch (IOException | RuntimeException ignored) { return false; }
    }

    public static boolean declaresLegacy(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0
                || value instanceof String text && text.equalsIgnoreCase("true");
    }

    public static int legacyMinimum(Object value) throws IOException {
        if (value == null) return 0;
        try {
            String text = value.toString().trim();
            if (!VERSION.matcher(text).matches()) throw new NumberFormatException();
            int minimum = Integer.parseInt(text);
            if (minimum > LEGACY_API) throw new IOException("Requires legacy Xposed API " + minimum
                    + "; NexBox provides " + LEGACY_API);
            return minimum;
        } catch (NumberFormatException error) { throw new IOException("Invalid xposedminversion", error); }
    }

    public static ModuleMetadata read(String apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            return readArchive(zip);
        }
    }

    /** Reads a caller-owned archive without closing it or reopening its path. */
    public static ModuleMetadata readArchive(ZipFile zip) throws IOException {
        // A dual-format module must never be instantiated twice. Prefer the modern protocol.
        if (zip.getEntry(MODERN_ENTRY) != null) {
            Properties props = new Properties();
            try {
                props.load(new ByteArrayInputStream(read(zip, "META-INF/xposed/module.prop", true)));
            } catch (IllegalArgumentException e) { throw new IOException("Invalid module.prop", e); }
            int min = version(props, "minApiVersion");
            int target = version(props, "targetApiVersion");
            if (min < 101 || min > MODERN_API || target < min) {
                throw new IOException("Unsupported libxposed API range " + min + "–" + target
                        + "; NexBox supports stable API 101–" + MODERN_API);
            }
            String mode = props.getProperty("exceptionMode", "protective").trim();
            if (!mode.equals("protective") && !mode.equals("passthrough"))
                throw new IOException("Invalid module.prop exceptionMode: " + mode);
            List<String> scope = parseScope(read(zip, "META-INF/xposed/scope.list", false));
            return new ModuleMetadata(true, min, target, booleanProperty(props, "staticScope"),
                    mode.equals("protective"),
                    readEntries(zip, MODERN_ENTRY), scope);
        }
        return new ModuleMetadata(false, 0, LEGACY_API, false, true,
                readEntries(zip, "assets/xposed_init"),
                new ArrayList<>());
    }

    private static List<String> readEntries(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing " + name);
        if (entry.isDirectory() || entry.getSize() > 65536) throw new IOException("Invalid or oversized " + name);
        try (var input = zip.getInputStream(entry)) {
            return ModuleEntries.parse(input, name);
        }
    }

    private static int version(Properties props, String name) throws IOException {
        try {
            String value = props.getProperty(name, "").trim();
            if (!VERSION.matcher(value).matches()) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException e) { throw new IOException("Invalid or missing module.prop " + name); }
    }

    private static boolean booleanProperty(Properties props, String name) throws IOException {
        String value = props.getProperty(name, "false").trim();
        if (!value.equals("true") && !value.equals("false"))
            throw new IOException("Invalid module.prop " + name);
        return Boolean.parseBoolean(value);
    }

    private static byte[] read(ZipFile zip, String name, boolean required) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            if (required) throw new IOException("Missing " + name);
            return new byte[0];
        }
        if (entry.isDirectory() || entry.getSize() > 65536) throw new IOException("Invalid or oversized " + name);
        try (var input = zip.getInputStream(entry)) {
            // InputStream.readNBytes requires newer Android versions without core-library desugaring.
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 65536) throw new IOException(name + " is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static List<String> parseScope(byte[] data) throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : LINES.split(new String(data, StandardCharsets.UTF_8).replace("\uFEFF", ""))) {
            int comment = raw.indexOf('#');
            String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
            if (line.isEmpty()) continue;
            if (!PACKAGE_NAME.matcher(line).matches() || result.size() >= 1000)
                throw new IOException("Invalid module scope.list");
            result.add(line);
        }
        return new ArrayList<>(result);
    }
}
