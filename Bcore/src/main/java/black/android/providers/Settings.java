package black.android.providers;

import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;
import com.nexbox.reflection.annotation.BStaticField;

@BClassName("android.provider.Settings")
public interface Settings {
    @BClassName("android.provider.Settings$System")
    interface System {
        @BStaticField
        Object sNameValueCache();
    }

    @BClassName("android.provider.Settings$Secure")
    interface Secure {
        @BStaticField
        Object sNameValueCache();
    }

    @BClassName("android.provider.Settings$ContentProviderHolder")
    interface ContentProviderHolder {
        @BField
        IInterface mContentProvider();
    }

    @BClassName("android.provider.Settings$NameValueCache")
    interface NameValueCacheOreo {
        @BField
        Object mProviderHolder();
    }

    @BClassName("android.provider.Settings$NameValueCache")
    interface NameValueCache {
        @BField
        Object mContentProvider();
    }

    @BClassName("android.provider.Settings$Global")
    interface Global {
        @BStaticField
        Object sNameValueCache();
    }
}
