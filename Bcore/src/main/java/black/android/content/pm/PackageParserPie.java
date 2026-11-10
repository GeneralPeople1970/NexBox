package black.android.content.pm;


import android.content.pm.PackageParser;

import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BStaticMethod;

@BClassName("android.content.pm.PackageParser")
public interface PackageParserPie {
    @BStaticMethod
    void collectCertificates(PackageParser.Package p, boolean skipVerify);
}
