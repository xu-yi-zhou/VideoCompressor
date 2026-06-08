package com.example.videocompressor.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.videocompressor.data.model.CompressConfig
import com.example.videocompressor.ui.viewmodel.CompressViewModel
import com.example.videocompressor.ui.viewmodel.TranscribeStatus
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: CompressViewModel,
    onStartCompress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.onVideoSelected(it)
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val tabs = listOf("压缩" to Icons.Default.PlayArrow, "字幕 / 节点" to Icons.Outlined.Subtitles)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                tabs.forEachIndexed { index, (title, icon) ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }
            when (selectedTab) {
                0 -> CompressTab(uiState, viewModel, pickVideo, onStartCompress)
                else -> TranscribeTab(uiState, viewModel, pickVideo)
            }
        }
    }
}

@Composable
private fun CompressTab(
    uiState: com.example.videocompressor.ui.viewmodel.CompressUiState,
    viewModel: CompressViewModel,
    onPickVideo: () -> Unit,
    onStartCompress: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VideoPickerCard(hasVideo = uiState.videoInfo != null, onClick = onPickVideo)

        // 本机编码能力（设备感知自适应编码的检测结果）
        uiState.deviceProfile?.let { profile ->
            Spacer(modifier = Modifier.height(12.dp))
            DeviceCapabilityCard(profile, uiState.thermalLabel)
        }

        uiState.videoInfo?.let { info ->
            Spacer(modifier = Modifier.height(12.dp))
            FileInfoCard(info)
        }

        Spacer(modifier = Modifier.height(12.dp))

        CompressSettingsCard(
            config = uiState.config,
            onConfigChange = { viewModel.updateConfig(it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                viewModel.startCompress()
                onStartCompress()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = uiState.videoInfo != null
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("开始压缩", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TranscribeTab(
    uiState: com.example.videocompressor.ui.viewmodel.CompressUiState,
    viewModel: CompressViewModel,
    onPickVideo: () -> Unit
) {
    val context = LocalContext.current
    val running = uiState.transcribeStatus is TranscribeStatus.Running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VideoPickerCard(hasVideo = uiState.videoInfo != null, onClick = onPickVideo)

        uiState.videoInfo?.let { info ->
            Spacer(modifier = Modifier.height(12.dp))
            FileInfoCard(info)
        }

        Spacer(modifier = Modifier.height(12.dp))

        SectionCard(title = "字幕 / 节点", icon = Icons.Outlined.Subtitles) {
            Text(
                "由局域网内的电脑端服务转写。手机抽取音频上传，电脑用 faster-whisper 识别并切分章节。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = { viewModel.updateServerUrl(it) },
                label = { Text("电脑服务地址") },
                placeholder = { Text("例如 192.168.1.20:8000") },
                leadingIcon = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { viewModel.startTranscribe() },
                    enabled = uiState.videoInfo != null && !running,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("软字幕(SRT)") }
                Button(
                    onClick = { viewModel.startBurn() },
                    enabled = uiState.videoInfo != null && !running,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("烧进视频") }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        TranscribeStatusCard(uiState.transcribeStatus, context) { viewModel.resetTranscribe() }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TranscribeStatusCard(
    status: TranscribeStatus,
    context: android.content.Context,
    onReset: () -> Unit
) {
    when (status) {
        is TranscribeStatus.Idle -> Unit

        is TranscribeStatus.Running -> SectionCard(title = "处理进度", icon = Icons.Outlined.Subtitles) {
            Text(status.stage, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(10.dp))
            if (status.progress != null) {
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "${(status.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is TranscribeStatus.Done -> SectionCard(
            title = "字幕已生成",
            icon = Icons.Outlined.Subtitles,
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                "已保存到应用 Movies 目录：\n${File(status.srtPath).name}" +
                    (status.chaptersPath?.let { "\n${File(it).name}" } ?: ""),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { shareFile(context, File(status.srtPath)) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("分享字幕")
                }
                OutlinedButton(onClick = onReset, shape = RoundedCornerShape(12.dp)) { Text("完成") }
            }
        }

        is TranscribeStatus.DoneVideo -> SectionCard(
            title = "烧字幕完成",
            icon = Icons.Outlined.Movie,
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text("成品已保存到相册（Movies）。", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onReset, shape = RoundedCornerShape(12.dp)) { Text("完成") }
        }

        is TranscribeStatus.Error -> SectionCard(
            title = "处理失败",
            icon = Icons.Outlined.Subtitles,
            containerColor = MaterialTheme.colorScheme.errorContainer
        ) {
            Text(status.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onReset, shape = RoundedCornerShape(12.dp)) { Text("知道了") }
        }
    }
}

@Composable
private fun FileInfoCard(info: com.example.videocompressor.data.model.VideoInfo) {
    SectionCard(title = "文件信息", icon = Icons.Outlined.Movie) {
        InfoRow("文件名", info.name)
        InfoRow("大小", formatSize(info.size))
        InfoRow("时长", formatDuration(info.durationMs))
        InfoRow("分辨率", "${info.width} x ${info.height}")
        InfoRow("编码", info.codec)
    }
}

private fun shareFile(context: android.content.Context, file: File) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享字幕").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun VideoPickerCard(hasVideo: Boolean, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (hasVideo)
                MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            ) {
                Icon(
                    Icons.Outlined.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(14.dp)
                        .size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (hasVideo) "已选择视频，点击可重新选择" else "点击选择视频",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "支持 MP4 / MOV 等常见格式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeviceCapabilityCard(
    profile: com.example.videocompressor.data.model.DeviceProfile,
    thermalLabel: String
) {
    SectionCard(
        title = "本机编码能力",
        icon = Icons.Outlined.Memory,
        containerColor = if (profile.isXring)
            MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        trailing = {
            if (profile.isXring) {
                AssistChip(onClick = {}, label = { Text("玄戒 O1 已适配") })
            }
        }
    ) {
        profile.summaryLines.forEach { line ->
            Text(
                "· $line",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "当前温度状态：$thermalLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            label = { it.label },
            onSelect = { onConfigChange(config.copy(quality = it)) }
        )
        Spacer(modifier = Modifier.height(14.dp))
        ChipGroup(
            title = "分辨率",
            icon = Icons.Outlined.AspectRatio,
            options = CompressConfig.Resolution.entries,
            selected = config.resolution,
            label = { it.label },
            onSelect = { onConfigChange(config.copy(resolution = it)) }
        )
        Spacer(modifier = Modifier.height(14.dp))
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    shape = RoundedCornerShape(12.dp)
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
