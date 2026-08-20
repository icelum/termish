package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.termish.data.SECRET_SERVICE
import dev.termish.data.SecretStore
import dev.termish.data.VncHost
import dev.termish.data.HostRepository
import dev.termish.data.newId
import dev.termish.data.secretAccountFor
import dev.termish.ui.theme.Spacing

/** VNC 主机编辑页：名称/地址/Display 序号/密码/只读模式。 */
@Composable
fun VncHostEditScreen(
    existing: VncHost?,
    repository: HostRepository,
    onSave: (VncHost) -> Unit,
    onDelete: (VncHost) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var hostname by remember { mutableStateOf(existing?.hostname ?: "") }
    var display by remember { mutableStateOf((existing?.display ?: 0).toString()) }
    var password by remember {
        mutableStateOf(
            existing?.let { SecretStore.get(SECRET_SERVICE, secretAccountFor(it.id, "vncPassword")) } ?: ""
        )
    }
    var viewOnly by remember { mutableStateOf(existing?.viewOnly ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 覆盖层拦截系统返回：先关编辑页，不穿透退 app
    PlatformBackHandler(enabled = true) { onBack() }

    // 必须画背景：覆盖层叠在底页之上，无背景会透明透出下层内容
    Column(Modifier.fillMaxSize().imePadding().background(MaterialTheme.colorScheme.background)) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                if (existing == null) s.vnc.editTitleNew else s.vnc.editTitleEdit,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth(),
                label = { Text(s.vnc.fieldName) }, singleLine = true,
            )
            OutlinedTextField(
                hostname, { hostname = it }, Modifier.fillMaxWidth(),
                label = { Text(s.vnc.fieldAddress) }, singleLine = true,
            )
            OutlinedTextField(
                display, { display = it.filter { c -> c.isDigit() }.take(2) }, Modifier.fillMaxWidth(),
                label = { Text(s.vnc.fieldDisplay) }, singleLine = true,
            )
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text(s.vnc.fieldPassword) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.vnc.fieldViewOnly, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        s.vnc.viewOnlyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = viewOnly, onCheckedChange = { viewOnly = it })
            }

            Button(
                onClick = {
                    val id = existing?.id ?: newId()
                    val host = VncHost(
                        id = id,
                        name = name.trim().ifBlank { hostname.trim() },
                        hostname = hostname.trim(),
                        display = display.toIntOrNull() ?: 0,
                        viewOnly = viewOnly,
                        colorIndex = existing?.colorIndex ?: 0,
                        createdAt = existing?.createdAt ?: nowMillis(),
                        lastConnectedAt = existing?.lastConnectedAt ?: 0L,
                    )
                    if (password.isNotBlank()) {
                        SecretStore.set(SECRET_SERVICE, secretAccountFor(id, "vncPassword"), password)
                    }
                    repository.upsertVncHost(host)
                    onSave(host)
                },
                enabled = hostname.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(s.vnc.save) }

            if (existing != null) {
                if (confirmDelete) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                        TextButton({
                            SecretStore.delete(SECRET_SERVICE, secretAccountFor(existing.id, "vncPassword"))
                            repository.deleteVncHost(existing.id)
                            onDelete(existing)
                        }) { Text(s.vnc.delete, color = MaterialTheme.colorScheme.error) }
                        TextButton({ confirmDelete = false }) { Text(s.terminalCancel) }
                    }
                } else {
                    TextButton(
                        { confirmDelete = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text(s.vnc.delete, color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.padding(bottom = Spacing.Xl))
        }
    }
}

private fun nowMillis(): Long =
    kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
