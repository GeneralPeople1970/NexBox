package black.android.app;

import android.content.Intent;
import android.os.IBinder;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BMethod;

@BClassName("android.app.IActivityManager")
public interface IActivityManagerN {
    @BMethod
    Boolean finishActivity(IBinder IBinder0, int int1, Intent Intent2, int int3);
}
