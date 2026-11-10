package black.android.telephony;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BConstructor;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.telephony.CellSignalStrengthGsm")
public interface CellSignalStrengthGsm {
    @BConstructor
    CellSignalStrengthGsm _new();

    @BField
    int mBitErrorRate();

    @BField
    int mSignalStrength();
}
