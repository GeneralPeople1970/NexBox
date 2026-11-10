package com.nexbox.core.core.system.user;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/** An absent registry entry is not sufficient proof that its storage may be reused. */
public final class UserIdAllocator {
    private UserIdAllocator() { }

    public static int nextAvailable(Collection<Integer> registeredIds, IntPredicate hasStoredState) {
        Set<Integer> registered = new HashSet<>(registeredIds);
        for (int candidate = 0; ; candidate++) {
            if (!registered.contains(candidate) && !hasStoredState.test(candidate)) return candidate;
            if (candidate == Integer.MAX_VALUE) throw new IllegalStateException("No free user ID is available");
        }
    }
}
