/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用程序入口类，继承自 [Application]。
 *
 * 使用 [@HiltAndroidApp] 注解触发 Hilt 的代码生成，
 * 建立依赖注入组件树的根节点，使整个应用具备依赖注入能力。
 */
@HiltAndroidApp
class App : Application()
