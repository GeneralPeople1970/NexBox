package black.android.content;

import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;
import com.nexbox.reflection.annotation.BStaticField;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.content.ClipboardManager")
public interface ClipboardManager {
    @BField
    IInterface mService();

    @BStaticField
    IInterface sService();

    @BStaticMethod
    IInterface getService();
}
