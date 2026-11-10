package black.android.content;

import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.content.IntentSender")
public interface IntentSender {
    @BField
    IInterface mTarget();
}
