package black.android.os.mount;

import android.os.IBinder;
import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.os.storage.IMountService")
public interface IMountService {
    @BClassName("android.os.storage.IMountService$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
