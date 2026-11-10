package black.android.app;

import java.io.File;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BConstructor;

@BClassName("android.app.SharedPreferencesImpl")
public interface SharedPreferencesImpl {
    @BConstructor
    SharedPreferencesImpl _new(File File0, int int1);
}
