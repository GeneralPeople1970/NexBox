package black.android.os;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticField;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.os.StrictMode")
public interface StrictMode {
    @BStaticField
    int DETECT_VM_FILE_URI_EXPOSURE();

    @BStaticField
    int PENALTY_DEATH_ON_FILE_URI_EXPOSURE();

    @BStaticField
    int sVmPolicyMask();

    @BStaticMethod
    void disableDeathOnFileUriExposure();
}
