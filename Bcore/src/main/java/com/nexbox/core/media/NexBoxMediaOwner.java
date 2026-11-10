package com.nexbox.core.media;

/** Immutable creation provenance; it does not partition or grant access to the shared library. */
public final class NexBoxMediaOwner {
    private final String packageName;
    private final int userId;
    private final long userGeneration;

    private NexBoxMediaOwner(String packageName, int userId, long userGeneration) {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("A confirmed creator package is required");
        }
        this.packageName = packageName;
        this.userId = userId;
        this.userGeneration = userGeneration;
    }

    public static NexBoxMediaOwner host(String packageName) {
        return new NexBoxMediaOwner(packageName, -1, 0L);
    }

    public static NexBoxMediaOwner guest(String packageName, int userId, long userGeneration) {
        if (userId < 0 || userGeneration <= 0L) {
            throw new IllegalArgumentException("A current guest user generation is required");
        }
        return new NexBoxMediaOwner(packageName, userId, userGeneration);
    }

    /** Old rows and incomplete provenance stay unknown; never infer an owner from a filename. */
    static NexBoxMediaOwner fromStored(String packageName, Integer userId, Long userGeneration) {
        if (packageName == null || packageName.isEmpty()) return null;
        if (userId == null && userGeneration == null) return host(packageName);
        if (userId == null || userGeneration == null || userId < 0 || userGeneration <= 0L) return null;
        return guest(packageName, userId, userGeneration);
    }

    public String getPackageName() { return packageName; }
    public int getUserId() { return userId; }
    public long getUserGeneration() { return userGeneration; }
    public boolean isGuest() { return userId >= 0; }
}
