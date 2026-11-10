package com.nexbox.core.utils.compat;

import com.nexbox.core.utils.Slog;

import android.app.Activity;
import android.content.Intent;
import android.os.IBinder;

import black.android.app.BRActivity;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRIActivityManager;
import black.android.app.BRIActivityManagerN;

public class ActivityManagerCompat {
    // AOSP ActivityManager fatal start-result codes; hidden from the SDK stubs.
    public static final int START_INTENT_NOT_RESOLVED = -91;
    public static final int START_CANCELED = -96;
	
	public static final int SERVICE_DONE_EXECUTING_ANON = 0;
	
	public static final int SERVICE_DONE_EXECUTING_START = 1;
	
	public static final int SERVICE_DONE_EXECUTING_STOP = 2;

























	
	public static final int INTENT_SENDER_BROADCAST = 1;

	
	public static final int INTENT_SENDER_ACTIVITY = 2;

	
	public static final int INTENT_SENDER_ACTIVITY_RESULT = 3;

	
	public static final int INTENT_SENDER_SERVICE = 4;

	
	public static final int USER_OP_SUCCESS = 0;

	public static final int START_FLAG_DEBUG = 1<<1;
	public static final int START_FLAG_TRACK_ALLOCATION = 1<<2;
	public static final int START_FLAG_NATIVE_DEBUGGING = 1<<3;

	public static boolean finishActivity(IBinder token, int code, Intent data) {
		return BRIActivityManagerN.get(BRActivityManagerNative.get().getDefault()).finishActivity(
				token, code, data, 0);
	}


    public static void setActivityOrientation(Activity activity, int orientation) {
        try {
            activity.setRequestedOrientation(orientation);
        } catch (Throwable e) {
            Slog.e("ActivityManagerCompat", "Container operation failed", e);
            
            Activity parent =  BRActivity.get(activity).mParent();
            while (true) {
				Activity tmp = BRActivity.get(parent).mParent();
				if (tmp != null) {
					parent = tmp;
				} else {
					break;
				}
			}
            IBinder token = BRActivity.get(parent).mToken();
            try {
				BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).setRequestedOrientation(token, orientation);
            }catch (Throwable ex){
                Slog.e("ActivityManagerCompat", "Container operation failed", ex);
            }
        }
    }
}
