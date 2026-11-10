package black.android.app.blob;

import android.os.IInterface;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.app.blob.BlobStoreManager")
public interface BlobStoreManager {
    @BField
    IInterface mService();
}
