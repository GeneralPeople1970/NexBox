package black.libcore.io;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticField;

@BClassName("libcore.io.Libcore")
public interface Libcore {
    @BStaticField
    Object os();
}
