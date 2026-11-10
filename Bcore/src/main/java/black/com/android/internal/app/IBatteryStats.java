package black.com.android.internal.app;

import android.os.IBinder;
import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("com.android.internal.app.IBatteryStats")
public interface IBatteryStats {
    @BClassName("com.android.internal.app.IBatteryStats$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
