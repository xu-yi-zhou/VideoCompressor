package com.example.videocompressor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
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
import com.example.videocompressor.service.CompressService
import com.example.videocompressor.ui.screen.*
import com.example.videocompressor.ui.theme.VideoCompressorTheme
import com.example.videocompressor.ui.viewmodel.CompressStatus
import com.example.videocompressor.ui.viewmodel.CompressViewModel
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: CompressViewModel by viewModels()
    private val gson = Gson()

    private val compressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                CompressService.ACTION_PROGRESS_UPDATE -> {
                    val progress = intent.getFloatExtra(CompressService.EXTRA_PROGRESS, 0f)
                    viewModel.onProgressUpdate(progress)
                }
                CompressService.ACTION_COMPRESS_COMPLETE -> {
                    val path = intent.getStringExtra(CompressService.EXTRA_OUTPUT_PATH) ?: ""
                    viewModel.onCompressComplete(path)
                }
                CompressService.ACTION_COMPRESS_ERROR -> {
                    val message = intent.getStringExtra(CompressService.EXTRA_ERROR_MESSAGE) ?: "未知错误"
                    viewModel.onCompressError(message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 注册广播接收器，接收 CompressService 的进度和结果
        val filter = IntentFilter().apply {
            addAction(CompressService.ACTION_PROGRESS_UPDATE)
            addAction(CompressService.ACTION_COMPRESS_COMPLETE)
            addAction(CompressService.ACTION_COMPRESS_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(compressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(compressReceiver, filter)
        }

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
                            onDone = { path ->
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

    override fun onDestroy() {
        unregisterReceiver(compressReceiver)
        super.onDestroy()
    }

    private fun startCompressService(videoUri: Uri, configJson: String) {
        val intent = Intent(this, CompressService::class.java).apply {
            putExtra("video_uri", videoUri)
            putExtra("config", configJson)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
