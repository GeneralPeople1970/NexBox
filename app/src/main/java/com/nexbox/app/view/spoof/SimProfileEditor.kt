package com.nexbox.app.view.spoof

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import com.nexbox.app.ui.icons.filled.SimCard
import com.nexbox.app.ui.icons.filled.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.nexbox.app.R
import com.nexbox.app.ui.components.*
import com.nexbox.app.ui.theme.NexBoxSpacing
import com.nexbox.core.fake.device.SimCarrier
import com.nexbox.core.fake.device.SimProfile

@Composable
fun SimProfileEditor(value: SimProfile, enabled: Boolean = true, onChange: (SimProfile) -> Unit) {
    val currentValue by rememberUpdatedState(value)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnChange by rememberUpdatedState(onChange)
    val update: (SimProfile) -> Unit = { if (currentEnabled) currentOnChange(it) }
    Spacer(Modifier.height(NexBoxSpacing.lg))
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sim_profile_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = value.enabled, onCheckedChange = { update(currentValue.withEnabled(it)) }, enabled = enabled)
    }
    if (!value.enabled) return
    val countOptions = listOf(stringResource(R.string.sim_none), stringResource(R.string.sim_single), stringResource(R.string.sim_dual))
    NexBoxPickerRow(Icons.Default.SimCard, stringResource(R.string.sim_card_count), countOptions[value.cards.size],
        countOptions, value.cards.size, { update(currentValue.withCount(it)) }, showDivider = false)
    value.cards.forEach { card ->
      key(card.slotIndex, card.subscriptionId) {
        var phoneFocused by remember { mutableStateOf(false) }
        fun updateCard(transform: (SimProfile.Card) -> SimProfile.Card) {
            if (!currentEnabled) return
            val latest = currentValue
            val existing = latest.cards.firstOrNull {
                it.slotIndex == card.slotIndex && it.subscriptionId == card.subscriptionId
            } ?: return
            currentOnChange(latest.withCard(transform(existing)))
        }
        Spacer(Modifier.height(NexBoxSpacing.md))
        Text(stringResource(R.string.sim_slot_title, card.slotIndex + 1), style = MaterialTheme.typography.titleSmall)
        CarrierPicker(stringResource(R.string.sim_home_carrier), card.home, enabled) { carrier ->
            updateCard { it.withHome(carrier) }
        }
        OutlinedTextField(value = card.phoneNumber, onValueChange = { phone -> updateCard { it.withPhoneNumber(phone) } },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true, label = { Text(stringResource(R.string.sim_phone)) },
            isError = !card.isValidPhoneNumber(),
            supportingText = { if (!card.isValidPhoneNumber()) Text(stringResource(R.string.sim_phone_invalid)) },
            trailingIcon = {
                IconButton(enabled = enabled, onClick = { updateCard { it.withRandomPhoneNumber() } }) {
                    Icon(Icons.Default.Shuffle, stringResource(R.string.sim_random_field, stringResource(R.string.sim_phone)))
                }
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                if (phoneFocused && !state.isFocused) {
                    updateCard { latest -> latest.withPhoneNumber(latest.formattedPhoneNumber()) }
                }
                phoneFocused = state.isFocused
            })
        Spacer(Modifier.height(NexBoxSpacing.md))
        OutlinedTextField(value = card.imsi, onValueChange = { value -> updateCard { it.withImsi(value) } },
            enabled = enabled, singleLine = true, label = { Text("IMSI") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !card.isValidImsi(),
            supportingText = { if (!card.isValidImsi()) Text(stringResource(R.string.sim_imsi_invalid)) },
            trailingIcon = {
                IconButton(enabled = enabled, onClick = { updateCard { it.withRandomImsi() } }) {
                    Icon(Icons.Default.Shuffle, stringResource(R.string.sim_random_field, "IMSI"))
                }
            }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(NexBoxSpacing.md))
        OutlinedTextField(value = card.iccid, onValueChange = { value -> updateCard { it.withIccid(value) } },
            enabled = enabled, singleLine = true, label = { Text("ICCID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !card.isValidIccid(),
            supportingText = { if (!card.isValidIccid()) Text(stringResource(R.string.sim_iccid_invalid)) },
            trailingIcon = {
                IconButton(enabled = enabled, onClick = { updateCard { it.withRandomIccid() } }) {
                    Icon(Icons.Default.Shuffle, stringResource(R.string.sim_random_field, "ICCID"))
                }
            }, modifier = Modifier.fillMaxWidth())
      }
    }
    if (value.validationError() != null) Text(stringResource(R.string.sim_profile_invalid), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun CarrierPicker(title: String, current: SimCarrier, enabled: Boolean, onChange: (SimCarrier) -> Unit) {
    val choices = SimCarrier.PRESETS
    NexBoxPickerRow(Icons.Default.SimCard, title, current.label(), choices.map { it.label() },
        choices.indexOfFirst { it.numeric() == current.numeric() }, { if (enabled) onChange(choices[it]) },
        showDivider = false)
}
