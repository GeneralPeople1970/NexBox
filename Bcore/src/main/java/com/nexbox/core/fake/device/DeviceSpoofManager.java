package com.nexbox.core.fake.device;

import com.nexbox.core.utils.OperationLog;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.function.Function;
import com.nexbox.core.fake.frameworks.BUserManager;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.core.system.xposed.XposedModulePolicy;
import java.util.UUID;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.NativeCore;
import com.nexbox.core.utils.Md5Utils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.ConfigurationAppStopper;

public final class DeviceSpoofManager {
    private static final String TAG = "DeviceSpoofManager";
    private static final Object MUTATION_LOCK = new Object();
    private static final String PREFS_NAME = "DeviceSpoofProfiles";
    private static final String GLOBAL_PREFS_NAME = "DeviceSpoofRuntime";
    private static final String KEY_GLOBAL_ESIM_ENABLED = "esim_enabled";
    private static final String KEY_FRESH_GENERATION = "fresh_generation";

    private static final String KEY_MANUFACTURER = "manufacturer_";
    private static final String KEY_MODEL = "model_";
    private static final String KEY_BRAND = "brand_";
    private static final String KEY_DEVICE = "device_";
    private static final String KEY_PRODUCT = "product_";
    private static final String KEY_FINGERPRINT = "fingerprint_";
    private static final String KEY_SERIAL = "serial_";
    private static final String KEY_ANDROID_ID = "android_id_";
    private static final String KEY_IMEI = "imei_";
    private static final String KEY_MAC = "mac_";
    private static final String KEY_OAID = "oaid_";
    private static final String KEY_ESIM_ENABLED = "esim_enabled_";
    private static final String KEY_CATALOG_ID = "catalog_id_";
    private static final String KEY_BUILD_MODE = "build_mode_";
    private static final String KEY_SIM_PROFILE = "sim_profile_";
    private static final String KEY_SCOPE = "device_scope_";

    private static final String APP_PREFIX = "app_";
    private static final String[] PROFILE_KEYS = {KEY_MANUFACTURER, KEY_MODEL, KEY_BRAND, KEY_DEVICE,
            KEY_PRODUCT, KEY_FINGERPRINT, KEY_SERIAL, KEY_ANDROID_ID, KEY_IMEI, KEY_MAC, KEY_OAID,
            KEY_CATALOG_ID, KEY_BUILD_MODE, KEY_SIM_PROFILE};

    private static volatile AppliedProfile sAppliedProfile;

    private static final class AppliedProfile {
        final int userId;
        final String packageName;
        final DeviceSpoofProfile profile;

        AppliedProfile(int userId, String packageName, DeviceSpoofProfile profile) {
            this.userId = userId;
            this.packageName = packageName;
            this.profile = profile;
        }
    }

    private DeviceSpoofManager() {
    }

    private static long identity(int userId) {
        try {
            BUserInfo user = BUserManager.get().getService().getUserInfo(userId);
            return user == null ? 0 : user.createTime;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read profile identity", e);
        }
    }

    private static SharedPreferences generationPrefs(int userId, long created) {
        return NexBoxCore.getContext().getSharedPreferences(
                PREFS_NAME + "_" + userId + "_" + created, Context.MODE_PRIVATE);
    }

    private static SharedPreferences prefs(int userId) {
        return prefs(userId, identity(userId));
    }

    private static SharedPreferences prefs(int userId, long created) {
        SharedPreferences target = generationPrefs(userId, created);
        synchronized (DeviceSpoofManager.class) {
            if (!target.getBoolean("initialized", false)) {
                // One-time migration of an existing profile. New generations are initialized
                // by the user service before they become visible and never read legacy keys.
                SharedPreferences old = NexBoxCore.getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor edit = target.edit();
                for (Map.Entry<String, ?> entry : old.getAll().entrySet()) {
                    if (belongsToUser(entry.getKey(), userId) && entry.getValue() instanceof String) {
                        edit.putString(entry.getKey(), (String) entry.getValue());
                    } else if ((KEY_ESIM_ENABLED + userId).equals(entry.getKey()) && entry.getValue() instanceof Boolean) {
                        // Preserve the opt-in as migration evidence. Device profile edits no
                        // longer mutate it, but opening this page must not erase a global opt-in.
                        edit.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                    }
                }
                if (!edit.putBoolean("initialized", true).commit()) throw new IllegalStateException("Could not migrate device profile");
            }
        }
        return target;
    }

    static boolean belongsToUser(String key, int userId) {
        if (key == null) return false;
        if (!key.startsWith(APP_PREFIX)) return key.endsWith("_" + userId);
        // A package name can itself contain "_0_". Match the owner immediately
        // before the known field suffix, never an arbitrary substring in the package.
        for (String field : PROFILE_KEYS) {
            if (key.endsWith("_" + userId + "_" + field)) return true;
        }
        return false;
    }

    public static void initializeUser(int userId, long created) {
        OperationLog.call(TAG, "initializeProfile", "userId=" + userId + " generation=" + created, () -> {
            if (!generationPrefs(userId, created).edit().clear().putBoolean("initialized", true)
                    .putBoolean(KEY_FRESH_GENERATION, true).commit()) {
                throw new IllegalStateException("Could not initialize device profile");
            }
            return null;
        });
    }

    public static void removeUser(int userId) {
        removeUser(userId, 0L);
    }

    /** The caller supplies its local user snapshot; no Binder reads while holding lifecycle locks. */
    public static void removeUser(int userId, long currentGeneration) {
        OperationLog.call(TAG, "removeProfile", "userId=" + userId, () -> {
            Context context = NexBoxCore.getContext();
            SharedPreferences old = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            promoteLegacyEsimBeforeRemoval(userId, currentGeneration, old);
            SharedPreferences.Editor edit = old.edit();
            for (String key : old.getAll().keySet()) if (belongsToUser(key, userId)) edit.remove(key);
            if (!edit.commit()) throw new IllegalStateException("Could not clear legacy device profile");
            File preferencesDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = preferencesDir.listFiles();
            if (files == null && preferencesDir.exists()) {
                throw new IllegalStateException("Could not enumerate device profiles");
            }
            java.util.Set<String> names = new java.util.HashSet<>();
            if (files != null) for (File file : files) {
                String name = file.getName();
                if (!name.startsWith(PREFS_NAME + "_" + userId + "_")) continue;
                if (name.endsWith(".xml.bak")) names.add(name.substring(0, name.length() - 8));
                else if (name.endsWith(".xml")) names.add(name.substring(0, name.length() - 4));
            }
            for (String name : names) {
                if (!context.deleteSharedPreferences(name)) {
                    throw new IllegalStateException("Could not remove device profile " + name);
                }
            }
            return null;
        });
    }

    public static DeviceSpoofProfile getProfile(int userId) {
        return getProfile(userId, identity(userId));
    }

    public static DeviceSpoofProfile getProfile(int userId, long created) {
        validateUserGeneration(userId, created);
        DeviceSpoofProfile result = readUserProfile(userId, created);
        validateUserGeneration(userId, created);
        return result;
    }

    private static DeviceSpoofProfile readUserProfile(int userId, long created) {
        DeviceSpoofProfile defaults = getDefaultProfile(userId, created);
        SharedPreferences prefs = prefs(userId, created);
        return new DeviceSpoofProfile(
                valueOrDefault(prefs.getString(KEY_MANUFACTURER + userId, null), defaults.manufacturer),
                valueOrDefault(prefs.getString(KEY_BRAND + userId, null), defaults.brand),
                valueOrDefault(prefs.getString(KEY_MODEL + userId, null), defaults.model),
                valueOrDefault(prefs.getString(KEY_DEVICE + userId, null), defaults.device),
                prefs.getString(KEY_PRODUCT + userId, defaults.product),
                prefs.getString(KEY_FINGERPRINT + userId, defaults.fingerprint),
                valueOrDefault(prefs.getString(KEY_SERIAL + userId, null), defaults.serial),
                valueOrDefault(prefs.getString(KEY_ANDROID_ID + userId, null), defaults.androidId),
                valueOrDefault(prefs.getString(KEY_IMEI + userId, null), defaults.imei),
                valueOrDefault(prefs.getString(KEY_MAC + userId, null), defaults.macAddress),
                valueOrDefault(prefs.getString(KEY_OAID + userId, null), defaults.oaid),
                prefs.getString(KEY_CATALOG_ID + userId, ""),
                prefs.getString(KEY_BUILD_MODE + userId, prefs.contains(KEY_MANUFACTURER + userId)
                        ? DeviceSpoofProfile.MODE_LEGACY : defaults.buildMode),
                SimProfile.fromJson(prefs.getString(KEY_SIM_PROFILE + userId, null))
        );
    }

    public static DeviceSpoofProfile getAppProfile(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) {
            return getProfile(userId);
        }
        return getAppProfile(packageName, userId, identity(userId));
    }

    public static DeviceSpoofProfile getAppProfile(String packageName, int userId, long created) {
        validateAppProfileTarget(packageName, userId, created);
        DeviceSpoofProfile result = readAppProfile(packageName, userId, created);
        validateAppProfileTarget(packageName, userId, created);
        return result;
    }

    /** A stored override, not merely the inherited user profile. Call on IO. */
    public static boolean hasAppProfile(String packageName, int userId, long created) {
        validateAppProfileTarget(packageName, userId, created);
        boolean exists = prefs(userId, created).contains(APP_PREFIX + packageName + "_" + userId + "_" + KEY_MANUFACTURER);
        validateUserGeneration(userId, created);
        return exists;
    }

    private static DeviceSpoofProfile readAppProfile(String packageName, int userId, long created) {
        return readAppProfile(packageName, userId, prefs(userId, created), getProfile(userId, created));
    }

    private static DeviceSpoofProfile readAppProfile(String packageName, int userId,
                                                    SharedPreferences prefs, DeviceSpoofProfile defaults) {
        String keyPrefix = APP_PREFIX + packageName + "_" + userId + "_";
        if (!prefs.contains(keyPrefix + KEY_MANUFACTURER)) {
            return defaults;
        }
        return new DeviceSpoofProfile(
                valueOrDefault(prefs.getString(keyPrefix + KEY_MANUFACTURER, null), defaults.manufacturer),
                valueOrDefault(prefs.getString(keyPrefix + KEY_BRAND, null), defaults.brand),
                valueOrDefault(prefs.getString(keyPrefix + KEY_MODEL, null), defaults.model),
                valueOrDefault(prefs.getString(keyPrefix + KEY_DEVICE, null), defaults.device),
                prefs.getString(keyPrefix + KEY_PRODUCT, defaults.product),
                prefs.getString(keyPrefix + KEY_FINGERPRINT, defaults.fingerprint),
                valueOrDefault(prefs.getString(keyPrefix + KEY_SERIAL, null), defaults.serial),
                valueOrDefault(prefs.getString(keyPrefix + KEY_ANDROID_ID, null), defaults.androidId),
                prefs.getString(keyPrefix + KEY_IMEI, null),
                prefs.getString(keyPrefix + KEY_MAC, null),
                prefs.getString(keyPrefix + KEY_OAID, null),
                prefs.getString(keyPrefix + KEY_CATALOG_ID, ""),
                prefs.getString(keyPrefix + KEY_BUILD_MODE, DeviceSpoofProfile.MODE_LEGACY),
                SimProfile.fromJson(prefs.getString(keyPrefix + KEY_SIM_PROFILE, defaults.simProfile.toJson()))
        );
    }

    private static String stableImei(String seed) {
        String hash = Md5Utils.md5(seed);
        StringBuilder digits = new StringBuilder("86");
        for (int i = 0; i < 12; i++) digits.append(Character.digit(hash.charAt(i), 16) % 10);
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int value = digits.charAt(i) - '0';
            if (i % 2 == 1) value *= 2;
            sum += value / 10 + value % 10;
        }
        return digits.append((10 - sum % 10) % 10).toString();
    }

    public static DeviceSpoofProfile getDefaultProfile(int userId) {
        return getDefaultProfile(userId, identity(userId));
    }

    private static DeviceSpoofProfile getDefaultProfile(int userId, long created) {
        HostDeviceInfo host = HostDeviceInfo.get();
        return new DeviceSpoofProfile(
                host.manufacturer,
                host.brand,
                host.model,
                host.device,
                host.product,
                host.fingerprint,
                stableSerial("serial_" + userId + "_" + created),
                stableAndroidId("nex_default_" + userId + "_" + created),
                stableImei("imei_" + userId + "_" + created),
                DeviceIdentifierGenerator.stableMac("mac_" + userId + "_" + created),
                UUID.nameUUIDFromBytes(("oaid_" + userId + "_" + created).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                "", DeviceSpoofProfile.MODE_HOST, SimProfile.disabled()
        );
    }

    /** First use loads the catalogue; call from a worker thread. */
    public static DeviceSpoofProfile generateRandomProfile() {
        HostDeviceInfo host = HostDeviceInfo.get();
        DeviceSpoofProfile base = new DeviceSpoofProfile(host.manufacturer, host.brand, host.model, host.device,
                host.product, host.fingerprint, generateRandomSerial(), generateRandomAndroidId(),
                generateRandomImei(), generateRandomMac(), generateRandomOaid());
        return randomizeModel(base);
    }

    public static DeviceSpoofProfile randomizeModel(DeviceSpoofProfile base) {
        return selectCatalogEntry(base, DeviceCatalog.get(NexBoxCore.getContext()).randomEntry(base.manufacturer, base.model));
    }

    public static DeviceSpoofProfile selectCatalogEntry(DeviceSpoofProfile base, DeviceCatalog.Entry entry) {
        return new DeviceSpoofProfile(entry.manufacturer, entry.brand, entry.modelName,
                entry.device, "", "", base.serial, base.androidId, base.imei, base.macAddress,
                base.oaid, entry.id, DeviceSpoofProfile.MODE_BASIC,
                base.simProfile);
    }

    public static DeviceSpoofProfile randomizeIdentifiers(DeviceSpoofProfile base) {
        return new DeviceSpoofProfile(base.manufacturer, base.brand, base.model, base.device, base.product,
                base.fingerprint, generateRandomSerial(), generateRandomAndroidId(), generateRandomImei(),
                generateRandomMac(), generateRandomOaid(), base.catalogId, base.buildMode, base.simProfile);
    }

    /** Complete device draft; preserves SIM and does not persist or change the global eSIM switch. */
    public static DeviceSpoofProfile randomizeAll(DeviceSpoofProfile base) {
        DeviceCatalog.Entry entry = DeviceCatalog.get(NexBoxCore.getContext())
                .randomEntry(base.manufacturer, base.model);
        return randomizeIdentifiers(selectCatalogEntry(base, entry));
    }

    public static void saveUserProfile(int userId, String manufacturer, String model, String androidId) {
        OperationLog.call(TAG, "saveUserProfile", "userId=" + userId, () -> {
            long created = identity(userId);
            DeviceSpoofProfile profile = editProfile(userId, getProfile(userId, created), manufacturer, model, androidId);
            saveUserProfile(userId, created, profile);
            return null;
        });
    }

    /** The editor's captured generation is used for both validation and the actual write. */
    public static void saveUserProfile(int userId, long created, DeviceSpoofProfile profile) {
        OperationLog.call(TAG, "saveUserProfile", "userId=" + userId + " generation=" + created, () -> {
            synchronized (MUTATION_LOCK) {
                validateUserGeneration(userId, created);
                Set<String> affected = changedUserTargets(userId, created, profile);
                SharedPreferences.Editor edit = profileEditor("", userId, created, profile);
                validateUserGeneration(userId, created);
                if (!edit.commit()) throw new IllegalStateException("Could not save device profile");
                ConfigurationAppStopper.stop(userId, created, affected);
                return null;
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences globalPrefs() {
        // Host UI writes this preference. A guest may be a reused process, so reload its disk view.
        return NexBoxCore.getContext().getSharedPreferences(GLOBAL_PREFS_NAME,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    /** Independent of device profiles, their scopes, user generations and reset operations. */
    public static boolean isGlobalEsimSpoofEnabled() {
        SharedPreferences settings = globalPrefs();
        if (settings.contains(KEY_GLOBAL_ESIM_ENABLED)) {
            return settings.getBoolean(KEY_GLOBAL_ESIM_ENABLED, false);
        }
        // Preserve existing opt-ins until the host performs the one-time migration. A failed
        // user-service read must never persist a false value over an unknown legacy state.
        final List<BUserInfo> users;
        try {
            users = BUserManager.get().getService().getUsers();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not migrate eSIM setting", failure);
        }
        if (users == null) throw new IllegalStateException("User list unavailable for eSIM migration");
        SharedPreferences legacy = NexBoxCore.getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Older builds allowed editing user zero before the first clone existed. Only consider
        // that pending identity while user zero is actually absent, not for a later generation.
        boolean hasUserZero = false;
        for (BUserInfo user : users) if (user != null && user.id == 0) hasUserZero = true;
        boolean enabled = !hasUserZero && legacyEsimOptIn(generationPrefs(0, 0), legacy, 0);
        for (BUserInfo user : users) {
            if (user == null || user.createTime <= 0) {
                throw new IllegalStateException("Incomplete user list for eSIM migration");
            }
            validateUserGeneration(user.id, user.createTime);
            SharedPreferences profile = generationPrefs(user.id, user.createTime);
            enabled |= legacyEsimOptIn(profile, legacy, user.id);
            validateUserGeneration(user.id, user.createTime);
        }
        if (!hasUserZero && identity(0) != 0) throw new IllegalStateException("User changed during eSIM migration");
        return enabled;
    }

    private static boolean legacyEsimOptIn(SharedPreferences profile, SharedPreferences legacy, int userId) {
        String key = KEY_ESIM_ENABLED + userId;
        if (profile.contains(key)) return profile.getBoolean(key, false);
        // Earlier one-time migrations set initialized without copying booleans. That marker
        // therefore cannot prove that eSIM was off. A freshly created generation can: the user
        // service first removes old preferences and then records KEY_FRESH_GENERATION.
        return !profile.getBoolean(KEY_FRESH_GENERATION, false) && legacy.getBoolean(key, false);
    }

    /** Call from the host settings page on IO; guest reads never write migration state. */
    public static boolean initializeGlobalEsimSpoofSetting() {
        // Any Binder-backed legacy scan happens outside the file lock. The user-service cleanup
        // path takes the same lock without calling Binder, so it cannot wait on a caller of itself.
        boolean enabled = isGlobalEsimSpoofEnabled();
        return withGlobalSettingsLock(settings -> {
            if (!settings.contains(KEY_GLOBAL_ESIM_ENABLED)) writeGlobalEsim(settings, enabled);
            return settings.getBoolean(KEY_GLOBAL_ESIM_ENABLED, false);
        });
    }

    public static void setGlobalEsimSpoofEnabled(boolean enabled) {
        synchronized (MUTATION_LOCK) {
            boolean changed = isGlobalEsimSpoofEnabled() != enabled;
            // eSIM is a global capability, independent of the device-profile scope switch.
            List<ConfigurationAppStopper.Target> affected = changed
                    ? ConfigurationAppStopper.captureAllVirtualApps() : Collections.emptyList();
            withGlobalSettingsLock(settings -> {
                writeGlobalEsim(settings, enabled);
                return null;
            });
            ConfigurationAppStopper.stop(affected);
        }
    }

    private static void writeGlobalEsim(SharedPreferences settings, boolean enabled) {
        if (!settings.edit().putBoolean(KEY_GLOBAL_ESIM_ENABLED, enabled).commit()) {
            throw new IllegalStateException("Could not save global eSIM setting");
        }
        Slog.i(TAG, "action=saveGlobalEsim enabled=" + enabled);
    }

    private static void promoteLegacyEsimBeforeRemoval(int userId, long currentGeneration, SharedPreferences legacy) {
        if (userId < 0 || currentGeneration < 0) return;
        // An absent nonzero ID has no user-owned legacy opt-in to promote. User zero also had
        // a real pre-clone editing state, represented by its generation-zero profile.
        if (currentGeneration == 0 && userId != 0) return;
        SharedPreferences profile = generationPrefs(userId, currentGeneration);
        if (!legacyEsimOptIn(profile, legacy, userId)) return;
        withGlobalSettingsLock(settings -> {
            // Promote only a known opt-in, never guess that an incomplete scan means off. An
            // explicit global choice, including false, always wins over deletion-time migration.
            if (!settings.contains(KEY_GLOBAL_ESIM_ENABLED)) writeGlobalEsim(settings, true);
            return null;
        });
    }

    private static <T> T withGlobalSettingsLock(Function<SharedPreferences, T> operation) {
        synchronized (DeviceSpoofManager.class) {
            File file = new File(NexBoxCore.getContext().getFilesDir(), "device-runtime-setting.lock");
            try (RandomAccessFile handle = new RandomAccessFile(file, "rw");
                 FileChannel channel = handle.getChannel();
                 FileLock ignored = channel.lock()) {
                return operation.apply(globalPrefs());
            } catch (IOException failure) {
                throw new IllegalStateException("Could not lock global device settings", failure);
            }
        }
    }

    /** Null preserves the existing all-apps behavior; an empty set explicitly disables all targets. */
    public static Set<String> getScopePackages(int userId) {
        return getScopePackages(userId, identity(userId));
    }

    public static Set<String> getScopePackages(int userId, long created) {
        validateUserGeneration(userId, created);
        Set<String> packages = prefs(userId, created).getStringSet(KEY_SCOPE + userId, null);
        validateUserGeneration(userId, created);
        return packages == null ? null : Collections.unmodifiableSet(new HashSet<>(packages));
    }

    /** Scope shares the profile's generation-specific storage and deletion lifecycle. Call on IO. */
    public static void saveScopePackages(int userId, long expectedCreated, Set<String> packages) {
        synchronized (MUTATION_LOCK) {
            if (packages == null) throw new IllegalArgumentException("A scope selection is required");
            validateUserGeneration(userId, expectedCreated);
            Set<String> available = eligiblePackages(userId, expectedCreated);
            Set<String> accepted = new HashSet<>(packages);
            accepted.retainAll(available);
            Set<String> previous = prefs(userId, expectedCreated).getStringSet(KEY_SCOPE + userId, null);
            Set<String> affected = new HashSet<>();
            for (String packageName : available) {
                if ((previous == null || previous.contains(packageName)) != accepted.contains(packageName)) {
                    affected.add(packageName);
                }
            }
            validateUserGeneration(userId, expectedCreated);
            if (!prefs(userId, expectedCreated).edit().putStringSet(KEY_SCOPE + userId, accepted).commit()) {
                throw new IllegalStateException("Could not save device scope");
            }
            Slog.i(TAG, "action=saveScope userId=" + userId + " generation=" + expectedCreated
                    + " targets=" + accepted.size());
            ConfigurationAppStopper.stop(userId, expectedCreated, affected);
        }
    }

    /** Enforced below the host UI so a stale selection cannot target an Xposed module. */
    public static boolean isEligibleScopeTarget(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName) || packageName.equals(NexBoxCore.getHostPkg())
                || packageName.equals("com.nexbox.gallery")) return false;
        IBPackageManagerService service = NexBoxCore.getBPackageManager().getService();
        if (service == null) throw new IllegalStateException("Package service unavailable");
        final ApplicationInfo info;
        try {
            info = service.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId);
        } catch (RemoteException failure) {
            throw new IllegalStateException("Could not read device scope app", failure);
        }
        return info != null && !XposedModulePolicy.isCandidate(info);
    }

    public static boolean isSpoofEnabledForApp(String packageName, int userId) {
        if (!isEligibleScopeTarget(packageName, userId)) return false;
        Set<String> packages = getScopePackages(userId);
        return packages == null || packages.contains(packageName);
    }

    public static boolean isEsimSpoofEnabledForApp(String packageName, int userId) {
        return isGlobalEsimSpoofEnabled() && isEligibleScopeTarget(packageName, userId);
    }

    private static void validateUserGeneration(int userId, long created) {
        if (userId < 0 || created < 0 || identity(userId) != created) {
            throw new IllegalStateException("User changed while editing device profile");
        }
    }

    private static void validateAppProfileTarget(String packageName, int userId, long created) {
        validateUserGeneration(userId, created);
        if (created == 0 || !isEligibleScopeTarget(packageName, userId)) {
            throw new IllegalStateException("App is no longer an eligible device profile target");
        }
        validateUserGeneration(userId, created);
    }

    /** Model edits change build fields, not the instance IDs; only explicit randomization rotates IDs. */
    public static DeviceSpoofProfile editProfile(int userId, DeviceSpoofProfile base,
            String manufacturer, String model, String androidId) {
        DeviceSpoofProfile build = base;
        if (!base.manufacturer.equals(manufacturer.trim()) || !base.model.equals(model.trim())) {
            build = buildCustomProfile(userId, manufacturer, model, androidId, base);
        }
        return new DeviceSpoofProfile(build.manufacturer, build.brand, build.model, build.device,
                build.product, build.fingerprint, base.serial,
                normalizeAndroidId(androidId, "nex_custom_" + userId + "_" + base.device),
                base.imei, base.macAddress, base.oaid, build.catalogId, build.buildMode, base.simProfile);
    }

    public static void saveAppProfile(String packageName, int userId, DeviceSpoofProfile profile) {
        saveAppProfile(packageName, userId, identity(userId), profile);
    }

    public static void saveAppProfile(String packageName, int userId, long created, DeviceSpoofProfile profile) {
        OperationLog.call(TAG, "saveAppProfile", "userId=" + userId + " package=" + packageName, () -> {
            synchronized (MUTATION_LOCK) {
                validateAppProfileTarget(packageName, userId, created);
                Set<String> affected = changedAppTargets(packageName, userId, created, profile, false);
                SharedPreferences.Editor edit = profileEditor(packageName, userId, created, profile);
                validateAppProfileTarget(packageName, userId, created);
                if (!edit.commit()) throw new IllegalStateException("Could not save app profile");
                ConfigurationAppStopper.stop(userId, created, affected);
                return null;
            }
        });
    }

    public static DeviceSpoofProfile randomizeAppProfile(String packageName, int userId) {
        return randomizeAppProfile(packageName, userId, identity(userId));
    }

    public static DeviceSpoofProfile randomizeAppProfile(String packageName, int userId, long created) {
        return OperationLog.call(TAG, "randomizeAppProfile", "userId=" + userId + " package=" + packageName, () -> {
            synchronized (MUTATION_LOCK) {
                validateAppProfileTarget(packageName, userId, created);
                DeviceSpoofProfile randomProfile = randomizeAll(getAppProfile(packageName, userId, created));
                Set<String> affected = changedAppTargets(packageName, userId, created, randomProfile, true);
                SharedPreferences.Editor edit = profileEditor(packageName, userId, created, randomProfile);
                Set<String> packages = prefs(userId, created).getStringSet(KEY_SCOPE + userId, null);
                // The explicit per-app action opts this app in, without changing any other target.
                if (packages != null) {
                    Set<String> updated = new HashSet<>(packages);
                    updated.add(packageName);
                    edit.putStringSet(KEY_SCOPE + userId, updated);
                }
                validateAppProfileTarget(packageName, userId, created);
                if (!edit.commit()) throw new IllegalStateException("Could not randomize app profile");
                ConfigurationAppStopper.stop(userId, created, affected);
                return randomProfile;
            }
        });
    }

    private static SharedPreferences.Editor profileEditor(String packageName, int userId, long created, DeviceSpoofProfile profile) {
        if (profile == null) throw new IllegalArgumentException("Device profile is required");
        String simError = profile.simProfile.validationError();
        if (simError != null) throw new IllegalArgumentException(simError);
        String keyPrefix = TextUtils.isEmpty(packageName) ? "" : (APP_PREFIX + packageName + "_" + userId + "_");
        String suffix = TextUtils.isEmpty(packageName) ? String.valueOf(userId) : "";
        Slog.i(TAG, "action=writeProfile userId=" + userId + " generation=" + created + " package=" + packageName
                + " buildMode=" + profile.buildMode + " catalog=" + profile.catalogId
                + " simEnabled=" + profile.simProfile.enabled + " simCards=" + profile.simProfile.cards.size());
        return prefs(userId, created).edit()
                .putString(keyPrefix + KEY_MANUFACTURER + suffix, profile.manufacturer)
                .putString(keyPrefix + KEY_BRAND + suffix, profile.brand)
                .putString(keyPrefix + KEY_MODEL + suffix, profile.model)
                .putString(keyPrefix + KEY_DEVICE + suffix, profile.device)
                .putString(keyPrefix + KEY_PRODUCT + suffix, profile.product)
                .putString(keyPrefix + KEY_FINGERPRINT + suffix, profile.fingerprint)
                .putString(keyPrefix + KEY_SERIAL + suffix, profile.serial)
                .putString(keyPrefix + KEY_ANDROID_ID + suffix, profile.androidId)
                .putString(keyPrefix + KEY_IMEI + suffix, profile.imei)
                .putString(keyPrefix + KEY_MAC + suffix, profile.macAddress)
                .putString(keyPrefix + KEY_OAID + suffix, profile.oaid)
                .putString(keyPrefix + KEY_CATALOG_ID + suffix, profile.catalogId)
                .putString(keyPrefix + KEY_BUILD_MODE + suffix, profile.buildMode)
                .putString(keyPrefix + KEY_SIM_PROFILE + suffix, profile.simProfile.toJson());
    }

    public static void resetToDefaults(int userId) {
        resetToDefaults(userId, identity(userId));
    }

    public static void resetToDefaults(int userId, long created) {
        OperationLog.call(TAG, "resetProfile", "userId=" + userId + " generation=" + created, () -> {
            synchronized (MUTATION_LOCK) {
                validateUserGeneration(userId, created);
                Set<String> affected = changedUserTargets(userId, created, getDefaultProfile(userId, created));
                SharedPreferences.Editor edit = prefs(userId, created).edit();
                for (String key : PROFILE_KEYS) edit.remove(key + userId);
                validateUserGeneration(userId, created);
                if (!edit.commit()) throw new IllegalStateException("Could not reset device profile");
                ConfigurationAppStopper.stop(userId, created, affected);
                return null;
            }
        });
    }

    /** Remove a generation-scoped app override so the app inherits the group profile again. */
    public static void resetAppProfile(String packageName, int userId, long created) {
        OperationLog.call(TAG, "resetAppProfile", "userId=" + userId + " package=" + packageName, () -> {
            synchronized (MUTATION_LOCK) {
                validateAppProfileTarget(packageName, userId, created);
                Set<String> affected = changedAppTargets(packageName, userId, created, getProfile(userId, created), false);
                String prefix = APP_PREFIX + packageName + "_" + userId + "_";
                SharedPreferences.Editor edit = prefs(userId, created).edit();
                for (String key : PROFILE_KEYS) edit.remove(prefix + key);
                validateAppProfileTarget(packageName, userId, created);
                if (!edit.commit()) throw new IllegalStateException("Could not reset app device profile");
                ConfigurationAppStopper.stop(userId, created, affected);
                return null;
            }
        });
    }

    /** Resolve all impact before committing, so a read failure cannot masquerade as a failed save. */
    private static Set<String> changedUserTargets(int userId, long created, DeviceSpoofProfile next) {
        if (next == null) throw new IllegalArgumentException("Device profile is required");
        DeviceSpoofProfile previous = getProfile(userId, created);
        if (sameEffectiveProfile(previous, next)) return Collections.emptySet();
        SharedPreferences settings = prefs(userId, created);
        Set<String> scope = settings.getStringSet(KEY_SCOPE + userId, null);
        Set<String> affected = new HashSet<>();
        for (String packageName : eligiblePackages(userId, created)) {
            if (scope != null && !scope.contains(packageName)) continue;
            DeviceSpoofProfile before = readAppProfile(packageName, userId, settings, previous);
            DeviceSpoofProfile after = readAppProfile(packageName, userId, settings, next);
            if (!sameEffectiveProfile(before, after)) affected.add(packageName);
        }
        return affected;
    }

    private static Set<String> changedAppTargets(String packageName, int userId, long created,
                                                 DeviceSpoofProfile next, boolean enableScope) {
        if (next == null) throw new IllegalArgumentException("Device profile is required");
        Set<String> scope = prefs(userId, created).getStringSet(KEY_SCOPE + userId, null);
        boolean enabled = scope == null || scope.contains(packageName);
        if ((!enabled && enableScope) || (enabled && !sameEffectiveProfile(readAppProfile(packageName, userId, created), next))) {
            return Collections.singleton(packageName);
        }
        return Collections.emptySet();
    }

    private static boolean sameEffectiveProfile(DeviceSpoofProfile first, DeviceSpoofProfile second) {
        return TextUtils.equals(first.manufacturer, second.manufacturer)
                && TextUtils.equals(first.brand, second.brand) && TextUtils.equals(first.model, second.model)
                && TextUtils.equals(first.device, second.device)
                && TextUtils.equals(first.effectiveProduct(), second.effectiveProduct())
                && TextUtils.equals(first.effectiveFingerprint(), second.effectiveFingerprint())
                && TextUtils.equals(first.serial, second.serial) && TextUtils.equals(first.androidId, second.androidId)
                && TextUtils.equals(first.imei, second.imei) && TextUtils.equals(first.macAddress, second.macAddress)
                && TextUtils.equals(first.oaid, second.oaid)
                && first.simProfile.enabled == second.simProfile.enabled
                && (!first.simProfile.enabled || first.simProfile.toJson().equals(second.simProfile.toJson()));
    }

    private static Set<String> eligiblePackages(int userId, long created) {
        validateUserGeneration(userId, created);
        if (created == 0L) return Collections.emptySet();
        final List<ApplicationInfo> installed;
        try {
            IBPackageManagerService service = NexBoxCore.getBPackageManager().getService();
            if (service == null) throw new IllegalStateException("Package service unavailable");
            installed = service.getInstalledApplications(PackageManager.GET_META_DATA, userId);
        } catch (RemoteException failure) {
            throw new IllegalStateException("Could not enumerate device targets", failure);
        }
        if (installed == null) throw new IllegalStateException("Device target list unavailable");
        Set<String> eligible = new HashSet<>();
        for (ApplicationInfo app : installed) {
            if (app == null || TextUtils.isEmpty(app.packageName)) throw new IllegalStateException("Incomplete device target metadata");
            if (!app.packageName.equals(NexBoxCore.getHostPkg()) && !app.packageName.equals("com.nexbox.gallery")
                    && !XposedModulePolicy.isCandidate(app)) eligible.add(app.packageName);
        }
        validateUserGeneration(userId, created);
        return eligible;
    }

    public static void applyToCurrentProcess(int userId) {
        String appPackage = BActivityThread.getAppPackageName();
        try {
            if (!isSpoofEnabledForApp(appPackage, userId)) {
                // Keep all Build/native/identifier readers off for the lifetime of this guest.
                sAppliedProfile = new AppliedProfile(userId, appPackage, null);
                Slog.d(TAG, "Device scope excludes package " + appPackage + " (user " + userId + ")");
                return;
            }
            DeviceSpoofProfile profile = getAppProfile(appPackage, userId);

            // All identifier readers use the same launch snapshot as Build/native fields.
            // A saved replacement takes effect on the next guest process launch.
            sAppliedProfile = new AppliedProfile(userId, appPackage, profile);
            DeviceBuildSpoofer.apply(profile);
            NativeCore.setDeviceSpoof(
                    profile.manufacturer,
                    profile.brand,
                    profile.model,
                    profile.device,
                    profile.effectiveProduct(),
                    profile.effectiveFingerprint(),
                    profile.serial
            );
            SimPropertySpoofer.apply(profile.simProfile);
            Slog.d(TAG, "Applied device spoof for package " + appPackage + " (user " + userId + "): " + profile.manufacturer + " " + profile.model);
        } catch (Throwable e) {
            sAppliedProfile = new AppliedProfile(userId, appPackage, null);
            Slog.w(TAG, "Failed to apply device spoof for user " + userId + ": " + e.getMessage(), e);
        }
    }

    public static boolean hasCurrentGuest() {
        return !TextUtils.isEmpty(BActivityThread.getAppPackageName());
    }

    /** All identifier hooks consult the same launch snapshot as Build and native properties. */
    public static boolean isCurrentProcessEnabled() {
        String packageName = BActivityThread.getAppPackageName();
        if (TextUtils.isEmpty(packageName)) return false;
        int userId = BActivityThread.getUserId();
        AppliedProfile applied = sAppliedProfile;
        if (applied != null && applied.userId == userId && TextUtils.equals(applied.packageName, packageName)) {
            return applied.profile != null;
        }
        try {
            return isSpoofEnabledForApp(packageName, userId);
        } catch (RuntimeException failure) {
            // An optional spoof must not turn a transient service outage into a guest crash.
            Slog.w(TAG, "Could not resolve device scope for user " + userId, failure);
            return false;
        }
    }

    public static DeviceSpoofProfile getCurrentProfile() {
        String appPackage = BActivityThread.getAppPackageName();
        if (TextUtils.isEmpty(appPackage)) {
            throw new IllegalStateException("No guest identity is bound to this process");
        }
        int userId = BActivityThread.getUserId();
        AppliedProfile applied = sAppliedProfile;
        if (applied != null && applied.userId == userId && TextUtils.equals(applied.packageName, appPackage)) {
            if (applied.profile == null) throw new IllegalStateException("Device spoofing is outside the guest scope");
            return applied.profile;
        }
        return getAppProfile(appPackage, userId);
    }

    public static String getAndroidIdForCurrentUser() {
        return getCurrentProfile().androidId;
    }

    public static String getImeiForCurrentUser() {
        return getCurrentProfile().imei;
    }

    public static String getImeiForCurrentSlot(int slot) {
        if (slot < 0) return null;
        String primary = getImeiForCurrentUser();
        return slot == 0 ? primary : DeviceIdentifierGenerator.stableImei(primary + "/slot/" + slot);
    }

    public static String getMacForCurrentUser() {
        return getCurrentProfile().macAddress;
    }

    public static String getSerialForCurrentUser() {
        return getCurrentProfile().serial;
    }

    public static String getOaidForCurrentUser() {
        return getCurrentProfile().oaid;
    }

    public static boolean isValidAndroidId(String value) {
        return DeviceIdentifierGenerator.isValidAndroidId(value);
    }

    public static String generateRandomAndroidId() {
        return DeviceIdentifierGenerator.androidId();
    }

    public static String generateRandomImei() {
        return DeviceIdentifierGenerator.imei();
    }

    public static String generateRandomSerial() {
        return DeviceIdentifierGenerator.serial();
    }

    public static String generateRandomMac() {
        return DeviceIdentifierGenerator.macAddress();
    }

    public static String generateRandomOaid() {
        return DeviceIdentifierGenerator.oaid();
    }

    private static DeviceSpoofProfile buildCustomProfile(int userId, String manufacturer, String model,
            String androidId, DeviceSpoofProfile base) {
        String safeManufacturer = normalizeManufacturer(manufacturer);
        String safeBrand = normalizeBrand(safeManufacturer);
        String safeModel = normalizeModel(model);
        String safeDevice = slugify(safeModel);
        String safeAndroidId = normalizeAndroidId(androidId, "nex_custom_" + userId + "_" + safeDevice);
        return new DeviceSpoofProfile(
                safeManufacturer,
                safeBrand,
                safeModel,
                safeDevice,
                "",
                "",
                base.serial,
                safeAndroidId,
                base.imei,
                base.macAddress,
                base.oaid, "", DeviceSpoofProfile.MODE_BASIC, base.simProfile
        );
    }

    private static String normalizeManufacturer(String manufacturer) {
        if (TextUtils.isEmpty(manufacturer)) {
            return HostDeviceInfo.get().manufacturer;
        }
        String trimmed = manufacturer.trim();
        if (trimmed.length() == 1) {
            return trimmed.toUpperCase(Locale.US);
        }
        return trimmed.substring(0, 1).toUpperCase(Locale.US) + trimmed.substring(1);
    }

    private static String normalizeBrand(String manufacturer) {
        return slugify(manufacturer);
    }

    private static String normalizeModel(String model) {
        if (TextUtils.isEmpty(model)) {
            return HostDeviceInfo.get().model;
        }
        return model.trim();
    }

    private static String normalizeAndroidId(String androidId, String seed) {
        if (!TextUtils.isEmpty(androidId)) {
            String normalized = androidId.trim().toLowerCase(Locale.US);
            if (isValidAndroidId(normalized)) {
                return normalized;
            }
        }
        return stableAndroidId(seed);
    }

    private static String stableAndroidId(String seed) {
        String hash = Md5Utils.md5(seed);
        if (TextUtils.isEmpty(hash)) {
            return "0f0f0f0f0f0f0f0f";
        }
        String normalized = hash.toLowerCase(Locale.US);
        return normalized.length() >= 16 ? normalized.substring(0, 16) : String.format(Locale.US, "%1$-16s", normalized).replace(' ', '0');
    }

    private static String stableSerial(String seed) {
        String hash = Md5Utils.md5(seed);
        if (TextUtils.isEmpty(hash)) {
            return "NEX000000000";
        }
        String normalized = hash.toUpperCase(Locale.US);
        return normalized.length() >= 12 ? normalized.substring(0, 12) : String.format(Locale.US, "%1$-12s", normalized).replace(' ', '0');
    }

    private static String valueOrDefault(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static String slugify(String input) {
        if (TextUtils.isEmpty(input)) {
            return "device";
        }
        String normalized = input.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "");
        return TextUtils.isEmpty(normalized) ? "device" : normalized;
    }
}
