package black.android.os;

import android.os.Handler.Callback;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.os.Handler")
public interface Handler {
    @BField
    Callback mCallback();
}
