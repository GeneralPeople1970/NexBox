package com.nexbox.core.utils.compat;

import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Package;

import java.io.File;

import black.android.content.pm.BRPackageParserMarshmallow;
import black.android.content.pm.BRPackageParserPie;

public class PackageParserCompat {

    public static final int[] GIDS = new int[]{};
    private static final int myUserId = 0;


    public static PackageParser createParser(File packageFile) {
        return BRPackageParserMarshmallow.get()._new();
    }

    public static Package parsePackage(PackageParser parser, File packageFile, int flags) throws Throwable {
        return BRPackageParserMarshmallow.getWithException(parser).parsePackage(packageFile, flags);
    }

    public static void collectCertificates(PackageParser parser, Package p, int flags) throws Throwable {
        collectCertificates(parser, p, flags, true);
    }

    public static void collectCertificates(PackageParser parser, Package p, int flags, boolean skipVerify) throws Throwable {
        BRPackageParserPie.getWithException().collectCertificates(p, skipVerify);
    }
}
