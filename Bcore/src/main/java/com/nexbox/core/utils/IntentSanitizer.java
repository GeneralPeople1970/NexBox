package com.nexbox.core.utils;

import android.content.Intent;
import android.os.Bundle;

import com.nexbox.core.utils.compat.BundleCompat;
import java.util.ArrayList;

public final class IntentSanitizer {
    private static final String TAG = "IntentSanitizer";
    private static final String CLASS_MARKER_PREFIX = NexBoxIpcKeys.CLASS_EXTRA_PREFIX;
    private static final String LEGACY_CLASS_MARKER_PREFIX = NexBoxIpcKeys.LEGACY_CLASS_EXTRA_PREFIX;
    private static final String CLASS_CATEGORY = "com.nexbox.internal.CLASS_EXTRAS";

    private IntentSanitizer() { }

    public static void sanitizeClassExtrasForIpc(Intent intent) {
        if (intent != null) sanitizeIntent(intent);
    }

    public static void restoreSanitizedClassExtras(Intent intent, ClassLoader classLoader) {
        if (intent != null) restoreIntent(intent, classLoader == null ? IntentSanitizer.class.getClassLoader() : classLoader);
    }

    private static boolean sanitizeIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        boolean changed = sanitizeBundle(extras);
        // Intent.getExtras() returns a copy. Without replacing it, class-name markers
        // only modify that copy and the original Class still crosses Binder.
        if (changed) intent.replaceExtras(extras);
        Intent selector = intent.getSelector();
        if (selector != null) changed |= sanitizeIntent(selector);
        if (changed) intent.addCategory(CLASS_CATEGORY);
        return changed;
    }

    private static boolean sanitizeBundle(Bundle bundle) {
        if (bundle == null) return false;
        boolean changed = false;
        for (String key : new ArrayList<>(bundle.keySet())) {
            Object value;
            try { value = BundleCompat.getValue(bundle, key); }
            catch (RuntimeException unreadable) {
                // Keep opaque guest Parcelables. Deleting a login extra turns a transport
                // limitation into an unrelated authentication failure.
                Slog.w(TAG, "Preserving opaque extra during class sanitization");
                continue;
            }
            if (value instanceof Class<?>) {
                bundle.putString(CLASS_MARKER_PREFIX + key, ((Class<?>) value).getName());
                bundle.remove(LEGACY_CLASS_MARKER_PREFIX + key);
                bundle.remove(key);
                changed = true;
            } else if (value instanceof Intent) {
                changed |= sanitizeIntent((Intent) value);
            } else if (value instanceof Bundle) {
                changed |= sanitizeBundle((Bundle) value);
            } else if (value instanceof ArrayList<?>) {
                changed |= sanitizeList((ArrayList<?>) value);
            }
        }
        return changed;
    }

    private static boolean sanitizeList(ArrayList<?> values) {
        boolean changed = false;
        for (Object value : values) {
            if (value instanceof Intent) changed |= sanitizeIntent((Intent) value);
            else if (value instanceof Bundle) changed |= sanitizeBundle((Bundle) value);
        }
        return changed;
    }

    private static void restoreIntent(Intent intent, ClassLoader classLoader) {
        // Categories are outside the extras Parcel. Normal GMS/module intents require
        // no transformation and must not be forced through the base app's class loader.
        if (!intent.hasCategory(CLASS_CATEGORY)) return;
        intent.setExtrasClassLoader(classLoader);
        Bundle extras = intent.getExtras();
        if (extras != null) {
            extras.setClassLoader(classLoader);
            restoreBundle(extras, classLoader);
            intent.replaceExtras(extras);
        }
        if (intent.getSelector() != null) restoreIntent(intent.getSelector(), classLoader);
        // Retain the marker if restoration fails, rather than publishing a partial result.
        intent.removeCategory(CLASS_CATEGORY);
    }

    private static void restoreBundle(Bundle bundle, ClassLoader classLoader) {
        bundle.setClassLoader(classLoader);
        for (String key : new ArrayList<>(bundle.keySet())) {
            if (key == null) continue;
            boolean legacy = key.startsWith(LEGACY_CLASS_MARKER_PREFIX);
            if (key.startsWith(CLASS_MARKER_PREFIX) || legacy) {
                String prefix = legacy ? LEGACY_CLASS_MARKER_PREFIX : CLASS_MARKER_PREFIX;
                String original = key.substring(prefix.length());
                // Each original extra has one value; a current marker must not fall back to an older one.
                if (legacy && bundle.containsKey(CLASS_MARKER_PREFIX + original)) continue;
                String name = bundle.getString(key);
                if (name == null || bundle.containsKey(original)) continue;
                try {
                    bundle.putSerializable(original, Class.forName(name, false, classLoader));
                    bundle.remove(CLASS_MARKER_PREFIX + original);
                    bundle.remove(LEGACY_CLASS_MARKER_PREFIX + original);
                } catch (ClassNotFoundException failure) {
                    throw new IllegalArgumentException("Cannot restore a class extra", failure);
                }
                continue;
            }
            Object value;
            try { value = BundleCompat.getValue(bundle, key); }
            catch (RuntimeException unreadable) {
                Slog.w(TAG, "Preserving opaque extra during class restoration");
                continue;
            }
            if (value instanceof Intent) restoreIntent((Intent) value, classLoader);
            else if (value instanceof Bundle) restoreBundle((Bundle) value, classLoader);
            else if (value instanceof ArrayList<?>) {
                for (Object item : (ArrayList<?>) value) {
                    if (item instanceof Intent) restoreIntent((Intent) item, classLoader);
                    else if (item instanceof Bundle) restoreBundle((Bundle) item, classLoader);
                }
            }
        }
    }
}
