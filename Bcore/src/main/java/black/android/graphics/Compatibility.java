package black.android.graphics;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;


@BClassName("android.graphics.Compatibility")
public interface Compatibility {
    @BStaticMethod
    void setTargetSdkVersion(int targetSdkVersion);
}
