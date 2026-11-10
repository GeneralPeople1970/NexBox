package black.dalvik.system;


import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BMethod;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("dalvik.system.VMRuntime")
public interface VMRuntime {
    @BStaticMethod
    String getCurrentInstructionSet();

    @BStaticMethod
    Object getRuntime();

    @BStaticMethod
    Boolean is64BitAbi(String String0);

    @BMethod
    Boolean is64Bit();

    @BMethod
    Boolean isJavaDebuggable();

    @BMethod
    void setTargetSdkVersion(int int0);
}
