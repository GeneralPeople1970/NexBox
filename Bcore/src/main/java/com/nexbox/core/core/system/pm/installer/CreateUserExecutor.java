package com.nexbox.core.core.system.pm.installer;

import com.nexbox.core.utils.Slog;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.env.UserDataFiles;
import com.nexbox.core.core.system.pm.BPackageSettings;
import com.nexbox.core.entity.pm.InstallOption;


public class CreateUserExecutor implements Executor {

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        try {
            UserDataFiles.deleteTree(BEnvironment.getDataLibDir(packageName, userId));
            UserDataFiles.createDirectory(BEnvironment.getDataDir(packageName, userId));
            UserDataFiles.createDirectory(BEnvironment.getDataCacheDir(packageName, userId));
            UserDataFiles.createDirectory(BEnvironment.getDataFilesDir(packageName, userId));
            UserDataFiles.createDirectory(BEnvironment.getDataDatabasesDir(packageName, userId));
            UserDataFiles.createDirectory(BEnvironment.getDeDataDir(packageName, userId));
            return 0;
        } catch (Exception failure) {
            Slog.e("CreateUserExecutor", "Container operation failed", failure);
            return -1;
        }
    }
}
