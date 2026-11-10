package black.android.os;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.os.Message")
public interface Message {
    @BStaticMethod
    void updateCheckRecycle(int int0);
}
