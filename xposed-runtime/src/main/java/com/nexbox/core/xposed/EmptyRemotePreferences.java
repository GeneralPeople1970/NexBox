package com.nexbox.core.xposed;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Read-only defaults for modules that request preferences without checking PROP_CAP_REMOTE. */
final class EmptyRemotePreferences implements SharedPreferences {
    static final EmptyRemotePreferences INSTANCE = new EmptyRemotePreferences();

    private EmptyRemotePreferences() {}

    @Override public Map<String, ?> getAll() { return Collections.emptyMap(); }
    @Override public String getString(String key, String defValue) { return defValue; }
    @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
    @Override public int getInt(String key, int defValue) { return defValue; }
    @Override public long getLong(String key, long defValue) { return defValue; }
    @Override public float getFloat(String key, float defValue) { return defValue; }
    @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
    @Override public boolean contains(String key) { return false; }

    @Override public Editor edit() {
        throw new UnsupportedOperationException("Remote preferences are unavailable; fallback preferences are read-only");
    }

    // This immutable view never changes. Do not retain listeners from guest/module class loaders.
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
}
