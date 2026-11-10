package black.android.renderscript;

import java.io.File;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.renderscript.RenderScriptCacheDir")
public interface RenderScriptCacheDir {
    @BStaticMethod
    void setupDiskCache(File File0);
}
