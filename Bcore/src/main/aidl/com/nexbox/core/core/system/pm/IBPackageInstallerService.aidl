package com.nexbox.core.core.system.pm;

import com.nexbox.core.core.system.pm.BPackageSettings;
import com.nexbox.core.entity.pm.InstallOption;

interface IBPackageInstallerService {
    int installPackageAsUser(in BPackageSettings ps, int userId);
    int uninstallPackageAsUser(in BPackageSettings ps, boolean removeApp, int userId);
    int clearPackage(in BPackageSettings ps, int userId);
    int updatePackage(in BPackageSettings ps);
}
