package com.nexbox.core.entity;

import com.nexbox.core.utils.Slog;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


public class ServiceRecord {
    private Service mService;
    private Map<Intent.FilterComparison, BoundInfo> mBounds = new HashMap<>();
    private int mStartId;
    private Intent mProxyIntent;

    public Intent getProxyIntent() {
        return mProxyIntent;
    }

    public void setProxyIntent(Intent proxyIntent) {
        mProxyIntent = new Intent(proxyIntent);
    }

    public class BoundInfo {
        private IBinder mIBinder;
        private boolean hasBinder;
        private boolean rebind;
        private AtomicInteger mBindCount = new AtomicInteger(0);

        public int incrementAndGetBindCount() {
            return mBindCount.incrementAndGet();
        }

        public int decrementAndGetBindCount() {
            return mBindCount.decrementAndGet();
        }

        public IBinder getIBinder() {
            return mIBinder;
        }

        public void setIBinder(IBinder IBinder) {
            mIBinder = IBinder;
            hasBinder = true;
        }
    }

    public int getStartId() {
        return mStartId;
    }

    public void setStartId(int startId) {
        mStartId = startId;
    }

    public Service getService() {
        return mService;
    }

    public void setService(Service service) {
        mService = service;
    }

    public IBinder getBinder(Intent intent) {
        BoundInfo boundInfo = getOrCreateBoundInfo(intent);
        return boundInfo.getIBinder();
    }

    public boolean hasBinder(Intent intent) {
        BoundInfo boundInfo = getOrCreateBoundInfo(intent);
        return boundInfo.hasBinder;
    }

    public void addBinder(Intent intent, final IBinder iBinder) {
        final Intent.FilterComparison filterComparison = new Intent.FilterComparison(intent);
        BoundInfo boundInfo = getOrCreateBoundInfo(intent);
        if (boundInfo == null) {
            boundInfo = new BoundInfo();
            mBounds.put(filterComparison, boundInfo);
        }
        boundInfo.setIBinder(iBinder);
        if (iBinder == null) return;
        try {
            iBinder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    iBinder.unlinkToDeath(this, 0);
                    mBounds.remove(filterComparison);
                }
            }, 0);
        } catch (RemoteException e) {
            Slog.e("ServiceRecord", "Container operation failed", e);
        }
    }

    public int incrementAndGetBindCount(Intent intent) {
        BoundInfo boundInfo = getOrCreateBoundInfo(intent);
        return boundInfo.incrementAndGetBindCount();
    }

    public boolean decreaseConnectionCount(Intent intent) {
        Intent.FilterComparison filterComparison = new Intent.FilterComparison(intent);
        BoundInfo boundInfo = mBounds.get(filterComparison);
        if (boundInfo == null)
            return true;
        int i = boundInfo.decrementAndGetBindCount();
        if (i <= 0) {

            return true;
        }
        return false;
    }

    public BoundInfo getOrCreateBoundInfo(Intent intent) {
        Intent.FilterComparison filterComparison = new Intent.FilterComparison(intent);
        BoundInfo boundInfo = mBounds.get(filterComparison);
        if (boundInfo == null) {
            boundInfo = new BoundInfo();
            mBounds.put(filterComparison, boundInfo);
        }
        return boundInfo;
    }

    public boolean isRebind(Intent intent) {
        return getOrCreateBoundInfo(intent).rebind;
    }

    public void setRebind(Intent intent, boolean rebind) {
        getOrCreateBoundInfo(intent).rebind = rebind;
    }
}
