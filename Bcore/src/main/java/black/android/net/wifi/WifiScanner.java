package black.android.net.wifi;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticField;

@BClassName("android.net.wifi.WifiScanner")
public interface WifiScanner {
    @BStaticField
    String GET_AVAILABLE_CHANNELS_EXTRA();
}
