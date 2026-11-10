package com.nexbox.core.core.system.pm;

import android.os.Bundle;

// Private transport; Android's hidden installer AIDL varies across platform releases.
interface IBGuestPackageInstaller {
    Bundle call(int sessionId, String operation, in Bundle arguments);
}
