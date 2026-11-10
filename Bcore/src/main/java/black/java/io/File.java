package black.java.io;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticField;

@BClassName("java.io.File")
public interface File {
    @BStaticField
    Object fs();
}
