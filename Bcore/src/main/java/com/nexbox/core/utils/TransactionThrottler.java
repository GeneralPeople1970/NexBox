package com.nexbox.core.utils;

import com.nexbox.core.utils.Slog;


import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class TransactionThrottler {
    private static final String TAG = "TransactionThrottler";
    
    private static final int MAX_FAILURES_PER_WINDOW = 50; 
    private static final long WINDOW_DURATION_MS = 15000; 
    private static final long THROTTLE_DURATION_MS = 1000; 
    
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong windowStartTime = new AtomicLong(0);
    private final AtomicLong lastThrottleTime = new AtomicLong(0);
    
    
    public boolean shouldThrottle() {
        long currentTime = System.currentTimeMillis();
        long windowStart = windowStartTime.get();
        
        
        if (currentTime - windowStart > WINDOW_DURATION_MS) {
            failureCount.set(0);
            windowStartTime.set(currentTime);
            return false;
        }
        
        
        long lastThrottle = lastThrottleTime.get();
        if (currentTime - lastThrottle < THROTTLE_DURATION_MS) {
            return true;
        }
        
        
        if (failureCount.get() >= MAX_FAILURES_PER_WINDOW) {
            lastThrottleTime.set(currentTime);
            Slog.w(TAG, "Throttling transactions due to " + failureCount.get() + " failures in window");
            return true;
        }
        
        return false;
    }
    
    
    public void recordFailure() {
        long currentTime = System.currentTimeMillis();
        long windowStart = windowStartTime.get();
        
        
        if (windowStart == 0) {
            windowStartTime.set(currentTime);
        }
        
        
        if (currentTime - windowStart > WINDOW_DURATION_MS) {
            failureCount.set(1);
            windowStartTime.set(currentTime);
        } else {
            failureCount.incrementAndGet();
        }
        
        Slog.d(TAG, "Recorded transaction failure, count: " + failureCount.get());
    }
    
    
    public void reset() {
        int recoveredFailures = failureCount.getAndSet(0);
        windowStartTime.set(0);
        long previousThrottle = lastThrottleTime.getAndSet(0);
        if (recoveredFailures > 0 || previousThrottle > 0) {
            try {
                Slog.d(TAG, "Recovered transaction throttler, failures=" + recoveredFailures);
            } catch (Throwable ignored) {
                // Recovery is complete even when a diagnostic sink is unavailable.
            }
        }
    }
    
    
    public int getFailureCount() {
        return failureCount.get();
    }
}
