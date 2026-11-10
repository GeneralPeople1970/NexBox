package black.android.app;

import android.os.IBinder;
import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.app.IUiModeManager")
public interface IUiModeManager {
    @BClassName("android.app.IUiModeManager$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
