package com.nexbox.core.proxy;

import java.util.Locale;

import com.nexbox.core.NexBoxCore;


public class ProxyManifest {
    public static final int FREE_COUNT = 50;

    public static boolean isProxy(String msg) {
        return getBindProvider().equals(msg) || msg.contains("proxy_content_provider_");
    }

    public static String getBindProvider() {
        return NexBoxCore.getHostPkg() + ".nexbox.SystemCallProvider";
    }

    public static String getProxyAuthorities(int index) {
        return String.format(Locale.CHINA, "%s.proxy_content_provider_%d", NexBoxCore.getHostPkg(), index);
    }

    public static String getProxyPendingActivity(int index) {
        return String.format(Locale.CHINA, "com.nexbox.core.proxy.ProxyPendingActivity$P%d", index);
    }

    public static String getProxyActivity(int index) {
        return String.format(Locale.CHINA, "com.nexbox.core.proxy.ProxyActivity$P%d", index);
    }

    public static String TransparentProxyActivity(int index) {
        return String.format(Locale.CHINA, "com.nexbox.core.proxy.TransparentProxyActivity$P%d", index);
    }

    public static String getProxyService(int index) {
        return String.format(Locale.CHINA, "com.nexbox.core.proxy.ProxyService$P%d", index);
    }

    public static String getProxyJobService(int index) {
        return String.format(Locale.CHINA, "com.nexbox.core.proxy.ProxyJobService$P%d", index);
    }

    public static String getProxyFileProvider() {
        return NexBoxCore.getHostPkg() + ".nexbox.FileProvider";
    }

    public static String getMediaProviderAuthority() {
        return NexBoxCore.getHostPkg() + ".nexbox.MediaProvider";
    }

    public static String getProxyReceiver() {
        return NexBoxCore.getHostPkg() + ".stub_receiver";
    }

    public static String getProcessName(int bPid) {
        return NexBoxCore.getHostPkg() + ":p" + bPid;
    }
}
