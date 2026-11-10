package com.nexbox.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexbox.app.R
import com.nexbox.app.data.UsersRepository
import com.nexbox.app.util.NexLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A live group label for pages that only receive an id through navigation. */
@Composable
fun rememberUserDisplayName(userId: Int): String {
    val changes by UsersRepository.changes.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val fallback = stringResource(R.string.user_prefix, userId)
    val name by produceState(fallback, userId, changes, locale) {
        value = try {
            withContext(Dispatchers.IO) { UsersRepository.displayName(userId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NexLog.w("Users", "action=readDisplayName userId=$userId", error)
            fallback
        }
    }
    return name
}
