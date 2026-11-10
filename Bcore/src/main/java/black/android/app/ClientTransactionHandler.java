package black.android.app;

import android.os.IBinder;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BMethod;

@BClassName("android.app.ClientTransactionHandler")
public interface ClientTransactionHandler {
    @BMethod
    Object getActivityClient(IBinder IBinder0);
}
