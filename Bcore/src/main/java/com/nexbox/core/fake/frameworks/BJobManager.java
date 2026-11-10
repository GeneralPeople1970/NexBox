package com.nexbox.core.fake.frameworks;

import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.am.IBJobManagerService;

public class BJobManager extends CoreServiceManager<IBJobManagerService> {
    private static final BJobManager INSTANCE = new BJobManager();
    public static BJobManager get() { return INSTANCE; }
    @Override
    protected String getServiceName() { return ServiceManager.JOB_MANAGER; }
}
