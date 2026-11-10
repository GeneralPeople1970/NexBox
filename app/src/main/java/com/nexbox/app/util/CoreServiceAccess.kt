package com.nexbox.app.util

import com.nexbox.core.NexBoxCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal fun requireNexBoxServices() {
    check(NexBoxCore.get().waitForServicesAvailable(10_000L)) {
        "NexBox services are unavailable. Please retry."
    }
}

internal suspend fun awaitNexBoxServices() = runInterruptible(Dispatchers.IO) {
    requireNexBoxServices()
}
