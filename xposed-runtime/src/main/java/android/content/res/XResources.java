package android.content.res;

/** Type compatibility only. No resource callback is dispatched by the Java-only runtime. */
public final class XResources extends Resources {
    // The legacy Resources subtype requires the only constructor exposed in the public SDK.
    @SuppressWarnings("deprecation")
    public XResources() {
        super(Resources.getSystem().getAssets(), Resources.getSystem().getDisplayMetrics(), Resources.getSystem().getConfiguration());
        throw unsupported();
    }
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("NexBox does not implement Xposed resource hooks");
    }
    public void setReplacement(int id, Object replacement) { throw unsupported(); }
    public void setReplacement(String pkg, String type, String name, Object replacement) { throw unsupported(); }
    public static void setSystemWideReplacement(int id, Object replacement) { throw unsupported(); }
    public static void setSystemWideReplacement(String pkg, String type, String name, Object replacement) { throw unsupported(); }
}
