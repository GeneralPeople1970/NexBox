package com.nexbox.core.core;

import com.nexbox.core.utils.Slog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.text.TextUtils;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.nexbox.core.NexBoxCore;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.TrieTree;


@SuppressLint("SdCardPath")
public class IOCore {
    public static final String TAG = "IOCore";

    private static final IOCore sIOCore = new IOCore();
    private static final TrieTree mTrieTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new ConcurrentHashMap<>();
    private volatile Map<String, String> mHistoricalRoots = Collections.emptyMap();
    private volatile String mInternalRoot;
    private volatile String mExternalRoot;

    public static IOCore get() {
        return sIOCore;
    }

    
    public synchronized void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        
        mRedirectMap.put(origPath, redirectPath);
        mTrieTree.add(origPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            BzFileUtils.mkdirs(redirectPath);
        }
        NativeCore.addIORule(origPath, redirectPath);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        String relocated = relocateHistoricalPath(path);
        if (!path.equals(relocated)) return relocated;
        if (inside(path, mInternalRoot) || inside(path, mExternalRoot)) {
            return path;
        }
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = Objects.requireNonNull(mRedirectMap.get(key)) + path.substring(key.length());

        return path;
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr));
    }

    public void enableRedirect(Context context) {
        // OsStub can run while BEnvironment itself is initializing. Cache the finished
        // layout here rather than recursively asking BEnvironment from every IO call.
        mInternalRoot = BEnvironment.getVirtualRoot().getAbsolutePath();
        mExternalRoot = BEnvironment.getExternalVirtualRoot().getAbsolutePath();
        mHistoricalRoots = BEnvironment.getLegacyRootMappings();
        Map<String, String> rule = new LinkedHashMap<>();
        // Preserve guest-stored absolute paths after the physical root move. These
        // exact, journaled root aliases must precede broad emulated-storage rules.
        rule.putAll(mHistoricalRoots);
        String packageName = context.getPackageName();

        // Prepare and preserve legacy files before exposing the shared Download path.
        // Failure must not fall through to a partially configured storage mapping.
        com.nexbox.core.media.NexBoxMediaStore.prepareSharedStorage();

        try {
            ApplicationInfo packageInfo = NexBoxCore.getBPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA, NexBoxCore.getUserId());
            int systemUserId = NexBoxCore.getHostUserId();
            rule.put(String.format("/data/data/%s/lib", packageName), packageInfo.nativeLibraryDir);
            rule.put(String.format("/data/user/%d/%s/lib", systemUserId, packageName), packageInfo.nativeLibraryDir);

            rule.put(String.format("/data/data/%s", packageName), packageInfo.dataDir);
            rule.put(String.format("/data/user/%d/%s", systemUserId, packageName), packageInfo.dataDir);

            
            File profilesRoot = new File(BEnvironment.getVirtualRoot(), "profiles");
            BzFileUtils.mkdirs(profilesRoot.getAbsolutePath());
            
            rule.put("/data/misc/profiles", profilesRoot.getAbsolutePath());

            File profilesCurDir = new File(profilesRoot, String.format("cur/%d/%s", NexBoxCore.getUserId(), packageName));
            File profilesRefDir = new File(profilesRoot, String.format("ref/%d/%s", NexBoxCore.getUserId(), packageName));
            BzFileUtils.mkdirs(profilesCurDir.getAbsolutePath());
            BzFileUtils.mkdirs(profilesRefDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/cur/%d/%s", NexBoxCore.getUserId(), packageName), profilesCurDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/ref/%d/%s", NexBoxCore.getUserId(), packageName), profilesRefDir.getAbsolutePath());

            if (NexBoxCore.getContext().getExternalCacheDir() != null && context.getExternalCacheDir() != null) {
                File external = BEnvironment.getExternalUserDir(NexBoxCore.getUserId());
                File sharedMedia = com.nexbox.core.media.NexBoxMediaStore.getSharedMediaRoot();
                for (String alias : new String[]{"/sdcard", String.format("/storage/emulated/%d", systemUserId), "/storage/self/primary"}) {
                    for (String directory : new String[]{"DCIM", "Pictures", "Movies", "Download"}) {
                        String from = alias + "/" + directory;
                        String to = new File(sharedMedia, directory).getAbsolutePath();
                        rule.put(from, to);
                    }
                }
                rule.put("/sdcard", external.getAbsolutePath());
                rule.put(String.format("/storage/emulated/%d", systemUserId), external.getAbsolutePath());
                rule.put("/storage/self/primary", external.getAbsolutePath());
            }
            if (NexBoxCore.get().isHideRoot()) {
                hideRoot(rule);
            }
            proc(rule);
        } catch (Exception e) {
            Slog.e("IOCore", "Container operation failed", e);
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
        NativeCore.enableIO();
    }

    private static boolean inside(String path, String root) {
        return root != null && path.startsWith(root)
                && (path.length() == root.length() || path.charAt(root.length()) == File.separatorChar);
    }

    private String relocateHistoricalPath(String path) {
        for (Map.Entry<String, String> entry : mHistoricalRoots.entrySet()) {
            String oldRoot = entry.getKey();
            if (inside(path, oldRoot)) {
                return entry.getValue() + path.substring(oldRoot.length());
            }
        }
        return path;
    }

    private void hideRoot(Map<String, String> rule) {
        rule.put("/system/app/Superuser.apk", "/system/app/Superuser.apk-fake");
        rule.put("/sbin/su", "/sbin/su-fake");
        rule.put("/system/bin/su", "/system/bin/su-fake");
        rule.put("/system/xbin/su", "/system/xbin/su-fake");
        rule.put("/data/local/xbin/su", "/data/local/xbin/su-fake");
        rule.put("/data/local/bin/su", "/data/local/bin/su-fake");
        rule.put("/system/sd/xbin/su", "/system/sd/xbin/su-fake");
        rule.put("/system/bin/failsafe/su", "/system/bin/failsafe/su-fake");
        rule.put("/data/local/su", "/data/local/su-fake");
        rule.put("/su/bin/su", "/su/bin/su-fake");
    }

    private void proc(Map<String, String> rule) {
        int appPid = NexBoxCore.getAppPid();
        int pid = Process.myPid();
        String selfProc = "/proc/self/";
        String proc = "/proc/" + pid + "/";

        String cmdline = new File(BEnvironment.getProcDir(appPid), "cmdline").getAbsolutePath();
        rule.put(proc + "cmdline", cmdline);
        rule.put(selfProc + "cmdline", cmdline);
    }
}
