package black.android.os;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BMethod;
import com.nexbox.reflection.annotation.BStaticField;

@BClassName("android.os.Parcel")
public interface Parcel {
    @BMethod
    boolean hasBinders();

    @BStaticField
    int VAL_PARCELABLE();

    @BStaticField
    int VAL_PARCELABLEARRAY();
}
