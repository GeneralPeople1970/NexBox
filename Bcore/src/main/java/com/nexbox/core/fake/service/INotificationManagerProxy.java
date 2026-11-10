package com.nexbox.core.fake.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;

import java.lang.reflect.Method;
import java.util.List;

import black.android.app.BRNotificationManager;
import black.android.content.pm.BRParceledListSlice;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.frameworks.BNotificationManager;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.utils.MethodParameterUtils;
import com.nexbox.core.utils.compat.ParceledListSliceCompat;


public class INotificationManagerProxy extends BinderInvocationStub {
    public static final String TAG = "INotificationManagerProxy";

    public INotificationManagerProxy() {
        super(BRNotificationManager.get().getService().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRNotificationManager.get().getService();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRNotificationManager.get()._set_sService(getProxyInvocation());
        replaceSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (!com.nexbox.core.NexBoxCore.get().isGuestProcess()) {
            try { return method.invoke(getBase(), args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        }
        MethodParameterUtils.replaceAllAppPkg(args);
        return super.invoke(proxy, method, args);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getAppActiveNotifications")
    public static class GetAppActiveNotifications extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) {
            List<android.service.notification.StatusBarNotification> active = BNotificationManager.get().getActiveNotifications();
            if (method.getReturnType().isArray()) return active.toArray(new android.service.notification.StatusBarNotification[0]);
            if (List.class.isAssignableFrom(method.getReturnType())) return active;
            return ParceledListSliceCompat.create(active);
        }
    }

    @ProxyMethod("cancelAllNotifications")
    public static class CancelAllNotifications extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) {
            BNotificationManager.get().cancelAllNotifications();
            return null;
        }
    }

    @ProxyMethod("getNotificationChannel")
    public static class GetNotificationChannel extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            NotificationChannel notificationChannel = BNotificationManager.get().getNotificationChannel((String) args[args.length - 1]);
            return notificationChannel;
        }
    }

    @ProxyMethod("getNotificationChannels")
    public static class GetNotificationChannels extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<NotificationChannel> notificationChannels = BNotificationManager.get().getNotificationChannels(BActivityThread.getAppPackageName());
            return ParceledListSliceCompat.create(notificationChannels);
        }
    }

    @ProxyMethod("cancelNotificationWithTag")
    public static class CancelNotificationWithTag extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            BNotificationManager.get().cancelNotificationWithTag(id, tag);
            return 0;
        }

        public int getTagIndex() {
            return 2;
        }

        public int getIdIndex() {
            return getTagIndex() + 1;
        }
    }


    @ProxyMethod("enqueueNotificationWithTag")
    public static class EnqueueNotificationWithTag extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            Notification notification = MethodParameterUtils.getFirstParam(args, Notification.class);
            BNotificationManager.get().enqueueNotificationWithTag(id, tag, notification);
            return 0;
        }

        public int getTagIndex() {
            return 2;
        }

        public int getIdIndex() {
            return getTagIndex() + 1;
        }
    }

    @ProxyMethod("createNotificationChannels")
    public static class CreateNotificationChannels extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<?> list = BRParceledListSlice.get(args[1]).getList();
            if (list == null)
                return 0;
            for (Object o : list) {
                BNotificationManager.get().createNotificationChannel((NotificationChannel) o);
            }
            return 0;
        }
    }

    @ProxyMethod("deleteNotificationChannel")
    public static class DeleteNotificationChannel extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BNotificationManager.get().deleteNotificationChannel((String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("createNotificationChannelGroups")
    public static class CreateNotificationChannelGroups extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<?> list = BRParceledListSlice.get(args[1]).getList();
            for (Object o : list) {
                BNotificationManager.get().createNotificationChannelGroup((NotificationChannelGroup) o);
            }
            return 0;
        }
    }

    @ProxyMethod("deleteNotificationChannelGroup")
    public static class DeleteNotificationChannelGroup extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BNotificationManager.get().deleteNotificationChannelGroup((String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("getNotificationChannelGroups")
    public static class GetNotificationChannelGroups extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<NotificationChannelGroup> notificationChannelGroups = BNotificationManager.get().getNotificationChannelGroups(BActivityThread.getAppPackageName());
            return ParceledListSliceCompat.create(notificationChannelGroups);
        }
    }
}
