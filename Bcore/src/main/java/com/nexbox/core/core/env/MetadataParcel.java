package com.nexbox.core.core.env;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AtomicFile;

import com.nexbox.core.utils.compat.ParcelCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashMap;

/**
 * Compatibility for our on-disk metadata only, never an IPC class-loader alias.
 * Callers supply the exact Creator for each known field. Application Bundles, strings,
 * framework Parcelables and guest files continue to use their original readers/data.
 */
public final class MetadataParcel implements AutoCloseable {
    private static final ThreadLocal<MetadataParcel> ACTIVE = new ThreadLocal<>();
    private static final String NAMESPACE = BEnvironment.class.getName()
            .substring(0, BEnvironment.class.getName().length() - ".core.env.BEnvironment".length());
    // Stable tags used by Parcel.writeMap/writeValue. Android 13 added a byte-length
    // frame around a Parcelable value; both encodings are recognized below.
    private static final int VALUE_NULL = -1;
    private static final int VALUE_STRING = 0;
    private static final int VALUE_INTEGER = 1;
    private static final int VALUE_PARCELABLE = 4;

    private final Parcel parcel;
    private final MetadataParcel previous;
    private String historicalNamespace;
    private boolean historicalNames;

    private MetadataParcel(Parcel parcel) {
        this.parcel = parcel;
        this.previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    /** Only the four known metadata formats may opt into historical type names. */
    public static MetadataParcel open(File file, Parcel parcel) throws IOException {
        requireMetadataFile(file, false);
        return new MetadataParcel(parcel);
    }

    public static byte[] readBytes(File file) throws IOException {
        requireMetadataFile(file, true);
        return new AtomicFile(file).readFully();
    }

    private static void requireMetadataFile(File file, boolean allowPrimitiveRegistry) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!canonical.equals(file.getAbsoluteFile())) {
            throw new IOException("Container metadata path contains a link or traversal");
        }
        boolean known = canonical.equals(BEnvironment.getAccountsConf().getCanonicalFile())
                || canonical.equals(BEnvironment.getFakeLocationConf().getCanonicalFile())
                || canonical.equals(BEnvironment.getSharedUserConf().getCanonicalFile());
        if (allowPrimitiveRegistry) {
            known |= canonical.equals(BEnvironment.getUidConf().getCanonicalFile())
                    || canonical.equals(BEnvironment.getUserInfoConf().getCanonicalFile());
        }
        if (!known && "package.conf".equals(canonical.getName())) {
            File app = canonical.getParentFile();
            known = app != null && app.getParentFile() != null
                    && app.getParentFile().equals(BEnvironment.getAppRootDir().getCanonicalFile());
        }
        if (!known) throw new IOException("Not a supported container metadata file");
    }

    @SafeVarargs
    public static <T extends Parcelable> T read(Parcel source, Class<T> type,
                                               Parcelable.Creator<T> creator,
                                               Class<? extends T>... compatibleTypes) {
        MetadataParcel scope = ACTIVE.get();
        if (scope == null || scope.parcel != source) {
            // Binder input still follows Android's normal class resolution and checks.
            return ParcelCompat.readParcelable(source, type.getClassLoader(), type);
        }
        String name = source.readString();
        if (name == null) return null;
        scope.acceptType(name, type, compatibleTypes);
        return creator.createFromParcel(source);
    }

    public static Integer readIntegerValue(Parcel source) {
        MetadataParcel scope = ACTIVE.get();
        if (scope == null || scope.parcel != source) {
            return (Integer) source.readValue(Integer.class.getClassLoader());
        }
        if (source.readInt() != VALUE_INTEGER) throw new BadParcelableException("Invalid metadata integer value");
        return source.readInt();
    }

    /** Read only a map with a known primitive key and one fixed Parcelable value type. */
    public <K, T extends Parcelable> HashMap<K, T> readMap(Class<K> keyType, Class<T> valueType,
                                                         Parcelable.Creator<T> creator) {
        if (keyType != String.class && keyType != Integer.class) {
            throw new BadParcelableException("Unsupported metadata map key");
        }
        int size = parcel.readInt();
        if (size < 0 || size > 100000 || size > parcel.dataAvail() / 8) {
            throw new BadParcelableException("Invalid metadata map size");
        }
        HashMap<K, T> result = new HashMap<>();
        for (int i = 0; i < size; i++) {
            int keyTag = parcel.readInt();
            Object key;
            if (keyType == String.class && keyTag == VALUE_STRING) key = parcel.readString();
            else if (keyType == Integer.class && keyTag == VALUE_INTEGER) key = parcel.readInt();
            else throw new BadParcelableException("Unexpected metadata map key type");
            if (key == null || result.containsKey(key)) {
                throw new BadParcelableException("Null or duplicate metadata map key");
            }
            T value = readMapValue(valueType, creator);
            if (value == null) throw new BadParcelableException("Null metadata map value");
            result.put(keyType.cast(key), value);
        }
        return result;
    }

    private <T extends Parcelable> T readMapValue(Class<T> type, Parcelable.Creator<T> creator) {
        int valueTag = parcel.readInt();
        if (valueTag == VALUE_NULL) return null;
        if (valueTag != VALUE_PARCELABLE) {
            throw new BadParcelableException("Unexpected metadata map value type");
        }
        int start = parcel.dataPosition();
        int end = -1;
        // Inspect a type token, not arbitrary bytes. Trying the old shape does not
        // load any class or mutate the namespace until the token has been validated.
        if (!hasTypeToken(type)) {
            parcel.setDataPosition(start);
            int length = parcel.readInt();
            if (length < 4 || (length & 3) != 0 || length > parcel.dataAvail()) {
                throw new BadParcelableException("Invalid metadata Parcelable frame");
            }
            end = parcel.dataPosition() + length;
            if (!hasTypeToken(type)) throw new BadParcelableException("Unknown metadata Parcelable type");
        }
        T result = read(parcel, type, creator);
        if (end >= 0 && parcel.dataPosition() != end) {
            throw new BadParcelableException("Metadata Parcelable frame length mismatch");
        }
        return result;
    }

    private boolean hasTypeToken(Class<?> type) {
        int position = parcel.dataPosition();
        try {
            String name = parcel.readString();
            return name != null && namespaceFor(name, type) != null;
        } catch (RuntimeException malformed) {
            return false;
        } finally {
            parcel.setDataPosition(position);
        }
    }

    private void acceptType(String name, Class<?> type, Class<?>[] compatibleTypes) {
        String namespace = namespaceFor(name, type);
        if (namespace == null) {
            for (Class<?> compatible : compatibleTypes) {
                namespace = namespaceFor(name, compatible);
                if (namespace != null) break;
            }
        }
        if (namespace == null) throw new BadParcelableException("Unexpected container metadata type");
        if (!NAMESPACE.equals(namespace)) {
            if (historicalNamespace != null && !historicalNamespace.equals(namespace)) {
                throw new BadParcelableException("Mixed historical metadata namespaces");
            }
            historicalNamespace = namespace;
            historicalNames = true;
        }
    }

    private String namespaceFor(String name, Class<?> type) {
        String currentName = type.getName();
        if (!currentName.startsWith(NAMESPACE + ".")) return null;
        String suffix = currentName.substring(NAMESPACE.length());
        if (!name.endsWith(suffix)) return null;
        String namespace = name.substring(0, name.length() - suffix.length());
        if (namespace.length() > 240 || !namespace.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            return null;
        }
        if (historicalNamespace != null && !NAMESPACE.equals(namespace)
                && !historicalNamespace.equals(namespace)) return null;
        return namespace;
    }

    /** Keep one immutable pre-migration copy before any normal writer updates the file. */
    public void finish(File file, byte[] originalBytes) throws IOException {
        if (parcel.dataAvail() != 0) throw new IOException("Unconsumed container metadata payload");
        if (historicalNames) preserveOriginal(file, originalBytes);
    }

    public static void preserveOriginal(File file, byte[] originalBytes) throws IOException {
        requireMetadataFile(file, true);
        File backup = new File(file.getParentFile(), file.getName() + ".migration-original");
        if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid metadata recovery copy");
            }
            VirtualStorageLayout.syncDirectory(file.getParentFile());
            return;
        }
        File pending = File.createTempFile(".metadata-", ".pending", file.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(originalBytes);
                output.getFD().sync();
            }
            // Metadata readers/writers are serialized by their service's existing lock.
            // Never replace an already-created recovery copy.
            Files.move(pending.toPath(), backup.toPath());
            VirtualStorageLayout.syncDirectory(file.getParentFile());
        } finally {
            Files.deleteIfExists(pending.toPath());
        }
    }

    @Override
    public void close() {
        if (ACTIVE.get() != this) throw new IllegalStateException("Metadata reader scope mismatch");
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }
}
