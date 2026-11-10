package com.nexbox.app.view.accounts

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nexbox.app.R
import com.nexbox.app.ui.components.Button
import com.nexbox.app.ui.components.CircularProgressIndicator
import com.nexbox.app.ui.components.Icon
import com.nexbox.app.ui.components.ListItem
import com.nexbox.app.ui.components.NexBoxEmptyState
import com.nexbox.app.ui.components.NexBoxTopAppBar
import com.nexbox.app.ui.components.Scaffold
import com.nexbox.app.ui.components.Text
import com.nexbox.app.ui.theme.NexBoxAppearanceHost
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.core.core.system.accounts.AccountInteractionContract
import com.nexbox.core.core.system.accounts.IBAccountManagerService
import com.nexbox.core.fake.frameworks.BAccountManager
import com.nexbox.core.utils.compat.BundleCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Account names come only from a core-issued request; Intent extras never supply choices. */
class AccountChoiceActivity : ComponentActivity() {
    private val uiLifetime = Binder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val capability = BundleCompat.getBinder(intent, AccountInteractionContract.EXTRA_REQUEST)
        if (capability == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            NexBoxAppearanceHost {
                AccountChoice(capability, uiLifetime) { result ->
                    if (result != null) setResult(RESULT_OK, Intent().putExtras(result))
                    finish()
                }
            }
        }
    }
}

private data class AccountChoiceState(
    val service: IBAccountManagerService? = null,
    val names: List<String> = emptyList(),
    val packageName: String = "",
    val grantToken: Boolean = false,
    val failed: Boolean = false
)

@Composable
private fun AccountChoice(capability: IBinder, uiLifetime: IBinder, onFinish: (Bundle?) -> Unit) {
    val state by produceState<AccountChoiceState?>(null, capability) {
        value = try {
            withContext(Dispatchers.IO) {
                val service = checkNotNull(BAccountManager.get().service)
                val result = service.getAccountInteraction(capability, uiLifetime)
                val names = result.getStringArray(AccountInteractionContract.ACCOUNT_NAMES)?.toList().orEmpty()
                check(names.isNotEmpty())
                AccountChoiceState(service, names, result.getString(AccountInteractionContract.CALLER_PACKAGE).orEmpty(),
                    result.getBoolean(AccountInteractionContract.GRANT_TOKEN))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AccountChoiceState(failed = true)
        }
    }
    var selected by rememberSaveable(capability) { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state?.names) {
        if (state?.names?.size == 1) selected = 0
    }
    fun finishRequest(choice: Int?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val service = state?.service ?: BAccountManager.get().service
                    if (choice == null) {
                        try { service?.cancelAccountInteraction(capability) } catch (_: Exception) { }
                        null
                    } else checkNotNull(service).completeAccountInteraction(capability, choice)
                }
                onFinish(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
                busy = false
            }
        }
    }
    BackHandler { finishRequest(null) }
    Scaffold(topBar = {
        NexBoxTopAppBar(title = stringResource(R.string.account_choose_title), onBack = { finishRequest(null) })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val loaded = state
            when {
                loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                failed || loaded.failed -> NexBoxEmptyState(message = stringResource(R.string.account_request_expired))
                else -> Column(Modifier.fillMaxSize().padding(NexBoxSpacing.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(NexBoxSpacing.md)) {
                    Text(stringResource(if (loaded.grantToken) R.string.account_grant_message else R.string.account_choose_message,
                        loaded.packageName))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(loaded.names) { index, name ->
                            ListItem(headlineContent = { Text(name) },
                                modifier = Modifier.clickable(enabled = !busy) { selected = index },
                                trailingContent = { if (selected == index) Icon(Icons.Default.Check, null) })
                        }
                    }
                    Button(onClick = { finishRequest(selected) }, enabled = !busy && selected in loaded.names.indices,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}
