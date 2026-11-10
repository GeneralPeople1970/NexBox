package black.android.content.pm;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BField;

@BClassName("android.content.pm.ApplicationInfo")
public interface ApplicationInfoL {
    @BField
    String primaryCpuAbi();

    @BField
    Integer privateFlags();

    @BField
    String scanPublicSourceDir();

    @BField
    String scanSourceDir();

    @BField
    String secondaryCpuAbi();

    @BField
    String secondaryNativeLibraryDir();

    @BField
    String nativeLibraryRootDir();

    @BField
    boolean nativeLibraryRootRequiresIsa();

    @BField
    String[] splitPublicSourceDirs();

    @BField
    String[] splitSourceDirs();

    @BField
    String[] resourceDirs();
}
