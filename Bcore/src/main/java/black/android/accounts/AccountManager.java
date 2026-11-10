package black.android.accounts;

import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.accounts.AccountManager")
public interface AccountManager {
    @BField
    IInterface mService();
}
