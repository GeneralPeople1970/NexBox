package com.nexbox.core.core.system.pm.installer;

import com.nexbox.core.utils.Slog;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.env.UserDataFiles;
import com.nexbox.core.core.system.pm.BPackageSettings;
import com.nexbox.core.entity.pm.InstallOption;


public class RemoveAppExecutor implements Executor {
    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        try {
            UserDataFiles.deleteTree(BEnvironment.getAppDir(ps.pkg.packageName));
            return 0;
        } catch (Exception failure) {
            Slog.e("RemoveAppExecutor", "Container operation failed", failure);
            return -1;
        }
    }
}
