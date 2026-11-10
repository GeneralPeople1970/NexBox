package com.nexbox.core.core.system.pm.installer;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.pm.BPackageSettings;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.utils.BzFileUtils;


public class CreatePackageExecutor implements Executor {

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        BzFileUtils.deleteDir(BEnvironment.getAppDir(ps.pkg.packageName));

        
        BzFileUtils.mkdirs(BEnvironment.getAppDir(ps.pkg.packageName));
        BzFileUtils.mkdirs(BEnvironment.getAppLibDir(ps.pkg.packageName));
        return 0;
    }
}
