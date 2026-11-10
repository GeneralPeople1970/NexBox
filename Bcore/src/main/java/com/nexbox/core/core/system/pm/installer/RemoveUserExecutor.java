package com.nexbox.core.core.system.pm.installer;

import com.nexbox.core.utils.Slog;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.env.UserDataFiles;
import com.nexbox.core.core.system.pm.BPackageSettings;
import com.nexbox.core.entity.pm.InstallOption;


public class RemoveUserExecutor implements Executor {

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        
        try {
            UserDataFiles.deleteTree(BEnvironment.getDataDir(packageName, userId));
            UserDataFiles.deleteTree(BEnvironment.getDeDataDir(packageName, userId));
            UserDataFiles.deleteTree(BEnvironment.getExternalDataDir(packageName, userId));
            UserDataFiles.deleteTree(BEnvironment.getExternalObbDir(packageName, userId));
            return 0;
        } catch (Exception failure) {
            Slog.e("RemoveUserExecutor", "Container operation failed", failure);
            return -1;
        }
    }
}
