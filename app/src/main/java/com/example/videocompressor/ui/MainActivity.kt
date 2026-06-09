/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.example.videocompressor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.videocompressor.ui.screen.*
import com.example.videocompressor.ui.theme.VideoCompressorTheme
import com.example.videocompressor.ui.viewmodel.CompressStatus
import com.example.videocompressor.ui.viewmodel.CompressViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用主界面 Activity，作为 Compose UI 的宿主容器。
 *
 * 内部持有 Activity 作用域的 [com.example.videocompressor.ui.viewmodel.CompressViewModel]，
 * 通过 [androidx.navigation.compose.NavHost] 管理以下四个页面路由：
 * - `home` — 视频选择与参数配置主页（[com.example.videocompressor.ui.screen.HomeScreen]）
 * - `progress` — 压缩进度页（[com.example.videocompressor.ui.screen.ProgressScreen]）
 * - `result` — 压缩完成结果页（[com.example.videocompressor.ui.screen.ResultScreen]）
 * - `error` — 压缩失败错误页（[com.example.videocompressor.ui.screen.ErrorScreen]）
 * - `subtitle_edit` — 字幕逐句编辑页（[com.example.videocompressor.ui.screen.SubtitleEditScreen]）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: CompressViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VideoCompressorTheme {
                val navController = rememberNavController()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                NavHost(
                    navController = navController,
                    startDestination = "home"
                ) {
                    composable("home") {
                        HomeScreen(
                            viewModel = viewModel,
                            onStartCompress = {
                                navController.navigate("progress")
                            },
                            onEditSubtitle = { srtPath ->
                                viewModel.loadSubtitles(srtPath)
                                navController.navigate("subtitle_edit")
                            }
                        )
                    }
                    composable("subtitle_edit") {
                        SubtitleEditScreen(
                            viewModel = viewModel,
                            videoUri = uiState.videoInfo?.uri,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("progress") {
                        ProgressScreen(
                            viewModel = viewModel,
                            onDone = {
                                navController.navigate("result") {
                                    popUpTo("home") { inclusive = true }
                                }
                            },
                            onError = {
                                navController.navigate("error") {
                                    popUpTo("home") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("result") {
                        val outputPath = (uiState.status as? CompressStatus.Done)?.outputPath ?: ""
                        ResultScreen(
                            viewModel = viewModel,
                            outputPath = outputPath,
                            onNewCompress = {
                                navController.navigate("home") {
                                    popUpTo("home") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("error") {
                        val message = (uiState.status as? CompressStatus.Error)?.message ?: "未知错误"
                        ErrorScreen(
                            viewModel = viewModel,
                            message = message,
                            onRetry = {
                                navController.navigate("home") {
                                    popUpTo("home") { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
