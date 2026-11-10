package com.nexbox.core.core.system.accounts;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.utils.compat.BundleCompat;

public final class AccountInteractionContract {
    public static final String ACTIVITY = "com.nexbox.app.view.accounts.AccountChoiceActivity";
    public static final String EXTRA_REQUEST = "com.nexbox.account.INTERACTION";
    public static final String ACCOUNT_NAMES = "accounts";
    public static final String CALLER_PACKAGE = "callerPackage";
    public static final String GRANT_TOKEN = "grantToken";

    private AccountInteractionContract() { }

    static Intent createIntent(IBinder capability) {
        Intent intent = new Intent().setComponent(new ComponentName(NexBoxCore.getHostPkg(), ACTIVITY));
        BundleCompat.putBinder(intent, EXTRA_REQUEST, capability);
        return intent;
    }
}
