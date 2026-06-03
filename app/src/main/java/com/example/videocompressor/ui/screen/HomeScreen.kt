package com.example.videocompressor.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.videocompressor.data.model.CompressConfig
import com.example.videocompressor.ui.viewmodel.CompressStatus
import com.example.videocompressor.ui.viewmodel.CompressViewModel
import java.io.File

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
            TopAppBar(
                title = { Text("视频压缩") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 视频选择区域
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { videoPicker.launch(arrayOf("video/*")) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (uiState.videoInfo != null) "已选择视频" else "点击选择视频",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "支持 MP4 / MOV 格式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 视频信息
            uiState.videoInfo?.let { info ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("文件信息", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("文件名", info.name)
                        InfoRow("大小", formatSize(info.size))
                        InfoRow("时长", formatDuration(info.durationMs))
                        InfoRow("分辨率", "${info.width} x ${info.height}")
                        InfoRow("编码", info.codec)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                enabled = uiState.videoInfo != null
            ) {
                Text("开始压缩", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun CompressSettingsCard(
    config: CompressConfig,
    onConfigChange: (CompressConfig) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("压缩设置", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            // 质量选择
            Text("画质", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            CompressConfig.Quality.entries.forEach { quality ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = config.quality == quality,
                        onClick = { onConfigChange(config.copy(quality = quality)) }
                    )
                    Text(quality.label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 分辨率选择
            Text("分辨率", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            CompressConfig.Resolution.entries.forEach { resolution ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = config.resolution == resolution,
                        onClick = { onConfigChange(config.copy(resolution = resolution)) }
                    )
                    Text(resolution.label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 编码器选择
            Text("编码器", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            CompressConfig.Encoder.entries.forEach { encoder ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = config.encoder == encoder,
                        onClick = { onConfigChange(config.copy(encoder = encoder)) }
                    )
                    Text(encoder.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
