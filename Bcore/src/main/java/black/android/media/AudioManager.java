package black.android.media;

import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticField;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.media.AudioManager")
public interface AudioManager {
    @BStaticField
    IInterface sService();

    @BStaticMethod
    void getService();
}
