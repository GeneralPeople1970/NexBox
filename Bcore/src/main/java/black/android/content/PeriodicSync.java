package black.android.content;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.content.PeriodicSync")
public interface PeriodicSync {
    @BField
    long flexTime();
}
