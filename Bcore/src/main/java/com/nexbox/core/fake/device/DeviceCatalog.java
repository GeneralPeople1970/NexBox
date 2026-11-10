package com.nexbox.core.fake.device;

import android.content.Context;
import android.os.Build;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Device candidates come only from the bundled catalogue; saved legacy profiles remain readable. */
public final class DeviceCatalog {
    private static volatile DeviceCatalog instance;
    public final List<Entry> entries;
    private final SecureRandom random = new SecureRandom();

    private DeviceCatalog(Context context) throws IOException {
        List<Entry> loaded = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        // Stream the source to avoid creating one enormous JSONArray on the UI/guest thread.
        try (JsonReader reader = new JsonReader(new InputStreamReader(context.getAssets().open(
                "device-catalog/android-devices-catalog.json"), StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (reader.hasNext()) {
                Map<String, String> fields = new HashMap<>();
                Map<String, List<String>> arrays = new HashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue; }
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        List<String> values = new ArrayList<>();
                        reader.beginArray();
                        while (reader.hasNext()) values.add(reader.nextString());
                        reader.endArray();
                        arrays.put(name, values);
                    } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.skipValue();
                    } else {
                        fields.put(name, reader.nextString());
                    }
                }
                reader.endObject();
                Entry entry = new Entry(fields, arrays);
                if (seenIds.add(entry.id)) loaded.add(entry);
            }
            reader.endArray();
        }
        if (loaded.isEmpty()) throw new IOException("Device catalogue is empty");
        loaded.sort(Comparator.comparing((Entry entry) -> entry.manufacturer.toLowerCase(Locale.ROOT))
                .thenComparing(entry -> entry.modelName.toLowerCase(Locale.ROOT)).thenComparing(entry -> entry.id));
        entries = Collections.unmodifiableList(loaded);
    }

    /** Call on a worker thread: the first load parses the bundled catalogue. */
    public static DeviceCatalog get(Context context) {
        DeviceCatalog current = instance;
        if (current == null) synchronized (DeviceCatalog.class) {
            current = instance;
            if (current == null) {
                try { instance = current = new DeviceCatalog(context.getApplicationContext()); }
                catch (IOException e) { throw new IllegalStateException("Could not load device catalogue", e); }
            }
        }
        return current;
    }

    public List<Entry> search(String query, boolean compatibleOnly) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (!"Phone".equals(entry.formFactor)
                    || (compatibleOnly && !entry.matchesHostAbi())) continue;
            if (needle.isEmpty() || entry.searchText.contains(needle)) result.add(entry);
        }
        return result;
    }

    public Entry randomEntry() {
        return randomEntry(null, null);
    }

    /** Prefer a different build tuple so an explicit randomization has a visible result. */
    public Entry randomEntry(String currentManufacturer, String currentModel) {
        List<Entry> pool = search("", true);
        if (pool.isEmpty()) throw new IllegalStateException("No compatible device candidate is available");
        List<Entry> alternatives = new ArrayList<>();
        for (Entry entry : pool) {
            if (!entry.manufacturer.equals(currentManufacturer) || !entry.modelName.equals(currentModel)) alternatives.add(entry);
        }
        if (!alternatives.isEmpty()) pool = alternatives;
        return pool.get(random.nextInt(pool.size()));
    }

    private static String key(String manufacturer, String brand, String device) {
        return (manufacturer.trim() + "|" + brand.trim() + "|" + device.trim()).toLowerCase(Locale.ROOT);
    }
    private static String value(Map<String, String> fields, String key) { return fields.containsKey(key) ? fields.get(key) : ""; }

    public static final class Entry {
        public final String id, manufacturer, brand, modelName, device, formFactor, ram, processorName, gpu;
        public final List<String> abis, sdkVersions, screenSizes, screenDensities, openGlEsVersions;
        private final String searchText;
        Entry(Map<String, String> fields, Map<String, List<String>> arrays) {
            manufacturer = value(fields, "manufacturer"); brand = value(fields, "brand"); modelName = value(fields, "modelName");
            device = value(fields, "device"); formFactor = value(fields, "formFactor"); ram = value(fields, "ram");
            processorName = value(fields, "processorName"); gpu = value(fields, "gpu");
            abis = array(arrays, "abis"); sdkVersions = array(arrays, "sdkVersions");
            screenSizes = array(arrays, "screenSizes"); screenDensities = array(arrays, "screenDensities"); openGlEsVersions = array(arrays, "openGlEsVersions");
            id = "catalog:" + DeviceIdentifierGenerator.stableOaid(key(manufacturer, brand, device) + "|" + modelName
                    + "|" + formFactor + "|" + processorName + "|" + ram + "|" + sdkVersions + "|" + abis);
            searchText = (manufacturer + " " + brand + " " + modelName + " " + device + " " + processorName).toLowerCase(Locale.ROOT);
        }
        public boolean matchesHostAbi() {
            if (abis.isEmpty()) return true; // Unknown architecture is not declared verified.
            for (String abi : Build.SUPPORTED_ABIS) if (abis.contains(abi)) return true;
            return false;
        }
        public boolean listsCurrentSdk() { return sdkVersions.contains(String.valueOf(Build.VERSION.SDK_INT)); }
        public String details() {
            return device + " · " + processorName + " · " + ram + "\n" + gpu
                    + "\nABI: " + abis + " · SDK: " + sdkVersions + "\n" + screenSizes + " · DPI: " + screenDensities;
        }
        private static List<String> array(Map<String, List<String>> arrays, String name) {
            List<String> value = arrays.get(name);
            return value == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(value));
        }
    }
}
