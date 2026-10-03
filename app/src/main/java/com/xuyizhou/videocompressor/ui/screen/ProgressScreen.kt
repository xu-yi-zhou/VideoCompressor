/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xuyizhou.videocompressor.ui.viewmodel.CompressStatus
import com.xuyizhou.videocompressor.ui.viewmodel.CompressViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    viewModel: CompressViewModel,
    onDone: (String) -> Unit,
    onError: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    val progress = when (val status = uiState.status) {
        is CompressStatus.Running -> status.progress
        else -> 0f
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300)
    )

    val status = uiState.status
    LaunchedEffect(status) {
        when (status) {
            is CompressStatus.Done -> onDone(status.outputPath)
            is CompressStatus.Error -> onError()
            else -> {}
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshThermal()
            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("压缩中") },
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
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(160.dp),
                strokeWidth = 8.dp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = run {
                    val pct = animatedProgress * 100
                    if (pct > 0f && pct < 10f) String.format(java.util.Locale.ROOT, "%.1f%%", pct)
                    else "${pct.toInt()}%"
                },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "正在压缩视频...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "请勿关闭应用，保持前台运行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(28.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    uiState.deviceProfile?.let { profile ->
                        Text(
                            text = buildString {
                                append(profile.displaySoc)
                                append("  ·  ")
                                append(if (profile.selectedMime.endsWith("hevc")) "H.265" else "H.264")
                                if (profile.supportsConstantQuality) append("  ·  恒定质量")
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = "温度状态：${uiState.thermalLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
