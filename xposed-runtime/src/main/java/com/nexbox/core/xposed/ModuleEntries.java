package com.nexbox.core.xposed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Strict bounded parser, independent of Android so malformed modules can be unit tested.
 *
 * <p>This is the single authority on what a module's entry points are: the guest loader reads
 * through it, and the server-side inspector delegates here, so "the server accepted it" and "the
 * guest can load it" cannot drift apart.
 */
public final class ModuleEntries {
    private static final Pattern LINES = Pattern.compile("\\r?\\n");
    private static final Pattern CLASS_NAME = Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)+");
    private ModuleEntries() {}

    public static List<String> read(String apk) throws IOException {
        return ModuleMetadata.read(apk).entries;
    }

    static List<String> parse(InputStream input) throws IOException {
        return parse(input, "assets/xposed_init");
    }

    static List<String> parse(InputStream input, String entryName) throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        // Limit decompressed bytes before readLine() to avoid a zip-bomb / enormous single line.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 65536) throw new IOException(entryName + " is too large");
            output.write(buffer, 0, count);
        }
        byte[] bytes = output.toByteArray();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        for (String raw : LINES.split(text)) {
            String line = raw.trim();
            // Several forks of the original loader accept a trailing comment; a whole-line
            // comment is just the case where the truncation leaves nothing behind.
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            if (line.isEmpty()) continue;
            // A bare class name can never be resolved by Class.forName, so rejecting it here
            // reports the problem at inspection time instead of at load time.
            if (!CLASS_NAME.matcher(line).matches())
                throw new IOException("Invalid " + entryName + " class name");
            result.add(line);
            if (result.size() > 256) throw new IOException("Too many module entry points");
        }
        if (result.isEmpty()) throw new IOException(entryName + " contains no entry points");
        return new ArrayList<>(result);
    }
}
