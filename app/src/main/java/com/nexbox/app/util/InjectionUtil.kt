package com.nexbox.app.util

import com.nexbox.app.data.AppsRepository
import com.nexbox.app.view.list.ListFactory

object InjectionUtil {

    private val appsRepositoryDelegate = lazy { AppsRepository() }

    fun getAppsRepository(): AppsRepository = appsRepositoryDelegate.value

    fun trimAppIcons(level: Int) {
        if (appsRepositoryDelegate.isInitialized()) appsRepositoryDelegate.value.onTrimMemory(level)
    }

    fun invalidateIconConfiguration() {
        if (appsRepositoryDelegate.isInitialized()) appsRepositoryDelegate.value.onConfigurationChanged()
    }

    fun invalidateUserIcons(userId: Int, createTime: Long?) {
        if (appsRepositoryDelegate.isInitialized()) appsRepositoryDelegate.value.invalidateUserIcons(userId, createTime)
    }

    fun getListFactory(): ListFactory {
        return ListFactory(getAppsRepository())
    }

}
