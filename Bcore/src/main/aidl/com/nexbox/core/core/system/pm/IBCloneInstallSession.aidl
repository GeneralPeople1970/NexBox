package com.nexbox.core.core.system.pm;

import com.nexbox.core.entity.pm.InstallResult;

/** A host-owned, one-shot preparation. Committing still validates the target and APKs. */
interface IBCloneInstallSession {
    String getPackageName();
    InstallResult commit(int userId, long expectedUserCreateTime);
    void cancel();
    String prepare();
}
