package black.android.telephony;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BConstructor;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.telephony.CellSignalStrengthCdma")
public interface CellSignalStrengthCdma {
    @BConstructor
    CellSignalStrengthCdma _new();

    @BField
    int mCdmaDbm();

    @BField
    int mCdmaEcio();

    @BField
    int mEvdoDbm();

    @BField
    int mEvdoEcio();

    @BField
    int mEvdoSnr();
}
