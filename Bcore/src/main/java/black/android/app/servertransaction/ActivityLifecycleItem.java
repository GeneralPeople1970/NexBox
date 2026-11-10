package black.android.app.servertransaction;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BMethod;

@BClassName("android.app.servertransaction.ActivityLifecycleItem")
public interface ActivityLifecycleItem {
    @BMethod
    Integer getTargetState();
}
