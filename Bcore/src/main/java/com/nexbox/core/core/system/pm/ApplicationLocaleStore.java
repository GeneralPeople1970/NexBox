package com.nexbox.core.core.system.pm;

import android.os.Bundle;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import com.nexbox.core.core.env.BEnvironment;

/** App-private storage is removed by the existing clear/uninstall/user cleanup paths. */
final class ApplicationLocaleStore {
    private static final String FILE_NAME = ".nexbox-application-locales";
    private static final int MAX_FILE_BYTES = 32 * 1024;

    static String canonicalTags(String tags) {
        Objects.requireNonNull(tags, "Locale tags cannot be null");
        if (tags.length() > 4096) throw new IllegalArgumentException("Too many locale tags");
        if (tags.isEmpty()) return "";
        Set<String> canonical = new LinkedHashSet<>();
        for (String tag : tags.split(",", -1)) {
            if (tag.isEmpty()) throw new IllegalArgumentException("Empty locale tag");
            canonical.add(new Locale.Builder().setLanguageTag(tag).build().toLanguageTag());
        }
        if (canonical.size() > 64) throw new IllegalArgumentException("Too many locales");
        return String.join(",", canonical);
    }

    Bundle read(String packageName, int userId, long generation) {
        Properties values = load(packageName, userId, generation);
        Bundle result = new Bundle();
        result.putString("locales", values.getProperty("locales", ""));
        result.putString("override", values.getProperty("override"));
        result.putLong("generation", generation);
        return result;
    }

    boolean write(String packageName, int userId, long generation, String tags, boolean override) {
        String value = tags == null && override ? null : canonicalTags(tags);
        Properties values = load(packageName, userId, generation);
        String key = override ? "override" : "locales";
        String previous = values.getProperty(key, override ? null : "");
        if (Objects.equals(previous, value)) return false;
        if (value == null) values.remove(key); else values.setProperty(key, value);
        values.setProperty("generation", Long.toString(generation));
        File file = file(packageName, userId);
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
                throw new IOException("Cannot create locale state directory");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            values.store(bytes, null);
            output = atomic.startWrite();
            output.write(bytes.toByteArray());
            atomic.finishWrite(output);
            return true;
        } catch (IOException failure) {
            if (output != null) atomic.failWrite(output);
            throw new IllegalStateException("Could not save application locales", failure);
        }
    }

    private Properties load(String packageName, int userId, long generation) {
        Properties values = new Properties();
        File file = file(packageName, userId);
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return values;
        try (FileInputStream input = new AtomicFile(file).openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_FILE_BYTES) throw new IOException("Locale state is too large");
                bytes.write(buffer, 0, count);
            }
            values.load(new ByteArrayInputStream(bytes.toByteArray()));
            if (!Long.toString(generation).equals(values.getProperty("generation"))) {
                throw new IOException("Locale state belongs to another user generation");
            }
            canonicalTags(values.getProperty("locales", ""));
            if (values.containsKey("override")) canonicalTags(values.getProperty("override"));
            return values;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Application locale state needs recovery", failure);
        }
    }

    private static File file(String packageName, int userId) {
        File directory = BEnvironment.getDataDir(packageName, userId);
        try {
            File expected = new File(BEnvironment.getUserDir(userId).getCanonicalFile(), packageName);
            if (!directory.getCanonicalFile().equals(expected)) throw new IOException("Locale directory is redirected");
            for (String suffix : new String[]{"", ".new", ".bak"}) {
                File candidate = new File(directory, FILE_NAME + suffix);
                if (!candidate.getCanonicalFile().equals(new File(expected, FILE_NAME + suffix))) {
                    throw new IOException("Locale state is redirected");
                }
            }
            return new File(directory, FILE_NAME);
        } catch (IOException failure) {
            throw new IllegalStateException("Invalid application locale path", failure);
        }
    }
}
