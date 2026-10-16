package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R

@Composable
fun CustomProviderDialog(
    state: CustomProviderDialogState,
    onIdChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelsChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.isSubmitting) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (state.editing) R.string.provider_edit_custom_title else R.string.provider_add_custom_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.provider_custom_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.id,
                    onValueChange = onIdChange,
                    label = { Text(stringResource(R.string.provider_custom_id_label)) },
                    placeholder = { Text(stringResource(R.string.provider_custom_id_hint)) },
                    singleLine = true,
                    enabled = !state.isBusy && !state.editing,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.provider_custom_name_label)) },
                    placeholder = { Text(stringResource(R.string.provider_custom_name_hint)) },
                    singleLine = true,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text(stringResource(R.string.provider_custom_base_url_label)) },
                    placeholder = { Text(stringResource(R.string.provider_custom_base_url_hint)) },
                    singleLine = true,
                    enabled = !state.isBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text(stringResource(R.string.api_key_hint)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (state.hasStoredApiKey) {
                                    R.string.provider_custom_api_key_stored_help
                                } else {
                                    R.string.provider_custom_api_key_help
                                },
                            ),
                        )
                    },
                    singleLine = true,
                    enabled = !state.isBusy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onFetchModels, enabled = !state.isBusy) {
                        Text(stringResource(R.string.provider_custom_fetch_models))
                    }
                    if (state.isFetchingModels) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
                state.fetchedModelCount?.let { count ->
                    Text(
                        pluralStringResource(R.plurals.provider_custom_fetch_success, count, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.fetchError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = state.models,
                    onValueChange = onModelsChange,
                    label = { Text(stringResource(R.string.provider_custom_models_label)) },
                    placeholder = { Text(stringResource(R.string.provider_custom_models_hint)) },
                    maxLines = 6,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = !state.isBusy) {
                Text(stringResource(if (state.editing) R.string.save else R.string.continue_label))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSubmitting) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
