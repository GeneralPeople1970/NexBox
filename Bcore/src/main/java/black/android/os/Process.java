package black.android.os;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.os.Process")
public interface Process {
    @BStaticMethod
    void setArgV0(String String0);
}
