package black.android.app.servertransaction;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.app.servertransaction.TopResumedActivityChangeItem")
public interface TopResumedActivityChangeItem {
    @BField
    Boolean mOnTop();
}
