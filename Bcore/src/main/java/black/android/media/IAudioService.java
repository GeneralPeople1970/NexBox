package black.android.media;

import android.os.IBinder;
import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.media.IAudioService")
public interface IAudioService {
    @BClassName("android.media.IAudioService$Stub")
    interface Stub {
        @BStaticMethod
        IInterface asInterface(IBinder IBinder0);
    }
}
