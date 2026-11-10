package com.nexbox.app.view.apps

import com.nexbox.app.data.CloneTarget

/** A displayed list keeps the identity it was loaded for, even while selection changes. */
internal data class OwnedAppList<T>(val owner: CloneTarget, val apps: List<T>) {
    fun actionTarget(selectedUserId: Int, loading: Boolean): CloneTarget? = owner.takeIf {
        !loading && it.createTime > 0L && it.userId == selectedUserId
    }

    fun accepts(target: CloneTarget, selectedUserId: Int, loading: Boolean): Boolean =
        actionTarget(selectedUserId, loading) == target
}
