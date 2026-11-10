package black.android.telephony;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BMethod;

@BClassName("android.telephony.SmsManager")
public interface SmsManager {
    @BMethod
    Boolean getAutoPersisting();

    @BMethod
    void setAutoPersisting(boolean boolean0);
}
