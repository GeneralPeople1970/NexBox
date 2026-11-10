package black.com.android.internal.app;

import android.os.IBinder;
import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("com.android.internal.app.IAppOpsService")
public interface IAppOpsService {
    @BClassName("com.android.internal.app.IAppOpsService$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
