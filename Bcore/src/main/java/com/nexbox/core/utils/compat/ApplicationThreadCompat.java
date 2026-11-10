package com.nexbox.core.utils.compat;

import android.os.IBinder;
import android.os.IInterface;

import black.android.app.BRIApplicationThreadOreoStub;

public class ApplicationThreadCompat {

    public static IInterface asInterface(IBinder binder) {
        return BRIApplicationThreadOreoStub.get().asInterface(binder);
    }
}
