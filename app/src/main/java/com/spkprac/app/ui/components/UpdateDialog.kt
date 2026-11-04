package com.spkprac.app.ui.components

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.spkprac.app.R
import com.spkprac.app.net.UpdateChecker
import com.spkprac.app.net.UpdateInfo
import com.spkprac.app.ui.theme.Brand
import com.spkprac.app.ui.theme.isDarkScheme

/**
 * "New version available" sheet: shows the release notes and lets the user either hand the APK to
 * the system download manager or open the release page in a browser.
 *
 * Install is intentionally left to the system: tapping the completed download notification launches
 * the package installer, so the app itself never needs REQUEST_INSTALL_PACKAGES.
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String,
    useProxy: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val accent = Brand.accent(isDarkScheme())
    val downloadUrl = UpdateChecker.acceleratedDownloadUrl(info, useProxy)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(Brush.linearGradient(accent), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.NewReleases,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.upd_new_version),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.upd_current_fmt, currentVersion),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillLabel(
                        text = currentVersion,
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "  →  ",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PillLabel(
                        text = info.version,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.weight(1f))
                    info.apkSize?.let { bytes ->
                        Text(
                            formatSize(bytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                if (info.notes.isNotBlank()) {
                    Text(
                        stringResource(R.string.upd_notes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.height(240.dp)) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            item { MarkdownText(info.notes) }
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (info.apkUrl == null) {
                            openUrl(context, info.releaseUrl)
                            onDismiss()
                        } else if (startApkDownload(context, downloadUrl, "SpkPrac-${info.version}.apk")) {
                            Toast.makeText(context, R.string.upd_downloading, Toast.LENGTH_LONG).show()
                            onDismiss()
                        } else {
                            openUrl(context, info.releaseUrl)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(
                        if (info.apkUrl == null) Icons.Default.NewReleases else Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (info.apkUrl == null) R.string.upd_open_page else R.string.upd_download
                        )
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (info.apkUrl != null) {
                        TextButton(onClick = {
                            openUrl(context, info.releaseUrl)
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.upd_open_page))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp).size(14.dp)
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.upd_later)) }
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Hands the APK to the system DownloadManager; the notification installs it when tapped. */
private fun startApkDownload(context: Context, url: String, fileName: String): Boolean = runCatching {
    val manager = context.getSystemService(DownloadManager::class.java) ?: return false
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle("SpkPrac")
        .setDescription(fileName)
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
    manager.enqueue(request)
    true
}.getOrDefault(false)

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
