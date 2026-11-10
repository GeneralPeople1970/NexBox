package black.android.content;

import android.os.IBinder;
import android.os.IInterface;
import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.content.IClipboard$Stub")
public interface IClipboardStub {
    @BStaticMethod
    IInterface asInterface(IBinder binder);
}
