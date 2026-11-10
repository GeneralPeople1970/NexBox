package com.nexbox.core.core.system.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import black.android.app.BRNotificationChannel;
import black.android.app.BRNotificationChannelGroup;
import black.android.app.BRNotificationO;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ISystemService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.core.system.user.UserResourceNamespace;
import com.nexbox.core.core.system.xposed.XposedCallerGuard;
import com.nexbox.core.utils.Slog;

/** OS records retain reversible package/user-generation ownership after server death. */
public class BNotificationManagerService extends IBNotificationManagerService.Stub implements ISystemService {
    private static final BNotificationManagerService INSTANCE = new BNotificationManagerService();
    public static final String CHANNEL_NAMESPACE = UserResourceNamespace.CHANNEL_MARKER;
    public static final String GROUP_NAMESPACE = UserResourceNamespace.GROUP_MARKER;
    private final NotificationManager real = NexBoxCore.getContext().getSystemService(NotificationManager.class);
    public static BNotificationManagerService get() { return INSTANCE; }
    @Override public void systemReady() { NotificationChannelManager.get(); }

    private ProcessRecord caller(int userId) {
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        BUserInfo user = BUserManagerService.get().getUserInfo(userId);
        if (Binder.getCallingUid() != android.os.Process.myUid() || process == null || process.userId != userId
                || user == null || process.userGeneration != user.createTime || process.bActivityThread == null
                || !process.bActivityThread.asBinder().isBinderAlive()) throw new SecurityException("Stale notification caller");
        return process;
    }
    private long generation(int userId) {
        // URI/channel/tag helpers can run after the first caller check. Never bind
        // that in-flight guest request to a newly created user with the same number.
        int pid = Binder.getCallingPid();
        if (pid != android.os.Process.myPid()) {
            ProcessRecord process = BProcessManagerService.get().findProcessByPid(pid);
            if (process != null) return caller(userId).userGeneration;
            XposedCallerGuard.enforceHostManagerOrCore();
        }
        BUserInfo user = BUserManagerService.get().getUserInfo(userId);
        if (user == null || user.createTime <= 0) throw new IllegalStateException("Virtual user no longer exists");
        return user.createTime;
    }
    private static String encode(String value) { return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING); }
    private static String decode(String value) { return new String(Base64.decode(value, Base64.NO_WRAP | Base64.URL_SAFE), StandardCharsets.UTF_8); }
    private static String packagePrefix(String pkg) { return "nexbox-app:" + encode(pkg) + ":"; }
    private String scoped(String id, String marker, String pkg, int userId) {
        return id == null ? null : UserResourceNamespace.scopedId(packagePrefix(pkg) + encode(id), marker, userId, generation(userId));
    }
    private String original(String id, String marker, String pkg, int userId) {
        if (id == null) return null;
        return original(id, marker, pkg, userId, generation(userId));
    }
    private static String original(String id, String marker, String pkg, int userId, long generation) {
        if (id == null || !id.endsWith(marker + userId + "." + generation)) return null;
        String bare = UserResourceNamespace.originalId(id, marker);
        String prefix = packagePrefix(pkg);
        if (!bare.startsWith(prefix)) return null;
        try { return decode(bare.substring(prefix.length())); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private String tagPrefix(String pkg, int userId) { return tagPrefix(pkg, userId, generation(userId)); }
    private static String tagPrefix(String pkg, int userId, long generation) { return UserResourceNamespace.notificationTag(userId, generation) + "|" + encode(pkg) + "|"; }
    private String tag(String tag, String pkg, int userId) { return tagPrefix(pkg, userId) + (tag == null ? "n" : "s" + encode(tag)); }
    private static <T extends Parcelable> T copy(T value, Parcelable.Creator<T> creator) {
        Parcel parcel = Parcel.obtain();
        try { value.writeToParcel(parcel, 0); parcel.setDataPosition(0); return creator.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
    private NotificationChannel toGuest(NotificationChannel channel, String pkg, int userId) {
        NotificationChannel result = copy(channel, NotificationChannel.CREATOR);
        BRNotificationChannel.get(result)._set_mId(original(channel.getId(), CHANNEL_NAMESPACE, pkg, userId));
        result.setGroup(original(channel.getGroup(), GROUP_NAMESPACE, pkg, userId));
        return result;
    }
    @Override public NotificationChannel getNotificationChannel(String id, int userId) {
        String pkg = caller(userId).getPackageName();
        NotificationChannel channel = real.getNotificationChannel(scoped(id, CHANNEL_NAMESPACE, pkg, userId));
        return channel == null ? null : toGuest(channel, pkg, userId);
    }
    @Override public List<NotificationChannel> getNotificationChannels(String packageName, int userId) {
        String pkg = caller(userId).getPackageName();
        if (!pkg.equals(packageName)) throw new SecurityException("Notification package mismatch");
        List<NotificationChannel> result = new ArrayList<>();
        for (NotificationChannel channel : real.getNotificationChannels()) {
            if (original(channel.getId(), CHANNEL_NAMESPACE, pkg, userId) != null) result.add(toGuest(channel, pkg, userId));
        }
        return result;
    }
    @Override public void createNotificationChannel(NotificationChannel channel, int userId) {
        String pkg = caller(userId).getPackageName();
        NotificationChannel mapped = copy(channel, NotificationChannel.CREATOR);
        BRNotificationChannel.get(mapped)._set_mId(scoped(channel.getId(), CHANNEL_NAMESPACE, pkg, userId));
        mapped.setGroup(scoped(channel.getGroup(), GROUP_NAMESPACE, pkg, userId));
        real.createNotificationChannel(mapped);
    }
    @Override public void deleteNotificationChannel(String channelId, int userId) {
        String pkg = caller(userId).getPackageName();
        real.deleteNotificationChannel(scoped(channelId, CHANNEL_NAMESPACE, pkg, userId));
    }
    @Override public List<NotificationChannelGroup> getNotificationChannelGroups(String packageName, int userId) {
        String pkg = caller(userId).getPackageName();
        if (!pkg.equals(packageName)) throw new SecurityException("Notification package mismatch");
        List<NotificationChannelGroup> result = new ArrayList<>();
        for (NotificationChannelGroup group : real.getNotificationChannelGroups()) {
            String id = original(group.getId(), GROUP_NAMESPACE, pkg, userId);
            if (id == null) continue;
            NotificationChannelGroup mapped = copy(group, NotificationChannelGroup.CREATOR);
            BRNotificationChannelGroup.get(mapped)._set_mId(id);
            List<NotificationChannel> channels = BRNotificationChannelGroup.get(mapped).mChannels();
            if (channels != null) {
                List<NotificationChannel> originals = new ArrayList<>();
                for (NotificationChannel channel : channels) if (original(channel.getId(), CHANNEL_NAMESPACE, pkg, userId) != null) originals.add(toGuest(channel, pkg, userId));
                channels.clear(); channels.addAll(originals);
            }
            result.add(mapped);
        }
        return result;
    }
    @Override public void createNotificationChannelGroup(NotificationChannelGroup group, int userId) {
        String pkg = caller(userId).getPackageName();
        NotificationChannelGroup mapped = copy(group, NotificationChannelGroup.CREATOR);
        BRNotificationChannelGroup.get(mapped)._set_mId(scoped(group.getId(), GROUP_NAMESPACE, pkg, userId));
        List<NotificationChannel> channels = BRNotificationChannelGroup.get(mapped).mChannels();
        if (channels != null) channels.clear();
        real.createNotificationChannelGroup(mapped);
        for (NotificationChannel channel : group.getChannels()) createNotificationChannel(channel, userId);
    }
    @Override public void deleteNotificationChannelGroup(String id, int userId) {
        real.deleteNotificationChannelGroup(scoped(id, GROUP_NAMESPACE, caller(userId).getPackageName(), userId));
    }
    @Override public void enqueueNotificationWithTag(int id, String tag, Notification notification, int userId) {
        String pkg = caller(userId).getPackageName();
        Notification mapped = notification.clone();
        BRNotificationO.get(mapped)._set_mChannelId(scoped(notification.getChannelId(), CHANNEL_NAMESPACE, pkg, userId));
        BRNotificationO.get(mapped)._set_mGroupKey(scoped(notification.getGroup(), GROUP_NAMESPACE, pkg, userId));
        real.notify(tag(tag, pkg, userId), id, mapped);
    }
    @Override public void cancelNotificationWithTag(int id, String tag, int userId) {
        String pkg = caller(userId).getPackageName();
        real.cancel(tag(tag, pkg, userId), id);
        // Retire only the legacy slot derived from this caller and original ID.
        real.cancel(UserResourceNamespace.notificationTag(userId, generation(userId)), getNotificationId(userId, id, pkg));
    }
    @Override public List<StatusBarNotification> getActiveNotifications(int userId) {
        ProcessRecord owner = caller(userId);
        String pkg = owner.getPackageName(), prefix = tagPrefix(pkg, userId);
        List<StatusBarNotification> result = new ArrayList<>();
        for (StatusBarNotification active : real.getActiveNotifications()) {
            if (active.getTag() == null || !active.getTag().startsWith(prefix)) continue;
            String encoded = active.getTag().substring(prefix.length());
            if (!encoded.equals("n") && !encoded.startsWith("s")) continue;
            String originalTag;
            try { originalTag = encoded.equals("n") ? null : decode(encoded.substring(1)); }
            catch (IllegalArgumentException malformed) { continue; }
            Notification notification = active.getNotification().clone();
            BRNotificationO.get(notification)._set_mChannelId(original(notification.getChannelId(), CHANNEL_NAMESPACE, pkg, userId));
            BRNotificationO.get(notification)._set_mGroupKey(original(notification.getGroup(), GROUP_NAMESPACE, pkg, userId));
            // The newer field-based constructor is hidden; using Parcel instead would depend on
            // the platform's private wire layout. Keep this public guest-identity reconstruction.
            @SuppressWarnings("deprecation")
            StatusBarNotification mapped = new StatusBarNotification(pkg, pkg, active.getId(), originalTag,
                    owner.buid, owner.pid, 0, notification, active.getUser(), active.getPostTime());
            result.add(mapped);
        }
        return result;
    }
    @Override public void cancelAllNotifications(int userId) {
        String prefix = tagPrefix(caller(userId).getPackageName(), userId);
        for (StatusBarNotification active : real.getActiveNotifications()) {
            if (active.getTag() != null && active.getTag().startsWith(prefix)) real.cancel(active.getTag(), active.getId());
        }
    }
    public void removeUser(int userId) {
        if (userId < 0) return;
        for (StatusBarNotification active : real.getActiveNotifications()) {
            if (UserResourceNamespace.notificationTagBelongsToUser(active.getTag(), userId) ||
                    (active.getTag() == null && UserResourceNamespace.belongsToUser(active.getNotification().getChannelId(), CHANNEL_NAMESPACE, userId))) real.cancel(active.getTag(), active.getId());
        }
        for (NotificationChannel channel : real.getNotificationChannels()) if (UserResourceNamespace.belongsToUser(channel.getId(), CHANNEL_NAMESPACE, userId)) real.deleteNotificationChannel(channel.getId());
        for (NotificationChannelGroup group : real.getNotificationChannelGroups()) if (UserResourceNamespace.belongsToUser(group.getId(), GROUP_NAMESPACE, userId)) real.deleteNotificationChannelGroup(group.getId());
    }
    /** Internal process retirement: use its captured owner, not a now-retired Binder caller. */
    public void deletePackageNotification(String packageName, int userId, long userGeneration) {
        if (packageName == null || packageName.isEmpty() || userId < 0 || userGeneration <= 0L) {
            throw new IllegalArgumentException("A captured notification owner is required");
        }
        String prefix = tagPrefix(packageName, userId, userGeneration);
        for (StatusBarNotification active : real.getActiveNotifications()) if (active.getTag() != null && active.getTag().startsWith(prefix)) real.cancel(active.getTag(), active.getId());
        for (NotificationChannel channel : real.getNotificationChannels()) if (original(channel.getId(), CHANNEL_NAMESPACE, packageName, userId, userGeneration) != null) real.deleteNotificationChannel(channel.getId());
        for (NotificationChannelGroup group : real.getNotificationChannelGroups()) if (original(group.getId(), GROUP_NAMESPACE, packageName, userId, userGeneration) != null) real.deleteNotificationChannelGroup(group.getId());
        Slog.dLazy("BNotificationManager", () -> "action=cleanupProcessNotifications stage=requestsDispatched package="
                + packageName + " userId=" + userId + " generation=" + userGeneration);
    }
    public static int getNotificationId(int userId, int id, String pkg) { return (pkg + userId + id).hashCode(); }
}
