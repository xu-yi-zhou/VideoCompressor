/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Share
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
import com.xuyizhou.videocompressor.service.BatchResult
import com.xuyizhou.videocompressor.ui.viewmodel.CompressStatus
import com.xuyizhou.videocompressor.ui.viewmodel.CompressViewModel

/**
 * 批量压缩结果页：顶部汇总成功/失败与总节省空间，下方逐项列出每个视频的结果。
 *
 * 单项失败不阻塞整批任务，失败项在此页展示错误原因；[com.xuyizhou.videocompressor.ui.screen.ErrorScreen]
 * 仅用于服务级致命错误。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    viewModel: CompressViewModel,
    onNewCompress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val results = (uiState.status as? CompressStatus.Done)?.results ?: emptyList()
    val succeeded = results.filter { it.error == null }
    val failed = results.filter { it.error != null }

    val outputSizes = remember(results) {
        succeeded.associate { it.index to queryOutputSize(context, it.outputUri) }
    }
    val totalInput = results.sumOf { it.inputSize }
    val totalOutput = outputSizes.values.sumOf { it }
    val savedBytes = if (totalInput > 0 && totalOutput > 0) totalInput - totalOutput else 0L

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("压缩完成", fontWeight = FontWeight.Bold) },
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
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "成功 ${succeeded.size} 个 · 失败 ${failed.size} 个",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            if (savedBytes > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "总节省约 ${formatSize(savedBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.index }) { result ->
                    ResultItemCard(result, outputSizes[result.index] ?: 0L)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    viewModel.reset()
                    onNewCompress()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("再压一批")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ResultItemCard(result: BatchResult, outputSize: Long) {
    val context = LocalContext.current
    val isSuccess = result.error == null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (isSuccess) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isSuccess) {
                        val saved = if (result.inputSize > 0 && outputSize > 0) {
                            ((1 - outputSize.toDouble() / result.inputSize) * 100).toInt().coerceAtLeast(0)
                        } else null
                        if (saved != null) "已保存至相册 · 节省 $saved%" else "已保存至相册"
                    } else {
                        result.error ?: "压缩失败"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSuccess) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            if (isSuccess && result.outputUri != null) {
                IconButton(onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/*"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(result.outputUri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享视频"))
                }) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "分享",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** 查询 MediaStore content Uri 的文件大小，非 content Uri 或查询失败返回 0。 */
private fun queryOutputSize(context: android.content.Context, uri: String?): Long {
    if (uri == null || !uri.startsWith("content://")) return 0L
    return runCatching {
        context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) cursor.getLong(idx) else 0L
            } else 0L
        } ?: 0L
    }.getOrDefault(0L)
}
