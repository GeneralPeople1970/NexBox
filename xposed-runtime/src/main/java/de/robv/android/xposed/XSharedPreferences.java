package de.robv.android.xposed;

import android.content.SharedPreferences;
import android.util.Xml;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import com.nexbox.core.xposed.XposedRuntime;

/** Read-only, user-scoped XML preferences. Never changes modes or reads the host's /data/data. */
public final class XSharedPreferences implements SharedPreferences {
    private final File file;
    private volatile Map<String, Object> values = Collections.emptyMap();
    private long lastModified = -1, lastLength = -1;

    public XSharedPreferences(String packageName) { this(packageName, packageName + "_preferences"); }
    public XSharedPreferences(String packageName, String prefFileName) {
        this(XposedRuntime.resolvePreferences(packageName, prefFileName));
    }
    public XSharedPreferences(File prefFile) {
        file = XposedRuntime.validatePreferencesFile(prefFile);
        reload();
    }
    public File getFile() { return file; }
    public boolean makeWorldReadable() { return false; }
    public synchronized boolean hasFileChanged() {
        return lastModified != file.lastModified() || lastLength != file.length();
    }
    public synchronized void reload() {
        try {
            XposedRuntime.validatePreferencesFile(file);
            if (!file.exists()) {
                values = Collections.emptyMap();
                lastModified = 0;
                lastLength = 0;
                return;
            }
            byte[] xml;
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (output.size() + length > 4 * 1024 * 1024)
                        throw new IOException("Module preferences exceed 4 MiB");
                    output.write(buffer, 0, length);
                }
                xml = output.toByteArray();
            }
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
            if (parser.nextTag() != XmlPullParser.START_TAG || !"map".equals(parser.getName()))
                throw new IOException("Preferences root must be map");
            Map<String, Object> loaded = new HashMap<>();
            while (parser.nextTag() == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                String key = parser.getAttributeValue(null, "name");
                if (key == null) throw new IOException("Preference entry is missing name");
                Object value;
                if ("string".equals(tag)) {
                    value = parser.nextText();
                } else if ("set".equals(tag)) {
                    Set<String> set = new HashSet<>();
                    while (parser.nextTag() == XmlPullParser.START_TAG) {
                        if (!"string".equals(parser.getName())) throw new IOException("Invalid string set");
                        set.add(parser.nextText());
                    }
                    value = Collections.unmodifiableSet(set);
                } else {
                    String raw = parser.getAttributeValue(null, "value");
                    switch (tag) {
                        case "boolean":
                            if (!"true".equals(raw) && !"false".equals(raw)) throw new IOException("Invalid boolean");
                            value = Boolean.valueOf(raw); break;
                        case "int": value = Integer.valueOf(raw); break;
                        case "long": value = Long.valueOf(raw); break;
                        case "float": value = Float.valueOf(raw); break;
                        default: throw new IOException("Unsupported preference type: " + tag);
                    }
                    if (parser.nextTag() != XmlPullParser.END_TAG) throw new IOException("Unexpected nested preference value");
                }
                loaded.put(key, value);
            }
            if (!"map".equals(parser.getName())) throw new IOException("Unterminated preferences map");
            values = Collections.unmodifiableMap(loaded);
            lastModified = file.lastModified();
            lastLength = file.length();
        } catch (Throwable failure) {
            // Keep the previous complete snapshot if a writer replaces the XML during reload.
            XposedBridge.log(failure);
        }
    }
    @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
    @Override public String getString(String key, String defValue) { return (String) values.getOrDefault(key, defValue); }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> defValues) {
        Set<String> value = (Set<String>) values.get(key);
        return value == null ? defValues : new HashSet<>(value);
    }
    @Override public int getInt(String key, int defValue) { return (Integer) values.getOrDefault(key, defValue); }
    @Override public long getLong(String key, long defValue) { return (Long) values.getOrDefault(key, defValue); }
    @Override public float getFloat(String key, float defValue) { return (Float) values.getOrDefault(key, defValue); }
    @Override public boolean getBoolean(String key, boolean defValue) { return (Boolean) values.getOrDefault(key, defValue); }
    @Override public boolean contains(String key) { return values.containsKey(key); }
    @Override public Editor edit() { throw new UnsupportedOperationException("Module preferences are read-only in hooked apps"); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        throw new UnsupportedOperationException("Call reload() to refresh read-only module preferences");
    }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        throw new UnsupportedOperationException("Call reload() to refresh read-only module preferences");
    }
}
