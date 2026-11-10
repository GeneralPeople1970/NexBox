package com.nexbox.app.app.rocker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.nexbox.app.data.FakeLocationRepository
import com.nexbox.core.NexBoxCore
import com.nexbox.core.fake.frameworks.BActivityManager
import com.nexbox.core.media.NexBoxMediaContract

/** The host main process alone owns the location preference cache. */
class RockerProvider : ContentProvider() {
    private val repository by lazy { FakeLocationRepository() }
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == NexBoxCore.getHostUid())
        val callerPid = Binder.getCallingPid()
        val identity = Binder.clearCallingIdentity()
        try {
            val owner = checkNotNull(BActivityManager.get().service?.resolveMediaCreationOwner(callerPid))
            val user = owner.getInt(NexBoxMediaContract.COL_OWNER_USER_ID, -1)
            val generation = owner.getLong(NexBoxMediaContract.COL_OWNER_USER_GENERATION, 0)
            val pkg = checkNotNull(owner.getString(NexBoxMediaContract.COL_OWNER_PACKAGE_NAME))
            val success = when (method) {
                "state" -> repository.loadRockerState(user, pkg, generation).let { it.enabled && it.selected && it.point != null }
                "move" -> repository.moveRocker(user, generation, pkg,
                    extras?.getFloat("angle") ?: Float.NaN, extras?.getFloat("distance") ?: Float.NaN)
                else -> false
            }
            return Bundle().apply { putBoolean("success", success) }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
}
