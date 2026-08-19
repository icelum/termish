package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.termish.data.VncHost
import dev.termish.util.monospaceFontFamily

/**
 * 新建 VNC 连接覆盖层：VNC 主机列表（点选连接）+ 右上「+」新增主机。
 * 与 SftpHostPickerOverlay 同款交互。
 */
@Composable
fun VncHostPickerOverlay(
    hosts: List<VncHost>,
    onDismiss: () -> Unit,
    onSelect: (VncHost) -> Unit,
    onAddHost: () -> Unit,
) {
    val s = LocalAppStrings.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = s.navBack)
                }
                Text(
                    s.vnc.newTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = monospaceFontFamily(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAddHost) {
                    Icon(Icons.Filled.Add, contentDescription = s.hostsAdd)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                s.vnc.sectionTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
            )
            if (hosts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        s.vnc.empty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(hosts, key = { it.id }) { host ->
                        VncHostRow(host = host, onClick = { onSelect(host) })
                    }
                }
            }
        }
    }
}

@Composable
fun VncHostRow(host: VncHost, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val s = LocalAppStrings.current
    val address = "${host.hostname}:${host.port}"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E5E8C)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.DesktopWindows, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    host.name.ifBlank { address },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(address)
                        if (host.viewOnly) append(" · ").append(s.vnc.fieldViewOnly)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
        }
    }
}
