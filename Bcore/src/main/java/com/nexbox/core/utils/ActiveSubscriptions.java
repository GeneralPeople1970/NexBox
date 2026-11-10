package com.nexbox.core.utils;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Owns a single periodic task exactly while subscribers exist. Values are registration tokens. */
public final class ActiveSubscriptions<K, V> {
    private final Map<K, V> entries = new LinkedHashMap<>();
    private final Supplier<Runnable> start;
    private Runnable cancel;
    private List<Map.Entry<K, V>> snapshot;

    public ActiveSubscriptions(Supplier<Runnable> start) {
        this.start = start;
    }

    public synchronized boolean add(K key, V value) {
        if (entries.containsKey(key)) return false;
        entries.put(key, value);
        snapshot = null;
        try {
            if (cancel == null) cancel = start.get();
        } catch (RuntimeException error) {
            entries.remove(key);
            snapshot = null;
            throw error;
        }
        return true;
    }

    public synchronized V remove(K key) {
        V previous = entries.remove(key);
        snapshot = null;
        stopIfEmpty();
        return previous;
    }

    public synchronized boolean remove(K key, V expected) {
        if (entries.get(key) != expected) return false;
        entries.remove(key);
        snapshot = null;
        stopIfEmpty();
        return true;
    }

    public synchronized List<V> removeMatching(Predicate<V> predicate) {
        List<V> removed = new ArrayList<>();
        snapshot = null;
        entries.entrySet().removeIf(entry -> {
            if (!predicate.test(entry.getValue())) return false;
            removed.add(entry.getValue());
            return true;
        });
        stopIfEmpty();
        return removed;
    }

    public synchronized boolean isCurrent(K key, V value) {
        return entries.get(key) == value;
    }

    public synchronized List<Map.Entry<K, V>> snapshot() {
        if (snapshot == null) {
            List<Map.Entry<K, V>> copy = new ArrayList<>(entries.size());
            entries.forEach((key, value) -> copy.add(new AbstractMap.SimpleImmutableEntry<>(key, value)));
            snapshot = Collections.unmodifiableList(copy);
        }
        return snapshot;
    }

    private void stopIfEmpty() {
        if (entries.isEmpty() && cancel != null) {
            cancel.run();
            cancel = null;
        }
    }
}
