package com.example.videocompressor.ui.screen

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
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.videocompressor.data.model.CompressConfig
import com.example.videocompressor.ui.viewmodel.CompressViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: CompressViewModel,
    onStartCompress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.onVideoSelected(it)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("视频压缩", fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 视频选择区域
            VideoPickerCard(
                hasVideo = uiState.videoInfo != null,
                onClick = { videoPicker.launch(arrayOf("video/*")) }
            )

            // 本机编码能力（设备感知自适应编码的检测结果）
            uiState.deviceProfile?.let { profile ->
                Spacer(modifier = Modifier.height(12.dp))
                DeviceCapabilityCard(profile, uiState.thermalLabel)
            }

            // 视频信息
            uiState.videoInfo?.let { info ->
                Spacer(modifier = Modifier.height(12.dp))
                SectionCard(title = "文件信息", icon = Icons.Outlined.Movie) {
                    InfoRow("文件名", info.name)
                    InfoRow("大小", formatSize(info.size))
                    InfoRow("时长", formatDuration(info.durationMs))
                    InfoRow("分辨率", "${info.width} x ${info.height}")
                    InfoRow("编码", info.codec)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 压缩设置
            CompressSettingsCard(
                config = uiState.config,
                onConfigChange = { viewModel.updateConfig(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 开始压缩按钮
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
