package com.nexbox.core.core.system.pm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Assigns each authority once within one package, in the parser's declaration order. */
final class ProviderAuthorityPlan<T> {
    private final Map<String, T> owners = new LinkedHashMap<>();
    private final Map<T, List<String>> authorities = new IdentityHashMap<>();
    private final List<T> providers = new ArrayList<>();
    private final List<Duplicate<T>> duplicates = new ArrayList<>();

    void add(T provider, String declaration) {
        Objects.requireNonNull(provider, "provider");
        if (authorities.containsKey(provider)) return;
        List<String> assigned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (declaration != null) {
            for (String authority : declaration.split(";")) {
                if (authority.isEmpty() || !seen.add(authority)) continue;
                T owner = owners.get(authority);
                if (owner == null) {
                    owners.put(authority, provider);
                    assigned.add(authority);
                } else {
                    duplicates.add(new Duplicate<>(provider, owner, authority));
                }
            }
        }
        authorities.put(provider, Collections.unmodifiableList(assigned));
        providers.add(provider);
    }

    List<T> providers() {
        return Collections.unmodifiableList(providers);
    }

    List<String> authoritiesFor(T provider) {
        List<String> result = authorities.get(provider);
        if (result == null) throw new IllegalArgumentException("Provider is not in this package plan");
        return result;
    }

    List<Duplicate<T>> duplicates() {
        return Collections.unmodifiableList(duplicates);
    }

    static final class Duplicate<T> {
        final T provider;
        final T owner;
        final String authority;

        private Duplicate(T provider, T owner, String authority) {
            this.provider = provider;
            this.owner = owner;
            this.authority = authority;
        }
    }
}
