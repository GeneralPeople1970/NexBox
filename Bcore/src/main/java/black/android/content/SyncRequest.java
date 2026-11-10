package black.android.content;

import android.accounts.Account;
import android.os.Bundle;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.content.SyncRequest")
public interface SyncRequest {
    @BField
    Account mAccountToSync();

    @BField
    String mAuthority();

    @BField
    Bundle mExtras();

    @BField
    boolean mIsPeriodic();

    @BField
    long mSyncFlexTimeSecs();

    @BField
    long mSyncRunTimeSecs();
}
