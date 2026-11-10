package black.android.os;

import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.os.DropBoxManager")
public interface DropBoxManager {
    @BField
    IInterface mService();
}
