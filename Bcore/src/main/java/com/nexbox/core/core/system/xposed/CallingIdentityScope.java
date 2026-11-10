package com.nexbox.core.core.system.xposed;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Android-independent finally boundary for querying AMS under the server's own identity. */
final class CallingIdentityScope {
    private CallingIdentityScope() {}

    static <T> T read(LongSupplier clear, LongConsumer restore, Supplier<T> query) {
        long token = clear.getAsLong();
        try { return query.get(); }
        finally { restore.accept(token); }
    }
}
