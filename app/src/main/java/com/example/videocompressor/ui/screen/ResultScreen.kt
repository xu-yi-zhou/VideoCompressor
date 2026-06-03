package com.example.videocompressor.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.videocompressor.ui.viewmodel.CompressViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    outputPath: String,
    viewModel: CompressViewModel,
    onNewCompress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val inputSize = remember { uiState.videoInfo?.size ?: 0L }

    val outputUri = remember(outputPath) { Uri.parse(outputPath) }
    val outputSize = remember(outputPath) {
        if (outputPath.startsWith("content://")) {
            context.contentResolver.query(outputUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        } else {
            0L
        }
    }

    val savedPercent = remember(inputSize, outputSize) {
        if (inputSize > 0) {
            ((1 - outputSize.toDouble() / inputSize) * 100).toInt()
        } else 0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("压缩完成") },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "已保存至相册",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 对比卡片
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("压缩对比", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    InfoRow("原始大小", formatSize(inputSize))
                    InfoRow("压缩后大小", formatSize(outputSize))
                    InfoRow(
                        "节省空间",
                        "${formatSize(inputSize - outputSize)} ($savedPercent%)"
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/*"
                            putExtra(Intent.EXTRA_STREAM, outputUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "分享视频"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("分享")
                }

                Button(
                    onClick = {
                        viewModel.reset()
                        onNewCompress()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("再压一个")
                }
            }
        }
    }
}
