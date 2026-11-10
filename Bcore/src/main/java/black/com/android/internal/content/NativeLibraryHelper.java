package black.com.android.internal.content;

import java.io.File;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;
import com.nexbox.reflection.annotation.BParamClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("com.android.internal.content.NativeLibraryHelper")
public interface NativeLibraryHelper {
    @BStaticMethod
    Integer copyNativeBinaries(@BParamClassName("com.android.internal.content.NativeLibraryHelper$Handle") Object handle,
                              File directory, String abi);

    @BStaticMethod
    Integer findSupportedAbi(@BParamClassName("com.android.internal.content.NativeLibraryHelper$Handle") Object handle,
                             @BParamClassName("[Ljava.lang.String;") String[] abis);

    @BClassName("com.android.internal.content.NativeLibraryHelper$Handle")
    interface Handle {
        @BField
        boolean extractNativeLibs();

        @BStaticMethod
        Object create(File File0);
    }
}
