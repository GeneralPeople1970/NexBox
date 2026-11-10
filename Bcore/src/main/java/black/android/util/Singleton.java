package black.android.util;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;
import com.nexbox.reflection.annotation.BMethod;

@BClassName("android.util.Singleton")
public interface Singleton {
    @BField
    Object mInstance();

    @BMethod
    Object get();
}
