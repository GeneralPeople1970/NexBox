package black.android.app.usage;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BConstructor;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.app.usage.StorageStats")
public interface StorageStats {
    @BConstructor
    StorageStats _new();

    @BField
    long cacheBytes();

    @BField
    long codeBytes();

    @BField
    long dataBytes();
}
