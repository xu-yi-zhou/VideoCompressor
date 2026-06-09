/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.example.videocompressor.ui.screen

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.videocompressor.domain.transcribe.SrtParser
import com.example.videocompressor.ui.viewmodel.CompressViewModel
import com.example.videocompressor.ui.viewmodel.TranscribeStatus
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleEditScreen(
    viewModel: CompressViewModel,
    videoUri: Uri?,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val edit = uiState.subtitleEdit
    val context = LocalContext.current

    val player = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            if (videoUri != null) {
                setMediaItem(MediaItem.fromUri(videoUri))
                prepare()
            }
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            isPlaying = player.isPlaying
            delay(150)
        }
    }

    LaunchedEffect(uiState.transcribeStatus) {
        if (uiState.transcribeStatus is TranscribeStatus.Running) player.pause()
    }

    val cues = edit?.cues ?: emptyList()
    val activeIndex = remember(positionMs, cues) {
        cues.indexOfLast { it.startMs <= positionMs }.takeIf { it >= 0 && positionMs < cues[it].endMs } ?: -1
    }

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex, isPlaying) {
        if (isPlaying && activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("字幕编辑", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val saved = edit?.savedAt != null && edit.dirty.not()
                    TextButton(onClick = { viewModel.saveSubtitles() }, enabled = edit != null) {
                        Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (saved) "已保存" else "保存")
                    }
                }
            )
        },
        bottomBar = {
            if (edit != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.saveSubtitles { shareFile(context, File(edit.path)) } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("分享 SRT")
                        }
                        Button(
                            onClick = { viewModel.burnEditedSubtitle() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("烧进视频")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 视频预览
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(androidx.compose.ui.graphics.Color.Black)
            )

            if (edit == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("共 ${cues.size} 句", style = MaterialTheme.typography.labelLarge)
                if (edit.dirty) {
                    Text("未保存", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(cues) { index, cue ->
                    CueRow(
                        number = index + 1,
                        timeLabel = SrtParser.shortTs(cue.startMs),
                        text = cue.text,
                        active = index == activeIndex,
                        onPlay = {
                            player.seekTo(cue.startMs)
                            player.play()
                        },
                        onTextChange = { viewModel.updateCueText(index, it) }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    BurnExportDialog(uiState.transcribeStatus, onFinish = { viewModel.resetTranscribe() })
}

@Composable
private fun BurnExportDialog(status: TranscribeStatus, onFinish: () -> Unit) {
    when (status) {
        is TranscribeStatus.Running -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("烧进视频中") },
            text = {
                Column {
                    Text(status.stage)
                    Spacer(Modifier.height(12.dp))
                    if (status.progress != null) {
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("${(status.progress * 100).toInt()}%")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        )

        is TranscribeStatus.DoneVideo -> AlertDialog(
            onDismissRequest = onFinish,
            confirmButton = { TextButton(onClick = onFinish) { Text("完成") } },
            title = { Text("烧字幕完成") },
            text = { Text("带字幕的视频已保存到相册（Movies）。") }
        )

        is TranscribeStatus.Error -> AlertDialog(
            onDismissRequest = onFinish,
            confirmButton = { TextButton(onClick = onFinish) { Text("知道了") } },
            title = { Text("导出失败") },
            text = { Text(status.message) }
        )

        else -> Unit
    }
}

@Composable
private fun CueRow(
    number: Int,
    timeLabel: String,
    text: String,
    active: Boolean,
    onPlay: () -> Unit,
    onTextChange: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#$number",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = onPlay,
                    label = { Text(timeLabel) },
                    leadingIcon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = "播放此句",
                            modifier = Modifier.size(16.dp))
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 1,
                maxLines = 4
            )
        }
    }
}
