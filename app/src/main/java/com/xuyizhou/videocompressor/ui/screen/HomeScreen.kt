/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xuyizhou.videocompressor.BuildConfig
import com.xuyizhou.videocompressor.data.model.CompressConfig
import com.xuyizhou.videocompressor.data.model.GithubRelease
import com.xuyizhou.videocompressor.data.netdisk.NetdiskConfig
import com.xuyizhou.videocompressor.ui.viewmodel.CompressViewModel
import com.xuyizhou.videocompressor.ui.viewmodel.UpdateUiState
import com.xuyizhou.videocompressor.util.ApkInstaller

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: CompressViewModel,
    onStartCompress: () -> Unit,
    onOpenAuth: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val context = LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onVideosSelected(uris)
        }
    }
    val pickVideo = { videoPicker.launch(arrayOf("video/*")) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("视频工具箱", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VideoPickerCard(
                    hasVideo = uiState.videos.isNotEmpty(),
                    videoCount = uiState.videos.size,
                    onClick = pickVideo
                )
                if (uiState.videos.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    uiState.videos.forEachIndexed { index, info ->
                        FileInfoCard(info, onRemove = { viewModel.removeVideo(index) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CompressTab(uiState, updateState, viewModel, onStartCompress, onOpenAuth)
            }
        }
    }
}

@Composable
private fun CompressTab(
    uiState: com.xuyizhou.videocompressor.ui.viewmodel.CompressUiState,
    updateState: UpdateUiState,
    viewModel: CompressViewModel,
    onStartCompress: () -> Unit,
    onOpenAuth: () -> Unit
) {
    val context = LocalContext.current

    CompressSettingsCard(
        config = uiState.config,
        onConfigChange = { viewModel.updateConfig(it) }
    )

    Spacer(modifier = Modifier.height(16.dp))

    NetdiskCard(
        authorized = uiState.netdiskName != null,
        baiduName = uiState.netdiskName,
        autoUpload = uiState.autoUpload,
        onToggleUpload = { viewModel.setAutoUpload(it) },
        onOpenAuth = onOpenAuth,
        onClearAuth = { viewModel.clearNetdiskAuth() }
    )

    Spacer(modifier = Modifier.height(16.dp))

    UpdateCard(updateState = updateState, onCheck = { viewModel.checkForUpdates() })

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = {
            viewModel.startCompress()
            onStartCompress()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        enabled = uiState.videos.isNotEmpty()
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            if (uiState.videos.size > 1) "开始压缩（${uiState.videos.size} 个）" else "开始压缩",
            style = MaterialTheme.typography.titleMedium
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    UpdateDialog(
        state = updateState,
        onDownload = { viewModel.downloadUpdate(it) },
        onCancelDownload = { viewModel.cancelDownload() },
        onInstall = {
            (updateState as? UpdateUiState.Downloaded)?.let { downloaded ->
                when {
                    // 未授权「安装未知应用」→ 引导到系统设置
                    !ApkInstaller.canInstall(context) -> ApkInstaller.openInstallSettings(context)
                    // 安装器拉起成功 → 标记防重复；失败 → 浏览器打开 release 页兜底
                    ApkInstaller.installApk(context, downloaded.file) ->
                        viewModel.markInstallLaunched()
                    else -> ApkInstaller.openInBrowser(context, downloaded.release.htmlUrl)
                }
            }
        },
        onDismiss = { viewModel.dismissUpdate() }
    )
}

/** 关于与更新卡片：当前版本 + 检查结果状态行 + 手动检查按钮。 */
@Composable
private fun UpdateCard(updateState: UpdateUiState, onCheck: () -> Unit) {
    SectionCard(title = "关于与更新", icon = Icons.Outlined.SystemUpdate) {
        InfoRow("当前版本", BuildConfig.VERSION_NAME)
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (updateState) {
                        is UpdateUiState.Checking -> "正在检查更新…"
                        is UpdateUiState.CheckFailed -> "检查失败：${updateState.message}"
                        is UpdateUiState.Downloading -> "正在下载更新…"
                        else -> "已是最新版本"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (updateState is UpdateUiState.CheckFailed)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (updateState is UpdateUiState.Available) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "发现新版本 ${updateState.release.tagName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onCheck,
                shape = RoundedCornerShape(12.dp),
                enabled = updateState !is UpdateUiState.Checking &&
                    updateState !is UpdateUiState.Downloading
            ) { Text("检查更新") }
        }
    }
}

/** 自更新对话框：Available（发现新版）/ Downloading（下载进度）/ Downloaded（安装）/ DownloadFailed（重试）。 */
@Composable
private fun UpdateDialog(
    state: UpdateUiState,
    onDownload: (GithubRelease) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现新版本 ${state.release.tagName}") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 240.dp)
                ) {
                    InfoRow("当前版本", BuildConfig.VERSION_NAME)
                    InfoRow("新版本", state.release.tagName)
                    state.release.body?.takeIf { it.isNotBlank() }?.let { body ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("更新内容：", style = MaterialTheme.typography.labelLarge)
                        Text(
                            body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onDownload(state.release) }) { Text("立即下载") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("暂不") } }
        )

        is UpdateUiState.Downloading -> AlertDialog(
            // 下载中不允许点外部关闭，只能「取消下载」，防止状态不一致
            onDismissRequest = {},
            title = { Text("正在下载更新") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (state.total > 0)
                            "正在下载 ${formatSize(state.bytesRead)} / ${formatSize(state.total)}"
                        else "正在下载 ${formatSize(state.bytesRead)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancelDownload) { Text("取消下载") } }
        )

        is UpdateUiState.Downloaded -> AlertDialog(
            onDismissRequest = {},
            title = { Text(if (state.installLaunched) "安装页面已打开" else "下载完成") },
            text = {
                Text(
                    if (state.installLaunched)
                        "请在系统安装器中完成安装，然后重新打开应用"
                    else "新版本 APK 已下载，是否立即安装？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                if (!state.installLaunched) {
                    TextButton(onClick = onInstall) { Text("立即安装") }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )

        is UpdateUiState.DownloadFailed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("下载失败") },
            text = { Text(state.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { onDownload(state.release) }) { Text("重试") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )

        else -> {}
    }
}

@Composable
private fun NetdiskCard(
    authorized: Boolean,
    baiduName: String?,
    autoUpload: Boolean,
    onToggleUpload: (Boolean) -> Unit,
    onOpenAuth: () -> Unit,
    onClearAuth: () -> Unit
) {
    SectionCard(title = "百度网盘", icon = Icons.Outlined.CloudUpload) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (authorized) "已授权：${baiduName ?: "百度账号"}" else "未授权",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "压缩完成后自动上传到应用目录「${NetdiskConfig.REMOTE_DIR}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (authorized) {
                TextButton(onClick = onClearAuth) { Text("解除授权") }
            } else {
                Button(onClick = onOpenAuth, shape = RoundedCornerShape(12.dp)) { Text("去授权") }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("压缩后自动上传", style = MaterialTheme.typography.bodyMedium)
                if (!authorized) {
                    Text(
                        "需先授权百度网盘",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = autoUpload && authorized,
                onCheckedChange = onToggleUpload,
                enabled = authorized
            )
        }
    }
}

@Composable
private fun FileInfoCard(
    info: com.xuyizhou.videocompressor.data.model.VideoInfo,
    onRemove: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${formatSize(info.size)} · ${formatDuration(info.durationMs)} · ${info.width}×${info.height} · ${info.codec}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPickerCard(hasVideo: Boolean, videoCount: Int, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (hasVideo)
                lerp(MaterialTheme.colorScheme.secondaryContainer, Color.Black, 0.18f)
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            ) {
                Icon(
                    if (hasVideo) Icons.Outlined.CheckCircle else Icons.Outlined.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp),
                    tint = if (hasVideo)
                        MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = if (hasVideo) "已选择 $videoCount 个视频" else "点击选择视频",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (hasVideo) "点击可重新选择（支持多选）" else "支持 MP4 / MOV 等常见格式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CompressSettingsCard(
    config: CompressConfig,
    onConfigChange: (CompressConfig) -> Unit
) {
    SectionCard(title = "压缩设置", icon = Icons.Outlined.HighQuality) {
        ChipGroup(
            title = "画质",
            icon = Icons.Outlined.HighQuality,
            options = CompressConfig.Quality.entries,
            selected = config.quality,
            label = {
                when (it) {
                    CompressConfig.Quality.HIGH -> "高质量"
                    CompressConfig.Quality.BALANCED -> "均衡"
                    CompressConfig.Quality.SMALL -> "最小体积"
                }
            },
            onSelect = { onConfigChange(config.copy(quality = it)) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        ChipGroup(
            title = "分辨率",
            icon = Icons.Outlined.AspectRatio,
            options = CompressConfig.Resolution.entries,
            selected = config.resolution,
            label = { it.label },
            onSelect = { onConfigChange(config.copy(resolution = it)) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        ChipGroup(
            title = "编码器",
            icon = Icons.Outlined.Memory,
            options = CompressConfig.Encoder.entries,
            selected = config.encoder,
            label = { it.label },
            onSelect = { onConfigChange(config.copy(encoder = it)) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(
    title: String,
    icon: ImageVector,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            label(option),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(32.dp)
                )
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(title, fontWeight = FontWeight.Bold)
                }
                trailing?.invoke()
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
