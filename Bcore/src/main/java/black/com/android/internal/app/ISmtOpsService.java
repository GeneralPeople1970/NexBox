package black.com.android.internal.app;

import android.os.IBinder;
import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("com.android.internal.app.ISmtOpsService")
public interface ISmtOpsService {
    @BClassName("com.android.internal.app.ISmtOpsService$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
