package com.example.videocompressor.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.videocompressor.ui.viewmodel.CompressStatus
import com.example.videocompressor.ui.viewmodel.CompressViewModel

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

    // 自动导航
    when (val status = uiState.status) {
        is CompressStatus.Done -> onDone(status.outputPath)
        is CompressStatus.Error -> onError()
        else -> {}
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
                text = "${(animatedProgress * 100).toInt()}%",
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
        }
    }
}
