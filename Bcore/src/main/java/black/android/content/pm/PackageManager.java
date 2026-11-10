package black.android.content.pm;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;
import com.nexbox.reflection.annotation.BMethod;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.content.pm.PackageManager")
public interface PackageManager {
    @BStaticMethod
    void disableApplicationInfoCache();
}
