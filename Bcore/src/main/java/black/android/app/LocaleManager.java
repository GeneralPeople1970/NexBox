package black.android.app;

import android.os.IInterface;
import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.app.LocaleManager")
public interface LocaleManager {
    @BField
    IInterface mService();
}
