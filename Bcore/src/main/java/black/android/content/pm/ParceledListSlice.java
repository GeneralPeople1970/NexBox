package black.android.content.pm;

import android.os.Parcelable;
import android.os.Parcelable.Creator;

import java.util.List;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BConstructor;
import com.nexbox.reflection.annotation.BMethod;
import com.nexbox.reflection.annotation.BStaticField;

@BClassName("android.content.pm.ParceledListSlice")
public interface ParceledListSlice {
    @BConstructor
    Object _new();

    @BConstructor
    Object _new(List<?> List0);

    @BStaticField
    Creator<?> CREATOR();

    @BMethod
    Boolean append(Object item);

    @BMethod
    List<?> getList();

    @BMethod
    Boolean isLastSlice();

    @BMethod
    Parcelable populateList();

    @BMethod
    void setLastSlice(boolean b);
}
