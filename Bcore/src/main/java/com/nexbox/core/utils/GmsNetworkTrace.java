package com.nexbox.core.utils;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.GmsCompat;

/** Bounded metadata-only tracing of GMS Java DNS/socket calls; never logs payloads or addresses. */
public final class GmsNetworkTrace {
    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadLocal<Boolean> WRITING = new ThreadLocal<>();
    private static long windowStart;
    private static int admitted;

    public static final class Call {
        final long id = IDS.incrementAndGet();
        final long start = SystemClock.elapsedRealtime();
        final String operation;
        Call(String operation) { this.operation = operation; }
    }

    private GmsNetworkTrace() { }

    public static Call begin(String method, Object[] args) {
        if (!("android_getaddrinfo".equals(method) || "getaddrinfo".equals(method)
                || "connect".equals(method))) return null;
        try {
            if (Boolean.TRUE.equals(WRITING.get()) || !Slog.isLoggable("GmsNetwork", Log.DEBUG)
                    || !GmsCompat.isCorePackage(BActivityThread.getAppPackageName())) return null;
            synchronized (GmsNetworkTrace.class) {
                long now = SystemClock.elapsedRealtime();
                if (now - windowStart >= 60_000) { windowStart = now; admitted = 0; }
                if (admitted >= 60) return null;
                admitted++;
            }
            Call call = new Call(method);
            String destination = "redacted";
            String endpoint = "";
            if ("connect".equals(method) && args != null && args.length > 1) {
                Object address = args[1];
                if (address instanceof java.net.InetSocketAddress) {
                    java.net.InetSocketAddress socket = (java.net.InetSocketAddress) address;
                    endpoint = " port=" + socket.getPort();
                    address = socket.getAddress();
                } else if (args.length > 2 && args[2] instanceof Integer) {
                    endpoint = " port=" + args[2];
                }
                if (address instanceof InetAddress) {
                    InetAddress ip = (InetAddress) address;
                    destination = ip.isLoopbackAddress() ? "loopback"
                            : ip.isLinkLocalAddress() || ip.isSiteLocalAddress() ? "local_network" : "public_network";
                    endpoint += " family=" + (ip instanceof java.net.Inet6Address ? "ipv6" : "ipv4");
                } else {
                    destination = "non_ip_socket";
                }
            }
            if (!"connect".equals(method) && args != null && args.length > 0 && args[0] instanceof String) {
                String host = ((String) args[0]).toLowerCase(java.util.Locale.ROOT);
                if (host.equals("android.clients.google.com") || host.equals("accounts.google.com")) {
                    destination = host;
                }
            }
            write("id=" + call.id + " action=" + method + " stage=start destination=" + destination + endpoint);
            return call;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void end(Call call, Object result, Throwable failure) {
        if (call == null) return;
        try {
            String status = failure == null ? "success" : "failure";
            String details = "";
            if (failure instanceof ErrnoException) {
                int errno = ((ErrnoException) failure).errno;
                if (errno == OsConstants.EINPROGRESS || errno == OsConstants.EALREADY) status = "pending";
                details = " errno=" + errno;
            }
            if (result instanceof InetAddress[]) details += " addressCount=" + ((InetAddress[]) result).length;
            write("id=" + call.id + " action=" + call.operation + " stage=finish status=" + status
                    + " durationMs=" + (SystemClock.elapsedRealtime() - call.start)
                    + " failure=" + (failure == null ? "none" : failure.getClass().getSimpleName()) + details);
        } catch (Throwable ignored) {
            // Tracing cannot replace a DNS/socket result or exception.
        }
    }

    private static void write(String message) {
        if (Boolean.TRUE.equals(WRITING.get())) return;
        WRITING.set(true);
        try { Slog.d("GmsNetwork", message); }
        finally { WRITING.remove(); }
    }
}
