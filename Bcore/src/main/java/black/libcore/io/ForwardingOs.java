package black.libcore.io;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("libcore.io.ForwardingOs")
public interface ForwardingOs {
    @BField
    Object os();
}
