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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: CompressViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 进度/结果通过 CompressProgressBus（进程内共享 Flow）回传，ViewModel 已在 init 中订阅，
        // 不再需要广播接收器。

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
                            }
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
