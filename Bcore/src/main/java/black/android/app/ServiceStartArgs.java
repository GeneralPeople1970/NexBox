package black.android.app;

import android.content.Intent;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BConstructor;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.app.ServiceStartArgs")
public interface ServiceStartArgs {
    @BConstructor
    ServiceStartArgs _new(boolean boolean0, int int1, int int2, Intent Intent3);

    @BField
    Intent args();

    @BField
    int flags();

    @BField
    int startId();

    @BField
    boolean taskRemoved();
}
