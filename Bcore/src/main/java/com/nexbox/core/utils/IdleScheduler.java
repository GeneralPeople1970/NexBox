package com.nexbox.core.utils;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class IdleScheduler {
    private IdleScheduler() { }

    public static ScheduledThreadPoolExecutor create(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(30, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
