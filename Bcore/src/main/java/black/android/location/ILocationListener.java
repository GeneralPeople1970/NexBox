package black.android.location;

import android.location.Location;
import android.os.IBinder;
import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BMethod;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.location.ILocationListener")
public interface ILocationListener {
    @BMethod
    void onLocationChanged(Location Location0);

    @BClassName("android.location.ILocationListener$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
