package com.nexbox.core.fake.service;

/** Exact framework bind layouts and flag transformations; no Android runtime is needed here. */
public final class ServiceBindingPolicy {
    private static final long EXTERNAL_SERVICE = 0x80000000L;
    // Context.BIND_EXTERNAL_SERVICE_LONG, Android 14+. Keep the unsigned legacy bit distinct.
    private static final long EXTERNAL_SERVICE_LONG = 1L << 62;

    private ServiceBindingPolicy() { }

    public static final class Layout {
        public final int callingPackage;
        public final int userId;
        public final int instanceName;
        public final boolean longFlags;

        private Layout(int callingPackage, int userId, int instanceName, boolean longFlags) {
            this.callingPackage = callingPackage;
            this.userId = userId;
            this.instanceName = instanceName;
            this.longFlags = longFlags;
        }
    }

    public static boolean isBindMethod(String method) {
        return "bindService".equals(method) || "bindServiceInstance".equals(method)
                || "bindIsolatedService".equals(method);
    }

    public static Layout layout(String method, Class<?>[] types) {
        if (types == null) return null;
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = types[i].getName();
        return layout(method, names);
    }

    public static Layout layout(String method, String[] types) {
        if (!isBindMethod(method) || types == null) return null;
        boolean named = !"bindService".equals(method);
        int count = named ? 9 : 8;
        if (types.length != count || !"android.app.IApplicationThread".equals(types[0])
                || !"android.os.IBinder".equals(types[1]) || !"android.content.Intent".equals(types[2])
                || !"java.lang.String".equals(types[3]) || !"android.app.IServiceConnection".equals(types[4])
                || (!"int".equals(types[5]) && !"long".equals(types[5]))
                || !"java.lang.String".equals(types[named ? 7 : 6])
                || !"int".equals(types[count - 1])
                || (named && !"java.lang.String".equals(types[6]))) return null;
        return new Layout(named ? 7 : 6, count - 1, named ? 6 : -1, "long".equals(types[5]));
    }

    public static long readFlags(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return Integer.toUnsignedLong((Integer) value);
        throw new IllegalArgumentException("Unknown service-binding flags type");
    }

    public static Number flagsArgument(long value, boolean longFlags) {
        if (longFlags) return Long.valueOf(value);
        return Integer.valueOf((int) value);
    }

    public static boolean isExternal(long flags) {
        return (flags & (EXTERNAL_SERVICE | EXTERNAL_SERVICE_LONG)) != 0;
    }

    public static long flagsForOrdinaryProxy(long flags) {
        return flags & ~(EXTERNAL_SERVICE | EXTERNAL_SERVICE_LONG);
    }

    public static String instanceForRoute(String supplied, boolean ordinaryProxy) {
        return ordinaryProxy ? null : supplied;
    }

    public static boolean isOrdinaryProxy(String packageName, String className, String hostPackage, int count) {
        if (hostPackage == null || !hostPackage.equals(packageName) || className == null) return false;
        String prefix = "com.nexbox.core.proxy.ProxyService$P";
        if (!className.startsWith(prefix)) return false;
        String suffix = className.substring(prefix.length());
        try {
            int index = Integer.parseInt(suffix);
            return index >= 0 && index < count && suffix.equals(String.valueOf(index));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
